package io.github.atrzad.ayomusica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.atrzad.ayomusica.data.Grouping
import io.github.atrzad.ayomusica.data.Playlist
import io.github.atrzad.ayomusica.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Replaces the home header while songs are ticked: what to do with all of them at once. */
@Composable
fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onLike: () -> Unit,
    onNoShuffle: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onUpload: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Sair da seleção") }
            Text(if (count == 1) "1 selecionada" else "$count selecionadas", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, "Selecionar todas") }
            IconButton(onClick = onAddToPlaylist, enabled = count > 0) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar à playlist")
            }
            IconButton(onClick = onPlay, enabled = count > 0) { Icon(Icons.Rounded.PlayArrow, "Tocar as selecionadas") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Tocar a seguir") }, enabled = count > 0,
                        onClick = { menu = false; onPlayNext() })
                    DropdownMenuItem(text = { Text("Adicionar à fila") }, enabled = count > 0,
                        onClick = { menu = false; onEnqueue() })
                    DropdownMenuItem(text = { Text("Adicionar à playlist…") }, enabled = count > 0,
                        onClick = { menu = false; onAddToPlaylist() })
                    DropdownMenuItem(text = { Text("Curtir") }, enabled = count > 0, onClick = { menu = false; onLike() })
                    DropdownMenuItem(text = { Text("Não tocar no aleatório") }, enabled = count > 0,
                        onClick = { menu = false; onNoShuffle() })
                    onDownload?.let { action ->
                        DropdownMenuItem(text = { Text("Baixar da nuvem") }, enabled = count > 0, onClick = { menu = false; action() })
                    }
                    onUpload?.let { action ->
                        DropdownMenuItem(text = { Text("Enviar para a nuvem") }, enabled = count > 0, onClick = { menu = false; action() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Selecionar todas") }, onClick = { menu = false; onSelectAll() })
                    DropdownMenuItem(text = { Text("Limpar seleção") }, onClick = { menu = false; onClose() })
                }
            }
        }
    }
}

/**
 * Adicionar músicas: the whole library with search and checkboxes, for one playlist. Songs already in it are marked;
 * the button at the bottom adds the ticked ones in the order they were picked.
 */
@Composable
fun PickSongsScreen(
    playlist: Playlist,
    library: List<Song>,
    currentId: String?,
    actions: SongActions,
    onAdd: (List<Song>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val inPlaylist = remember(playlist.songIds) { playlist.songIds.toSet() }
    val byId = remember(library) { library.associateBy { it.id } }
    // Searching thousands of songs runs off the main thread, a moment after typing stops.
    val shown by produceState(library, library, query) {
        if (query.isNotBlank()) delay(180)
        value = withContext(Dispatchers.Default) { Grouping.search(library, query) }
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (picked.size == 1) "1 selecionada" else "${picked.size} selecionadas", Modifier.weight(1f), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (picked.isNotEmpty()) TextButton(onClick = { picked = emptyList() }) { Text("Limpar") }
            TextButton(onClick = {
                val chosen = picked.toSet()
                picked = picked + shown.map { it.id }.filterNot { it in chosen || it in inPlaylist }
            }) { Text("Marcar todas", maxLines = 1) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { song ->
                val toggle = { picked = if (song.id in picked) picked - song.id else picked + song.id }
                SongRow(song, song.id.toString() == currentId, actions, onClick = toggle, selected = song.id in picked,
                    onLongClick = toggle, note = if (song.id in inPlaylist) "já na playlist" else null)
            }
            if (shown.isNotEmpty()) item { CountFooter(shown.size) }
        }
        Button(onClick = { onAdd(picked.mapNotNull(byId::get)) },
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().height(52.dp), enabled = picked.isNotEmpty()) {
            Text(when (picked.size) {
                0 -> "Marque as músicas para adicionar"
                1 -> "Adicionar 1 música a “${playlist.name}”"
                else -> "Adicionar ${picked.size} músicas a “${playlist.name}”"
            }, maxLines = 1)
        }
    }
}
