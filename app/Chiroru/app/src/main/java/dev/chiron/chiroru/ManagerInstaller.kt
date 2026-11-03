package dev.chiron.chiroru

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import dev.chiron.chiroru.firmware.FirmwareEnvironment
import dev.chiron.chiroru.firmware.ManagerSource
import kotlin.coroutines.coroutineContext

internal object ManagerInstaller {
    private val mutex = Mutex()
    private val mutableProgress = MutableStateFlow<ManagerStep?>(null)
    val progress = mutableProgress.asStateFlow()

    fun shouldRun(operation: Operation, report: String): Boolean =
        (operation == Operation.MODULE || operation == Operation.MODULE_SAFE) &&
            ReportResult.parse(operation, report).moduleLoaded

    suspend fun ensureInstalled(context: Context, progress: (ManagerStep) -> Unit): ManagerResult = mutex.withLock {
        val selection = FirmwareEnvironment.inspect(context)
        val requirement = selection.profile?.kernelSu?.manager
        if (!selection.support.canLoadKernelSu || requirement == null || requirement.source == ManagerSource.BUNDLED) {
            mutableProgress.value = ManagerStep.CHECKING
            progress(ManagerStep.CHECKING)
            val ready = requirement != null && selection.support.canLoadKernelSu &&
                runCatching { ManagerPackages.isCompatible(context, requirement) }.getOrDefault(false)
            val result = ManagerResult(if (ready) ManagerStep.ALREADY_INSTALLED else ManagerStep.ERROR,
                if (ready) "manager: matching bundled manager is installed\n"
                else "manager: install the matching bundled manager from Chiroru before loading\n")
            mutableProgress.value = result.step
            progress(result.step)
            return@withLock result
        }
        val dispatcher = AppIo.dispatcher
        ManagerProvisioning(AndroidBackend(context.applicationContext, dispatcher)).ensureInstalled { step ->
            mutableProgress.value = step
            progress(step)
        }.also {
            if (it.error != null) Log.e(Loader.TAG, it.report, it.error)
            else Log.i(Loader.TAG, it.report)
        }
    }

