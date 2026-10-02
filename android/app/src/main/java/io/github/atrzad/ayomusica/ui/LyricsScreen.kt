package io.github.atrzad.ayomusica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.durationText
import io.github.atrzad.ayomusica.lyrics.Clean
import io.github.atrzad.ayomusica.lyrics.LrcLibResult
import io.github.atrzad.ayomusica.lyrics.Lyrics
import io.github.atrzad.ayomusica.playback.PlayerConnection
import io.github.atrzad.ayomusica.playback.PlayerUi
import kotlinx.coroutines.launch

/** The lyrics, big. Swipe up (on the header) goes back to Tocando agora. */
@Composable
fun LyricsScreen(
    ui: PlayerUi,
    song: Song?,
    player: PlayerConnection,
    lyrics: LyricsUi,
    onBack: () -> Unit,
    onShift: (Long) -> Unit,
    onRetry: () -> Unit,
    onSearch: suspend (String) -> Result<List<LrcLibResult>>,
    onChoose: (LrcLibResult) -> Unit,
    onSync: () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    val position = rememberPosition(player, ui, 100)
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    val shown = (lyrics as? LyricsUi.Shown)?.lyrics
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxWidth().pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -threshold) onBack() }) { change, amount ->
                    total += amount
                    change.consume()
                }
            }) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Deslize para cima para voltar",
                    Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onBack),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(song?.artUri, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song?.title ?: ui.current?.title().orEmpty(), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song?.let { "${it.shownArtist} - ${it.shownAlbum}" }.orEmpty(), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = player::toggle) {
                        Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.isPlaying) "Pausar" else "Tocar")
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                LyricsPanel(lyrics, position, onSeek = player::seekTo, onRetry = onRetry)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { searching = true }, label = { Text("Buscar letra") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) })
                if (shown != null && shown.lines.any { it.text.isNotBlank() }) {
                    AssistChip(onClick = onSync, label = { Text(if (shown.synced) "Sincronizar de novo" else "Sincronizar") },
                        leadingIcon = { Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)) })
                }
                Spacer(Modifier.weight(1f))
                if (shown != null && shown.synced) {
                    TextButton(onClick = { onShift(-500) }) { Text("−0,5") }
                    TextButton(onClick = { onShift(500) }) { Text("+0,5") }
                }
            }
        }
    }
    if (searching) LyricsSearchSheet(song, onSearch, onChoose = { onChoose(it); searching = false }) { searching = false }
}

/** Search LRCLIB by any text and pick the right lyrics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSearchSheet(
    song: Song?,
    onSearch: suspend (String) -> Result<List<LrcLibResult>>,
    onChoose: (LrcLibResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = song?.let { Clean.readings(it).firstOrNull() }
    var text by remember { mutableStateOf(listOfNotNull(first?.artist, first?.title).joinToString(" ").trim()) }
    var results by remember { mutableStateOf<List<LrcLibResult>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LrcLibResult?>(null) }
    val scope = rememberCoroutineScope()
    fun run() {
        if (text.isBlank()) return
        busy = true
        error = null
        scope.launch {
            onSearch(text).onSuccess { results = it }.onFailure { error = "Sem conexão com o LRCLIB." }
            busy = false
        }
    }
    LaunchedEffect(Unit) { run() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text("Buscar letra", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Artista e música") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }))
                IconButton(onClick = ::run) { Icon(Icons.Rounded.Search, "Buscar") }
            }
            when {
                busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text(error!!, Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.error)
                results?.isEmpty() == true -> Text("Nada encontrado. Tente só o nome da música, ou outra grafia.",
                    Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val chosen = preview
            if (chosen != null) {
                Text("${chosen.artistName} — ${chosen.trackName}", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                Text(Lyrics.parse(chosen.syncedLyrics ?: chosen.plainLyrics).lines.take(8).joinToString("\n") { it.text },
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onChoose(chosen) }) { Text("Usar esta letra") }
                    OutlinedButton(onClick = { preview = null }) { Text("Voltar") }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                LazyColumn(Modifier.padding(top = 8.dp).height(420.dp)) {
                    items(results.orEmpty(), key = { it.id }) { result ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { preview = result }
                            .padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(result.trackName.orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(result.artistName, result.albumName,
                                    result.duration?.let { durationText((it * 1000).toLong()) }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (result.synced) "Sincronizada" else "Só texto", Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (result.synced) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sync by hand: the song starts from the beginning and you tap when each line starts. Undo goes back
 * one line (and a few seconds). The result is saved as synced lyrics for this song.
 */
@Composable
fun SyncEditor(
    ui: PlayerUi,
    song: Song?,
    lyrics: Lyrics,
    player: PlayerConnection,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val lines = remember(lyrics) { lyrics.lines.map { it.text }.filter { it.isNotBlank() } }
    val times = remember(lyrics) { mutableStateListOf<Long>() }
    var started by remember { mutableStateOf(false) }
    val index = times.size
    val listState = rememberLazyListState()
    LaunchedEffect(index) { if (index > 1) listState.animateScrollToItem(maxOf(0, index - 2)) }
    fun finish() {
        val synced = Lyrics(lines.take(times.size).mapIndexed { i, text -> Lyrics.Line(times[i], text) }, synced = true)
        onDone(io.github.atrzad.ayomusica.lyrics.LrcWriter.write(synced, song?.title.orEmpty(), song?.artist.orEmpty()))
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            Text("Sincronizar a letra", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (!started) "A música vai começar do início. Toque no botão grande sempre que uma linha começar a ser cantada."
                else "Linha ${minOf(index + 1, lines.size)} de ${lines.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f).padding(vertical = 12.dp), state = listState) {
                itemsIndexed(lines) { i, text ->
                    Text(text, Modifier.padding(vertical = 6.dp),
                        color = when {
                            i == index -> MaterialTheme.colorScheme.primary
                            i < index -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        },
                        fontSize = if (i == index) 24.sp else 18.sp,
                        fontWeight = if (i == index) FontWeight.ExtraBold else FontWeight.Medium)
                }
            }
            if (!started) {
                Button(onClick = { started = true; player.seekTo(0); if (!ui.isPlaying) player.toggle() },
                    Modifier.fillMaxWidth().height(64.dp)) { Text("Começar") }
            } else if (index < lines.size) {
                Button(onClick = { times += player.positionMs - 150 },  // a tap comes a little after the voice
                    Modifier.fillMaxWidth().height(96.dp)) {
                    Text("Agora: ${lines[index].take(40)}", fontSize = 18.sp, textAlign = TextAlign.Center, maxLines = 2)
                }
            } else {
                Button(onClick = ::finish, Modifier.fillMaxWidth().height(64.dp)) { Text("Salvar a letra sincronizada") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text("Cancelar") }
                if (started && times.isNotEmpty()) {
                    TextButton(onClick = {
                        val previous = times.removeAt(times.lastIndex)
                        player.seekTo(maxOf(0, previous - 3000))
                    }) {
                        Icon(Icons.Rounded.Undo, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Voltar uma linha")
                    }
                }
                if (started && times.size in 1 until lines.size) TextButton(onClick = ::finish) { Text("Salvar até aqui") }
            }
        }
    }
}
