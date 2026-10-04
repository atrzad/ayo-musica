package io.github.atrzad.ayomusica.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowManager
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
        io.github.atrzad.ayomusica.util.AppLog.init(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val theme by viewModel.prefs.theme.collectAsStateWithLifecycle()
            val mode by viewModel.prefs.mode.collectAsStateWithLifecycle()
            val useMode by viewModel.prefs.useMode.collectAsStateWithLifecycle()
            val fullscreen by viewModel.prefs.fullscreen.collectAsStateWithLifecycle()
            // Car mode: the screen lies sideways and stays on.
            LaunchedEffect(useMode) {
                val car = useMode == UseMode.Car
                requestedOrientation = if (car) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (car) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val dark = when (mode) {
                Mode.Auto -> isSystemInDarkTheme()
                Mode.Light -> false
                Mode.Dark, Mode.Amoled -> true
            }
            // Status and navigation bar icons follow the chosen light/dark, not only the system's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(style, style)
            }
            // Tela cheia: status and navigation bars hidden; a swipe from the edge shows them for a moment.
            LaunchedEffect(fullscreen) {
                val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                val types = androidx.core.view.WindowInsetsCompat.Type.systemBars()
                if (fullscreen) {
                    bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    bars.hide(types)
                } else {
                    bars.show(types)
                }
            }
            AyoTheme(theme, mode) { App(viewModel, packageManager.getPackageInfo(packageName, 0).versionName ?: "") }
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
