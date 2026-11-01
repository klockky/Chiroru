package dev.chiron.chiroru

import org.junit.Assert.*
import org.junit.Test

class RuntimeStatusTest {
    private val loaded = "wifi 123 0 - Live 0x0\nkernelsu 233472 0 - Live 0x0 (O)\n"

    @Test fun reopenedUnprivilegedProcessCanSeeLoadedKernelWithoutClaimingRoot() {
        val status = RuntimeStatus.fromEvidence(10081, 10081, loaded, false)
        assertFalse(status.processRoot)
        assertEquals(KernelSuState.LOADED, status.kernelSu)
        assertTrue(status.blocksRootOperation)
    }

    @Test fun driverReplyWorksWhenModuleListIsDenied() {
        val status = RuntimeStatus.fromEvidence(10081, 10081, null, true)
        assertEquals(KernelSuState.LOADED, status.kernelSu)
        assertFalse(status.processRoot)
    }

    @Test fun unavailableEvidenceDoesNotBecomeConfirmedAbsence() {
        val denied = RuntimeStatus.fromEvidence(10081, 10081, null, false)
        val readable = RuntimeStatus.fromEvidence(10081, 10081, "", false)
        assertEquals(KernelSuState.UNKNOWN, denied.kernelSu)
        assertEquals(KernelSuState.NOT_FOUND, readable.kernelSu)
        assertFalse(denied.processRoot)
    }

    @Test fun rootOnlyCredentialsDoNotSurviveANewProcess() {
        val before = RuntimeStatus.fromEvidence(0, 0, "", false)
        val after = RuntimeStatus.fromEvidence(10081, 10081, "", false)
        assertTrue(before.processRoot)
        assertTrue(before.blocksRootOperation)
        assertFalse(after.processRoot)
        assertFalse(after.blocksRootOperation)
        assertFalse(RuntimeStatus.fromEvidence(10081, 0, "", false).processRoot)
    }

    @Test fun partialModuleNamesAndHistoricalReportsAreNotLiveEvidence() {
        for (text in listOf("not_kernelsu 123 0 - Live 0x0", "kernelsu_helper 123 0 - Live 0x0",
            "root_stage_done uid=0 euid=0\nksud rc=0\nmodule: kernelsu already loaded")) {
            assertEquals(KernelSuState.NOT_FOUND,
                RuntimeStatus.fromEvidence(10081, 10081, text, false).kernelSu)
        }
        assertEquals(KernelSuState.UNKNOWN,
            RuntimeStatus.fromEvidence(10081, 10081, "kernelsu 123 0 - Loading 0x0", false).kernelSu)
    }
}
