@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.chiron.chiroru.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.chiron.chiroru.AppState
import dev.chiron.chiroru.R

@Composable
internal fun ConsoleScreen(state: AppState, run: (String, Boolean) -> Unit, stop: () -> Unit, clear: () -> Unit, restart: () -> Unit) {
    if ((state.kernelTouched || state.runtime.processRoot) && !state.processConsoleAvailable) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ExpressiveEmblem(Glyph.Console, size = 88.dp)
            Text(stringResource(R.string.console_restart_hint), style = MaterialTheme.typography.bodyLarge)
            ExpressiveAction(stringResource(R.string.console_restart), onClick = restart,
                enabled = !state.checkingRuntime && state.running == null && state.managerStep?.busy != true)
        }
        return
    }
    var command by rememberSaveable { mutableStateOf("") }
    var selectedRoot by rememberSaveable { mutableStateOf(false) }
    val root = state.processConsoleAvailable || selectedRoot
    val console = state.console
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.isImeVisible
    val canRun = command.isNotBlank() && !console.running && state.running == null &&
        state.managerStep?.busy != true && (!root || state.consoleRootAvailable)
    val submit = { if (canRun) { keyboard?.hide(); run(command, root) } }
    val scroll = rememberScrollState()
    var followOutput by remember { mutableStateOf(true) }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress) followOutput = scroll.maxValue - scroll.value < 160
    }
    // Layout updates maxValue after the text changes (also when IME resizes us).
    LaunchedEffect(console.output, scroll.maxValue) {
        if (followOutput) scroll.scrollTo(scroll.maxValue)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(if (root) "# root" else "$ shell",
                style = if (keyboardVisible) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text("Root")
            Switch(checked = root, onCheckedChange = { selectedRoot = it }, modifier = Modifier.semantics { contentDescription = "Root" },
                enabled = !state.processConsoleAvailable && !console.running && (state.consoleRootAvailable || root))
            TextButton(onClick = clear) { Text(stringResource(R.string.console_clear)) }
        }
        if (!keyboardVisible) Text(stringResource(when {
            state.processConsoleAvailable -> R.string.console_process_root
            state.consoleRootAvailable -> R.string.console_hint
            else -> R.string.console_no_root
        }),
            style = MaterialTheme.typography.bodySmall)
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            SelectionContainer {
                Text(console.output.ifEmpty { stringResource(R.string.console_empty) },
                     modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(if (keyboardVisible) 12.dp else 20.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        console.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2) }
        Text(when {
            console.running -> stringResource(R.string.console_running)
            console.stopped -> stringResource(R.string.console_stopped)
            console.exitCode != null -> stringResource(R.string.console_exit, console.exitCode)
            else -> stringResource(R.string.console_fresh_shell)
        }, style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = command, onValueChange = { if (it.length <= 16384) command = it },
                 modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                 label = { Text(stringResource(R.string.console_command)) },
                singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }))
            if (console.running) ExpressiveAction(stringResource(R.string.console_stop), onClick = stop, icon = Glyph.Stop)
            else ExpressiveAction(stringResource(R.string.console_run), onClick = { submit() }, enabled = canRun)
        }
    }
}
