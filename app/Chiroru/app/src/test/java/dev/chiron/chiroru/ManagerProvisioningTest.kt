package dev.chiron.chiroru

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URL

class ManagerProvisioningTest {
    private class Backend : ManagerInstallBackend {
        var present = false
        var installs = 0
        var downloads = 0
        var removals = 0
        var checks = 0
        var installRace = false
        var downloadFailure: Exception? = null
        var verificationFailure: Exception? = null
        var installResult = InstallCommandResult(0, "Success\n")
        var installedAfterCommand = true
        override suspend fun isInstalled(): Boolean {
            checks++
            if (installRace && checks == 2) present = true
            return present
        }
        override suspend fun download(): DownloadedManager {
            downloads++
            downloadFailure?.let { throw it }
            return DownloadedManager(File("fake-manager.apk"), ManagerRelease("v3.4.2",
                URL("https://github.com/KernelSU-Next/KernelSU-Next/releases/download/v3.4.2/app.apk"), 10, "a".repeat(64)))
        }
        override suspend fun verify(apk: DownloadedManager) { verificationFailure?.let { throw it } }
        override suspend fun install(apk: File): InstallCommandResult {
            installs++
            present = installedAfterCommand
            return installResult
        }
        override suspend fun remove(apk: File) { removals++ }
    }

    @Test fun existingManagerSkipsNetworkAndInstallation() = runBlocking {
        val backend = Backend().apply { present = true }
        val result = ManagerProvisioning(backend).ensureInstalled { }
        assertEquals(ManagerStep.ALREADY_INSTALLED, result.step)
        assertEquals(0, backend.downloads)
        assertEquals(0, backend.installs)
    }

    @Test fun missingManagerIsVerifiedInstalledAndCleanedUp() = runBlocking {
        val backend = Backend()
        val progress = mutableListOf<ManagerStep>()
        val result = ManagerProvisioning(backend).ensureInstalled { progress += it }
        assertEquals(ManagerStep.INSTALLED, result.step)
        assertEquals(listOf(ManagerStep.CHECKING, ManagerStep.DOWNLOADING, ManagerStep.VERIFYING,
            ManagerStep.INSTALLING, ManagerStep.INSTALLED), progress)
        assertEquals(1, backend.installs)
        assertEquals(1, backend.removals)
    }

    @Test fun failedVerificationNeverInstallsAndRemovesTheApk() = runBlocking {
        val backend = Backend().apply { verificationFailure = IllegalStateException("Wrong signer") }
        val result = ManagerProvisioning(backend).ensureInstalled { }
        assertEquals(ManagerStep.ERROR, result.step)
        assertTrue(result.report.contains("Wrong signer"))
        assertEquals(0, backend.installs)
        assertEquals(1, backend.removals)
    }

    @Test fun networkFailureIsReportedWithoutAttemptingInstallation() = runBlocking {
        val backend = Backend().apply { downloadFailure = java.io.IOException("offline") }
        assertEquals(ManagerStep.ERROR, ManagerProvisioning(backend).ensureInstalled { }.step)
        assertEquals(0, backend.installs)
    }

    @Test fun concurrentInstallationIsNotOverwritten() = runBlocking {
        val backend = Backend().apply { installRace = true }
        assertEquals(ManagerStep.ALREADY_INSTALLED, ManagerProvisioning(backend).ensureInstalled { }.step)
        assertEquals(0, backend.installs)
        assertEquals(1, backend.removals)
    }

    @Test fun packageManagerFailureIsReported() = runBlocking {
        val backend = Backend().apply {
            installResult = InstallCommandResult(1, "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]")
            installedAfterCommand = false
        }
        val result = ManagerProvisioning(backend).ensureInstalled { }
        assertEquals(ManagerStep.ERROR, result.step)
        assertTrue(result.report.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        assertEquals(1, backend.removals)
    }

    @Test fun installerSuccessMustBeConfirmedByPackageManager() = runBlocking {
        val backend = Backend().apply { installedAfterCommand = false }
        assertEquals(ManagerStep.ERROR, ManagerProvisioning(backend).ensureInstalled { }.step)
    }

    @Test fun cancellationIsPropagatedAndDownloadedFileIsRemoved() {
        val backend = Backend().apply { verificationFailure = CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) {
            runBlocking { ManagerProvisioning(backend).ensureInstalled { } }
        }
        assertEquals(0, backend.installs)
        assertEquals(1, backend.removals)
    }
}