    internal class AndroidBackend(
        private val context: Context,
        private val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher,
    ) : ManagerInstallBackend {
        override suspend fun isInstalled(): Boolean = withContext(dispatcher) {
            val requirement = officialRequirement()
            val info = ManagerPackages.installed(context, requirement)
            check(info == null || ManagerPackages.accepts(info, requirement)) { "Installed manager signature does not match this firmware" }
            info != null
        }

        private fun officialRequirement() = FirmwareEnvironment.inspect(context).let { selection ->
            check(selection.support.canLoadKernelSu) { "No exact KernelSU firmware profile" }
            checkNotNull(selection.profile?.kernelSu).manager.also {
                check(it.source == ManagerSource.OFFICIAL_RELEASE && it.packageName == ManagerReleases.PACKAGE &&
                    it.signerSha256 == ManagerReleases.SIGNER_SHA256) { "Official manager download is not supported by this profile" }
            }
        }

        override suspend fun download(): DownloadedManager = withContext(dispatcher) {
            officialRequirement()
            val release = connect(URL(ManagerReleases.API)).useConnection { connection ->
                connection.inputStream.bufferedReader().use { reader ->
                    val metadata = reader.readTextLimited(1024 * 1024)
                    ManagerReleases.parse(metadata)
                }
            }
            val file = AppIo.managerApk
            val output = AppIo.clearManagerDownload()
            try {
                connect(release.url).useConnection { connection ->
                    val declaredSize = connection.contentLengthLong
                    check(declaredSize == -1L || declaredSize == release.size) { "Unexpected APK response length" }
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= release.size) { "APK is larger than the release asset" }
                            output.write(buffer, 0, count)
                        }
                        check(total == release.size) { "Incomplete manager APK download" }
                    }
                }
                DownloadedManager(file, release)
            } catch (error: Exception) {
                AppIo.clearManagerDownload()
                throw error
            }
        }

        override suspend fun verify(apk: DownloadedManager) = withContext(dispatcher) {
            val requirement = officialRequirement()
            val digest = MessageDigest.getInstance("SHA-256")
            apk.file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().hex() == apk.release.sha256) { "Manager APK SHA-256 does not match GitHub" }
            val info = checkNotNull(context.packageManager.getPackageArchiveInfo(
                apk.file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES,
            )) { "Downloaded file is not a readable APK" }
            check(info.packageName == ManagerReleases.PACKAGE) { "Unexpected APK package: ${info.packageName}" }
            check(ManagerPackages.accepts(info, requirement)) { "Manager APK does not match this firmware's certificate/version" }
            check(checkNotNull(info.applicationInfo).minSdkVersion <= Build.VERSION.SDK_INT) { "Manager requires a newer Android version" }
            val signers = checkNotNull(info.signingInfo).apkContentsSigners
            check(signers.size == 1 && MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray()).hex() ==
                ManagerReleases.SIGNER_SHA256) { "Manager APK signature is not the expected official signature" }
        }

        override suspend fun install(apk: File): InstallCommandResult {
            // fork/exec must originate from the root main thread. Stream the APK over stdin so that
            // the package manager never needs access to an app-private path.
            val size = withContext(dispatcher) { apk.length().also { check(it > 0) { "Empty manager APK" } } }
            val process = withContext(Dispatchers.Main.immediate) {
                check(Os.getuid() == 0 && Os.geteuid() == 0) { "Load KernelSU through Chiroru before installing the manager" }
                // On this Android 11 build a trailing '-' is parsed as an unknown
                // option. With -S and no path, pm already reads the APK from stdin.
                ProcessBuilder("/system/bin/pm", "install", "--user", "0", "-S", size.toString())
                    .redirectErrorStream(true).start()
            }
            return try {
                coroutineScope {
                    val output = async(Dispatchers.IO) {
                        process.inputStream.bufferedReader().use { it.readTextLimited(64 * 1024, drain = true) }
                    }
                    val transfer = async(dispatcher) {
                        runCatching { process.outputStream.use { sink -> apk.inputStream().use { it.copyTo(sink) } } }
                    }
                    try {
                        val exited = runInterruptible(Dispatchers.IO) { process.waitFor(90, TimeUnit.SECONDS) }
                        check(exited) { "Package manager timed out" }
                    } catch (error: Exception) {
                        process.destroyForcibly()
                        runCatching { process.outputStream.close() }
                        runCatching { process.inputStream.close() }
                        throw error
                    }
                    val text = output.await()
                    // Preserve package-manager diagnostics when it rejects stdin early.
                    transfer.await().getOrElse {
                        if (process.exitValue() == 0) throw it
                    }
                    InstallCommandResult(process.exitValue(), text)
                }
            } finally {
                process.destroyForcibly()
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
            }
        }

        override suspend fun remove(apk: File) { withContext(dispatcher) { AppIo.clearManagerDownload(); Unit } }
    }

    private fun connect(initial: URL): HttpURLConnection {
        var url = initial
        repeat(6) {
            check(url.protocol == "https" && url.host in setOf("api.github.com", "github.com",
                "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Unexpected download redirect" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Chiroru/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            }
            val code = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                check(!location.isNullOrBlank()) { "Empty GitHub redirect" }
                url = URL(url, location)
            } else {
                if (code != 200) { connection.disconnect(); error("GitHub HTTP $code") }
                return connection
            }
        }
        error("Too many GitHub redirects")
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun java.io.Reader.readTextLimited(limit: Int, drain: Boolean = false): String {
        val result = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            val remaining = limit - result.length
            if (!drain) check(count <= remaining) { "Response exceeds size limit" }
            if (remaining > 0) result.append(buffer, 0, minOf(count, remaining))
        }
        return result.toString()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
