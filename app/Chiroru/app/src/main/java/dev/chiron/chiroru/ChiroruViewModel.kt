package dev.chiron.chiroru

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.chiron.chiroru.firmware.FirmwareEnvironment
import dev.chiron.chiroru.firmware.FirmwareSupport
import dev.chiron.chiroru.firmware.ManagerSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal data class AppState(
    val running: Operation? = null,
    val lastOperation: Operation? = null,
    val outcome: Outcome? = null,
    val kernelTouched: Boolean = false,
    val report: String = "",
    val bootReport: String = "",
    val bootEnabled: Boolean = false,
    val adbOnBoot: Boolean = false,
    val adbSettingsPermission: Boolean = false,
    val bootDelaySeconds: Int = BootDelay.DEFAULT_SECONDS,
    val theme: String = "system",
    val managerStep: ManagerStep? = null,
    val firmwareLabel: String? = null,
    val firmwareSupport: FirmwareSupport = FirmwareSupport.UNSUPPORTED,
    val runtime: RuntimeStatus = RuntimeStatus(),
    val checkingRuntime: Boolean = false,
    val runtimeChecked: Boolean = false,
    val adbRootSupported: Boolean = false,
    val console: ConsoleState = ConsoleState(),
    val consoleRootAvailable: Boolean = false,
    val processConsoleAvailable: Boolean = false,
    val rootMethod: RootMethod = RootMethod.KERNEL_SU,
    val duoMax: Boolean = false,
    val bundledManager: BundledManagerState? = null,
    val preparingManager: Boolean = false,
    val managerInstallError: String? = null,
)

