package io.github.atrzad.ayomusica.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import io.github.atrzad.ayomusica.analyzer.Analyzer
import io.github.atrzad.ayomusica.analyzer.Deezer
import io.github.atrzad.ayomusica.analyzer.Proposal
import io.github.atrzad.ayomusica.analyzer.Verdict
import io.github.atrzad.ayomusica.data.Album
import io.github.atrzad.ayomusica.data.Artist
import io.github.atrzad.ayomusica.data.AutoList
import io.github.atrzad.ayomusica.data.Group
import io.github.atrzad.ayomusica.data.Grouping
import io.github.atrzad.ayomusica.data.MediaLibrary
import io.github.atrzad.ayomusica.data.Overrides
import io.github.atrzad.ayomusica.data.Playlist
import io.github.atrzad.ayomusica.data.Playlists
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.SongStats
import io.github.atrzad.ayomusica.data.Stats
import io.github.atrzad.ayomusica.lyrics.EmbeddedLyrics
import io.github.atrzad.ayomusica.lyrics.LrcLibResult
import io.github.atrzad.ayomusica.lyrics.Lyrics
import io.github.atrzad.ayomusica.lyrics.LyricsRepository
import io.github.atrzad.ayomusica.playback.PlayerConnection
import io.github.atrzad.ayomusica.playback.SleepTimer
import io.github.atrzad.ayomusica.playback.externalMediaItem
import io.github.atrzad.ayomusica.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LyricsUi {
    data object Idle : LyricsUi
    data object Loading : LyricsUi
    data class Shown(val lyrics: Lyrics) : LyricsUi
    data object Missing : LyricsUi
    data object Offline : LyricsUi
}

enum class SettingsPage(val title: String) {
    Analyzer("Analisador de músicas"), Equalizer("Equalizador"), UseModes("Modo"), Themes("Temas"),
    HomeTabs("Página inicial"), LyricsSettings("Letras")
}

sealed interface Route {
    data class Home(val tab: Tab) : Route
    data class AlbumPage(val key: String) : Route
    data class ArtistPage(val name: String) : Route
    data class PlaylistPage(val id: Long) : Route
    data class AutoPage(val list: AutoList) : Route
    data class GenrePage(val name: String) : Route
    data class FolderPage(val name: String) : Route
    data object Settings : Route
    data class SettingsOf(val page: SettingsPage) : Route
}

/** What covers the library: nothing, the full player, the lyrics, or the sync editor. */
enum class Screen { Library, Player, Lyrics, Sync }

data class AnalysisItem(val song: Song, val verdict: Verdict, val proposal: Proposal?, val applied: Boolean = false)

