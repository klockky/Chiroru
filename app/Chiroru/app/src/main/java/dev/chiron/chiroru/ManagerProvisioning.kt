package dev.chiron.chiroru

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

internal enum class ManagerStep {
    CHECKING, DOWNLOADING, VERIFYING, INSTALLING, INSTALLED, ALREADY_INSTALLED, ERROR;

    val busy: Boolean get() = this in setOf(CHECKING, DOWNLOADING, VERIFYING, INSTALLING)
}

internal data class ManagerResult(val step: ManagerStep, val report: String, val error: Exception? = null)
internal data class DownloadedManager(val file: File, val release: ManagerRelease)
internal data class InstallCommandResult(val exitCode: Int, val output: String)

internal interface ManagerInstallBackend {
    suspend fun isInstalled(): Boolean
    suspend fun download(): DownloadedManager
    suspend fun verify(apk: DownloadedManager)
    suspend fun install(apk: File): InstallCommandResult
    suspend fun remove(apk: File)
}

/** Shared by the UI and boot service. Existing installations are never replaced. */
internal class ManagerProvisioning(private val backend: ManagerInstallBackend) {
    suspend fun ensureInstalled(progress: (ManagerStep) -> Unit): ManagerResult {
        var downloaded: DownloadedManager? = null
        var stage = ManagerStep.CHECKING
        fun emit(step: ManagerStep) {
            stage = step
            progress(step)
        }
        return try {
            emit(ManagerStep.CHECKING)
            if (backend.isInstalled()) {
                ManagerResult(ManagerStep.ALREADY_INSTALLED, "manager: KernelSU Next is already installed\n")
            } else {
                emit(ManagerStep.DOWNLOADING)
                val apk = backend.download().also { downloaded = it }
                emit(ManagerStep.VERIFYING)
                backend.verify(apk)
                // Another installer may have completed while we were downloading.
                if (backend.isInstalled()) {
                    ManagerResult(ManagerStep.ALREADY_INSTALLED, "manager: installed while downloading; skipped\n")
                } else {
                    emit(ManagerStep.INSTALLING)
                    val result = backend.install(apk.file)
                    val present = backend.isInstalled()
                    if (result.exitCode != 0 || !result.output.lineSequence().any { it.trim() == "Success" }) {
                        check(present) { "Package installation failed (${result.exitCode}): ${result.output}" }
                        ManagerResult(ManagerStep.ALREADY_INSTALLED, "manager: package is present; installer output: ${result.output}\n")
                    } else {
                        check(present) { "Installer returned success, but manager package was not found" }
                        ManagerResult(ManagerStep.INSTALLED, "manager: installed official KernelSU Next ${apk.release.tag}\n")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ManagerResult(ManagerStep.ERROR,
                "manager: ${stage.name.lowercase()} failed: ${error.message ?: error.javaClass.simpleName}\n", error)
        } finally {
            downloaded?.let { apk ->
                withContext(NonCancellable) { runCatching { backend.remove(apk.file) } }
            }
        }.also { progress(it.step) }
    }
}
