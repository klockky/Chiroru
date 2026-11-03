package dev.chiron.chiroru

import org.junit.Assert.*
import org.junit.Test

class ReportResultTest {
    @Test fun adbSuccessNeverStartsTheKernelSuManagerAndRequiresSetupCompletion() {
        val partial = "root_stage_done uid=0 euid=0\nksud rc=0\nadb_root: module and client installed\n"
        assertEquals(Outcome.ERROR, ReportResult.parse(Operation.ADB_ROOT, partial).outcome)
        val report = partial + "selinux enforcing restored\nadb_root: ready\n"
        val result = ReportResult.parse(Operation.ADB_ROOT, report)
        assertEquals(Outcome.ADB_ROOT_READY, result.outcome)
        assertTrue(result.kernelTouched)
        assertFalse(result.moduleLoaded)
        assertFalse(ManagerInstaller.shouldRun(Operation.ADB_ROOT, report))
    }
    @Test fun successfulProbeRequiresAnOpenDeviceAndHandshake() {
        assertEquals(Outcome.FINISHED, ReportResult.parse(Operation.PROBE,
            "open(/dev/mali0) = 41 errno=0 (ok)\nVERSION_CHECK rc=0 errno=0 api=11.21\n").outcome)
        assertEquals(Outcome.ERROR, ReportResult.parse(Operation.PROBE,
            "open(/dev/mali0) = -1 errno=2\n").outcome)
        assertEquals(Outcome.ERROR, ReportResult.parse(Operation.PROBE,
            "open(/dev/mali0) = 41 errno=0\nVERSION_CHECK rc=-1 errno=1\n").outcome)
    }

    @Test fun moduleNameInAnErrorIsNotSuccess() {
        val result = ReportResult.parse(Operation.MODULE, "FAIL open kernelsu.ko errno=2")
        assertEquals(Outcome.ERROR, result.outcome)
        assertFalse(result.kernelTouched)
    }

    @Test fun moduleFailureAfterRootStillLocksTheProcess() {
        val result = ReportResult.parse(Operation.MODULE,
            "root_stage_done uid=0 euid=0\nksud rc=1 out=module load failed\n")
        assertEquals(Outcome.ERROR, result.outcome)
        assertTrue(result.kernelTouched)
    }

    @Test fun confirmedModuleLoadIsSuccess() {
        assertEquals(Outcome.MODULE_LOADED, ReportResult.parse(Operation.MODULE,
            "root_stage_done uid=0 euid=0\nksud rc=0 out=Loaded kernel module: ksu.ko\n").outcome)
    }

    @Test fun successfulHelperWithoutConfirmedRootIsNotSuccess() {
        assertEquals(Outcome.ERROR, ReportResult.parse(Operation.MODULE, "ksud rc=0 out=loaded\n").outcome)
    }

    @Test fun directFactoryLoadRequiresRootAndPreservesLifecycleFailure() {
        val loaded = "module: kernelsu loaded\n"
        assertEquals(Outcome.ERROR, ReportResult.parse(Operation.MODULE, loaded).outcome)
        val rooted = "root_stage_done uid=0 euid=0\n" + loaded
        assertEquals(Outcome.MODULE_LOADED, ReportResult.parse(Operation.MODULE, rooted).outcome)
        assertEquals(Outcome.MODULE_HOOKS_FAILED, ReportResult.parse(Operation.MODULE,
            rooted + "lifecycle: status=failed\n").outcome)
        assertFalse(ReportResult.parse(Operation.MODULE,
            "root_stage_done uid=0 euid=0\nfactory KSU: init_module errno=129\n").moduleLoaded)
    }

    @Test fun rootMarkerRequiresZeroIds() {
        assertEquals(Outcome.ROOT_READY, ReportResult.parse(Operation.ROOT, "root_stage_done uid=0 euid=0\nroot_console: ready\n").outcome)
        assertEquals(Outcome.ERROR, ReportResult.parse(Operation.ROOT, "root_stage_done uid=0 euid=0\nroot_console: live verification failed\n").outcome)
        assertEquals(Outcome.ERROR, ReportResult.parse(Operation.ROOT, "root_stage_done uid=0 euid=10079\n").outcome)
    }

