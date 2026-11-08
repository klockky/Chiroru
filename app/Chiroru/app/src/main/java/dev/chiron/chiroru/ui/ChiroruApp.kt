@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.chiron.chiroru.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.chiron.chiroru.*
import dev.chiron.chiroru.R
import dev.chiron.chiroru.firmware.FirmwareSupport
import dev.chiron.chiroru.firmware.ManagerSource

@Composable
internal fun ChiroruApp(state: AppState, model: ChiroruViewModel) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        model.refreshBootReport(); model.refreshRuntime(); model.refreshAdbPermission()
    }
    ChiroruScaffold(
        state, model::run, model::setBootEnabled, model::setTheme,
        model::clearReport, model::refreshBootReport,
        model::retryManager,
        model::runConsole, model::stopConsole, model::clearConsole,
        restartConsole = { RestartActivity.restart(context) },
        setRootMethod = model::setRootMethod,
        setBootDelay = model::setBootDelay,
        setAdbOnBoot = model::setAdbOnBoot,
        refreshAdbPermission = model::refreshAdbPermission,
        installBundledManager = { model.installBundledManager(context) },
        uninstallManager = { model.uninstallIncompatibleManager(context) },
        continueManager = model::continueAfterManagerConflict,
        dismissManager = model::dismissManagerConflict,
        refreshManager = model::refreshRuntime,
        initialTab = if ((context as? android.app.Activity)?.intent?.getBooleanExtra("open_console", false) == true) 3 else 0,
    )
}

