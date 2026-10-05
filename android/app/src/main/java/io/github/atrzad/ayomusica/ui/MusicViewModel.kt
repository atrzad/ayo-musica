package io.github.atrzad.ayomusica.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import io.github.atrzad.ayomusica.analyzer.Analyzer
import io.github.atrzad.ayomusica.analyzer.Candidate
import io.github.atrzad.ayomusica.analyzer.Deezer
import io.github.atrzad.ayomusica.analyzer.Http
import io.github.atrzad.ayomusica.analyzer.ITunes
import io.github.atrzad.ayomusica.analyzer.MetadataSource
import io.github.atrzad.ayomusica.analyzer.MusicBrainz
import io.github.atrzad.ayomusica.analyzer.Source
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
import io.github.atrzad.ayomusica.playback.syncKey
import io.github.atrzad.ayomusica.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.withLock
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
    Account("Conta e nuvem"), Tutorial("Como usar"), Report("Relatório de erros"), Analyzer("Analisador de músicas"), Equalizer("Equalizador"), UseModes("Modo"), Themes("Temas"),
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
    /** Search the online sources by hand and write over a song's information. */
    data class FixSong(val id: Long) : Route
    /** Pick songs from the whole library (with search and checkboxes) to add to a playlist. */
    data class PickSongs(val playlistId: Long) : Route
}

/** What covers the library: nothing, the full player, the lyrics, or the sync editor. */
enum class Screen { Library, Player, Lyrics, Sync }

/** Syncing by voice: what it is doing (or the result), with progress 0..1 (−1: unknown). */
data class VoiceState(val text: String, val progress: Float = -1f, val running: Boolean = true)

data class AnalysisItem(
    val song: Song,
    val verdict: Verdict,
    val proposal: Proposal?,
    val applied: Boolean = false,
    /** Corrected by hand on "Corrigir informações": what was saved (it leaves the review lists). */
    val manual: io.github.atrzad.ayomusica.data.SongOverride? = null,
    /** The person chose to ignore it: out of the lists, never analyzed again. */
    val ignored: Boolean = false,
)

/** Numbers for the analyzer page. */
data class AnalyzerCounts(
    /** Songs with missing information not looked up yet (the next "Analisar" goes through these). */
    val pending: Int = 0,
    /** All songs not looked up yet ("Todas"). */
    val pendingAll: Int = 0,
    val analyzed: Int = 0,
    val corrected: Int = 0,
)

/** The manual search on the "Corrigir informações" page. */
data class MetaSearchState(
    val loading: Boolean = false,
    val results: List<Candidate> = emptyList(),
    val searched: Boolean = false,
    /** Sources that could not be reached on the last search. */
    val failed: List<Source> = emptyList(),
    /** Why recognizing by the sound failed (rate limit, AcoustID key...). */
    val note: String? = null,
)

/** The run in progress (the results themselves live in [AnalysisStore]). */
data class AnalysisState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    /** "Aceitar todas" in progress: how many done of how many. */
    val accepting: Pair<Int, Int>? = null,
)

/** Which songs the library shows, like Spotify's filters: everything, this phone's, or the cloud's. */
enum class LibrarySource(val title: String) { All("Tudo"), Local("Neste celular"), Cloud("Nuvem") }

/** The account's sync: running, when it last worked, and what went wrong. */
/** Another device of the account and what it plays (from the server). */
data class RemoteDevice(
    val id: String,
    val name: String,
    val platform: String,
    val online: Boolean,
    val updated: Long,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val state: kotlinx.serialization.json.JsonObject?,
)

