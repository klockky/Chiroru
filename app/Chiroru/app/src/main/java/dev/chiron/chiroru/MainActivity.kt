package dev.chiron.chiroru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dev.chiron.chiroru.ui.ChiroruApp
import dev.chiron.chiroru.ui.ChiroruTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: ChiroruViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val dark = when (state.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { model.handleIntent(intent) }
            ChiroruTheme(state.theme) {
                ChiroruApp(state, model)
            }
        }
    }
}