internal class ChiroruViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val firmware = FirmwareEnvironment.inspect(application)
    private val mutableState = MutableStateFlow(AppState(
        bootEnabled = preferences.bootEnabled,
        adbOnBoot = preferences.adbOnBoot,
        adbSettingsPermission = BootAdb.hasPermission(application),
        bootDelaySeconds = preferences.bootDelaySeconds,
        theme = preferences.theme,
        rootMethod = preferences.rootMethod,
        duoMax = firmware.identity.isDuoMax,
        kernelTouched = Loader.kernelTouched,
        firmwareLabel = firmware.profile?.label,
        firmwareSupport = firmware.support,
        adbRootSupported = firmware.profile?.adbRoot != null,
        bundledManager = if (firmware.profile?.kernelSu?.manager?.source == ManagerSource.BUNDLED)
            BundledManagerState.CHECKING else null,
    ))
    val state = mutableState.asStateFlow()
    private var intentHandled = false
    private var runtimeJob: Job? = null
    private val console = ConsoleSession(
        "${application.applicationInfo.nativeLibraryDir}/libchiroru_console.so", application.filesDir) { command ->
        if (Loader.kernelTouched) NativeConsoleProcess(command) else null
    }

    init {
        viewModelScope.launch { console.state.collect { value -> mutableState.update { it.copy(console = value) } } }
        refreshBootReport()
        refreshRuntime()
        viewModelScope.launch {
            // The boot service can be provisioning the manager before an Activity exists.
            ManagerInstaller.progress.collect { step ->
                mutableState.update {
                    it.copy(managerStep = step, kernelTouched = Loader.kernelTouched,
                        outcome = it.outcome ?: Loader.moduleOutcome)
                }
            }
        }
    }

    fun handleIntent(intent: Intent) {
        // Preserve the original CLI entry points without replaying on rotation.
        if (intentHandled) return
        intentHandled = true
        val operation = when (intent.getStringExtra("mode")) {
            "probe" -> Operation.PROBE
            "root" -> Operation.ROOT
            "root+ko" -> Operation.MODULE
            "root+ko-safe" -> Operation.MODULE_SAFE
            "root+adb" -> Operation.ADB_ROOT
            else -> return
        }
        viewModelScope.launch {
            runtimeJob?.join()
            run(operation)
        }
    }

    fun run(operation: Operation) {
        if (mutableState.value.preparingManager) return
        if (console.state.value.running) return
        if (mutableState.value.running != null) return
        if (mutableState.value.managerStep?.busy == true) return
        if (operation != Operation.PROBE && (mutableState.value.checkingRuntime ||
            mutableState.value.runtime.blocksRootOperation || Loader.kernelTouched)) return
        mutableState.update { it.copy(running = operation) }
        viewModelScope.launch {
            // Let Compose draw the pending state. The native call itself must run on main.
            delay(120)
            val report = Loader.run(getApplication(), operation)
            val result = ReportResult.parse(operation, report)
            val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
            mutableState.update {
                it.copy(
                    running = null, lastOperation = operation, outcome = result.outcome,
                    kernelTouched = Loader.kernelTouched,
                    report = "[$time] ${operation.name}\n$report\n\n${it.report}",
                )
            }
            if (ManagerInstaller.shouldRun(operation, report)) ensureManager()
            refreshAdbPermission()
            refreshRuntime()
        }
    }

    fun retryManager() {
        if (mutableState.value.running != null || mutableState.value.managerStep?.busy == true ||
            !Loader.moduleLoaded) return
        viewModelScope.launch { ensureManager() }
    }

    fun installBundledManager(context: android.content.Context) {
        if (mutableState.value.preparingManager || mutableState.value.running != null ||
            Loader.kernelTouched || mutableState.value.runtime.processRoot) return
        mutableState.update { it.copy(preparingManager = true, managerInstallError = null) }
        viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { ManagerPackages.installIntent(context) }
                context.startActivity(intent)
            } catch (error: Exception) {
                mutableState.update { it.copy(managerInstallError = error.message) }
            } finally {
                mutableState.update { it.copy(preparingManager = false) }
            }
        }
    }

    private suspend fun ensureManager() {
        val result = ManagerInstaller.ensureInstalled(getApplication()) { step ->
            mutableState.update { it.copy(managerStep = step) }
        }
        mutableState.update { it.copy(report = "${result.report}\n${it.report}") }
    }

    fun setBootEnabled(enabled: Boolean) {
        if (enabled && !mutableState.value.firmwareSupport.canLoadKernelSu) return
        preferences.bootEnabled = enabled
        if (!enabled) getApplication<Application>().stopService(Intent(getApplication(), LoadService::class.java))
        mutableState.update { it.copy(bootEnabled = enabled) }
    }

    fun setTheme(theme: String) {
        preferences.theme = theme
        mutableState.update { it.copy(theme = theme) }
    }

    fun setAdbOnBoot(enabled: Boolean) {
        val granted = BootAdb.hasPermission(getApplication())
        if (!enabled || granted) preferences.adbOnBoot = enabled
        mutableState.update { it.copy(adbOnBoot = preferences.adbOnBoot, adbSettingsPermission = granted) }
    }

    fun refreshAdbPermission() {
        mutableState.update { it.copy(adbOnBoot = preferences.adbOnBoot,
            adbSettingsPermission = BootAdb.hasPermission(getApplication())) }
    }

    fun setBootDelay(seconds: Int) {
        if (seconds !in 0..BootDelay.MAX_SECONDS) return
        preferences.bootDelaySeconds = seconds
        mutableState.update { it.copy(bootDelaySeconds = seconds) }
    }

    fun setRootMethod(method: RootMethod) {
        if (mutableState.value.running != null || mutableState.value.managerStep?.busy == true) return
        preferences.rootMethod = method
        mutableState.update { it.copy(rootMethod = method) }
    }

    fun refreshBootReport() {
        viewModelScope.launch {
            val report = runCatching { AppIo.readBootReport(getApplication()) }
                .getOrElse { "Cannot read boot report: $it" }
            mutableState.update { it.copy(bootReport = report, kernelTouched = Loader.kernelTouched) }
        }
    }

    fun refreshRuntime() {
        if (runtimeJob?.isActive == true || mutableState.value.running != null) return
        mutableState.update { it.copy(checkingRuntime = true) }
        runtimeJob = viewModelScope.launch {
            val (kernelSu, adbRoot, consoleRoot) = withContext(Dispatchers.IO) {
                Triple(RuntimeDetector.kernelSu(), RuntimeDetector.adbRoot(),
                    runCatching { NativeBridge.consoleRootPresent() }.getOrDefault(false))
            }
            val runtime = RuntimeStatus(RuntimeDetector.processRoot(), kernelSu, adbRoot)
            val processConsole = withContext(Dispatchers.IO) {
                runCatching { NativeBridge.processConsolePresent() }.getOrDefault(false)
            }
            val bundledManager = if (!Loader.kernelTouched && !runtime.processRoot) withContext(Dispatchers.IO) {
                firmware.profile?.kernelSu?.manager?.takeIf { it.source == ManagerSource.BUNDLED }?.let {
                    runCatching { ManagerPackages.state(getApplication(), it) }.getOrDefault(BundledManagerState.UNAVAILABLE)
                }
            } else mutableState.value.bundledManager
            Loader.runtime = runtime
            android.util.Log.i(Loader.TAG, "Runtime: processRoot=${runtime.processRoot} KernelSU=${runtime.kernelSu} adbRoot=${runtime.adbRoot}")
            mutableState.update { it.copy(runtime = runtime, consoleRootAvailable = consoleRoot || processConsole,
                processConsoleAvailable = processConsole,
                bundledManager = bundledManager,
                checkingRuntime = false, runtimeChecked = true) }
        }
    }

    fun clearReport() { mutableState.update { it.copy(report = "") } }

    fun runConsole(command: String, root: Boolean) {
        if ((Loader.kernelTouched || mutableState.value.runtime.processRoot) &&
            (!mutableState.value.processConsoleAvailable || !root)) return
        if (mutableState.value.running != null || mutableState.value.managerStep?.busy == true) return
        if (console.reserve(command, root)) viewModelScope.launch(Dispatchers.IO) {
            console.execute(command, root)
            withContext(Dispatchers.Main) { refreshRuntime() }
        }
    }
    fun stopConsole() { console.stop() }
    fun clearConsole() { console.clear() }
    override fun onCleared() { console.close(); super.onCleared() }
}
