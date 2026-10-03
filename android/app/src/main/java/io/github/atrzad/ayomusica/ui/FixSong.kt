package io.github.atrzad.ayomusica.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.atrzad.ayomusica.analyzer.Candidate
import io.github.atrzad.ayomusica.analyzer.Http
import io.github.atrzad.ayomusica.analyzer.Source
import io.github.atrzad.ayomusica.data.Artwork
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.SongOverride
import io.github.atrzad.ayomusica.data.durationText
import io.github.atrzad.ayomusica.lyrics.Clean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Corrigir informações: search Deezer, Apple Music and MusicBrainz by hand, pick a result, adjust the fields and
 * save. Only the app shows the new information; the file keeps its tags (and "Restaurar" brings them back).
 */
@Composable
fun FixSongScreen(
    original: Song,
    current: Song,
    fix: SongOverride?,
    state: MetaSearchState,
    onSearch: (title: String, artist: String, sources: Set<Source>) -> Unit,
    onComplete: suspend (Candidate) -> Candidate,
    onSave: (SongOverride, covers: List<String>, useCover: Boolean) -> Unit,
    onRestore: () -> Unit,
) {
    val reading = remember(original.id) { Clean.readings(original).firstOrNull() }
    var title by rememberSaveable(original.id) { mutableStateOf(reading?.title ?: original.title) }
    var artist by rememberSaveable(original.id) { mutableStateOf(reading?.artist ?: original.artist) }
    var sources by remember { mutableStateOf(Source.entries.toSet()) }
    var editing by remember { mutableStateOf<Candidate?>(null) }
    var manual by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val search = {
        focus.clearFocus()
        onSearch(title.trim(), artist.trim(), sources)
    }
    // Search right away with what the file says: most of the time the right result is already there.
    LaunchedEffect(original.id) { if (!state.searched && !state.loading) search() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        item {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(current.artUri, 72.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${current.shownArtist} · ${current.shownAlbum}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val extra = listOfNotNull(current.year.takeIf { it > 0 }?.toString(), current.genre.ifBlank { null },
                        durationText(current.durationMs))
                    Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (fix != null) {
                Text("Corrigida no app (${sourceName(fix.source)}). No arquivo: ${original.artist.ifBlank { "?" }} — " +
                    original.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRestore, contentPadding = PaddingValues(0.dp)) { Text("Restaurar as tags do arquivo") }
            } else {
                Text("As mudanças valem dentro do app; o arquivo da música não é alterado.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Título") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text("Artista (opcional)") },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }))
            Text("Fontes", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Source.entries.forEach { source ->
                    FilterChip(source in sources, {
                        sources = if (source in sources) sources - source else sources + source
                    }, label = { Text(source.label) })
                }
            }
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = search, enabled = title.isNotBlank() && sources.isNotEmpty() && !state.loading) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Procurar")
                }
                OutlinedButton(onClick = { manual = true }) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Editar à mão")
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (state.failed.isNotEmpty()) {
                Text("Sem resposta de ${state.failed.joinToString { it.label }}.", Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.searched && !state.loading && state.results.isEmpty()) {
                Text("Nada encontrado. Tente só o título, ou corrija o nome do artista.", Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.results.isNotEmpty()) {
                Text("${state.results.size} resultados · toque em um para revisar e salvar", Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.results, key = { "${it.source}:${it.id}" }) { candidate ->
            CandidateRow(candidate, original.durationMs) { editing = candidate }
        }
    }

    editing?.let { candidate ->
        OverrideDialog(candidate, current, onComplete, onDismiss = { editing = null }) { override, useCover ->
            editing = null
            onSave(override, candidate.covers, useCover)
        }
    }
    if (manual) {
        OverrideDialog(null, current, onComplete, onDismiss = { manual = false }) { override, _ ->
            manual = false
            onSave(override, emptyList(), false)
        }
    }
}

private fun sourceName(id: String) = Source.entries.firstOrNull { it.name.equals(id, true) }?.label
    ?: if (id == "manual") "à mão" else id

@Composable
private fun CandidateRow(candidate: Candidate, durationMs: Long, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteCover(candidate.thumb, 56.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(candidate.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(candidate.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(listOfNotNull(candidate.album.ifBlank { null }, candidate.year.takeIf { it > 0 }?.toString())
                .joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(candidate.source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (candidate.seconds > 0) {
                val difference = if (durationMs > 0) abs(candidate.seconds - durationMs / 1000) else 0
                // Same length (give or take a few seconds) is the best sign it is the same recording.
                Text(durationText(candidate.seconds * 1000L), style = MaterialTheme.typography.bodySmall,
                    color = if (difference <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (difference > 3) Text("${if (candidate.seconds > durationMs / 1000) "+" else "−"}${difference}s",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Review before saving: the fields come from the result (or the song, editing by hand) and can be changed. */
@Composable
private fun OverrideDialog(
    candidate: Candidate?,
    current: Song,
    onComplete: suspend (Candidate) -> Candidate,
    onDismiss: () -> Unit,
    onSave: (SongOverride, useCover: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf(candidate?.title ?: current.title) }
    var artist by remember { mutableStateOf(candidate?.artist ?: current.artist) }
    var album by remember { mutableStateOf(candidate?.album ?: current.album) }
    var albumArtist by remember { mutableStateOf(candidate?.albumArtist ?: current.albumArtist) }
    var year by remember { mutableStateOf((candidate?.year ?: current.year).takeIf { it > 0 }?.toString().orEmpty()) }
    var genre by remember { mutableStateOf(candidate?.genre ?: current.genre) }
    var useCover by remember { mutableStateOf(candidate?.covers?.isNotEmpty() == true) }
    var loading by remember { mutableStateOf(candidate != null) }
    LaunchedEffect(candidate) {
        if (candidate == null) return@LaunchedEffect
        // Year, genre and album artist may need one more request (Deezer reads them from the album).
        val full = onComplete(candidate)
        if (albumArtist.isBlank()) albumArtist = full.albumArtist
        if (year.isBlank() && full.year > 0) year = full.year.toString()
        if (genre.isBlank()) genre = full.genre
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (candidate == null) "Editar à mão" else "Salvar do ${candidate.source.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (candidate != null && candidate.covers.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { useCover = !useCover },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(useCover, { useCover = it })
                        Text("Usar esta capa", Modifier.weight(1f))
                        RemoteCover(candidate.thumb, 56.dp)
                    }
                }
                if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Buscando ano e gênero…", style = MaterialTheme.typography.bodySmall)
                }
                Field("Título", title) { title = it }
                Field("Artista", artist) { artist = it }
                Field("Álbum", album) { album = it }
                Field("Artista do álbum", albumArtist) { albumArtist = it }
                Field("Ano", year, number = true) { value -> year = value.filter(Char::isDigit).take(4) }
                Field("Gênero", genre) { genre = it }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(SongOverride(
                    title = title.trim(), artist = artist.trim(), album = album.trim(), albumArtist = albumArtist.trim(),
                    year = year.toIntOrNull() ?: 0, genre = genre.trim(),
                    source = candidate?.source?.name?.lowercase() ?: "manual",
                ), useCover)
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun Field(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text))
}

/** Covers of search results, straight from the source; a few in memory so scrolling back is instant. */
private object RemoteArt {
    val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

@Composable
fun RemoteCover(url: String?, size: Dp) {
    val bitmap by produceState(url?.let { RemoteArt.cache.get(it) }, url) {
        if (url != null && value == null) value = withContext(Dispatchers.IO) {
            Http.bytes(url)?.let { Artwork.decode(it, 200) }?.also { RemoteArt.cache.put(url, it) }
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
