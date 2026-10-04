package io.github.atrzad.ayomusica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.atrzad.ayomusica.data.Album
import io.github.atrzad.ayomusica.data.Artist
import io.github.atrzad.ayomusica.data.AutoList
import io.github.atrzad.ayomusica.data.Group
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.atrzad.ayomusica.data.Playlist
import io.github.atrzad.ayomusica.data.Song

// Room at the end of every list so the last songs can scroll above the floating tab button.
private val listPadding = PaddingValues(bottom = 104.dp)

@Composable
fun PlayButtons(songs: List<Song>, onPlay: (List<Song>, Int, Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onPlay(songs, 0, false) }, enabled = songs.isNotEmpty()) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("Tocar")
        }
        FilledTonalButton(onClick = { onPlay(songs, 0, true) }, enabled = songs.isNotEmpty()) {
            Icon(Icons.Rounded.Shuffle, null)
            Spacer(Modifier.width(6.dp))
            Text("Aleatório")
        }
    }
}

@Composable
fun SongsScreen(songs: List<Song>, currentId: String?, query: String, onQuery: (String) -> Unit, actions: SongActions,
                onPlay: (List<Song>, Int, Boolean) -> Unit, selection: Set<Long>? = null, onSelect: ((Song) -> Unit)? = null) {
    LazyColumn(contentPadding = listPadding) {
        item {
            SearchField(query, onQuery)
        }
        if (songs.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    EmptyState(if (query.isNotBlank()) "Nada encontrado" else "Nenhuma música",
                        if (query.isNotBlank()) "Tente outras palavras." else "Coloque músicas no celular e atualize em Configurações.")
                }
            }
        }
        if (selection == null) item { PlayButtons(songs, onPlay) }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song, song.id.toString() == currentId, actions,
                onClick = { if (selection != null && onSelect != null) onSelect(song) else onPlay(songs, index, false) },
                selected = selection?.let { song.id in it },
                // Holding a song starts selecting several (the ⋮ keeps the options for one song).
                onLongClick = onSelect?.let { select -> { select(song) } })
        }
        if (songs.isNotEmpty()) item { CountFooter(songs.size) }
    }
}

/** The search field of the song lists: one line, placeholder included. */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String = "Buscar músicas, artistas, álbuns") {
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Limpar") } },
        shape = RoundedCornerShape(24.dp))
}

