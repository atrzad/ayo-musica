package io.github.atrzad.ayomusica.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
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
 * The round button at the bottom: hold and drag toward a tab to open it. The tabs open as a fan centered
 * above the button, however many there are; the direction of the drag picks one (no exact spot to hit),
 * and the one pointed at grows. A tap shows the tabs to tap instead.
 */
@Composable
fun RadialTabs(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val radius = with(density) { (if (tabs.size > 4) 128.dp else 116.dp).toPx() }
    val minDrag = with(density) { 28.dp.toPx() }
    // A fan centered on "straight up": wider with more tabs, never wider than 160°.
    val span = if (tabs.size <= 1) 0.0 else minOf(160.0, 46.0 * (tabs.size - 1))
    val angles = tabs.indices.map { i -> 90.0 + span / 2 - if (tabs.size <= 1) 0.0 else i * span / (tabs.size - 1) }
    val positions = angles.map { angle ->
        val radians = Math.toRadians(angle)
        Offset((cos(radians) * radius).toFloat(), (-sin(radians) * radius).toFloat())
    }
    /** The tab in the direction of the drag (closest angle), once the finger has moved a little. */
    fun hovered(): Int? {
        if (drag.getDistance() < minDrag || drag.y > minDrag) return null
        val angle = Math.toDegrees(atan2(-drag.y.toDouble(), drag.x.toDouble()))
        return angles.indices.minByOrNull { abs(angles[it] - angle) }
    }
    val grow by animateFloatAsState(if (open) 1f else 0f, label = "radial")
    val pointed = if (dragging) hovered() else null

    Box(modifier.fillMaxWidth().height(if (open) 250.dp else 84.dp), contentAlignment = Alignment.BottomCenter) {
        if (open || grow > 0f) {
            tabs.forEachIndexed { index, tab ->
                val hot = pointed == index
                val selected = !dragging && tab == current
                // The one pointed at grows and moves out a bit; the others step back.
                val emphasis by animateFloatAsState(
                    when {
                        hot -> 1.35f
                        pointed != null -> 0.85f
                        else -> 1f
                    },
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    label = "tab",
                )
                val reach = if (hot) 1.12f else 1f
                val p = positions[index] * grow * reach
                Column(
                    Modifier.padding(bottom = 18.dp)
                        .offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                        .scale((0.6f + 0.4f * grow) * emphasis)
                        .clickable(enabled = open && !dragging) { open = false; onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape)
                            .background(when {
                                hot || selected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            })
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon(), tab.title,
                            tint = if (hot || selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                    Text(tab.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Box(
            Modifier.padding(bottom = 12.dp).size(62.dp)
                .shadow(10.dp, CircleShape)  // floating over the list
                .clip(CircleShape)
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
                        val choice = hovered()
                        dragging = false
                        if (moved) {
                            choice?.let { onSelect(tabs[it]) }
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
