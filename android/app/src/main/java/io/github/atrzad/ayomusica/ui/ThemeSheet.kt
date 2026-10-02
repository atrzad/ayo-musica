package io.github.atrzad.ayomusica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.atrzad.ayomusica.ui.theme.Mode
import io.github.atrzad.ayomusica.ui.theme.Palette
import io.github.atrzad.ayomusica.ui.theme.Themes
import io.github.atrzad.ayomusica.ui.theme.WALLPAPER
import io.github.atrzad.ayomusica.ui.theme.color

/** Pick a color theme and light/dark; the same themes as the desktop app. */
@Composable
fun ThemeContent(current: String, mode: Mode, dark: Boolean, onTheme: (String) -> Unit, onMode: (Mode) -> Unit) {
    val themes = Themes.all(LocalContext.current)
    run {
        Column(Modifier.navigationBarsPadding()) {
            Text("Claro ou escuro", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { option ->
                    FilterChip(mode == option, { onMode(option) }, label = { Text(option.title) })
                }
            }
            LazyColumn(Modifier.padding(bottom = 16.dp).weight(1f, fill = false)) {
                if (Themes.wallpaperAvailable) {
                    item {
                        ThemeRow("Cores do papel de parede", current == WALLPAPER, { onTheme(WALLPAPER) }) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Wallpaper, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                items(themes, key = { it.id }) { theme ->
                    ThemeRow(theme.name, theme.id == current, { onTheme(theme.id) }) {
                        Swatch(if (dark) theme.dark else theme.light)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(name: String, selected: Boolean, onClick: () -> Unit, preview: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        preview()
        Spacer(Modifier.width(16.dp))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (selected) Icon(Icons.Rounded.Check, "Escolhido")
    }
}

/** A tiny preview: the background with a line of "text" and an accent dot. */
@Composable
private fun Swatch(palette: Palette) {
    Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(color(palette.bg))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))) {
        Box(Modifier.padding(start = 8.dp, top = 10.dp).size(width = 26.dp, height = 5.dp)
            .clip(RoundedCornerShape(3.dp)).background(color(palette.fg)))
        Box(Modifier.padding(start = 8.dp, top = 21.dp).size(width = 18.dp, height = 5.dp)
            .clip(RoundedCornerShape(3.dp)).background(color(palette.fg).copy(alpha = 0.5f)))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(16.dp).clip(CircleShape)
            .background(color(palette.accent)))
    }
}