data class AnalysisState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val items: List<AnalysisItem> = emptyList(),
    val error: String? = null,
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val library = MediaLibrary(application)
    private val lyricsRepository = LyricsRepository(application)
    private val playlistStore = Playlists(application)
    private val overrides = Overrides(application)
    private val statsStore = Stats.get(application)
    val prefs = Prefs(application)
    val player = PlayerConnection(application)

    private val scanned = MutableStateFlow<List<Song>>(emptyList())
    /** The library with the analyzer's corrections applied. */
    val songs: StateFlow<List<Song>> = combine(scanned, overrides.all) { list, fixes ->
        list.map { Overrides.apply(it, fixes[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val loading = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val routes = MutableStateFlow<List<Route>>(listOf(Route.Home(prefs.startTab.value)))
    val screen = MutableStateFlow(Screen.Library)
    val lyrics = MutableStateFlow<LyricsUi>(LyricsUi.Idle)
    val playlists: StateFlow<List<Playlist>> = playlistStore.all
    val stats: StateFlow<Map<Long, SongStats>> = statsStore.all
    val sleep: StateFlow<SleepTimer.Mode> = SleepTimer.mode
    val analysis = MutableStateFlow(AnalysisState())

    val albums: StateFlow<List<Album>> = songs.map(Grouping::albums).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val artists: StateFlow<List<Artist>> = songs.map(Grouping::artists).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val genres: StateFlow<List<Group>> = songs.map(Grouping::genres).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders: StateFlow<List<Group>> = songs.map(Grouping::folders).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val results: StateFlow<List<Song>> = combine(songs, query) { all, text -> Grouping.search(all, text) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val byId = songs.map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var lyricsJob: Job? = null
    private var analysisJob: Job? = null
    private var pendingExternal: Uri? = null

    init {
        viewModelScope.launch {
            player.connect()
            pendingExternal?.let { openExternal(it) }
            pendingExternal = null
        }
        viewModelScope.launch {
            player.ui.map { it.current?.mediaId }.distinctUntilChanged().collect { loadLyrics() }
        }
    }

    override fun onCleared() {
        player.release()
    }

    fun refresh() {
        if (loading.value) return
        viewModelScope.launch {
            loading.value = true
            scanned.value = runCatching { library.load() }.getOrDefault(scanned.value)
            loading.value = false
            loaded.value = true
        }
    }

    fun songOf(item: MediaItem?): Song? = item?.mediaId?.toLongOrNull()?.let { byId.value[it] }

    // ── navigation ───────────────────────────────────────────────────────
    fun open(route: Route) {
        routes.value = if (route is Route.Home) listOf(route) else routes.value + route
    }

    fun show(next: Screen) {
        screen.value = next
    }

    /** Back: closes the sync editor, lyrics, player, then pages. False when there is nothing to close. */
    fun back(): Boolean {
        when (screen.value) {
            Screen.Sync -> screen.value = Screen.Lyrics
            Screen.Lyrics -> screen.value = Screen.Player
            Screen.Player -> screen.value = Screen.Library
            Screen.Library -> {
                if (routes.value.size <= 1) return false
                routes.value = routes.value.dropLast(1)
            }
        }
        return true
    }

    // ── playing ──────────────────────────────────────────────────────────
    fun play(list: List<Song>, start: Int = 0, shuffle: Boolean = false) =
        player.play(list.map { it.toMediaItem() }, start, shuffle)

    fun playNext(list: List<Song>) = player.playNext(list.map { it.toMediaItem() })

    fun enqueue(list: List<Song>) = player.enqueue(list.map { it.toMediaItem() })

    fun openExternal(uri: Uri) {
        if (!player.ui.value.connected) {
            pendingExternal = uri
            return
        }
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        player.play(listOf(externalMediaItem(uri, name.substringBeforeLast('.'))))
    }

    // ── lyrics ───────────────────────────────────────────────────────────
    fun loadLyrics(force: Boolean = false) {
        val song = songOf(player.ui.value.current)
        lyricsJob?.cancel()
        if (song == null) {
            lyrics.value = LyricsUi.Idle
            return
        }
        lyrics.value = LyricsUi.Loading
        lyricsJob = viewModelScope.launch {
            if (force) lyricsRepository.forget(song)
            val embedded = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(song.uri)?.use(EmbeddedLyrics::read)
                }.getOrNull()
            }
            lyrics.value = when (val result = lyricsRepository.find(song, embedded, prefs.lyricsOnline.value)) {
                is LyricsRepository.Result.Found -> LyricsUi.Shown(result.lyrics)
                LyricsRepository.Result.Missing -> LyricsUi.Missing
                LyricsRepository.Result.Offline -> LyricsUi.Offline
            }
        }
    }

    /** Positive: the lyrics appear earlier. Remembered for the song. */
    fun shiftLyrics(deltaMs: Long) {
        val shown = lyrics.value as? LyricsUi.Shown ?: return
        val song = songOf(player.ui.value.current) ?: return
        val updated = shown.lyrics.copy(offsetMs = shown.lyrics.offsetMs + deltaMs)
        lyrics.value = LyricsUi.Shown(updated)
        viewModelScope.launch { lyricsRepository.setOffset(song, updated.offsetMs) }
    }

    suspend fun searchLyrics(text: String): Result<List<LrcLibResult>> = runCatching {
        lyricsRepository.search(text, songOf(player.ui.value.current))
    }

    /** A result picked in the manual search becomes this song's lyrics. */
    fun chooseLyrics(result: LrcLibResult) {
        val song = songOf(player.ui.value.current) ?: return
        viewModelScope.launch {
            lyricsRepository.save(song, result.syncedLyrics.orEmpty(), result.plainLyrics.orEmpty(), "escolhida")
            loadLyrics()
        }
    }

    /** Lyrics synced by hand (tap per line) or by voice. */
    fun saveSynced(lrc: String, source: String) {
        val song = songOf(player.ui.value.current) ?: return
        viewModelScope.launch {
            lyricsRepository.save(song, lrc, "", source)
            loadLyrics()
        }
    }

    // ── favorites (Curtidas), automatic lists, speed and sleep ──────────
    fun isFavorite(song: Song) = stats.value[song.id]?.favorite == true

    fun toggleFavorite(song: Song) = statsStore.setFavorite(song.id, !isFavorite(song))

    fun autoList(list: AutoList): List<Song> = list.songs(songs.value, stats.value)

    fun sleepAfter(minutes: Int?) = when (minutes) {
        null -> SleepTimer.cancel()
        0 -> SleepTimer.endOfSong()
        else -> SleepTimer.start(minutes)
    }

    // ── analyzer ─────────────────────────────────────────────────────────
    fun needingWork(): List<Song> = scanned.value.filter { it.id !in overrides.all.value && Analyzer.needsWork(it) }

    fun analyze(all: Boolean) {
        if (analysis.value.running) return
        val targets = if (all) scanned.value.filter { it.id !in overrides.all.value } else needingWork()
        analysisJob = viewModelScope.launch {
            analysis.value = AnalysisState(running = true, total = targets.size)
            val analyzer = Analyzer()
            val found = mutableListOf<AnalysisItem>()
            for ((index, song) in targets.withIndex()) {
                if (!isActive) break
                val outcome = withContext(Dispatchers.IO) { runCatching { analyzer.analyze(song) } }
                val (verdict, proposal) = outcome.getOrElse {
                    analysis.value = analysis.value.copy(running = false, error = "Sem conexão com o Deezer.")
                    return@launch
                }
                var item = AnalysisItem(song, verdict, proposal)
                if (verdict == Verdict.Auto && proposal != null) item = item.copy(applied = applyProposal(song, proposal))
                found += item
                analysis.value = analysis.value.copy(done = index + 1, items = found.toList())
            }
            analysis.value = analysis.value.copy(running = false)
        }
    }

    fun stopAnalysis() {
        analysisJob?.cancel()
        analysis.value = analysis.value.copy(running = false)
    }

    private suspend fun applyProposal(song: Song, proposal: Proposal): Boolean = withContext(Dispatchers.IO) {
        val cover = proposal.coverUrl?.let { url ->
            File(getApplication<Application>().filesDir, "covers/${song.id}.jpg").takeIf { Deezer.download(url, it) }
        }
        overrides.put(song.id, proposal.override.copy(coverFile = cover?.absolutePath))
        true
    }

    fun accept(item: AnalysisItem) {
        val proposal = item.proposal ?: return
        viewModelScope.launch {
            applyProposal(item.song, proposal)
            updateItem(item.song.id) { it.copy(applied = true) }
        }
    }

    fun undo(item: AnalysisItem) {
        overrides.remove(item.song.id)
        updateItem(item.song.id) { it.copy(applied = false) }
    }

    fun undoAll() = analysis.value.items.filter { it.applied }.forEach(::undo)

    private fun updateItem(id: Long, change: (AnalysisItem) -> AnalysisItem) {
        analysis.value = analysis.value.copy(items = analysis.value.items.map { if (it.song.id == id) change(it) else it })
    }

    fun correctedCount() = overrides.all.value.size

    // ── playlists ────────────────────────────────────────────────────────
    fun playlistSongs(playlist: Playlist): List<Song> = playlist.songIds.mapNotNull { byId.value[it] }

    fun createPlaylist(name: String, with: List<Song> = emptyList()) =
        viewModelScope.launch { playlistStore.create(name, with.map { it.id }) }

    fun addToPlaylist(id: Long, list: List<Song>) = viewModelScope.launch { playlistStore.add(id, list.map { it.id }) }

    fun removeFromPlaylist(id: Long, position: Int) = viewModelScope.launch { playlistStore.removeAt(id, position) }

    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { playlistStore.rename(id, name) }

    fun deletePlaylist(id: Long) = viewModelScope.launch {
        playlistStore.delete(id)
        routes.value = routes.value.filterNot { it is Route.PlaylistPage && it.id == id }
    }
}
