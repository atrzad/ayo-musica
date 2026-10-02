package io.github.atrzad.ayomusica.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrzad.ayomusica.ui.theme.AyoTheme
import io.github.atrzad.ayomusica.ui.theme.Mode

class MainActivity : ComponentActivity() {
    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            val mode by viewModel.mode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                Mode.Auto -> isSystemInDarkTheme()
                Mode.Light -> false
                Mode.Dark -> true
            }
            // Status and navigation bar icons follow the chosen light/dark, not only the system's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(style, style)
            }
            AyoTheme(theme, mode) { App(viewModel) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** "Abrir com Ayo Música" from a file manager or a download. */
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) viewModel.openExternal(uri)
    }
}
