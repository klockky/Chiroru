package dev.chiron.chiroru

import dev.chiron.chiroru.firmware.FirmwareSupport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RootMethodTest {
    private val supported = AppState(duoMax = true, firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU,
        adbRootSupported = true)

    @Test fun methodsRouteToFourIndependentOperations() {
        assertEquals(listOf(Operation.PROBE, Operation.ROOT, Operation.ADB_ROOT, Operation.MODULE),
            RootMethod.entries.map { it.operation })
        assertEquals(4, RootMethod.entries.map { it.id }.distinct().size)
    }

    @Test fun preferenceSurvivesNewInstancesAndStoresOnlyMethodNotRootStatus() {
        val context = RuntimeEnvironment.getApplication()
        RootMethod.entries.forEach { method ->
            AppPreferences(context).rootMethod = method
            assertEquals(method, AppPreferences(context).rootMethod)
        }
        assertEquals(RootMethod.KERNEL_SU, RootMethod.fromId(null))
        assertEquals(RootMethod.KERNEL_SU, RootMethod.fromId("unknown"))
    }

    @Test fun everyMethodRequiresDuoMaxAndSupportedFirmware() {
        RootMethod.entries.forEach { method ->
            assertTrue(method.canRun(supported))
            assertFalse(method.canRun(supported.copy(duoMax = false)))
            assertFalse(method.canRun(supported.copy(firmwareSupport = FirmwareSupport.UNSUPPORTED)))
        }
    }

    @Test fun missingOptionalMethodIsNeverSubstituted() {
        val rootOnly = supported.copy(firmwareSupport = FirmwareSupport.ROOT_ONLY, adbRootSupported = false)
        assertTrue(RootMethod.IN_APP.canRun(rootOnly))
        assertTrue(RootMethod.PROBE.canRun(rootOnly))
        assertFalse(RootMethod.KERNEL_SU.canRun(rootOnly))
        assertFalse(RootMethod.ADB.canRun(rootOnly))
    }

    @Test fun rootGuardsDoNotPreventReadOnlyProbe() {
        for (state in listOf(supported.copy(kernelTouched = true), supported.copy(checkingRuntime = true),
            supported.copy(runtime = RuntimeStatus(processRoot = true)), supported.copy(runtime = RuntimeStatus(adbRoot = true)),
            supported.copy(runtime = RuntimeStatus(kernelSu = KernelSuState.LOADED)))) {
            assertTrue(RootMethod.PROBE.canRun(state))
            RootMethod.entries.filter { it != RootMethod.PROBE }.forEach { assertFalse(it.canRun(state)) }
        }
    }

    @Test fun busyStateDisablesEveryLaunch() {
        for (state in listOf(supported.copy(running = Operation.PROBE),
            supported.copy(console = ConsoleState(running = true)), supported.copy(managerStep = ManagerStep.DOWNLOADING))) {
            RootMethod.entries.forEach { assertFalse(it.canRun(state)) }
        }
    }

    @Test fun factoryManagerPrerequisiteOnlyBlocksKernelSu() {
        BundledManagerState.entries.forEach { manager ->
            val state = supported.copy(bundledManager = manager)
            assertEquals(manager == BundledManagerState.READY, RootMethod.KERNEL_SU.canRun(state))
            assertTrue(RootMethod.IN_APP.canRun(state))
            assertTrue(RootMethod.ADB.canRun(state))
            assertTrue(RootMethod.PROBE.canRun(state))
        }
        RootMethod.entries.forEach { assertFalse(it.canRun(supported.copy(preparingManager = true))) }
    }
}