data class SyncStatus(val running: Boolean = false, val lastAt: Long = 0, val message: String = "", val error: String? = null)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    init {
        io.github.atrzad.ayomusica.sync.Account.init(application)
    }
    private val library = MediaLibrary(application)
    private val lyricsRepository = LyricsRepository(application)
    private val playlistStore = Playlists(application)
    private val overrides = Overrides(application)
    private val statsStore = Stats.get(application)
    val prefs = Prefs(application)
    val player = PlayerConnection(application)

    private val scanned = MutableStateFlow<List<Song>>(emptyList())
    val cloud = io.github.atrzad.ayomusica.sync.Cloud(application)
    val account = io.github.atrzad.ayomusica.sync.Account.current
    val librarySource = MutableStateFlow(LibrarySource.All)
    val syncStatus = MutableStateFlow(SyncStatus())
    /** The cloud's songs (played from this phone when downloaded). */
    private val cloudRaw: StateFlow<List<Song>> = combine(cloud.tracks, cloud.downloaded, account) { tracks, saved, who ->
        if (!who.signedIn) emptyList() else tracks.map { cloud.song(it, it.id in saved) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Every song this phone can play — its own and the cloud's — with the corrections applied (for playing anything). */
    // Everything derived from the library runs off the main thread: thousands of songs must not freeze the screen.
    private val allSongs: StateFlow<Pair<List<Song>, List<Song>>> = combine(scanned, cloudRaw, overrides.all) { local, remote, fixes ->
        fun fixed(list: List<Song>) = if (fixes.isEmpty()) list else list.map { song -> fixes[song.id]?.let { Overrides.apply(song, it) } ?: song }
        // The key the other devices know (the file's tags), stamped before the Analyzer's corrections change what is shown.
        val keyed = local.map { it.copy(syncKey = io.github.atrzad.ayomusica.sync.SongKeys.of(it)) }
        fixed(keyed) to fixed(remote.map { if (it.syncKey.isBlank()) it.copy(syncKey = io.github.atrzad.ayomusica.sync.SongKeys.of(it)) else it })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<Song>() to emptyList())
    /** The library as shown: everything (a song on both sides once, this phone's copy), this phone's, or the cloud's. */
    val songs: StateFlow<List<Song>> = combine(allSongs, librarySource) { (local, remote), source ->
        when (source) {
            LibrarySource.Local -> local
            LibrarySource.Cloud -> remote
            LibrarySource.All -> {
                val here = local.mapTo(HashSet()) { io.github.atrzad.ayomusica.sync.SongKeys.name(it.syncKey) }
                local + remote.filter { io.github.atrzad.ayomusica.sync.SongKeys.name(it.syncKey) !in here }
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val loading = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val routes = MutableStateFlow<List<Route>>(listOf(Route.Home(prefs.startTab.value)))
    val screen = MutableStateFlow(Screen.Library)
    val lyrics = MutableStateFlow<LyricsUi>(LyricsUi.Idle)
    val playlists: StateFlow<List<Playlist>> = playlistStore.all
    /** Other devices' plays (by song name), added to this phone's in "Mais tocadas" and "Tocadas recentemente". */
    private val remotePlays = MutableStateFlow<Map<String, Triple<Int, Int, Long>>>(emptyMap())
    val stats: StateFlow<Map<Long, SongStats>> = combine(statsStore.all, remotePlays, allSongs) { own, remote, (local, cloudSongs) ->
        if (remote.isEmpty()) own else {
            val merged = own.toMutableMap()
            for (song in local + cloudSongs) {
                val other = remote[io.github.atrzad.ayomusica.sync.SongKeys.name(song.syncKey)] ?: continue
                val mine = merged[song.id] ?: SongStats()
                merged[song.id] = mine.copy(plays = mine.plays + other.first, skips = mine.skips + other.second,
                    lastPlayed = maxOf(mine.lastPlayed, other.third))
            }
            merged
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val sleep: StateFlow<SleepTimer.Mode> = SleepTimer.mode
    val analysis = MutableStateFlow(AnalysisState())
    val metaSearch = MutableStateFlow(MetaSearchState())
    /** Songs ticked on the home list (in the order they were picked); null when not selecting. */
    val selection = MutableStateFlow<Set<Long>?>(null)
    /** The corrections saved in the app, by song id. */
    val fixes: StateFlow<Map<Long, io.github.atrzad.ayomusica.data.SongOverride>> = overrides.all
    private val metaSources: Map<Source, MetadataSource> by lazy {
        listOf(Deezer(), ITunes(), MusicBrainz()).associateBy { it.source }
    }
    private var metaJob: Job? = null
    private val audioId by lazy {
        io.github.atrzad.ayomusica.analyzer.AudioId(application, { prefs.shazam.value }, { prefs.acoustidKey.value })
    }
    val voice = MutableStateFlow<VoiceState?>(null)
    private val voiceSync by lazy { io.github.atrzad.ayomusica.voice.VoiceSync(application) }
    private var voiceJob: Job? = null

    private fun <T> derived(initial: T, work: (List<Song>) -> T): StateFlow<T> =
        songs.map(work).flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val albums: StateFlow<List<Album>> = derived(emptyList(), Grouping::albums)
    val artists: StateFlow<List<Artist>> = derived(emptyList(), Grouping::artists)
    val genres: StateFlow<List<Group>> = derived(emptyList(), Grouping::genres)
    val folders: StateFlow<List<Group>> = derived(emptyList(), Grouping::folders)
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val results: StateFlow<List<Song>> = combine(songs, query.debounce { if (it.isEmpty()) 0L else 180L }) { all, text ->
        Grouping.search(all, text)
    }.conflate().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Every playable song by id (this phone's and the cloud's), whatever the library shows. */
    val byId: StateFlow<Map<Long, Song>> = allSongs.map { (local, remote) -> (local + remote).associateBy { it.id } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    /** Sizes of the automatic lists (Curtidas, Mais tocadas...), counted in the background. */
    val autoCounts: StateFlow<Map<AutoList, Int>> = combine(songs, stats) { list, stats ->
        AutoList.entries.associateWith { it.songs(list, stats).size }
    }.conflate().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val analysisStore = io.github.atrzad.ayomusica.analyzer.AnalysisStore(application)
    private val originals: StateFlow<Map<Long, Song>> = scanned.map { list -> list.associateBy { it.id } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── account and sync ─────────────────────────────────────────────────
    private val librarySync = io.github.atrzad.ayomusica.sync.LibrarySync(application.filesDir, playlistStore, statsStore,
        lyricsRepository, overrides, analysisStore, prefs, localSongs = { scanned.value },
        shownSongs = { byId.value }, cloudSongs = { cloudRaw.value })
    private val syncEngine = io.github.atrzad.ayomusica.sync.SyncEngine(
        java.io.File(application.filesDir, "sync/state.json"), librarySync.hooks,
        device = { io.github.atrzad.ayomusica.sync.Account.deviceId },
        transport = { since, changes ->
            io.github.atrzad.ayomusica.sync.Api.post("/api/sync", kotlinx.serialization.json.buildJsonObject {
                put("device", kotlinx.serialization.json.JsonPrimitive(io.github.atrzad.ayomusica.sync.Account.deviceId))
                put("since", kotlinx.serialization.json.JsonPrimitive(since))
                put("changes", kotlinx.serialization.json.JsonArray(changes))
            })
        })
    private val syncLock = kotlinx.coroutines.sync.Mutex()

    /** Sends what changed here and brings what changed elsewhere (cloud songs first, so playlists can use them). */
    fun syncNow(quiet: Boolean = false) {
        val who = account.value
        if (!who.signedIn || syncLock.isLocked) return
        viewModelScope.launch(Dispatchers.IO) {
            syncLock.withLock {
                // Only after the phone's library is read: otherwise everything would look deleted.
                if (!loaded.value) return@withLock
                syncStatus.update { it.copy(running = true, error = null, message = if (quiet) it.message else "Sincronizando…") }
                try {
                    if (!syncEngine.belongsTo(io.github.atrzad.ayomusica.sync.Account.server, who.email)) {
                        syncEngine.reset(io.github.atrzad.ayomusica.sync.Account.server, who.email)
                    }
                    cloud.refresh()
                    val result = syncEngine.sync()
                    remotePlays.value = syncEngine.remotePlays()
                    io.github.atrzad.ayomusica.util.AppLog.i("Sync", "enviou ${result.sent}, recebeu ${result.received}, guardou ${result.held}")
                    syncStatus.value = SyncStatus(lastAt = System.currentTimeMillis(), message = "Sincronizado")
                } catch (error: Exception) {
                    io.github.atrzad.ayomusica.util.AppLog.w("Sync", "falhou", error)
                    syncStatus.update { it.copy(running = false, error = error.message ?: "Sem conexão com o servidor.") }
                }
            }
        }
    }

    /** "Entrar com o Google": Google's token goes to our server, which answers with its own session. */
    suspend fun signIn(idToken: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val answer = io.github.atrzad.ayomusica.sync.Api.login(idToken)
            val user = io.github.atrzad.ayomusica.sync.Api.objectOf(answer, "user")
            io.github.atrzad.ayomusica.sync.Account.signIn(io.github.atrzad.ayomusica.sync.Api.string(answer, "token"),
                user?.let { io.github.atrzad.ayomusica.sync.Api.string(it, "email") }.orEmpty(),
                user?.let { io.github.atrzad.ayomusica.sync.Api.string(it, "name") }.orEmpty(),
                user?.let { io.github.atrzad.ayomusica.sync.Api.string(it, "picture") }.orEmpty())
            io.github.atrzad.ayomusica.util.AppLog.i("Conta", "entrou como ${io.github.atrzad.ayomusica.sync.Account.current.value.email}")
            null
        }.getOrElse { it.message ?: "Não deu para entrar." }
    }

    /** Debug builds: a session made on the server's PC (admin.js token) instead of Google. */
    suspend fun signInWithSession(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val me = io.github.atrzad.ayomusica.sync.Api.get("/api/me", token = token.trim())
            val user = io.github.atrzad.ayomusica.sync.Api.objectOf(me, "user")
            io.github.atrzad.ayomusica.sync.Account.signIn(token.trim(), user?.let { io.github.atrzad.ayomusica.sync.Api.string(it, "email") }.orEmpty(),
                user?.let { io.github.atrzad.ayomusica.sync.Api.string(it, "name") }.orEmpty(), "")
            null
        }.getOrElse { io.github.atrzad.ayomusica.sync.Account.signOut(); it.message ?: "Sessão inválida." }
    }

    /** The Web client id the server gives (Android asks Google for a token meant for it). */
    suspend fun googleClientId(): String = withContext(Dispatchers.IO) {
        runCatching { io.github.atrzad.ayomusica.sync.Api.string(io.github.atrzad.ayomusica.sync.Api.config(), "webClientId") }.getOrDefault("")
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { io.github.atrzad.ayomusica.sync.Api.post("/api/logout", kotlinx.serialization.json.JsonObject(emptyMap())) }
            io.github.atrzad.ayomusica.sync.Account.signOut()
            cloud.clear()
            remotePlays.value = emptyMap()
            syncStatus.value = SyncStatus()
            librarySource.value = LibrarySource.All
        }
    }

    fun setServer(url: String) = io.github.atrzad.ayomusica.sync.Account.setServer(url)

    // ── cloud songs ──────────────────────────────────────────────────────
    fun download(list: List<Song>) {
        val tracks = cloud.tracks.value.associateBy { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            for (song in list) tracks[song.cloudId]?.let { runCatching { cloud.download(it) }.onFailure { e ->
                io.github.atrzad.ayomusica.util.AppLog.w("Nuvem", "baixar ${song.cloudId}", e) } }
        }
    }

    fun removeDownload(song: Song) = cloud.removeDownload(song.cloudId)

    /** Sends this phone's songs to the cloud (one at a time; the same file twice is skipped by the server). */
    fun upload(list: List<Song>) {
        val local = list.filter { !it.inCloud }
        if (local.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var done = 0
            for (song in local) {
                syncStatus.update { it.copy(message = "Enviando ${done + 1} de ${local.size} para a nuvem…") }
                runCatching { cloud.upload(song) }.onFailure { e ->
                    io.github.atrzad.ayomusica.util.AppLog.w("Nuvem", "enviar ${song.id}", e)
                    syncStatus.update { it.copy(error = e.message) }
                }
                done++
            }
            syncStatus.update { it.copy(message = "$done enviadas para a nuvem") }
            runCatching { cloud.refresh() }
        }
    }

    // ── other devices: continue, play there, remote control ──────────────
    val devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    /** "Continuar de onde parou": another device's recent song, offered when this phone is not playing. */
    val continueOffer = MutableStateFlow<RemoteDevice?>(null)
    private var offerDismissed = ""

    fun refreshDevices() {
        if (!account.value.signedIn) return
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { io.github.atrzad.ayomusica.sync.Api.get("/api/player/devices") }.getOrNull() ?: return@launch
            val me = io.github.atrzad.ayomusica.sync.Account.deviceId
            devices.value = list["devices"]?.let { it as? kotlinx.serialization.json.JsonArray }.orEmpty().mapNotNull { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun text(o: kotlinx.serialization.json.JsonObject?, k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val id = text(obj, "device")
                if (id == me) return@mapNotNull null
                val state = obj["state"] as? kotlinx.serialization.json.JsonObject
                val item = state?.get("item") as? kotlinx.serialization.json.JsonObject
                RemoteDevice(id, text(obj, "name").ifBlank { "Outro aparelho" }, text(obj, "platform"), text(obj, "online") == "true",
                    text(obj, "updated").toLongOrNull() ?: 0, text(item, "title"), text(item, "artist"), text(state, "playing") == "true",
                    text(state, "positionMs").toLongOrNull() ?: 0, text(state, "durationMs").toLongOrNull() ?: 0, state)
            }.sortedByDescending { it.updated }
            val idle = !player.ui.value.isPlaying
            continueOffer.value = devices.value.firstOrNull {
                idle && it.state != null && it.title.isNotBlank() && System.currentTimeMillis() - it.updated < 12 * 3_600_000 &&
                    "${it.id}:${it.updated}" != offerDismissed
            }
        }
    }

    fun dismissContinue() {
        continueOffer.value?.let { offerDismissed = "${it.id}:${it.updated}" }
        continueOffer.value = null
    }

    fun command(device: RemoteDevice, action: String, args: Map<String, Any> = emptyMap()) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                io.github.atrzad.ayomusica.sync.Api.post("/api/player/command", kotlinx.serialization.json.buildJsonObject {
                    put("target", kotlinx.serialization.json.JsonPrimitive(device.id))
                    put("device", kotlinx.serialization.json.JsonPrimitive(io.github.atrzad.ayomusica.sync.Account.deviceId))
                    put("action", kotlinx.serialization.json.JsonPrimitive(action))
                    put("args", kotlinx.serialization.json.JsonObject(args.mapValues { (_, v) ->
                        when (v) { is Number -> kotlinx.serialization.json.JsonPrimitive(v); is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                            is kotlinx.serialization.json.JsonElement -> v; else -> kotlinx.serialization.json.JsonPrimitive(v.toString()) }
                    }))
                })
            }.onFailure { e -> syncStatus.update { it.copy(error = e.message) } }
            kotlinx.coroutines.delay(1200)
            refreshDevices()
        }
    }

    /** Continue here what the other device was playing (same queue and moment); the other one pauses. */
    fun playHere(device: RemoteDevice) {
        val state = device.state ?: return
        val items = (state["queue"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        if (items.isEmpty()) return
        viewModelScope.launch {
            // Finding thousands of songs by key: off the main thread.
            val list = withContext(Dispatchers.Default) {
                val (local, remote) = allSongs.value
                val matcher = io.github.atrzad.ayomusica.sync.Matcher((local + remote).map { it to keyOf(it) })
                val byCloud = remote.associateBy { it.cloudId }
                items.mapNotNull { item ->
                    fun text(k: String) = (item[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    matcher.find(text("key")) ?: byCloud[text("cloudId").toLongOrNull() ?: 0]
                }
            }
            if (list.isEmpty()) {
                syncStatus.update { it.copy(error = "As músicas de ${device.name} não estão neste celular nem na nuvem.") }
                return@launch
            }
            val index = ((state["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceIn(0, list.lastIndex)
            val position = device.positionMs + if (device.playing) System.currentTimeMillis() - device.updated else 0
            play(list, index, false)
            command(device, "pause")
            continueOffer.value = null
            kotlinx.coroutines.delay(600)
            player.seekTo(position.coerceAtLeast(0))
        }
    }

    /** Send what plays here to another device (it continues from this moment); this phone pauses. */
    fun playThere(device: RemoteDevice) {
        val current = player.ui.value.current ?: return
        val order = player.queue().map { it.item }
        if (order.isEmpty()) return
        // The songs around the one playing (a whole-library queue is thousands long).
        val at = order.indexOfFirst { it.mediaId == current.mediaId }.coerceAtLeast(0)
        val from = maxOf(0, at - 50)
        val window = order.subList(from, minOf(order.size, at + 250))
        val items = kotlinx.serialization.json.JsonArray(window.map { media ->
            val meta = media.mediaMetadata
            val id = media.mediaId.toLongOrNull() ?: 0
            kotlinx.serialization.json.buildJsonObject {
                put("key", kotlinx.serialization.json.JsonPrimitive(media.syncKey()))
                put("cloudId", kotlinx.serialization.json.JsonPrimitive(if (id < 0) -id else 0))
                put("title", kotlinx.serialization.json.JsonPrimitive(meta.title?.toString().orEmpty()))
                put("artist", kotlinx.serialization.json.JsonPrimitive(meta.artist?.toString().orEmpty()))
                put("durationMs", kotlinx.serialization.json.JsonPrimitive(meta.durationMs ?: 0))
            }
        })
        command(device, "playQueue", mapOf("items" to items, "index" to at - from, "positionMs" to player.positionMs))
        player.pause()
    }

    /** How the other devices know a song: its own tags (or the cloud's), whatever the Analyzer corrected here. */
    private fun keyOf(song: Song) = song.syncKey.ifBlank { io.github.atrzad.ayomusica.sync.SongKeys.of(song) }

    /** This phone's songs the cloud does not have yet (by artist and title). */
    val localNotInCloud: StateFlow<List<Song>> = allSongs.map { (local, remote) ->
        val there = remote.mapTo(HashSet()) { io.github.atrzad.ayomusica.sync.SongKeys.name(it.syncKey) }
        local.filter { io.github.atrzad.ayomusica.sync.SongKeys.name(it.syncKey) !in there }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun uploadMissing() = upload(localNotInCloud.value)

    fun deleteFromCloud(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { cloud.delete(song.cloudId); cloud.refresh() }.onFailure { e -> syncStatus.update { it.copy(error = e.message) } }
        }
    }

    /**
     * The analyzer's lists, worked out from what was saved: its results, the ignored songs and the corrections. Whether
     * a song is corrected comes only from the corrections file, so accepting, correcting by hand or undoing (even during
     * a run) moves the song to the right list once and for all.
     */
    val analysisItems: StateFlow<List<AnalysisItem>> = combine(analysisStore.data, overrides.all, originals) { data, fixes, songs ->
        data.results.mapNotNull { (id, result) ->
            val song = songs[id] ?: return@mapNotNull null
            val fix = fixes[id]
            AnalysisItem(song, result.verdict, result.proposal, applied = fix != null, manual = fix?.takeIf { it.byHand },
                ignored = id in data.ignored)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val analyzerCounts: StateFlow<AnalyzerCounts> = combine(scanned, overrides.all, analysisStore.data) { list, fixes, data ->
        var pending = 0
        var pendingAll = 0
        for (song in list) {
            if (song.id in fixes || song.id in data.ignored || song.id in data.results) continue
            pendingAll++
            if (Analyzer.needsWork(song)) pending++
        }
        AnalyzerCounts(pending, pendingAll, data.results.size, fixes.size)
    }.conflate().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, AnalyzerCounts())

    private var lyricsJob: Job? = null
    private var analysisJob: Job? = null
    private var pendingExternal: Uri? = null

    init {
        // Sync: when signed in and the library is read, 20 s after anything synced changes, and every 5 minutes.
        viewModelScope.launch {
            combine(account.map { it.signedIn to it.email }.distinctUntilChanged(), loaded) { who, ready -> who.first && ready }
                .distinctUntilChanged().collect { if (it) syncNow() }
        }
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            merge(playlistStore.all.map { 1 }, statsStore.all.map { 2 }, overrides.all.map { 3 }, analysisStore.data.map { 4 },
                prefs.theme.map { 5 }, prefs.tabs.map { 6 }).drop(6).debounce(20_000).collect { syncNow(quiet = true) }
        }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5 * 60_000)
                syncNow(quiet = true)
            }
        }
        viewModelScope.launch {
            player.connect()
            pendingExternal?.let { openExternal(it) }
            pendingExternal = null
        }
        viewModelScope.launch {
            // Load the lyrics when the song changes, and again once the library (built in the background)
            // knows that song: right after opening, the player restores a song before the library is ready.
            combine(player.ui.map { it.current?.mediaId }.distinctUntilChanged(), byId) { id, known ->
                id to (id?.toLongOrNull()?.let(known::containsKey) == true)
            }.distinctUntilChanged().collect { loadLyrics() }
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
        selection.value = null
    }

    // ── selecting several songs ──────────────────────────────────────────
    /** Holding a song starts selecting with it; while selecting, tapping ticks or unticks. */
    fun toggleSelected(song: Song) {
        val now = selection.value ?: emptySet()
        selection.value = if (song.id in now) now - song.id else now + song.id
    }

    fun selectAll(list: List<Song>) {
        selection.value = (selection.value ?: emptySet()) + list.map { it.id }
    }

    fun clearSelection() {
        selection.value = null
    }

    fun selectedSongs(): List<Song> = selection.value.orEmpty().mapNotNull { byId.value[it] }

    /** Likes them all (the ones already liked stay liked). */
    fun likeAll(list: List<Song>) = list.filterNot(::isFavorite).forEach(::toggleFavorite)

    /** Creates the playlist and goes straight to picking its songs. */
    fun createPlaylistAndPick(name: String) = viewModelScope.launch {
        val playlist = playlistStore.create(name)
        open(Route.PlaylistPage(playlist.id))
        open(Route.PickSongs(playlist.id))
    }

    fun show(next: Screen) {
        screen.value = next
    }

    /** Back: closes the sync editor, lyrics, player, then pages. False when there is nothing to close. */
    fun back(): Boolean {
        if (selection.value != null && screen.value == Screen.Library) {
            selection.value = null
            return true
        }
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
    fun play(list: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        // In shuffle the blacklisted songs stay out (unless that leaves nothing).
        val songs = if (shuffle) list.filterNot { statsStore.isNoShuffle(it.id) }.ifEmpty { list } else list
        player.play(songs.map { it.toMediaItem() }, start, shuffle)
    }

    fun isNoShuffle(song: Song) = statsStore.isNoShuffle(song.id)

    fun setNoShuffle(list: List<Song>, on: Boolean) = statsStore.setNoShuffle(list.map { it.id }, on)

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

    /** Listen to the song on the phone (whisper.cpp) and give each line of the lyrics its time. */
    fun syncByVoice() {
        val song = songOf(player.ui.value.current) ?: return
        val shown = (lyrics.value as? LyricsUi.Shown)?.lyrics ?: return
        val plain = shown.copy(lines = shown.lines.map { Lyrics.Line(null, it.text) }, synced = false)
        val previous = voiceJob
        if (!voiceSync.supported) {
            io.github.atrzad.ayomusica.util.AppLog.w("Voz", "processador sem as instruções da voz")
            voice.value = VoiceState("O processador deste celular não tem as instruções que a sincronização pela voz usa. " +
                "Use Sincronizar tocando, que marca cada linha na hora.", running = false)
            return
        }
        voiceJob = viewModelScope.launch {
            // Only one voice job at a time: the previous one is stopped and waited for (it runs in native code).
            previous?.let { voiceSync.cancel(); it.cancelAndJoin() }
            try {
                if (!voiceSync.hasModel) {
                    voice.value = VoiceState("Baixando o modelo de voz (60 MB, só desta vez)…", 0f)
                    voiceSync.downloadModel { fraction ->
                        voice.value = VoiceState("Baixando o modelo de voz (60 MB, só desta vez)…", fraction)
                    }
                }
                val result = voiceSync.sync(song, plain) { stage, fraction ->
                    voice.value = VoiceState("${stage.text}…", fraction)
                }
                if (result == null) {
                    voice.value = VoiceState("Não deu para entender a voz o bastante para sincronizar esta música. " +
                        "Tente Sincronizar tocando.", running = false)
                    return@launch
                }
                val (synced, share) = result
                lyricsRepository.save(song, io.github.atrzad.ayomusica.lyrics.LrcWriter.write(synced, song.title, song.artist), "", "voz")
                loadLyrics()
                voice.value = VoiceState("Letra sincronizada pela voz (${(share * 100).toInt()}% das palavras reconhecidas). " +
                    "Se alguma linha ficar adiantada ou atrasada, use −0,5 / +0,5.", running = false)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                io.github.atrzad.ayomusica.util.AppLog.e("Voz", "sincronizar falhou", error)
                voice.value = VoiceState(if (error.message == "Cancelado") "Sincronização cancelada."
                    else "Não deu para sincronizar: ${error.message ?: "erro"}", running = false)
            }
        }
    }

    /** No lyrics found anywhere: transcribe them from the singing (same model as syncing by voice). */
    fun transcribeByVoice() {
        val song = songOf(player.ui.value.current) ?: return
        val previous = voiceJob
        if (!voiceSync.supported) {
            voice.value = VoiceState("O processador deste celular não tem as instruções que a transcrição pela voz usa.",
                running = false)
            return
        }
        voiceJob = viewModelScope.launch {
            previous?.let { voiceSync.cancel(); it.cancelAndJoin() }
            try {
                if (!voiceSync.hasModel) {
                    voice.value = VoiceState("Baixando o modelo de voz (60 MB, só desta vez)…", 0f)
                    voiceSync.downloadModel { fraction -> voice.value = VoiceState("Baixando o modelo de voz (60 MB, só desta vez)…", fraction) }
                }
                val heard = voiceSync.transcribe(song) { stage, fraction ->
                    voice.value = VoiceState(if (stage == io.github.atrzad.ayomusica.voice.VoiceSync.Stage.Listen)
                        "Ouvindo e escrevendo a letra…" else "${stage.text}…", fraction)
                }
                if (heard == null) {
                    voice.value = VoiceState("Não deu para ouvir letra nesta música (pode ser instrumental).", running = false)
                    return@launch
                }
                lyricsRepository.save(song, io.github.atrzad.ayomusica.lyrics.LrcWriter.write(heard, song.title, song.artist), "",
                    "transcrita")
                loadLyrics()
                voice.value = VoiceState("Letra transcrita pela voz, com ${heard.lines.size} linhas. Pode ter palavras erradas: " +
                    "se achar a letra certa depois, use Buscar letra.", running = false)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                io.github.atrzad.ayomusica.util.AppLog.e("Voz", "transcrever falhou", error)
                voice.value = VoiceState(if (error.message == "Cancelado") "Transcrição cancelada."
                    else "Não deu para transcrever: ${error.message ?: "erro"}", running = false)
            }
        }
    }

    fun cancelVoice() {
        voiceSync.cancel()
        voiceJob?.cancel()
        voice.value = null
    }

    fun closeVoice() {
        voice.value = null
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
    /** Looks up the songs not analyzed yet (going on from where the last run stopped). */
    fun analyze(all: Boolean) {
        if (analysis.value.running) return
        analysisJob = viewModelScope.launch {
            val fixes = overrides.all.value
            val data = analysisStore.data.value
            val targets = withContext(Dispatchers.Default) {
                scanned.value.filter {
                    it.id !in fixes && it.id !in data.ignored && it.id !in data.results && (all || Analyzer.needsWork(it))
                }
            }
            analysis.value = AnalysisState(running = true, total = targets.size)
            val analyzer = Analyzer(audio = audioId)
            try {
                for ((index, song) in targets.withIndex()) {
                    if (!isActive) break
                    val outcome = withContext(Dispatchers.IO) { runCatching { analyzer.analyze(song) } }
                    val (verdict, proposal) = outcome.getOrElse { error ->
                        io.github.atrzad.ayomusica.util.AppLog.e("Analisador", "parou em ${song.id}", error)
                        analysis.update {
                            it.copy(running = false, error = "Sem conexão com as fontes (Deezer, Apple Music, MusicBrainz). " +
                                "O que já foi analisado ficou salvo.")
                        }
                        return@launch
                    }
                    // Skipped if, meanwhile, the person corrected or ignored it.
                    if (song.id in overrides.all.value || song.id in analysisStore.data.value.ignored) continue
                    analysisStore.put(song.id, io.github.atrzad.ayomusica.analyzer.SavedResult(verdict, proposal))
                    if (verdict == Verdict.Auto && proposal != null) applyProposal(song, proposal)
                    analysis.update { it.copy(done = index + 1) }
                }
            } finally {
                analysisStore.flush()
                analysis.update { it.copy(running = false) }
            }
        }
    }

    fun stopAnalysis() {
        analysisJob?.cancel()
        analysis.update { it.copy(running = false) }
    }

    /** Looks up the not-found songs again on the next run (sources change, tags get fixed). */
    fun retryNotFound() {
        analysisStore.forget(Verdict.NotFound)
        analyze(all = true)
    }

    fun ignore(item: AnalysisItem) = analysisStore.ignore(item.song.id)

    fun unignore(item: AnalysisItem) = analysisStore.unignore(item.song.id)

    private suspend fun applyProposal(song: Song, proposal: Proposal): Boolean {
        writeOverride(song.id, proposal.override, proposal.covers, useCover = true)
        return true
    }

    /**
     * Saves [override] for a song, with the first of [covers] that downloads when [useCover]; otherwise keeps the cover
     * saved before, if any. Each cover gets a new file name so no screen shows the old one from its cache.
     */
    private suspend fun writeOverride(id: Long, override: io.github.atrzad.ayomusica.data.SongOverride, covers: List<String>,
                                      useCover: Boolean) = withContext(Dispatchers.IO) {
        val previous = overrides.all.value[id]?.coverFile
        val cover = if (useCover && covers.isNotEmpty()) {
            File(getApplication<Application>().filesDir, "covers/$id-${System.currentTimeMillis()}.jpg")
                .takeIf { Http.download(covers, it) }?.absolutePath
        } else null
        overrides.put(id, override.copy(coverFile = cover ?: previous))
        if (cover != null && previous != null && previous != cover) File(previous).delete()
    }

    /** "Corrigir informações": what the file's own tags say (without the app's correction). */
    fun originalSong(id: Long): Song? = scanned.value.firstOrNull { it.id == id }

    fun searchMetadata(song: Song, title: String, artist: String, sources: Set<Source>) {
        metaJob?.cancel()
        metaSearch.value = MetaSearchState(loading = true)
        metaJob = viewModelScope.launch {
            val asked = sources.filterNot { it.byAudio }.ifEmpty { Source.byText }.toList()
            val answers = asked.map { source ->
                async(Dispatchers.IO) { runCatching { metaSources.getValue(source).search(title, artist) } }
            }.awaitAll()
            val reading = io.github.atrzad.ayomusica.lyrics.Clean.Reading(artist, title)
            val found = withContext(Dispatchers.Default) {
                // The likeliest first (title, artist and duration against this song), whichever source it came from.
                answers.flatMap { it.getOrDefault(emptyList()) }
                    .sortedByDescending { Analyzer.score(it, song, listOf(reading) + io.github.atrzad.ayomusica.lyrics.Clean.readings(song)) }
            }
            metaSearch.value = MetaSearchState(results = found, searched = true,
                failed = asked.filterIndexed { index, _ -> answers[index].isFailure })
        }
    }

    suspend fun completeCandidate(candidate: Candidate): Candidate = withContext(Dispatchers.IO) {
        runCatching {
            if (candidate.source.byAudio) audioId.complete(candidate) else metaSources.getValue(candidate.source).complete(candidate)
        }.getOrDefault(candidate)
    }

    /** "Corrigir informações" → Pelo som: what Shazam and AcoustID hear in the song. */
    fun identifyByAudio(song: Song) {
        metaJob?.cancel()
        metaSearch.value = MetaSearchState(loading = true)
        metaJob = viewModelScope.launch {
            val heard = withContext(Dispatchers.IO) { runCatching { audioId.identify(song) } }
            heard.exceptionOrNull()?.let { io.github.atrzad.ayomusica.util.AppLog.w("PeloSom", "reconhecer ${song.id} falhou", it) }
            metaSearch.value = MetaSearchState(results = heard.getOrDefault(emptyList()), searched = true,
                failed = if (heard.isFailure) listOf(Source.Shazam) else emptyList(),
                note = heard.exceptionOrNull()?.message)
        }
    }

    fun saveOverride(id: Long, override: io.github.atrzad.ayomusica.data.SongOverride, covers: List<String>, useCover: Boolean) {
        viewModelScope.launch {
            writeOverride(id, override.copy(byHand = true), covers, useCover)
        }
    }

    /** Back to what the file's tags say. */
    fun restoreTags(id: Long) {
        overrides.remove(id)
    }

    fun clearMetaSearch() {
        metaJob?.cancel()
        metaSearch.value = MetaSearchState()
    }

    fun accept(item: AnalysisItem) {
        val proposal = item.proposal ?: return
        viewModelScope.launch { applyProposal(item.song, proposal) }
    }

    fun undo(item: AnalysisItem) = overrides.remove(item.song.id)

    /** Accepts every suggestion still waiting for review, one after the other (once: a second tap does nothing). */
    fun acceptAll() {
        if (analysis.value.accepting != null) return
        val waiting = analysisItems.value.filter { !it.applied && !it.ignored && it.proposal != null }
        if (waiting.isEmpty()) return
        viewModelScope.launch {
            try {
                for ((index, item) in waiting.withIndex()) {
                    analysis.update { it.copy(accepting = index to waiting.size) }
                    if (item.song.id !in overrides.all.value) applyProposal(item.song, item.proposal!!)
                }
            } finally {
                analysis.update { it.copy(accepting = null) }
            }
        }
    }

    /** Undoes what the analyzer applied (corrections made by hand stay). */
    fun undoAll() = analysisItems.value.filter { it.applied && it.manual == null }.forEach(::undo)

    // ── playlists ────────────────────────────────────────────────────────
    fun playlistSongs(playlist: Playlist): List<Song> = playlist.songIds.mapNotNull { byId.value[it] }

    fun createPlaylist(name: String, with: List<Song> = emptyList()) =
        viewModelScope.launch { playlistStore.create(name, with.map { it.id }) }

    fun addToPlaylist(id: Long, list: List<Song>) = viewModelScope.launch { playlistStore.add(id, list.map { it.id }) }

    fun removeFromPlaylist(id: Long, position: Int) = viewModelScope.launch { playlistStore.removeAt(id, position) }

    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { playlistStore.rename(id, name) }

    /**
     * Saves title, description and picture. A new [image] (from the photo picker) is copied, at most 1024 px, into the
     * app's storage under a new name; [removeImage] goes back to the first song's cover.
     */
    fun editPlaylist(id: Long, name: String, description: String, image: Uri?, removeImage: Boolean) = viewModelScope.launch {
        val previous = playlists.value.firstOrNull { it.id == id }?.coverFile
        val cover = when {
            image != null -> withContext(Dispatchers.IO) { copyPicture(image, id) } ?: previous
            removeImage -> null
            else -> previous
        }
        playlistStore.edit(id, name, description, cover)
        if (previous != null && previous != cover) withContext(Dispatchers.IO) { File(previous).delete() }
    }

    private fun copyPicture(image: Uri, id: Long): String? = runCatching {
        val context = getApplication<Application>()
        val data = context.contentResolver.openInputStream(image)?.use { it.readBytes() } ?: return null
        val bitmap = io.github.atrzad.ayomusica.data.Artwork.decode(data, 1024) ?: return null
        val side = maxOf(bitmap.width, bitmap.height)
        val scaled = if (side > 1024) android.graphics.Bitmap.createScaledBitmap(bitmap,
            bitmap.width * 1024 / side, bitmap.height * 1024 / side, true) else bitmap
        val target = File(context.filesDir, "playlist-covers/$id-${System.currentTimeMillis()}.jpg")
        target.parentFile?.mkdirs()
        target.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        target.absolutePath
    }.getOrNull()

    fun deletePlaylist(id: Long) = viewModelScope.launch {
        playlistStore.delete(id)
        routes.value = routes.value.filterNot { it is Route.PlaylistPage && it.id == id }
    }
}
