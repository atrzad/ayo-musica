package io.github.atrzad.ayomusica.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One palette of a theme, the same file the desktop app reads (data/themes/themes.json). */
@Serializable
data class Palette(val bg: String, val fg: String, val accent: String, val onAccent: String)

@Serializable
data class ThemeSpec(val id: String, val name: String, val light: Palette, val dark: Palette)

@Serializable
private data class ThemeFile(val themes: List<ThemeSpec>)

enum class Mode(val title: String) { Auto("Automático"), Light("Claro"), Dark("Escuro") }

const val MONO = "mono"
/** Android 12+: the colors Android takes from the wallpaper. */
const val WALLPAPER = "wallpaper"

object Themes {
    @Volatile private var cache: List<ThemeSpec>? = null

    fun all(context: Context): List<ThemeSpec> = cache ?: runCatching {
        val text = context.assets.open("themes.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<ThemeFile>(text).themes
    }.getOrDefault(emptyList()).also { cache = it }

    fun parse(text: String): List<ThemeSpec> = Json { ignoreUnknownKeys = true }.decodeFromString<ThemeFile>(text).themes

    val wallpaperAvailable: Boolean get() = Build.VERSION.SDK_INT >= 31
}

fun color(hex: String): Color = Color(("FF" + hex.removePrefix("#")).toLong(16))

/** A full Material scheme from four colors: surfaces step from the background toward the text color. */
fun scheme(palette: Palette, dark: Boolean): ColorScheme {
    val bg = color(palette.bg)
    val fg = color(palette.fg)
    val accent = color(palette.accent)
    val onAccent = color(palette.onAccent)
    fun surface(amount: Float) = lerp(bg, fg, amount)
    val lift = if (dark) fg else Color.White
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = lerp(bg, accent, 0.24f), onPrimaryContainer = fg,
        secondary = lerp(fg, accent, 0.5f), onSecondary = bg,
        secondaryContainer = lerp(bg, accent, 0.18f), onSecondaryContainer = fg,
        tertiary = accent, onTertiary = onAccent,
        tertiaryContainer = lerp(bg, accent, 0.24f), onTertiaryContainer = fg,
        background = bg, onBackground = fg, surface = bg, onSurface = fg,
        surfaceVariant = surface(0.09f), onSurfaceVariant = lerp(fg, bg, 0.3f),
        surfaceTint = accent, outline = lerp(fg, bg, 0.5f), outlineVariant = surface(0.16f),
        surfaceContainerLowest = lerp(bg, lift, if (dark) 0f else 0.6f),
        surfaceContainerLow = surface(0.03f), surfaceContainer = surface(0.05f),
        surfaceContainerHigh = surface(0.08f), surfaceContainerHighest = surface(0.11f),
        surfaceBright = surface(0.12f), surfaceDim = bg,
        inverseSurface = fg, inverseOnSurface = bg, inversePrimary = lerp(accent, bg, 0.4f),
    )
}

@Composable
fun AyoTheme(themeId: String = MONO, mode: Mode = Mode.Auto, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) {
        Mode.Auto -> isSystemInDarkTheme()
        Mode.Light -> false
        Mode.Dark -> true
    }
    val colors = if (themeId == WALLPAPER && Themes.wallpaperAvailable) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val spec = Themes.all(context).let { all -> all.firstOrNull { it.id == themeId } ?: all.firstOrNull() }
        if (spec == null) (if (dark) darkColorScheme() else lightColorScheme())
        else scheme(if (dark) spec.dark else spec.light, dark)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
