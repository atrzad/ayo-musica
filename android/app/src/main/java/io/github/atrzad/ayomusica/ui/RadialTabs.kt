package io.github.atrzad.ayomusica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun Tab.icon(): ImageVector = when (this) {
    Tab.Songs -> Icons.Rounded.MusicNote
    Tab.Playlists -> Icons.AutoMirrored.Rounded.PlaylistPlay
    Tab.Albums -> Icons.Rounded.Album
    Tab.Artists -> Icons.Rounded.Person
    Tab.Genres -> Icons.Rounded.Category
    Tab.Folders -> Icons.Rounded.Folder
}

/**
 * The round button at the bottom: hold and drag toward a tab to open it (with the default tabs:
 * ← Músicas, ↖ Playlists, ↑ Álbuns, ↗ Artistas). A tap shows the tabs to tap instead.
 */
@Composable
fun RadialTabs(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val radius = with(density) { 118.dp.toPx() }
    // 45° apart starting at the left; six tabs share the half circle.
    val step = if (tabs.size <= 5) 45.0 else 180.0 / (tabs.size - 1)
    val positions = tabs.indices.map { i ->
        val angle = Math.toRadians(180.0 - i * step)
        Offset((cos(angle) * radius).toFloat(), (-sin(angle) * radius).toFloat())
    }
    fun hovered(): Int? = positions.indices.minByOrNull { (positions[it] - drag).getDistance() }
        ?.takeIf { drag.getDistance() > radius * 0.35f && (positions[it] - drag).getDistance() < radius * 0.6f }
    val grow by animateFloatAsState(if (open) 1f else 0f, label = "radial")

    Box(modifier.fillMaxWidth().height(if (open) 230.dp else 84.dp), contentAlignment = Alignment.BottomCenter) {
        if (open || grow > 0f) {
            tabs.forEachIndexed { index, tab ->
                val p = positions[index] * grow
                val hot = (dragging && hovered() == index) || (!dragging && tab == current)
                Column(
                    Modifier.padding(bottom = 10.dp)
                        .offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                        .scale(0.6f + 0.4f * grow)
                        .clickable(enabled = open && !dragging) { open = false; onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(if (hot) 58.dp else 50.dp).clip(CircleShape)
                            .background(if (hot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon(), tab.title,
                            tint = if (hot) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                    Text(tab.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Box(
            Modifier.padding(bottom = 12.dp).size(62.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(tabs) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val wasOpen = open
                        drag = Offset.Zero
                        var moved = false
                        open = true
                        dragging = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            drag += change.positionChange()
                            if (drag.getDistance() > viewConfiguration.touchSlop) moved = true
                            change.consume()
                        }
                        dragging = false
                        if (moved) {
                            hovered()?.let { onSelect(tabs[it]) }
                            open = false
                        } else {
                            open = !wasOpen  // a tap opens the tabs to tap (or closes them)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(current.icon(), "Abas: segure e arraste", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
