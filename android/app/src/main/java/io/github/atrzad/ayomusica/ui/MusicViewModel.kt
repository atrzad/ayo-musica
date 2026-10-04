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
import io.github.atrzad.ayomusica.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    Tutorial("Como usar"), Analyzer("Analisador de músicas"), Equalizer("Equalizador"), UseModes("Modo"), Themes("Temas"),
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
    // Everything derived from the library runs off the main thread: thousands of songs must not freeze the screen.
    val songs: StateFlow<List<Song>> = combine(scanned, overrides.all) { list, fixes ->
        if (fixes.isEmpty()) list else list.map { song -> fixes[song.id]?.let { Overrides.apply(song, it) } ?: song }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
    val metaSearch = MutableStateFlow(MetaSearchState())
    /** Songs ticked on the home list (in the order they were picked); null when not selecting. */
    val selection = MutableStateFlow<Set<Long>?>(null)
    /** The corrections saved in the app, by song id. */
    val fixes: StateFlow<Map<Long, io.github.atrzad.ayomusica.data.SongOverride>> = overrides.all
    private val metaSources: Map<Source, MetadataSource> by lazy {
        listOf(Deezer(), ITunes(), MusicBrainz()).associateBy { it.source }
    }
    private var metaJob: Job? = null
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
    val byId: StateFlow<Map<Long, Song>> = derived(emptyMap()) { list -> list.associateBy { it.id } }
    /** Sizes of the automatic lists (Curtidas, Mais tocadas...), counted in the background. */
    val autoCounts: StateFlow<Map<AutoList, Int>> = combine(songs, statsStore.all) { list, stats ->
        AutoList.entries.associateWith { it.songs(list, stats).size }
    }.conflate().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val analysisStore = io.github.atrzad.ayomusica.analyzer.AnalysisStore(application)
    private val originals: StateFlow<Map<Long, Song>> = scanned.map { list -> list.associateBy { it.id } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
        voiceJob?.cancel()
        if (!voiceSync.supported) {
            voice.value = VoiceState("O processador deste celular não tem as instruções que a sincronização pela voz usa. " +
                "Use Sincronizar tocando, que marca cada linha na hora.", running = false)
            return
        }
        voiceJob = viewModelScope.launch {
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
                voice.value = VoiceState(if (error.message == "Cancelado") "Sincronização cancelada."
                    else "Não deu para sincronizar: ${error.message ?: "erro"}", running = false)
            }
        }
    }

    /** No lyrics found anywhere: transcribe them from the singing (same model as syncing by voice). */
    fun transcribeByVoice() {
        val song = songOf(player.ui.value.current) ?: return
        voiceJob?.cancel()
        if (!voiceSync.supported) {
            voice.value = VoiceState("O processador deste celular não tem as instruções que a transcrição pela voz usa.",
                running = false)
            return
        }
        voiceJob = viewModelScope.launch {
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
            val analyzer = Analyzer()
            try {
                for ((index, song) in targets.withIndex()) {
                    if (!isActive) break
                    val outcome = withContext(Dispatchers.IO) { runCatching { analyzer.analyze(song) } }
                    val (verdict, proposal) = outcome.getOrElse {
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
            val asked = sources.ifEmpty { Source.entries.toSet() }.toList()
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
        runCatching { metaSources.getValue(candidate.source).complete(candidate) }.getOrDefault(candidate)
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
