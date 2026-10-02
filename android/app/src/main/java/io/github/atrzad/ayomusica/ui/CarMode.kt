package io.github.atrzad.ayomusica.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.playback.PlayerConnection
import io.github.atrzad.ayomusica.playback.PlayerUi

/** Driving: the screen lies sideways, only the song and three huge buttons (plus swipe ← →). */
@Composable
fun CarScreen(
    ui: PlayerUi,
    song: Song?,
    player: PlayerConnection,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onShuffleAll: () -> Unit,
    onExit: () -> Unit,
) {
    val position = rememberPosition(player, ui, 500)
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxSize().systemBarsPadding().padding(20.dp).pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(onDragStart = { total = 0f },
                    onDragEnd = { if (total > threshold) player.next() else if (total < -threshold) player.back() }) { change, amount ->
                    total += amount
                    change.consume()
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(Modifier.fillMaxHeight().weight(0.42f), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                Cover(song?.artUri ?: ui.current?.localConfiguration?.uri, Modifier.size(side),
                    with(LocalDensity.current) { side.roundToPx() }.coerceAtMost(900), 20.dp)
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onExit) { Text("Sair do modo carro") }
                }
                if (ui.current == null) {
                    Text("Nada tocando", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onShuffleAll, Modifier.fillMaxWidth().height(88.dp)) {
                        Icon(Icons.Rounded.Shuffle, null, Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Tocar tudo aleatório", fontSize = 22.sp)
                    }
                    return@Column
                }
                Text(song?.title ?: ui.current.title(), fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 40.sp)
                Text(song?.shownArtist ?: ui.current.artist(), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { if (ui.durationMs > 0) (position.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f },
                    Modifier.fillMaxWidth().height(6.dp))
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = player::back, Modifier.size(96.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Voltar", Modifier.size(56.dp))
                    }
                    FilledIconButton(onClick = player::toggle, Modifier.size(120.dp)) {
                        Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (ui.isPlaying) "Pausar" else "Tocar", Modifier.size(72.dp))
                    }
                    FilledTonalIconButton(onClick = { player.next() }, Modifier.size(96.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Próxima", Modifier.size(56.dp))
                    }
                    IconButton(onClick = onFavorite, Modifier.size(72.dp)) {
                        Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Curtir",
                            Modifier.size(40.dp), tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
