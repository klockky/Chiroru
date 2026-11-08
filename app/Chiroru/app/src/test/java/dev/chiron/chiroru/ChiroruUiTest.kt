package dev.chiron.chiroru

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.mutableStateOf
import java.io.File
import dev.chiron.chiroru.ui.ChiroruScaffold
import dev.chiron.chiroru.ui.ChiroruTheme
import dev.chiron.chiroru.firmware.FirmwareSupport
import dev.chiron.chiroru.firmware.FirmwareEnvironment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en-rUS-w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChiroruUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var renderedView: View

    @Test
    // Robolectric #8460: custom window widths loop with text fields in dialogs.
    @Config(sdk = [30], qualifiers = "en-rUS-w320dp-h915dp-xhdpi")
    fun delayEditorValidatesAndSavesOnlyOnConfirmation() {
        val changes = mutableListOf<Int>()
        val bootChanges = mutableListOf<Boolean>()
        val state = mutableStateOf(AppState(duoMax = true, firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU))
        compose.setContent { ChiroruTheme { ChiroruScaffold(state.value, setBoot = bootChanges::add,
            setBootDelay = { changes.add(it); state.value = state.value.copy(bootDelaySeconds = it) }) } }
        compose.onNodeWithText("Boot loading").assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Autoload delay").performScrollTo().performClick()
        compose.onNodeWithText("Seconds").performTextReplacement("86401")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Seconds").performTextReplacement("")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Seconds").performTextReplacement("420")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<Int>(), changes)
        compose.onNodeWithText("180 s after station startup").assertIsDisplayed()
        compose.onNodeWithText("Autoload delay").performClick()
        compose.onNodeWithText("Seconds").performTextReplacement("420")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("420 s after station startup").assertIsDisplayed()
        assertEquals(listOf(420), changes)
        assertEquals(emptyList<Boolean>(), bootChanges)
        compose.onNode(isToggleable() and hasText("Load KernelSU automatically")).assertIsOff()
    }

    @Test fun methodSelectionOnlyChangesDescriptionAndSingleLaunchButton() {
        val operations = mutableListOf<Operation>()
        val state = mutableStateOf(AppState(duoMax = true, firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU,
            adbRootSupported = true))
        compose.setContent { ChiroruTheme { ChiroruScaffold(state.value, run = operations::add,
            setRootMethod = { method -> state.value = state.value.copy(rootMethod = method) }) } }
        compose.onAllNodesWithText("Acquire root").assertCountEquals(1)
        compose.onNodeWithText("Settings").performClick()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        RootMethod.entries.forEach { method ->
            val label = context.getString(method.label)
            val description = context.getString(method.description)
            compose.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText(description).performScrollTo().assertIsDisplayed()
            assertEquals(emptyList<Operation>(), operations)
        }
        compose.onNodeWithText("Home").performClick()
        compose.onAllNodesWithText("Acquire root").assertCountEquals(1)
        compose.onNodeWithText("Acquire root").performScrollTo().performClick()
        assertEquals(listOf(Operation.MODULE), operations)
    }

    @Test fun probeButtonRunsOnlyProbe() {
        val operations = mutableListOf<Operation>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(duoMax = true,
            firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU, rootMethod = RootMethod.PROBE), run = operations::add) } }
        compose.onNodeWithText("Acquire root").assertDoesNotExist()
        compose.onNodeWithText("Run check").performScrollTo().performClick()
        assertEquals(listOf(Operation.PROBE), operations)
    }

    @Test fun factoryManagerSetupDoesNotStartRootAndUnlocksOnlyAfterVerification() {
        val operations = mutableListOf<Operation>()
        var installs = 0
        val state = mutableStateOf(AppState(duoMax = true,
            firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU,
            bundledManager = BundledManagerState.MISSING))
        compose.setContent { ChiroruTheme { ChiroruScaffold(state.value, run = operations::add,
            installBundledManager = { installs++ }) } }
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Install matching manager").performScrollTo().performClick()
        assertEquals(1, installs)
        assertEquals(emptyList<Operation>(), operations)
        compose.runOnIdle { state.value = state.value.copy(bundledManager = BundledManagerState.READY) }
        compose.onNodeWithText("Install matching manager").assertDoesNotExist()
        compose.onNodeWithText("Acquire root").performScrollTo().performClick()
        assertEquals(listOf(Operation.MODULE), operations)
    }

    @Test fun otherDeviceCannotLaunchEvenWithSupportedFirmwareState() {
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(duoMax = false,
            firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU)) } }
        compose.onNodeWithText("Yandex Duo Max only").assertIsDisplayed()
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
    }

    @Test fun incompatibleManagerScreenRequiresRemovalThenExplicitContinuation() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = FirmwareEnvironment.parseCatalog(context.assets.open("firmware/profiles.json")
            .bufferedReader().use { it.readText() }).profiles.single { it.id == "chiron-5000001388" }.kernelSu!!.manager
        val state = mutableStateOf(AppState(managerConflict = ManagerConflict(
            Operation.MODULE_SAFE, manager, BundledManagerState.INCOMPATIBLE)))
        var removes = 0
        var continues = 0
        var cancels = 0
        compose.setContent { ChiroruTheme { ChiroruScaffold(state.value, uninstallManager = { removes++ },
            continueManager = { continues++ }, dismissManager = { cancels++ }) } }
        compose.onNodeWithText("Incompatible KernelSU manager").assertIsDisplayed()
        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.onNodeWithText("Continue to root").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Uninstall incompatible manager").performScrollTo().performClick()
        assertEquals(1, removes)
        // Cancellation leaves the incompatible status and Continue disabled.
        compose.onNodeWithText("Continue to root").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(managerConflict = state.value.managerConflict!!
            .copy(status = BundledManagerState.MISSING)) }
        compose.onNodeWithText("Uninstall incompatible manager").assertDoesNotExist()
        compose.onNodeWithText("Continue to root").performScrollTo().assertIsEnabled()
        assertEquals(0, continues)
        compose.onNodeWithText("Continue to root").performClick()
        assertEquals(1, continues)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(1, cancels)
    }

    @Test fun factoryConflictRequiresMatchingInstallBeforeContinuation() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = FirmwareEnvironment.parseCatalog(context.assets.open("firmware/profiles.json")
            .bufferedReader().use { it.readText() }).profiles.single { it.id == "chiron-1982735225" }.kernelSu!!.manager
        val state = mutableStateOf(AppState(managerConflict = ManagerConflict(
            Operation.MODULE, manager, BundledManagerState.MISSING)))
        var installs = 0
        var continues = 0
        compose.setContent { ChiroruTheme { ChiroruScaffold(state.value, installBundledManager = { installs++ },
            continueManager = { continues++ }) } }
        compose.onNodeWithText("Continue to root").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Install matching manager").performScrollTo().performClick()
        assertEquals(1, installs)
        assertEquals(0, continues)
        compose.runOnIdle { state.value = state.value.copy(managerConflict = state.value.managerConflict!!
            .copy(status = BundledManagerState.READY), checkingRuntime = true) }
        compose.onNodeWithText("Continue to root").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(checkingRuntime = false) }
        compose.onNodeWithText("Continue to root").assertIsEnabled().performClick()
        assertEquals(1, continues)
    }

    @Test fun liveProcessConsoleAllowsCommandsWithoutRestartOrAdbModule() {
        val commands = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(kernelTouched = true,
            runtime = RuntimeStatus(processRoot = true), processConsoleAvailable = true, consoleRootAvailable = true),
            initialTab = 3, runConsole = { command, root -> commands.add(command to root) }) } }
        compose.onNodeWithText("Restart Chiroru").assertDoesNotExist()
        compose.onNodeWithContentDescription("Root").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Command").performTextInput("id")
        compose.onNodeWithText("Run").performClick()
        assertEquals(listOf("id" to true), commands)
    }

    @Test fun consoleRequiresFreshProcessAfterBootstrap() {
        var restarts = 0
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(kernelTouched = true),
            restartConsole = { restarts++ }, initialTab = 3) } }
        compose.onNodeWithText("Command").assertDoesNotExist()
        compose.onNodeWithText("Restart Chiroru").performClick()
        assertEquals(1, restarts)
    }

    @Test fun consoleSubmitsOnlyExplicitCommandAndKeepsDraftAcrossTabs() {
        val commands = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(consoleRootAvailable = true),
            runConsole = { command, root -> commands.add(command to root) }) } }
        compose.onNodeWithText("Console").performClick()
        compose.onNodeWithText("Run").assertIsNotEnabled()
        compose.onNodeWithText("Command").performTextInput("id; getenforce")
        compose.onNodeWithText("Journal").performClick()
        compose.onNodeWithText("Console").performClick()
        compose.onNodeWithText("id; getenforce").assertIsDisplayed()
        assertEquals(emptyList<Pair<String, Boolean>>(), commands)
        compose.onNodeWithText("Run").performClick()
        assertEquals(listOf("id; getenforce" to false), commands)
        compose.onNodeWithContentDescription("Root").performClick()
        compose.onNodeWithText("Run").performClick()
        assertEquals("id; getenforce" to true, commands.last())
    }

    @Test fun consoleShowsLiveOutputAndStopInsteadOfAnotherRun() {
        var stops = 0
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            console = ConsoleState(running = true, output = "streamed output")), stopConsole = { stops++ }) } }
        compose.onNodeWithText("Console").performClick()
        compose.onNodeWithText("streamed output").assertIsDisplayed()
        compose.onNodeWithText("Run").assertDoesNotExist()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(1, stops)
    }

    @Test fun adbModuleDetectedAfterReopenShowsConsoleCommandWithoutAppRoot() {
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            duoMax = true, firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU, adbRootSupported = true,
            runtime = RuntimeStatus(adbRoot = true), runtimeChecked = true,
        )) } }
        compose.onNodeWithText("ADB root is available").assertIsDisplayed()
        compose.onNodeWithText("adb shell -t /data/local/tmp/chiroru-su").assertIsDisplayed()
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
    }

    @Test fun adbActionIsExplicitAndIndependentOfKernelSu() {
        val operations = mutableListOf<Operation>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            duoMax = true, firmwareSupport = FirmwareSupport.ROOT_ONLY, adbRootSupported = true, rootMethod = RootMethod.ADB,
        ), run = operations::add) } }
        compose.onNodeWithText("Acquire root").performScrollTo().performClick()
        assertEquals(listOf(Operation.ADB_ROOT), operations)
    }

    @Test fun reopenedAppShowsDetectedModuleWithoutRootOrManagerSuccess() {
        val operations = mutableListOf<Operation>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            duoMax = true, firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU,
            runtime = RuntimeStatus(false, KernelSuState.LOADED), runtimeChecked = true,
        ), run = operations::add) } }
        compose.onNodeWithText("KernelSU loaded").assertIsDisplayed()
        compose.onNodeWithText("Chiroru process: no root").assertIsDisplayed()
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("KSU Next Manager installed.").assertDoesNotExist()
        assertEquals(emptyList<Operation>(), operations)
    }

    @Test fun deniedRuntimeEvidenceIsNotShownAsAbsentOrNotStarted() {
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(duoMax = true, runtimeChecked = true)) } }
        compose.onAllNodesWithText("KernelSU: status unknown")[0].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("KernelSU: not loaded").assertDoesNotExist()
        compose.onNodeWithText("Root acquired").assertDoesNotExist()
    }

    @Test fun runtimeCheckDisablesNativeRootActionsUntilEvidenceArrives() {
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            duoMax = true, firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU, checkingRuntime = true,
        )) } }
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
    }

    @Test fun startupAndNavigationNeverRunNativeOperations() {
        val operations = mutableListOf<Operation>()
        compose.setContent {
            renderedView = LocalView.current
            ChiroruTheme { ChiroruScaffold(AppState(duoMax = true, firmwareLabel = "Duo Max · 5000001307",
                firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU), run = operations::add) }
        }
        compose.onNodeWithText("Chiroru").assertIsDisplayed()
        compose.onNodeWithText("Ready").assertIsDisplayed()
        snapshot("home-light")
        compose.onNodeWithText("Journal").performClick()
        compose.onNodeWithText("No reports yet").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<Operation>(), operations)
    }

    @Test fun mainActionCallsTheModuleOperation() {
        val operations = mutableListOf<Operation>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(duoMax = true, firmwareLabel = "Duo Max · 5000001307",
            firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU), run = operations::add) } }
        compose.onNodeWithText("Acquire root").performScrollTo().performClick()
        assertEquals(listOf(Operation.MODULE), operations)
    }

    @Test fun partialKernelChangeDisablesFurtherRootActions() {
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            duoMax = true, rootMethod = RootMethod.IN_APP, kernelTouched = true, outcome = Outcome.ERROR, firmwareLabel = "Duo Max · 5000001307",
            firmwareSupport = FirmwareSupport.ROOT_AND_KERNEL_SU,
        )) } }
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unsupportedFirmwareOffersDiagnosticsButDisablesRoot() {
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(duoMax = true)) } }
        compose.onNodeWithText("Firmware not supported yet").assertIsDisplayed()
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
    }

    @Test fun rootOnlyProfileEnablesRootAndDisablesKernelSuAndAutoload() {
        val operations = mutableListOf<Operation>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            duoMax = true, rootMethod = RootMethod.IN_APP, firmwareLabel = "Root-only fixture", firmwareSupport = FirmwareSupport.ROOT_ONLY,
        ), run = operations::add) } }
        compose.onNodeWithText("Root is supported").assertIsDisplayed()
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(Operation.ROOT), operations)
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(isToggleable() and hasText("Load KernelSU automatically")).performScrollTo().assertIsNotEnabled()
    }

    @Test fun rootOnlyCanTurnOffAnExistingAutoloadPreference() {
        val changes = mutableListOf<Boolean>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            firmwareLabel = "Root-only fixture", firmwareSupport = FirmwareSupport.ROOT_ONLY, bootEnabled = true,
        ), setBoot = changes::add) } }
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(isToggleable() and hasText("Load KernelSU automatically")).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(false), changes)
    }

    @Test fun bootAdbRequiresPermissionButNotKernelSuSupport() {
        val changes = mutableListOf<Boolean>()
        val state = mutableStateOf(AppState())
        compose.setContent { ChiroruTheme { ChiroruScaffold(state.value,
            initialTab = 2, setAdbOnBoot = changes::add) } }
        val toggle = compose.onNode(isToggleable() and hasText("Keep ADB enabled"))
        toggle.performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(adbSettingsPermission = true) }
        toggle.performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(true), changes)
        compose.runOnIdle { state.value = state.value.copy(adbSettingsPermission = false, adbOnBoot = true) }
        toggle.performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(true, false), changes)
    }

    @Test fun rootOnlyStillRespectsTheKernelTouchedGuard() {
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            firmwareLabel = "Root-only fixture", firmwareSupport = FirmwareSupport.ROOT_ONLY,
            kernelTouched = true, outcome = Outcome.ROOT_READY,
        )) } }
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
    }

    @Test fun darkThemeRenders() {
        compose.setContent {
            renderedView = LocalView.current
            ChiroruTheme("dark") { ChiroruScaffold(AppState()) }
        }
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsDisplayed()
        snapshot("home-dark")
    }

    @Test fun managerDownloadKeepsTheSuccessfulKernelSuStatus() {
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            kernelTouched = true, outcome = Outcome.MODULE_LOADED, managerStep = ManagerStep.DOWNLOADING,
        )) } }
        compose.onNodeWithText("KernelSU loaded").assertIsDisplayed()
        compose.onNodeWithText("Downloading the manager from GitHub…").performScrollTo().assertIsDisplayed()
    }

    @Test fun failedManagerInstallCanBeRetriedWithoutRepeatingRoot() {
        var retries = 0
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            kernelTouched = true, outcome = Outcome.MODULE_LOADED, managerStep = ManagerStep.ERROR,
        ), retryManager = { retries++ }) } }
        compose.onNodeWithText("Retry manager installation").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun failedHooksRemainVisibleWhileInstallingManager() {
        val operations = mutableListOf<Operation>()
        compose.setContent { ChiroruTheme { ChiroruScaffold(AppState(
            kernelTouched = true, outcome = Outcome.MODULE_HOOKS_FAILED, managerStep = ManagerStep.DOWNLOADING,
        ), run = operations::add) } }
        compose.onNodeWithText("KernelSU loaded with errors").assertIsDisplayed()
        compose.onNodeWithText("Module startup scripts failed. Root remains available; see the journal for details.")
            .assertIsDisplayed()
        compose.onNodeWithText("Acquire root").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Downloading the manager from GitHub…").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<Operation>(), operations)
    }

    private fun snapshot(name: String) {
        val directory = File("build/reports/screenshots").apply { mkdirs() }
        compose.runOnIdle {
            val root = renderedView.rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
