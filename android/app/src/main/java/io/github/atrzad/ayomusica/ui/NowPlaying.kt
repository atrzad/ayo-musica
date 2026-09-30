package io.github.atrzad.ayomusica.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.atrzad.ayomusica.data.durationText
import io.github.atrzad.ayomusica.lyrics.Lyrics
import io.github.atrzad.ayomusica.playback.PlayerConnection
import io.github.atrzad.ayomusica.playback.PlayerUi
import io.github.atrzad.ayomusica.playback.SleepTimer
import kotlinx.coroutines.delay

/** The playback position, refreshed while shown. */
@Composable
fun rememberPosition(player: PlayerConnection, ui: PlayerUi, everyMs: Long = 250): Long {
    val position by produceState(player.positionMs, ui.current, ui.isPlaying) {
        while (true) {
            value = player.positionMs
            delay(everyMs)
        }
    }
    return position
}

private fun MediaItem.title() = mediaMetadata.title?.toString().orEmpty()
private fun MediaItem.artist() = mediaMetadata.artist?.toString().orEmpty()

@Composable
fun MiniPlayer(ui: PlayerUi, player: PlayerConnection, onExpand: () -> Unit) {
    val item = ui.current ?: return
    val position = rememberPosition(player, ui, 500)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.clickable(onClick = onExpand)) {
            LinearProgressIndicator(
                progress = { if (ui.durationMs > 0) (position.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(item.localConfiguration?.uri ?: item.requestMetadata.mediaUri, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(item.artist(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = player::toggle) {
                    Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (ui.isPlaying) "Pausar" else "Tocar")
                }
                IconButton(onClick = { player.next() }) { Icon(Icons.Rounded.SkipNext, "Próxima") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedPlayer(
    ui: PlayerUi,
    player: PlayerConnection,
    lyrics: LyricsUi,
    favorite: Boolean,
    onFavorite: () -> Unit,
    visualizer: Boolean,
    onVisualizer: () -> Unit,
    sessionId: Int,
    sleep: SleepTimer.Mode,
    onSleep: (Int?) -> Unit,
    onCollapse: () -> Unit,
    onArtist: () -> Unit,
    onShiftLyrics: (Long) -> Unit,
    onRetryLyrics: () -> Unit,
) {
    val item = ui.current
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSound by remember { mutableStateOf(false) }
    val position = rememberPosition(player, ui, 100)
    val dimIcon = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize()) {
        if (visualizer && ui.isPlaying) {
            SpectrumBackground(sessionId, Modifier.fillMaxWidth().fillMaxHeight(0.6f).align(Alignment.BottomCenter))
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Recolher") }
                Text("Tocando agora", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onVisualizer) {
                    Icon(Icons.Rounded.GraphicEq, "Visualizador",
                        tint = if (visualizer) MaterialTheme.colorScheme.onSurface else dimIcon)
                }
                IconButton(onClick = { showSound = true }) {
                    Icon(Icons.Rounded.Tune, "Som: equalizador, velocidade e timer",
                        tint = if (sleep != SleepTimer.Mode.Off || ui.speed != 1f) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Fila") }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (showLyrics) {
                    LyricsPanel(lyrics, position, onSeek = player::seekTo, onRetry = onRetryLyrics)
                } else {
                    BoxWithConstraints(contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth, maxHeight)
                        val px = with(LocalDensity.current) { side.roundToPx() }.coerceAtMost(1024)
                        Cover(item?.localConfiguration?.uri ?: item?.requestMetadata?.mediaUri,
                            Modifier.size(side).aspectRatio(1f), px, 16.dp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item?.title().orEmpty(), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item?.artist().orEmpty(), Modifier.clickable(onClick = onArtist).padding(vertical = 2.dp),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (favorite) "Tirar das favoritas" else "Favoritar")
                }
            }
            SeekBar(position, ui.durationMs, player::seekTo)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                IconButton(onClick = { player.setShuffle(!ui.shuffle) }) {
                    Icon(Icons.Rounded.Shuffle, "Ordem aleatória",
                        tint = if (ui.shuffle) MaterialTheme.colorScheme.onSurface else dim)
                }
                IconButton(onClick = player::previous, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(36.dp))
                }
                FilledIconButton(onClick = player::toggle, Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors()) {
                    Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (ui.isPlaying) "Pausar" else "Tocar", Modifier.size(40.dp))
                }
                IconButton(onClick = { player.next() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Próxima", Modifier.size(36.dp))
                }
                IconButton(onClick = player::cycleRepeat) {
                    Icon(if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repetir", tint = if (ui.repeat == Player.REPEAT_MODE_OFF) dim else MaterialTheme.colorScheme.onSurface)
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = showLyrics, onClick = { showLyrics = !showLyrics }, label = { Text("Letra") },
                    leadingIcon = { Icon(Icons.Rounded.Lyrics, null, Modifier.size(18.dp)) })
                Spacer(Modifier.weight(1f))
                val shown = lyrics as? LyricsUi.Shown
                if (showLyrics && shown != null && shown.lyrics.synced) {
                    TextButton(onClick = { onShiftLyrics(-500) }) { Text("−0,5 s") }
                    Text(offsetText(shown.lyrics.offsetMs), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onShiftLyrics(500) }) { Text("+0,5 s") }
                }
            }
        }
      }
    }
    if (showQueue) QueueSheet(ui, player) { showQueue = false }
    if (showSound) SoundSheet(ui.speed, player::setSpeed, sleep, onSleep) { showSound = false }
}

private fun offsetText(ms: Long): String = when {
    ms == 0L -> "no tempo"
    ms > 0 -> "%.1f s antes".format(ms / 1000.0).replace('.', ',')
    else -> "%.1f s depois".format(-ms / 1000.0).replace('.', ',')
}

@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(top = 8.dp)) {
        Slider(value = shown, onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { onSeek((dragValue * duration).toLong()); dragging = false },
            enabled = duration > 0)
        Row(Modifier.fillMaxWidth()) {
            Text(durationText(if (dragging) (dragValue * duration).toLong() else position),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(durationText(duration), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Synced lyrics highlight the sung line and follow it; scrolling by hand pauses that for a few seconds. */
@Composable
fun LyricsPanel(state: LyricsUi, positionMs: Long, onSeek: (Long) -> Unit, onRetry: () -> Unit) {
    val lyrics = (state as? LyricsUi.Shown)?.lyrics
    if (lyrics == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when (state) {
                LyricsUi.Loading -> "Procurando a letra…"
                LyricsUi.Offline -> "Sem conexão para buscar a letra."
                else -> "Nenhuma letra encontrada."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state == LyricsUi.Missing || state == LyricsUi.Offline) {
                TextButton(onClick = onRetry) { Text("Buscar de novo") }
            }
        }
        return
    }
    val listState = rememberLazyListState()
    var pausedUntil by remember { mutableLongStateOf(0L) }
    val manual = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) pausedUntil = System.currentTimeMillis() + 4000
                return Offset.Zero
            }
        }
    }
    val current = lyrics.currentIndex(positionMs)
    LaunchedEffect(current, lyrics) {
        if (lyrics.synced && System.currentTimeMillis() >= pausedUntil) {
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(maxOf(current, 0), -(viewport * 0.35f).toInt())
        }
    }
    LazyColumn(Modifier.fillMaxSize().nestedScroll(manual), state = listState,
        contentPadding = PaddingValues(vertical = 120.dp)) {
        itemsIndexed(lyrics.lines) { index, line ->
            LyricLine(line, lyrics, index, current) { time -> onSeek(time) }
        }
        item {
            Text(sourceText(lyrics), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LyricLine(line: Lyrics.Line, lyrics: Lyrics, index: Int, current: Int, onSeek: (Long) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val color by animateColorAsState(when {
        !lyrics.synced -> onSurface.copy(alpha = 0.85f)
        index == current -> onSurface
        index < current -> onSurface.copy(alpha = 0.5f)
        else -> onSurface.copy(alpha = 0.3f)
    }, label = "lyric")
    val time = line.timeMs
    Text(
        line.text.ifEmpty { if (lyrics.synced) "♪" else "" },
        Modifier.fillMaxWidth()
            .then(if (lyrics.synced && time != null) Modifier.clickable { onSeek(maxOf(0, time - lyrics.offsetMs)) } else Modifier)
            .padding(vertical = if (lyrics.synced) 8.dp else 3.dp),
        color = color,
        fontSize = if (lyrics.synced) 24.sp else 18.sp,
        lineHeight = if (lyrics.synced) 30.sp else 24.sp,
        fontWeight = if (lyrics.synced) FontWeight.ExtraBold else FontWeight.Medium,
    )
}

private fun sourceText(lyrics: Lyrics): String {
    val where = when (lyrics.source) {
        "lrclib" -> "LRCLIB"
        "embutida" -> "tags da música"
        else -> lyrics.source
    }
    return (if (lyrics.synced) "Letra sincronizada" else "Letra sem tempos") + if (where.isNotEmpty()) " · $where" else ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(ui: PlayerUi, player: PlayerConnection, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Fila", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        val start = ui.queue.indexOfFirst { it.index == ui.index }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(ui.queue, key = { _, entry -> "${entry.index}-${entry.item.mediaId}" }) { _, entry ->
                val current = entry.index == ui.index
                Row(Modifier.fillMaxWidth()
                    .background(if (current) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { player.jumpTo(entry.index) }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.item.title(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        Text(entry.item.artist(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!current) {
                        IconButton(onClick = { player.removeAt(entry.index) }) { Icon(Icons.Rounded.Close, "Tirar da fila") }
                    }
                }
            }
        }
    }
}