    @Test fun partialKernelWriteAlsoLocksTheProcess() {
        assertTrue(ReportResult.parse(Operation.ROOT, "cred patched uid=0\nFAIL own task").kernelTouched)
        assertTrue(ReportResult.parse(Operation.ROOT, "seccomp disarmed\n").kernelTouched)
    }

    @Test fun earlyFailureCanBeRetried() {
        assertFalse(ReportResult.parse(Operation.ROOT, "FAIL reclaim\n").kernelTouched)
    }

    @Test fun managerInstallationRequiresConfirmedRootAndModuleLoad() {
        val success = "root_stage_done uid=0 euid=0\nksud rc=0 out=Loaded kernel module\n"
        for (operation in listOf(Operation.MODULE, Operation.MODULE_SAFE)) {
            assertTrue(ManagerInstaller.shouldRun(operation, success))
            assertFalse(ManagerInstaller.shouldRun(operation, "root_stage_done uid=0 euid=0\nksud rc=1\n"))
            assertFalse(ManagerInstaller.shouldRun(operation, "FAIL kernelsu\n"))
            assertFalse(ManagerInstaller.shouldRun(operation, "ksud rc=0\n"))
        }
        assertFalse(ManagerInstaller.shouldRun(Operation.ROOT, success))
        assertFalse(ManagerInstaller.shouldRun(Operation.PROBE, success))
    }

    @Test fun failedHooksPreserveLoadedModuleAndManagerInstallation() {
        val report = "root_stage_done uid=0 euid=0\nksud rc=0 out=loaded\n" +
            "KSU lifecycle rc=1 out=services failed\nlifecycle: status=failed\n"
        val result = ReportResult.parse(Operation.MODULE, report)
        assertEquals(Outcome.MODULE_HOOKS_FAILED, result.outcome)
        assertTrue(result.kernelTouched)
        assertTrue(result.moduleLoaded)
        assertTrue(ManagerInstaller.shouldRun(Operation.MODULE, report))
    }

    @Test fun hookFailureCannotTurnAnUnloadedModuleIntoSuccess() {
        val report = "root_stage_done uid=0 euid=0\nksud rc=1\nlifecycle: status=failed\n"
        val result = ReportResult.parse(Operation.MODULE, report)
        assertEquals(Outcome.ERROR, result.outcome)
        assertFalse(result.moduleLoaded)
        assertFalse(ManagerInstaller.shouldRun(Operation.MODULE, report))
    }

    @Test fun alreadyLoadedModuleAlsoReportsFailedHooks() {
        val report = "root_stage_done uid=0 euid=0\nksud rc=1 out=File exists\n" +
            "module: kernelsu already loaded\nlifecycle: status=failed\n"
        assertEquals(Outcome.MODULE_HOOKS_FAILED, ReportResult.parse(Operation.MODULE, report).outcome)
        assertTrue(ManagerInstaller.shouldRun(Operation.MODULE, report))
    }

    @Test fun skippedHooksInSafeModeStillAllowManagerInstallation() {
        for (moduleReport in listOf("ksud rc=0 out=loaded", "module: kernelsu already loaded")) {
            val report = "root_stage_done uid=0 euid=0\n$moduleReport\nlifecycle hooks explicitly skipped\n"
            val result = ReportResult.parse(Operation.MODULE_SAFE, report)
            assertEquals(Outcome.MODULE_LOADED, result.outcome)
            assertTrue(result.moduleLoaded)
            assertTrue(ManagerInstaller.shouldRun(Operation.MODULE_SAFE, report))
        }
    }

    @Test fun completedHooksKeepSuccessfulOutcome() {
        val report = "root_stage_done uid=0 euid=0\nksud rc=0 out=loaded\nlifecycle: status=complete\n"
        assertEquals(Outcome.MODULE_LOADED, ReportResult.parse(Operation.MODULE, report).outcome)
    }
}
