package io.github.atrzad.ayomusica.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.atrzad.ayomusica.data.Artwork
import io.github.atrzad.ayomusica.data.Playlist
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.durationText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** At most four covers decoded at a time, so fast scrolling through thousands of songs stays smooth. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val COVERS = Dispatchers.IO.limitedParallelism(4)

/** Cover art from the song's file, or a note icon. `sizePx` is the decode size. */
@Composable
fun Cover(uri: Uri?, modifier: Modifier = Modifier, sizePx: Int = 256, corner: Dp = 8.dp) {
    val context = LocalContext.current
    val bitmap by produceState(uri?.let { Artwork.cached(it, sizePx) }, uri, sizePx) {
        // A new song: drop the previous cover right away, then load this one. Covers scrolled past are cancelled.
        value = uri?.let { Artwork.cached(it, sizePx) }
        if (uri != null && value == null) value = withContext(COVERS) { Artwork.load(context, uri, sizePx) }
    }
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Cover(uri: Uri?, size: Dp, corner: Dp = 8.dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    Cover(uri, Modifier.size(size), px, corner)
}

/** What a song's menu can do; each screen passes what makes sense there. */
class SongActions(
    val playNext: (Song) -> Unit,
    val enqueue: (Song) -> Unit,
    val addToPlaylist: (Song) -> Unit,
    val goToAlbum: ((Song) -> Unit)?,
    val goToArtist: ((Song) -> Unit)?,
    val remove: ((Int) -> Unit)? = null,
    val isFavorite: (Song) -> Boolean = { false },
    val toggleFavorite: ((Song) -> Unit)? = null,
    val fixInfo: ((Song) -> Unit)? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    playing: Boolean,
    actions: SongActions,
    onClick: () -> Unit,
    position: Int = 0,
    showCover: Boolean = true,
    number: Int? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            number != null -> Text(if (number > 0) "$number" else "", Modifier.width(32.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            showCover -> Cover(song.artUri, 48.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyLarge)
            Text("${song.shownArtist} · ${song.shownAlbum}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(durationText(song.durationMs), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Tocar a seguir") }, onClick = { menu = false; actions.playNext(song) })
                DropdownMenuItem(text = { Text("Adicionar à fila") }, onClick = { menu = false; actions.enqueue(song) })
                DropdownMenuItem(text = { Text("Adicionar à playlist…") },
                    onClick = { menu = false; actions.addToPlaylist(song) })
                actions.toggleFavorite?.let { toggle ->
                    DropdownMenuItem(text = { Text(if (actions.isFavorite(song)) "Tirar das favoritas" else "Favoritar") },
                        onClick = { menu = false; toggle(song) })
                }
                actions.goToAlbum?.let { go ->
                    DropdownMenuItem(text = { Text("Ir para o álbum") }, onClick = { menu = false; go(song) })
                }
                actions.goToArtist?.let { go ->
                    DropdownMenuItem(text = { Text("Ir para o artista") }, onClick = { menu = false; go(song) })
                }
                actions.fixInfo?.let { fix ->
                    DropdownMenuItem(text = { Text("Corrigir informações…") }, onClick = { menu = false; fix(song) })
                }
                actions.remove?.let { remove ->
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Remover desta playlist") },
                        onClick = { menu = false; remove(position) })
                }
            }
        }
    }
}

/** Pick a playlist for [songs], or create one. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "Nova playlist" else "Adicionar à playlist") },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
            } else {
                LazyColumn {
                    item {
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { creating = true }).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null)
                            Spacer(Modifier.width(12.dp))
                            Text("Nova playlist…")
                        }
                    }
                    items(playlists, key = { it.id }) { playlist ->
                        Text(playlist.name, Modifier.fillMaxWidth().combinedClickable(onClick = { onPick(playlist) })
                            .padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun EmptyState(title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(4.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        action?.let {
            Spacer(Modifier.size(16.dp))
            it()
        }
    }
}
