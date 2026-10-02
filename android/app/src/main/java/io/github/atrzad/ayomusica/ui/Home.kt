package io.github.atrzad.ayomusica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.playback.PlayerConnection
import io.github.atrzad.ayomusica.playback.PlayerUi
import kotlinx.coroutines.launch
import kotlin.math.abs

/** "AYO PLAYER" (tap: settings) and, on the right, what is playing (tap: open; drag ←/→: back/next). */
@Composable
fun HomeHeader(ui: PlayerUi, song: Song?, player: PlayerConnection, onSettings: () -> Unit, onOpenPlayer: () -> Unit) {
    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AYO PLAYER", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSettings).padding(4.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            NowPlayingChip(ui, song, player, onOpenPlayer)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
fun NowPlayingChip(ui: PlayerUi, song: Song?, player: PlayerConnection, onOpen: () -> Unit) {
    val item = ui.current ?: return
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    Row(
        Modifier.graphicsLayer { translationX = slide.value; alpha = 1f - abs(slide.value) / 300f }
            .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total > threshold) player.next() else if (total < -threshold) player.back()
                        scope.launch { slide.animateTo(0f) }
                    },
                ) { change, amount ->
                    total += amount
                    change.consume()
                    scope.launch { slide.snapTo((total * 0.5f).coerceIn(-90f, 90f)) }
                }
            }
            .clickable(onClick = onOpen).padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = player::toggle, Modifier.size(36.dp)) {
            Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.isPlaying) "Pausar" else "Tocar")
        }
        Text(song?.title ?: item.title(), Modifier.widthIn(max = 140.dp).padding(end = 8.dp), maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Cover(song?.artUri ?: item.localConfiguration?.uri, 36.dp, corner = 8.dp)
    }
}

/** Inside a page (album, settings...): back and the title. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
            Text(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        HorizontalDivider()
    }
}

/** Simplified mode: the tabs as a fixed bar (no hold and drag). */
@Composable
fun FixedTabs(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(selected = tab == current, onClick = { onSelect(tab) },
                icon = { Icon(tab.icon(), null) }, label = { Text(tab.title) })
        }
    }
}

@Composable
fun Spacing(width: Int) = Spacer(Modifier.width(width.dp))
