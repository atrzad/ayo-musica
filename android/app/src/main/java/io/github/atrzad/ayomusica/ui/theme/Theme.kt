package io.github.atrzad.ayomusica.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Black and white like the desktop app: the system decides light or dark, accents stay achromatic.
private val Light = lightColorScheme(
    primary = Color(0xFF111114), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E2E6), onPrimaryContainer = Color(0xFF111114),
    secondary = Color(0xFF3A3A40), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E2E6), onSecondaryContainer = Color(0xFF111114),
    tertiary = Color(0xFF3A3A40), onTertiary = Color.White,
    background = Color(0xFFF7F7F8), onBackground = Color(0xFF111114),
    surface = Color(0xFFF7F7F8), onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFE6E6EA), onSurfaceVariant = Color(0xFF55555C),
    surfaceTint = Color(0xFF111114), outline = Color(0xFFA0A0A8), outlineVariant = Color(0xFFD4D4D8),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F1F3),
    surfaceContainer = Color(0xFFECECEF), surfaceContainerHigh = Color(0xFFE6E6EA),
    surfaceContainerHighest = Color(0xFFE0E0E4), inverseSurface = Color(0xFF26262B),
    inverseOnSurface = Color(0xFFF1F1F3), inversePrimary = Color(0xFFE2E2E6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF4F4F5), onPrimary = Color(0xFF111114),
    primaryContainer = Color(0xFF34343A), onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFFD4D4D8), onSecondary = Color(0xFF111114),
    secondaryContainer = Color(0xFF34343A), onSecondaryContainer = Color(0xFFF4F4F5),
    tertiary = Color(0xFFD4D4D8), onTertiary = Color(0xFF111114),
    background = Color(0xFF111114), onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF111114), onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF2A2A30), onSurfaceVariant = Color(0xFFB4B4BC),
    surfaceTint = Color(0xFFF4F4F5), outline = Color(0xFF6E6E76), outlineVariant = Color(0xFF3A3A40),
    surfaceContainerLowest = Color(0xFF0B0B0D), surfaceContainerLow = Color(0xFF18181C),
    surfaceContainer = Color(0xFF1C1C21), surfaceContainerHigh = Color(0xFF232328),
    surfaceContainerHighest = Color(0xFF2A2A30), inverseSurface = Color(0xFFE6E6EA),
    inverseOnSurface = Color(0xFF26262B), inversePrimary = Color(0xFF34343A),
)

@Composable
fun AyoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