/** "N músicas" at the end of a list. */
@Composable
fun CountFooter(count: Int) {
    Text(if (count == 1) "1 música" else "$count músicas", Modifier.fillMaxWidth().padding(16.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** Genres or folders: a name and how many songs. */
@Composable
fun GroupsScreen(groups: List<Group>, icon: ImageVector, onOpen: (Group) -> Unit) {
    if (groups.isEmpty()) return EmptyState("Nada aqui", "As músicas aparecem conforme as tags e as pastas.")
    LazyColumn(contentPadding = listPadding) {
        items(groups, key = { it.name }) { group ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(group) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(if (group.songs.size == 1) "1 música" else "${group.songs.size} músicas",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun GroupScreen(group: Group, currentId: String?, actions: SongActions, onPlay: (List<Song>, Int, Boolean) -> Unit) {
    LazyColumn(contentPadding = listPadding) {
        item {
            Header(group.songs.firstOrNull(), group.name, "", if (group.songs.size == 1) "1 música" else "${group.songs.size} músicas")
            PlayButtons(group.songs, onPlay)
        }
        itemsIndexed(group.songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song, song.id.toString() == currentId, actions, onClick = { onPlay(group.songs, index, false) })
        }
    }
}

@Composable
fun AlbumsScreen(albums: List<Album>, onOpen: (Album) -> Unit) {
    if (albums.isEmpty()) return EmptyState("Nenhum álbum", "Os álbuns aparecem conforme as tags das músicas.")
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(albums, key = { it.key }) { album ->
            Column(Modifier.clickable { onOpen(album) }) {
                val px = with(LocalDensity.current) { 180.dp.roundToPx() }
                Cover(album.cover.artUri, Modifier.fillMaxWidth().aspectRatio(1f), px, 12.dp)
                Text(album.title, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ArtistsScreen(artists: List<Artist>, onOpen: (Artist) -> Unit) {
    if (artists.isEmpty()) return EmptyState("Nenhum artista", "Os artistas aparecem conforme as tags das músicas.")
    LazyColumn(contentPadding = listPadding) {
        items(artists, key = { it.name }) { artist ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(artist) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Cover(artist.songs.first().artUri, 48.dp, corner = 24.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge)
                    val albums = if (artist.albumCount == 1) "1 álbum" else "${artist.albumCount} álbuns"
                    val songs = if (artist.songs.size == 1) "1 música" else "${artist.songs.size} músicas"
                    Text("$albums · $songs", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(playlists: List<Playlist>, count: (Playlist) -> Int, onOpen: (Playlist) -> Unit,
                    onCreate: () -> Unit, autoCount: (AutoList) -> Int, onAuto: (AutoList) -> Unit) {
    LazyColumn(contentPadding = listPadding) {
        items(AutoList.entries.toList(), key = { "auto-${it.name}" }) { list ->
            Row(Modifier.fillMaxWidth().clickable { onAuto(list) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(when (list) {
                    AutoList.Favorites -> Icons.Rounded.Favorite
                    AutoList.MostPlayed -> Icons.Rounded.TrendingUp
                    AutoList.Recent -> Icons.Rounded.History
                    AutoList.Added -> Icons.Rounded.NewReleases
                    AutoList.NoShuffle -> Icons.Rounded.Block
                }, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(list.title, style = MaterialTheme.typography.bodyLarge)
                    val songs = autoCount(list)
                    Text(if (songs == 1) "1 música" else "$songs músicas", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Suas playlists", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth().clickable(onClick = onCreate).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(12.dp))
                Text("Nova playlist", style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(playlist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val art = playlist.artUri
                if (art != null) Cover(art, 48.dp)
                else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                    if (playlist.description.isNotBlank()) Text(playlist.description, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val songs = count(playlist)
                    Text(if (songs == 1) "1 música" else "$songs músicas", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Header(cover: Song?, title: String, subtitle: String, detail: String, round: Boolean = false,
                   art: android.net.Uri? = null, description: String = "") {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (art != null) Cover(art, 120.dp, corner = 12.dp)
        else if (cover != null) Cover(cover.artUri, 120.dp, corner = if (round) 60.dp else 12.dp)
        else Icon(if (round) Icons.Rounded.Person else Icons.AutoMirrored.Rounded.PlaylistPlay, null, Modifier.size(56.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (description.isNotBlank()) {
        Text(description, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AlbumScreen(album: Album, currentId: String?, actions: SongActions, onPlay: (List<Song>, Int, Boolean) -> Unit) {
    LazyColumn(contentPadding = listPadding) {
        item {
            val detail = listOfNotNull(album.year.takeIf { it > 0 }?.toString(),
                if (album.songs.size == 1) "1 música" else "${album.songs.size} músicas").joinToString(" · ")
            Header(album.cover, album.title, album.artist, detail)
            PlayButtons(album.songs, onPlay)
        }
        itemsIndexed(album.songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song, song.id.toString() == currentId, actions, onClick = { onPlay(album.songs, index, false) },
                number = song.track)
        }
    }
}

@Composable
fun ArtistScreen(artist: Artist, currentId: String?, actions: SongActions, onPlay: (List<Song>, Int, Boolean) -> Unit) {
    LazyColumn(contentPadding = listPadding) {
        item {
            Header(artist.songs.first(), artist.name, "",
                "${artist.albumCount} álbuns · ${artist.songs.size} músicas", round = true)
            PlayButtons(artist.songs, onPlay)
        }
        itemsIndexed(artist.songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song, song.id.toString() == currentId, actions, onClick = { onPlay(artist.songs, index, false) })
        }
    }
}

@Composable
fun PlaylistScreen(playlist: Playlist, songs: List<Song>, currentId: String?, actions: SongActions,
                   onPlay: (List<Song>, Int, Boolean) -> Unit, onAddSongs: () -> Unit) {
    LazyColumn(contentPadding = listPadding) {
        item {
            Header(songs.firstOrNull(), playlist.name, "",
                if (songs.size == 1) "1 música" else "${songs.size} músicas", art = playlist.artUri,
                description = playlist.description)
            if (songs.isNotEmpty()) PlayButtons(songs, onPlay)
            androidx.compose.material3.FilledTonalButton(onClick = onAddSongs, Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Adicionar músicas")
            }
            if (songs.isEmpty()) {
                Text("Escolha as músicas em Adicionar músicas, ou segure uma música na tela inicial para selecionar várias.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
            SongRow(song, song.id.toString() == currentId, actions, onClick = { onPlay(songs, index, false) },
                position = index)
        }
    }
}

@Composable
fun AutoListScreen(list: AutoList, songs: List<Song>, currentId: String?, actions: SongActions,
                   onPlay: (List<Song>, Int, Boolean) -> Unit) {
    LazyColumn(contentPadding = listPadding) {
        item {
            Header(songs.firstOrNull(), list.title, "", if (songs.size == 1) "1 música" else "${songs.size} músicas")
            PlayButtons(songs, onPlay)
            if (songs.isEmpty()) {
                Text(when (list) {
                    AutoList.Favorites -> "Toque no coração da tela cheia ou use Favoritar no menu ⋮ de uma música."
                    AutoList.MostPlayed, AutoList.Recent -> "Uma música conta depois de tocar metade (ou 4 minutos)."
                    AutoList.Added -> "Nenhuma música no celular."
                    AutoList.NoShuffle -> "Nenhuma. No menu ⋮ de uma música, “Não tocar no aleatório” a coloca aqui: ela " +
                        "não aparece quando você ouve no aleatório, mas toca quando você a escolhe."
                }, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song, song.id.toString() == currentId, actions, onClick = { onPlay(songs, index, false) })
        }
    }
}
