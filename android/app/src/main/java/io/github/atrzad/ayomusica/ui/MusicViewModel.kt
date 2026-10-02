package io.github.atrzad.ayomusica.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import io.github.atrzad.ayomusica.data.Album
import io.github.atrzad.ayomusica.data.Artist
import io.github.atrzad.ayomusica.data.AutoList
import io.github.atrzad.ayomusica.data.Grouping
import io.github.atrzad.ayomusica.data.MediaLibrary
import io.github.atrzad.ayomusica.data.Playlist
import io.github.atrzad.ayomusica.data.Playlists
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.SongStats
import io.github.atrzad.ayomusica.data.Stats
import io.github.atrzad.ayomusica.lyrics.EmbeddedLyrics
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LyricsUi {
    data object Idle : LyricsUi
    data object Loading : LyricsUi
    data class Shown(val lyrics: Lyrics) : LyricsUi
    data object Missing : LyricsUi
    data object Offline : LyricsUi
}

enum class Tab(val title: String) { Songs("Músicas"), Albums("Álbuns"), Artists("Artistas"), Playlists("Playlists") }

sealed interface Route {
    data class Home(val tab: Tab) : Route
    data class AlbumPage(val key: String) : Route
    data class ArtistPage(val name: String) : Route
    data class PlaylistPage(val id: Long) : Route
    data class AutoPage(val list: AutoList) : Route
}

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val library = MediaLibrary(application)
    private val lyricsRepository = LyricsRepository(application)
    private val playlistStore = Playlists(application)
    val player = PlayerConnection(application)

    val songs = MutableStateFlow<List<Song>>(emptyList())
    val loading = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val routes = MutableStateFlow<List<Route>>(listOf(Route.Home(Tab.Songs)))
    val lyrics = MutableStateFlow<LyricsUi>(LyricsUi.Idle)
    val playlists: StateFlow<List<Playlist>> = playlistStore.all
    private val statsStore = Stats.get(application)
    val stats: StateFlow<Map<Long, SongStats>> = statsStore.all
    val sleep: StateFlow<SleepTimer.Mode> = SleepTimer.mode
    private val prefs = application.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    val visualizer = MutableStateFlow(prefs.getBoolean("visualizer", false))
    val theme = MutableStateFlow(prefs.getString("theme", io.github.atrzad.ayomusica.ui.theme.MONO)!!)
    val mode = MutableStateFlow(runCatching {
        io.github.atrzad.ayomusica.ui.theme.Mode.valueOf(prefs.getString("mode", "Auto")!!)
    }.getOrDefault(io.github.atrzad.ayomusica.ui.theme.Mode.Auto))

    fun setTheme(id: String) {
        theme.value = id
        prefs.edit().putString("theme", id).apply()
    }

    fun setMode(value: io.github.atrzad.ayomusica.ui.theme.Mode) {
        mode.value = value
        prefs.edit().putString("mode", value.name).apply()
    }

    fun setVisualizer(on: Boolean) {
        visualizer.value = on
        prefs.edit().putBoolean("visualizer", on).apply()
    }

    val albums: StateFlow<List<Album>> = songs.map(Grouping::albums).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val artists: StateFlow<List<Artist>> = songs.map(Grouping::artists).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val results: StateFlow<List<Song>> = combine(songs, query) { all, text -> Grouping.search(all, text) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val byId = songs.map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var lyricsJob: Job? = null
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
            songs.value = runCatching { library.load() }.getOrDefault(songs.value)
            loading.value = false
            loaded.value = true
        }
    }

    fun songOf(item: MediaItem?): Song? = item?.mediaId?.toLongOrNull()?.let { byId.value[it] }

    // ── navigation ───────────────────────────────────────────────────────
    fun open(route: Route) {
        routes.value = if (route is Route.Home) listOf(route) else routes.value + route
    }

    /** False when there is nothing to go back to. */
    fun back(): Boolean {
        if (routes.value.size <= 1) return false
        routes.value = routes.value.dropLast(1)
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
            lyrics.value = when (val result = lyricsRepository.find(song, embedded, online = true)) {
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

    // ── favorites, automatic lists, speed and sleep ──────────────────────
    fun isFavorite(song: Song) = stats.value[song.id]?.favorite == true

    fun toggleFavorite(song: Song) = statsStore.setFavorite(song.id, !isFavorite(song))

    fun autoList(list: AutoList): List<Song> = list.songs(songs.value, stats.value)

    fun setSpeed(speed: Float) = player.setSpeed(speed)

    fun sleepAfter(minutes: Int?) = when (minutes) {
        null -> SleepTimer.cancel()
        0 -> SleepTimer.endOfSong()
        else -> SleepTimer.start(minutes)
    }

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