@Composable
internal fun ChiroruScaffold(
    state: AppState,
    run: (Operation) -> Unit = {},
    setBoot: (Boolean) -> Unit = {},
    setTheme: (String) -> Unit = {},
    clear: () -> Unit = {},
    refresh: () -> Unit = {},
    retryManager: () -> Unit = {},
    runConsole: (String, Boolean) -> Unit = { _, _ -> },
    stopConsole: () -> Unit = {},
    clearConsole: () -> Unit = {},
    restartConsole: () -> Unit = {},
    initialTab: Int = 0,
    setRootMethod: (RootMethod) -> Unit = {},
    setBootDelay: (Int) -> Unit = {},
    installBundledManager: () -> Unit = {},
    setAdbOnBoot: (Boolean) -> Unit = {},
    refreshAdbPermission: () -> Unit = {},
    uninstallManager: () -> Unit = {},
    continueManager: () -> Unit = {},
    dismissManager: () -> Unit = {},
    refreshManager: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val screenStates = rememberSaveableStateHolder()
    val destinations = remember { listOf(R.string.home to Glyph.Home, R.string.journal to Glyph.Journal,
        R.string.settings to Glyph.Settings, R.string.console to Glyph.Console) }
    val keyboardVisible = WindowInsets.isImeVisible
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            if (!keyboardVisible && state.managerConflict == null) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(32.dp), modifier = Modifier.widthIn(max = 640.dp)) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            windowInsets = WindowInsets(0, 0, 0, 0)) {
                            destinations.forEachIndexed { index, (title, icon) ->
                                NavigationBarItem(
                                    selected = tab == index, onClick = { tab = index },
                                    icon = { Icon(icon, contentDescription = null) },
                                    label = { Text(stringResource(title)) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            // The station's Mali GPU need only draw the selected page, without
            // simultaneously composing and blending two full-screen surfaces.
            Box(Modifier.widthIn(max = 800.dp).fillMaxSize()) {
                if (state.managerConflict != null) {
                    BackHandler { dismissManager() }
                    ManagerConflictScreen(state, uninstallManager, continueManager, dismissManager,
                        refreshManager, installBundledManager)
                } else {
                    screenStates.SaveableStateProvider(tab) {
                        when (tab) {
                            0 -> HomeScreen(state, run, { tab = 1 }, retryManager, installBundledManager)
                            1 -> JournalScreen(state, clear, refresh)
                            3 -> ConsoleScreen(state, runConsole, stopConsole, clearConsole, restartConsole)
                            else -> SettingsScreen(state, setBoot, setTheme, setRootMethod, setBootDelay,
                                setAdbOnBoot, refreshAdbPermission)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagerConflictScreen(state: AppState, uninstall: () -> Unit, proceed: () -> Unit,
    dismiss: () -> Unit, refresh: () -> Unit, install: () -> Unit) {
    val conflict = state.managerConflict ?: return
    val busy = state.preparingManager || state.checkingRuntime || state.running != null ||
        state.managerStep?.busy == true || state.console.running
    val canAct = !busy && !state.kernelTouched && !state.runtime.blocksRootOperation
    Page(stringResource(R.string.manager_title), state.firmwareLabel.orEmpty()) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExpressiveEmblem(Glyph.Shield)
                Text(stringResource(if (conflict.status == BundledManagerState.INCOMPATIBLE)
                    R.string.manager_unsupported_title else R.string.manager_continue_title),
                    style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(when (conflict.status) {
                    BundledManagerState.INCOMPATIBLE -> R.string.manager_unsupported_description
                    BundledManagerState.MISSING -> if (conflict.requirement.source == ManagerSource.BUNDLED)
                        R.string.manager_bundled_required else R.string.manager_removed_description
                    BundledManagerState.READY -> R.string.manager_compatible_description
                    BundledManagerState.CHECKING -> R.string.manager_checking
                    BundledManagerState.UNAVAILABLE -> R.string.manager_bundled_unavailable
                }))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.managerInstallError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (conflict.status == BundledManagerState.INCOMPATIBLE) {
                    ExpressiveAction(stringResource(R.string.manager_uninstall), uninstall,
                        enabled = canAct, modifier = Modifier.fillMaxWidth())
                }
                if (conflict.status == BundledManagerState.MISSING && conflict.requirement.source == ManagerSource.BUNDLED) {
                    ExpressiveAction(stringResource(R.string.manager_bundled_install), install,
                        enabled = canAct, modifier = Modifier.fillMaxWidth())
                }
                if (state.kernelTouched || state.runtime.blocksRootOperation) {
                    Text(stringResource(R.string.restart_required))
                }
                FilledTonalButton(onClick = proceed, enabled = canAct && conflict.canContinue,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.manager_continue)) }
                TextButton(onClick = refresh, enabled = !busy) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = dismiss, enabled = !state.preparingManager) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun Page(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.displayLarge)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun HomeScreen(state: AppState, run: (Operation) -> Unit, journal: () -> Unit,
    retryManager: () -> Unit, installBundledManager: () -> Unit) {
    Page(stringResource(R.string.app_name)) {
        StatusCard(state, run, journal)
        if (state.rootMethod == RootMethod.KERNEL_SU) state.bundledManager?.let { manager ->
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.manager_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (manager) {
                        BundledManagerState.READY -> R.string.manager_bundled_ready
                        BundledManagerState.INCOMPATIBLE -> R.string.manager_bundled_conflict
                        BundledManagerState.MISSING -> R.string.manager_bundled_required
                        BundledManagerState.CHECKING -> R.string.manager_checking
                        BundledManagerState.UNAVAILABLE -> R.string.manager_bundled_unavailable
                    }))
                    if (manager == BundledManagerState.MISSING) {
                        TextButton(onClick = installBundledManager,
                            enabled = !state.preparingManager && !state.kernelTouched && state.running == null) {
                            Text(stringResource(R.string.manager_bundled_install))
                        }
                    }
                    if (state.preparingManager) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.managerInstallError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        state.managerStep?.let { ManagerCard(it, retryManager) }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ExpressiveEmblem(Glyph.Device, size = 48.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.device_format, Build.MODEL, Build.VERSION.RELEASE), style = MaterialTheme.typography.bodyMedium)
                Text(state.firmwareLabel
                    ?: stringResource(R.string.firmware_unmatched, Build.VERSION.INCREMENTAL),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        }
    }
}

@Composable
private fun ManagerCard(step: ManagerStep, retry: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.manager_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(step.messageResource), style = MaterialTheme.typography.bodyMedium)
            if (step.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (step == ManagerStep.ERROR) TextButton(onClick = retry) { Text(stringResource(R.string.manager_retry)) }
        }
    }
}

@Composable
private fun StatusCard(state: AppState, run: (Operation) -> Unit, journal: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val title = when {
        state.running != null -> R.string.running
        state.outcome == Outcome.ERROR -> R.string.operation_failed
        state.runtime.adbRoot || state.outcome == Outcome.ADB_ROOT_READY -> R.string.adb_root_ready
        state.outcome == Outcome.MODULE_HOOKS_FAILED -> R.string.module_hooks_failed
        state.runtime.kernelSu == KernelSuState.LOADED || state.outcome == Outcome.MODULE_LOADED -> R.string.module_loaded
        state.runtime.processRoot -> R.string.root_ready
        state.outcome == Outcome.FINISHED -> R.string.check_complete
        state.outcome == Outcome.ROOT_READY -> R.string.root_ready
        !state.duoMax -> R.string.duo_max_only
        !state.firmwareSupport.canRoot -> R.string.firmware_unsupported
        !state.firmwareSupport.canLoadKernelSu -> R.string.firmware_root_only
        else -> R.string.ready
    }
    Surface(shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp, bottomStart = 12.dp, bottomEnd = 36.dp),
        color = if (state.outcome == Outcome.ERROR) colors.errorContainer else colors.primaryContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.displaySmall)
                if (state.running != null) LoadingIndicator(Modifier.size(56.dp), color = colors.onPrimaryContainer)
                else ExpressiveEmblem(if (state.runtime.blocksRootOperation) Glyph.Check else state.rootMethod.icon)
            }
            val description = when {
                state.running != null -> null
                state.processConsoleAvailable -> R.string.console_process_root
                state.runtime.adbRoot || state.outcome == Outcome.ADB_ROOT_READY -> R.string.adb_root_ready_description
                state.outcome == Outcome.MODULE_HOOKS_FAILED -> R.string.module_hooks_failed_description
                state.runtime.kernelSu == KernelSuState.LOADED -> null
                state.outcome != null -> null
                !state.duoMax -> R.string.duo_max_only_description
                !state.firmwareSupport.canRoot -> R.string.firmware_unsupported_description
                !state.firmwareSupport.canLoadKernelSu -> R.string.firmware_root_only_description
                else -> null
            }
            description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            if (state.runtime.adbRoot || state.outcome == Outcome.ADB_ROOT_READY) {
                SelectionContainer {
                    Text("adb shell -t /data/local/tmp/chiroru-su", style = MaterialTheme.typography.bodySmall)
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surface.copy(alpha = .65f)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(state.rootMethod.icon, null, Modifier.size(24.dp))
                    Text(stringResource(state.rootMethod.label),
                        style = MaterialTheme.typography.titleMedium)
                }
            }
            if (state.duoMax && state.firmwareSupport.canRoot && !state.rootMethod.supported(state)) {
                Text(stringResource(R.string.method_unsupported), style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            ExpressiveAction(stringResource(if (state.rootMethod == RootMethod.PROBE) R.string.run_probe else R.string.acquire_root),
                onClick = { run(state.rootMethod.operation) }, enabled = state.rootMethod.canRun(state),
                modifier = Modifier.fillMaxWidth())
            if (state.kernelTouched && state.rootMethod != RootMethod.PROBE) {
                Text(stringResource(R.string.restart_required), style = MaterialTheme.typography.bodySmall)
            }
            if (state.report.isNotEmpty()) TextButton(onClick = journal, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.open_journal))
                Spacer(Modifier.width(8.dp))
                Icon(Glyph.Arrow, null, Modifier.size(18.dp))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Glyph.Console, null, tint = colors.primary)
                Text(stringResource(if (state.runtime.processRoot) R.string.runtime_root_yes else R.string.runtime_root_no),
                    style = MaterialTheme.typography.labelLarge)
            }
        }
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Glyph.Shield, null, tint = colors.onSecondaryContainer)
                Text(stringResource(when {
                    state.checkingRuntime -> R.string.runtime_checking
                    state.runtime.kernelSu == KernelSuState.LOADED -> R.string.runtime_ksu_loaded
                    state.runtime.kernelSu == KernelSuState.NOT_FOUND -> R.string.runtime_ksu_absent
                    else -> R.string.runtime_ksu_unknown
                }), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: Int, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ExpressiveEmblem(icon, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Glyph.Arrow, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun JournalScreen(state: AppState, clear: () -> Unit, refresh: () -> Unit) {
    var boot by rememberSaveable { mutableStateOf(false) }
    val report = if (boot) state.bootReport else state.report
    val context = LocalContext.current
    val reportTitle = stringResource(R.string.report_title)
    val shareTitle = stringResource(R.string.share)
    Page(stringResource(R.string.journal)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!boot, onClick = { boot = false }, label = { Text(stringResource(R.string.session)) })
            FilterChip(boot, onClick = { boot = true; refresh() }, label = { Text(stringResource(R.string.boot_report)) })
        }
        if (report.isBlank()) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExpressiveEmblem(Glyph.Journal, size = 88.dp)
                    Text(stringResource(R.string.empty_journal), style = MaterialTheme.typography.titleLarge)
                    if (boot) Text(stringResource(R.string.empty_boot_description), style = MaterialTheme.typography.bodyLarge)
                    if (boot) TextButton(onClick = refresh) { Text(stringResource(R.string.refresh)) }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Chiroru", report))
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }) { Icon(Glyph.Copy, stringResource(R.string.copy)) }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, reportTitle)
                        putExtra(Intent.EXTRA_TEXT, report)
                    }, shareTitle))
                }) { Icon(Glyph.Share, stringResource(R.string.share)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = if (boot) refresh else clear) { Text(stringResource(if (boot) R.string.refresh else R.string.clear)) }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(report, Modifier.horizontalScroll(rememberScrollState()).padding(20.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp), softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: AppState, setBoot: (Boolean) -> Unit, setTheme: (String) -> Unit,
    setRootMethod: (RootMethod) -> Unit, setBootDelay: (Int) -> Unit,
    setAdbOnBoot: (Boolean) -> Unit, refreshAdbPermission: () -> Unit) {
    var editingDelay by rememberSaveable { mutableStateOf(false) }
    if (editingDelay) BootDelayDialog(state.bootDelaySeconds,
        onDismiss = { editingDelay = false }, onSave = { setBootDelay(it); editingDelay = false })
    Page(stringResource(R.string.settings)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(R.string.root_method)
            BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
                val columns = if (maxWidth >= 520.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RootMethod.entries.chunked(columns).forEach { methods ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            methods.forEach { method ->
                                MethodOption(method, state.rootMethod == method,
                                    state.running == null && state.managerStep?.busy != true,
                                    { setRootMethod(method) }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(topStart = 8.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(state.rootMethod.description), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    if (!state.rootMethod.supported(state)) {
                        Text(stringResource(when {
                            !state.duoMax -> R.string.duo_max_only_description
                            !state.firmwareSupport.canRoot -> R.string.firmware_unsupported_description
                            else -> R.string.method_unsupported
                        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(R.string.appearance)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark).forEach { (value, title) ->
                    val selected = state.theme == value
                    Surface(Modifier.weight(1f).selectable(selected, role = Role.RadioButton, onClick = { setTheme(value) }),
                        shape = RoundedCornerShape(if (selected) 28.dp else 16.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(vertical = 16.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(when (value) { "light" -> Glyph.Sun; "dark" -> Glyph.Moon; else -> Glyph.Device },
                                null, Modifier.size(24.dp))
                            Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(R.string.automation)
            SettingSwitch(R.string.autoload, null, state.bootEnabled, setBoot,
                enabled = (state.duoMax && state.firmwareSupport.canLoadKernelSu) || state.bootEnabled)
            ActionRow(Glyph.Settings, R.string.boot_delay,
                stringResource(R.string.boot_delay_value, state.bootDelaySeconds), onClick = { editingDelay = true })
            if (!state.duoMax || !state.firmwareSupport.canLoadKernelSu) {
                Text(stringResource(R.string.autoload_requires_kernelsu), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.autoload_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingSwitch(R.string.adb_on_boot, R.string.adb_on_boot_description, state.adbOnBoot, setAdbOnBoot,
                enabled = state.adbSettingsPermission || state.adbOnBoot)
            Text(stringResource(R.string.adb_on_boot_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.adbSettingsPermission) {
                Text(stringResource(R.string.adb_permission_needed), style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(BootAdb.GRANT_COMMAND, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = refreshAdbPermission) { Text(stringResource(R.string.adb_permission_check)) }
            }
        }
    }
}

@Composable
private fun BootDelayDialog(seconds: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(seconds.toString()) }
    val parsed = BootDelay.parse(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.boot_delay)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.boot_delay_hint))
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                    label = { Text(stringResource(R.string.boot_delay_seconds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = parsed == null,
                    supportingText = { Text(stringResource(R.string.boot_delay_range)) })
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun MethodOption(method: RootMethod, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 28.dp else 16.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), label = "method shape")
    val color by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "method color")
    Surface(modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(corner), color = color) {
        Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(if (selected) 20.dp else 12.dp),
                color = if (selected) colors.primary else colors.surfaceContainerHighest) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(method.icon, null, Modifier.size(24.dp))
                }
            }
            Text(stringResource(method.label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f))
            RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun SettingSwitch(title: Int, description: Int?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
        Row(
            Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
}

@Preview(showBackground = true, locale = "ru", device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun HomePreview() { ChiroruTheme { ChiroruScaffold(AppState()) } }

@Preview(showBackground = true, locale = "ru", device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun DarkPreview() { ChiroruTheme("dark") { ChiroruScaffold(AppState(outcome = Outcome.MODULE_LOADED, kernelTouched = true)) } }
