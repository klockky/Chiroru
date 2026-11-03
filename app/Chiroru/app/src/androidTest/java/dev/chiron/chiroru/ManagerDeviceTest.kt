package dev.chiron.chiroru

import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.system.Os
import dev.chiron.chiroru.firmware.FirmwareEnvironment
import dev.chiron.chiroru.firmware.ManagerSource
import dev.chiron.chiroru.firmware.FirmwareAssets
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** These preflight checks never request root or install/delete another package. */
@RunWith(AndroidJUnit4::class)
class ManagerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun prepareNormalAppIo() {
        instrumentation.runOnMainSync { AppIo.prepare(context) }
    }

    @Test fun normalAppIoCanAccessPrivateFiles() = runBlocking {
        withContext(AppIo.dispatcher) {
            assertEquals(context.applicationInfo.uid, Os.getuid())
            val file = java.io.File.createTempFile("chiroru-io-", ".tmp", Loader.storage(context).cacheDir)
            try {
                file.writeText("private IO works")
                assertEquals("private IO works", file.readText())
            } finally { file.delete() }
        }
    }

    @Test fun installedManagerIsDetectedWithoutRoot() = runBlocking {
        val installed = try {
            context.packageManager.getPackageInfo(ManagerReleases.PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        assumeTrue("This check needs an existing manager", installed)
        val states = mutableListOf<ManagerStep>()
        val result = ManagerInstaller.ensureInstalled(context) { states += it }
        assertEquals(ManagerStep.ALREADY_INSTALLED, result.step)
        assertEquals(listOf(ManagerStep.CHECKING, ManagerStep.ALREADY_INSTALLED), states)
    }

    @Test fun bundledManagerMatchesItsProfileAndInstalledCertificateWithoutRoot() {
        val selection = FirmwareEnvironment.inspect(context)
        val profile = checkNotNull(selection.profile)
        val manager = checkNotNull(profile.kernelSu).manager
        assumeTrue(manager.source == ManagerSource.BUNDLED)
        val file = java.io.File(FirmwareAssets(context.cacheDir) { context.assets.open(it) }.prepareManager(profile))
        try {
            val info = checkNotNull(context.packageManager.getPackageArchiveInfo(file.path,
                PackageManager.GET_SIGNING_CERTIFICATES))
            assertTrue(ManagerPackages.accepts(info, manager))
            assertEquals(30046L, info.longVersionCode)
            assertEquals(context.applicationInfo.uid, Os.getuid())
        } finally { file.delete() }
    }

    @Test fun bundledInstallerUsesReadableContentUriAndNeverStartsRoot() {
        assumeTrue(context.packageName.endsWith(".preflight"))
        assumeTrue(FirmwareEnvironment.inspect(context).profile?.kernelSu?.manager?.source == ManagerSource.BUNDLED)
        // Set this on the disposable package via adb before instrumentation:
        // Android kills an app when this app-op changes during a running test.
        assumeTrue("Grant REQUEST_INSTALL_PACKAGES before running this test",
            context.packageManager.canRequestPackageInstalls())
        val intent = ManagerPackages.installIntent(context)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.manager", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.resolveActivity(context.packageManager))
        context.contentResolver.openInputStream(checkNotNull(intent.data))!!.use {
            assertEquals('P'.code, it.read())
            assertEquals('K'.code, it.read())
        }
        assertEquals(context.applicationInfo.uid, Os.getuid())
        assertFalse(Loader.kernelTouched)
    }

    @Test fun downloadedApkCanBeVerifiedAndStreamedWithoutDirectoryAccess() = runBlocking {
        assumeTrue("Official manager belongs only to its selected firmware",
            FirmwareEnvironment.inspect(context).profile?.kernelSu?.manager?.source == ManagerSource.OFFICIAL_RELEASE)
        val backend = ManagerInstaller.AndroidBackend(context, AppIo.dispatcher)
        val downloaded = backend.download()
        val directory = java.io.File(Loader.storage(context).cacheDir, "manager")
        val diskFile = java.io.File(directory, "KernelSU-Next.apk")
        val permissions = Os.stat(directory.absolutePath).st_mode and 0xFFF
        try {
            Os.chmod(directory.absolutePath, 0)
            // Reproduce the post-root EACCES without requesting root or touching
            // another package's files: the original private path cannot be used.
            assertThrows(java.io.IOException::class.java) { diskFile.inputStream().use { it.read() }; Unit }
            backend.verify(downloaded)
            val sink = object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(buffer: ByteArray, offset: Int, count: Int) = Unit
            }
            val bytes = downloaded.file.inputStream().use { it.copyTo(sink) }
            assertEquals(downloaded.release.size, bytes)
            assertTrue(Build.VERSION.SDK_INT >= 30)
        } finally {
            Os.chmod(directory.absolutePath, permissions)
            backend.remove(downloaded.file)
        }
    }
}
