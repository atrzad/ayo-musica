package io.github.atrzad.ayomusica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.playback.PlayerConnection
import io.github.atrzad.ayomusica.playback.PlayerUi
import io.github.atrzad.ayomusica.playback.SleepTimer
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Tocando agora: back and like at the top, the cover, title and "artist - album", play in the middle,
 * shuffle and repeat at the bottom. Swipe ← restarts (twice: previous song), → next, ↓ opens the lyrics.
 */
@Composable
fun PlayerScreen(
    ui: PlayerUi,
    song: Song?,
    player: PlayerConnection,
    favorite: Boolean,
    onFavorite: () -> Unit,
    visualizer: Boolean,
    onVisualizer: () -> Unit,
    sessionId: Int,
    sleep: SleepTimer.Mode,
    onSleep: (Int?) -> Unit,
    onBack: () -> Unit,
    onLyrics: () -> Unit,
    onArtist: () -> Unit,
    /** Simplified mode: buttons for previous/next and the lyrics instead of relying on swipes. */
    simple: Boolean = false,
) {
    val item = ui.current
    var menu by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    val position = rememberPosition(player, ui, 200)
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val title = song?.title ?: item?.title().orEmpty()
    val subtitle = song?.let { "${it.shownArtist} - ${it.shownAlbum}" } ?: item?.artist().orEmpty()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            if (visualizer && ui.isPlaying) {
                SpectrumBackground(sessionId, Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter))
            }
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)
                    .pointerInput(Unit) {
                        var total = androidx.compose.ui.geometry.Offset.Zero
                        detectDragGestures(
                            onDragStart = { total = androidx.compose.ui.geometry.Offset.Zero },
                            onDragEnd = {
                                val (dx, dy) = total
                                when {
                                    abs(dx) > abs(dy) && dx > threshold -> player.next()
                                    abs(dx) > abs(dy) && dx < -threshold -> player.back()
                                    abs(dy) > abs(dx) && dy > threshold -> onLyrics()
                                }
                                scope.launch { slide.animateTo(0f) }
                            },
                            onDragCancel = { scope.launch { slide.animateTo(0f) } },
                        ) { change, amount ->
                            total += amount
                            change.consume()
                            scope.launch { slide.snapTo((total.x * 0.35f).coerceIn(-160f, 160f)) }
                        }
                    },
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Fila") }, onClick = { menu = false; showQueue = true })
                            DropdownMenuItem(text = { Text("Letra") }, onClick = { menu = false; onLyrics() })
                            DropdownMenuItem(text = { Text("Velocidade e timer de sono") },
                                onClick = { menu = false; showSpeed = true })
                            DropdownMenuItem(text = { Text(if (visualizer) "Desligar o visualizador" else "Ligar o visualizador") },
                                onClick = { menu = false; onVisualizer() })
                            DropdownMenuItem(text = { Text("Ir para o artista") }, onClick = { menu = false; onArtist() })
                        }
                    }
                    IconButton(onClick = onFavorite) {
                        Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (favorite) "Tirar das curtidas" else "Curtir",
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight)
                    val px = with(LocalDensity.current) { side.roundToPx() }.coerceAtMost(1024)
                    Cover(song?.artUri ?: item?.localConfiguration?.uri,
                        Modifier.size(side).graphicsLayer {
                            translationX = slide.value
                            alpha = 1f - abs(slide.value) / 400f
                        }, px, 18.dp)
                }
                Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, Modifier.fillMaxWidth().padding(top = 2.dp), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                SeekBar(position, ui.durationMs, player::seekTo)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    IconButton(onClick = { player.setShuffle(!ui.shuffle) }, Modifier.size(56.dp)) {
                        ShuffleIcon(ui.shuffle, if (ui.shuffle) MaterialTheme.colorScheme.primary else dim)
                    }
                    if (simple) {
                        IconButton(onClick = player::previous, Modifier.size(64.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, "Música anterior", Modifier.size(40.dp))
                        }
                    }
                    FilledIconButton(onClick = player::toggle, Modifier.size(84.dp)) {
                        Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (ui.isPlaying) "Pausar" else "Tocar", Modifier.size(48.dp))
                    }
                    if (simple) {
                        IconButton(onClick = { player.next() }, Modifier.size(64.dp)) {
                            Icon(Icons.Rounded.SkipNext, "Próxima música", Modifier.size(40.dp))
                        }
                    }
                    // 1 tap: repeat the playlist/album; 2 taps: only this song; 3: off.
                    IconButton(onClick = player::cycleRepeat, Modifier.size(56.dp)) {
                        Icon(if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            when (ui.repeat) {
                                Player.REPEAT_MODE_ONE -> "Repetindo esta música"
                                Player.REPEAT_MODE_ALL -> "Repetindo a lista"
                                else -> "Repetir"
                            },
                            Modifier.size(28.dp),
                            tint = if (ui.repeat == Player.REPEAT_MODE_OFF) dim else MaterialTheme.colorScheme.primary)
                    }
                }
                if (simple) {
                    OutlinedButton(onClick = onLyrics, Modifier.fillMaxWidth().padding(bottom = 8.dp).height(52.dp)) {
                        Icon(Icons.Rounded.Lyrics, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Letra")
                    }
                } else {
                    Spacer(Modifier.height(12.dp))  // the gestures are taught in the tutorial (Configurações → Como usar)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    if (showQueue) QueueSheet(ui, player) { showQueue = false }
    if (showSpeed) SpeedSleepSheet(ui.speed, player::setSpeed, sleep, onSleep) { showSpeed = false }
}
