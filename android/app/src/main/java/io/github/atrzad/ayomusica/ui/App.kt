package io.github.atrzad.ayomusica.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.lyrics.Lyrics
import io.github.atrzad.ayomusica.playback.PlayerHub
import kotlinx.coroutines.launch

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun App(viewModel: MusicViewModel, version: String) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        granted = results[audioPermission] == true
    }
    val permissions = buildList {
        add(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    LaunchedEffect(Unit) { if (!granted) ask.launch(permissions) }
    LaunchedEffect(granted) { if (granted) viewModel.refresh() }
    if (!granted) {
        EmptyState("Permita o acesso às músicas",
            "O Ayo Música lê as músicas do celular para montar a biblioteca. Nada é enviado ou alterado.") {
            Button(onClick = { ask.launch(permissions) }) { Text("Permitir") }
        }
        return
    }
    Main(viewModel, version)
}

@Composable
private fun Main(viewModel: MusicViewModel, version: String) {
    val prefs = viewModel.prefs
    val ui by viewModel.player.ui.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val sleep by viewModel.sleep.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val metaSearch by viewModel.metaSearch.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val fixes by viewModel.fixes.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    // "Corrigir informações" starts with a fresh search for that song.
    val openFix: (Long) -> Unit = { id -> viewModel.clearMetaSearch(); viewModel.open(Route.FixSong(id)) }
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val autoCounts by viewModel.autoCounts.collectAsStateWithLifecycle()
    val needingWork by viewModel.needingWorkCount.collectAsStateWithLifecycle()
    val useMode by prefs.useMode.collectAsStateWithLifecycle()
    val tabs by prefs.tabs.collectAsStateWithLifecycle()
    val startTab by prefs.startTab.collectAsStateWithLifecycle()
    val visualizer by prefs.visualizer.collectAsStateWithLifecycle()
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val mode by prefs.mode.collectAsStateWithLifecycle()
    val lyricsOnline by prefs.lyricsOnline.collectAsStateWithLifecycle()
    val tutorialSeen by prefs.tutorialSeen.collectAsStateWithLifecycle()
    val sessionId by PlayerHub.audioSessionId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var explainVisualizer by remember { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf<List<Song>?>(null) }
    var naming by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var editingPlaylist by remember { mutableStateOf<io.github.atrzad.ayomusica.data.Playlist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<io.github.atrzad.ayomusica.data.Playlist?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun tell(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        prefs.setVisualizer(ok)
    }
    fun toggleVisualizer() {
        when {
            visualizer -> prefs.setVisualizer(false)
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> prefs.setVisualizer(true)
            else -> explainVisualizer = true
        }
    }

    val route = routes.last()
    // Observed, so a correction saved in the app shows at once on every screen (not only on the next song).
    val byId by viewModel.byId.collectAsStateWithLifecycle()
    val current = ui.current?.mediaId?.toLongOrNull()?.let(byId::get)
    val currentId = ui.current?.mediaId
    val favorite = current != null && stats[current.id]?.favorite == true
    val actions = SongActions(
        playNext = { viewModel.playNext(listOf(it)); tell("Vai tocar a seguir") },
        enqueue = { viewModel.enqueue(listOf(it)); tell("Adicionada à fila") },
        addToPlaylist = { addingToPlaylist = listOf(it) },
        goToAlbum = { song -> viewModel.open(Route.AlbumPage(song.albumKey)) },
        goToArtist = { song -> viewModel.open(Route.ArtistPage(song.shownArtist)) },
        remove = (route as? Route.PlaylistPage)?.let { page -> { position: Int -> viewModel.removeFromPlaylist(page.id, position) } },
        isFavorite = { stats[it.id]?.favorite == true },
        toggleFavorite = { song ->
            viewModel.toggleFavorite(song)
            tell(if (stats[song.id]?.favorite == true) "Tirada das curtidas" else "Adicionada às curtidas")
        },
        fixInfo = { song -> openFix(song.id) },
    )
    // The search keyboard must not stay open over the player or the lyrics.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(screen) {
        if (screen != Screen.Library) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }
    val play: (List<Song>, Int, Boolean) -> Unit = { list, start, shuffle ->
        keyboard?.hide()
        focus.clearFocus()
        viewModel.play(list, start, shuffle)
    }

    BackHandler(enabled = screen != Screen.Library || routes.size > 1 || selection != null) { viewModel.back() }

    // First run (or Configurações → Como usar): the tutorial covers everything.
    val tutorialRoute = route is Route.SettingsOf && route.page == SettingsPage.Tutorial
    if (!tutorialSeen || tutorialRoute) {
        TutorialScreen(onDone = {
            prefs.setTutorialSeen(true)
            if (tutorialRoute) viewModel.back()
        })
        return
    }

    if (useMode == UseMode.Car) {
        CarScreen(ui, current, viewModel.player, favorite, onFavorite = { current?.let(viewModel::toggleFavorite) },
            onShuffleAll = { viewModel.play(songs, 0, true) }, onExit = { prefs.setUseMode(UseMode.Normal) })
        return
    }

    val home = route as? Route.Home
    val tab = home?.tab ?: startTab
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                val picking = selection
                if (home != null && picking != null) {
                    SelectionBar(picking.size, onClose = viewModel::clearSelection,
                        onSelectAll = { viewModel.selectAll(results) },
                        onPlay = { viewModel.play(viewModel.selectedSongs()); viewModel.clearSelection() },
                        onAddToPlaylist = { addingToPlaylist = viewModel.selectedSongs() },
                        onPlayNext = {
                            viewModel.playNext(viewModel.selectedSongs()); viewModel.clearSelection(); tell("Vão tocar a seguir")
                        },
                        onEnqueue = {
                            viewModel.enqueue(viewModel.selectedSongs()); viewModel.clearSelection(); tell("Adicionadas à fila")
                        },
                        onLike = {
                            viewModel.likeAll(viewModel.selectedSongs()); viewModel.clearSelection(); tell("Adicionadas às curtidas")
                        })
                } else if (home != null) {
                    HomeHeader(ui, current, viewModel.player, onSettings = { viewModel.open(Route.Settings) },
                        onOpenPlayer = { viewModel.show(Screen.Player) }, simple = useMode == UseMode.Simple)
                } else {
                    PageHeader(titleOf(route, playlists), onBack = { viewModel.back() }) {
                        (route as? Route.PlaylistPage)?.let { page ->
                            val playlist = playlists.firstOrNull { it.id == page.id }
                            IconButton(onClick = { editingPlaylist = playlist }) { Icon(Icons.Rounded.Edit, "Editar playlist") }
                            IconButton(onClick = { deletingPlaylist = playlist }) { Icon(Icons.Rounded.Delete, "Excluir playlist") }
                        }
                        if (route == Route.Settings) IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Rounded.Refresh, "Atualizar biblioteca")
                        }
                    }
                }
            },
            bottomBar = {
                if (home != null && useMode == UseMode.Simple) {
                    Column {
                        NowPlayingBar(ui, current, viewModel.player) { viewModel.show(Screen.Player) }
                        FixedTabs(tabs, tab) { viewModel.open(Route.Home(it)) }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            // In normal mode the tab button floats over the list (the lists leave room at their end).
            Column(Modifier.padding(padding)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (route) {
                    is Route.Home -> when (route.tab) {
                        Tab.Songs -> SongsScreen(results, currentId, query, { viewModel.query.value = it }, actions, play,
                            selection, viewModel::toggleSelected)
                        Tab.Albums -> AlbumsScreen(albums) { viewModel.open(Route.AlbumPage(it.key)) }
                        Tab.Artists -> ArtistsScreen(artists) { viewModel.open(Route.ArtistPage(it.name)) }
                        Tab.Genres -> GroupsScreen(genres, Icons.Rounded.Category) { viewModel.open(Route.GenrePage(it.name)) }
                        Tab.Folders -> GroupsScreen(folders, Icons.Rounded.Folder) { viewModel.open(Route.FolderPage(it.name)) }
                        Tab.Playlists -> PlaylistsScreen(playlists, { viewModel.playlistSongs(it).size },
                            onOpen = { viewModel.open(Route.PlaylistPage(it.id)) },
                            onCreate = { naming = "Nova playlist" to { name: String -> viewModel.createPlaylistAndPick(name) } },
                            autoCount = { list -> autoCounts[list] ?: 0 },
                            onAuto = { viewModel.open(Route.AutoPage(it)) })
                    }
                    is Route.AlbumPage -> albums.firstOrNull { it.key == route.key }?.let { AlbumScreen(it, currentId, actions, play) }
                    is Route.ArtistPage -> artists.firstOrNull { it.name == route.name }?.let { ArtistScreen(it, currentId, actions, play) }
                    is Route.GenrePage -> genres.firstOrNull { it.name == route.name }?.let { GroupScreen(it, currentId, actions, play) }
                    is Route.FolderPage -> folders.firstOrNull { it.name == route.name }?.let { GroupScreen(it, currentId, actions, play) }
                    is Route.PlaylistPage -> playlists.firstOrNull { it.id == route.id }
                        ?.let { PlaylistScreen(it, viewModel.playlistSongs(it), currentId, actions, play,
                            onAddSongs = { viewModel.open(Route.PickSongs(it.id)) }) }
                    is Route.PickSongs -> playlists.firstOrNull { it.id == route.playlistId }?.let { playlist ->
                        PickSongsScreen(playlist, songs, currentId, actions) { chosen ->
                            viewModel.addToPlaylist(playlist.id, chosen)
                            tell(addedText(playlist, chosen))
                            viewModel.back()
                        }
                    }
                    is Route.AutoPage -> {
                        val list = remember(route.list, songs, stats) { route.list.songs(songs, stats) }
                        AutoListScreen(route.list, list, currentId, actions, play)
                    }
                    is Route.FixSong -> {
                        val original = remember(route.id, loaded) { viewModel.originalSong(route.id) }
                        val shown = songs.firstOrNull { it.id == route.id } ?: original
                        if (original != null && shown != null) {
                            FixSongScreen(original, shown, fixes[route.id], metaSearch,
                                onSearch = { title, artist, sources -> viewModel.searchMetadata(original, title, artist, sources) },
                                onComplete = viewModel::completeCandidate,
                                onSave = { override, covers, useCover ->
                                    viewModel.saveOverride(route.id, override, covers, useCover)
                                    tell("Informações salvas no app")
                                    viewModel.back()
                                },
                                onRestore = { viewModel.restoreTags(route.id); tell("Tags do arquivo restauradas") })
                        }
                    }
                    Route.Settings -> SettingsScreen({ viewModel.open(Route.SettingsOf(it)) }, version)
                    is Route.SettingsOf -> when (route.page) {
                        SettingsPage.Tutorial -> Unit  // shown full screen above
                        SettingsPage.Analyzer -> AnalyzerPage(analysis, needingWork, songs.size,
                            viewModel.correctedCount(), viewModel::analyze, viewModel::stopAnalysis, viewModel::accept,
                            viewModel::undo, viewModel::undoAll, viewModel::acceptAll, onSearch = { openFix(it.song.id) })
                        SettingsPage.Equalizer -> EqualizerPage()
                        SettingsPage.UseModes -> UseModePage(useMode, prefs::setUseMode)
                        SettingsPage.Themes -> ThemeContent(theme, mode, MaterialTheme.colorScheme.background.luminance() < 0.3f,
                            prefs::setTheme, prefs::setMode)
                        SettingsPage.HomeTabs -> HomeTabsPage(tabs, startTab, prefs::setTabs, prefs::setStartTab)
                        SettingsPage.LyricsSettings -> LyricsSettingsPage(lyricsOnline, prefs::setLyricsOnline)
                    }
                }
            }
        }
        if (home != null && useMode == UseMode.Normal && selection == null) {
            RadialTabs(tabs, tab, { viewModel.open(Route.Home(it)) },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        AnimatedVisibility(screen != Screen.Library && ui.current != null,
            enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            PlayerScreen(ui, current, viewModel.player, favorite, onFavorite = { current?.let(viewModel::toggleFavorite) },
                visualizer = visualizer, onVisualizer = ::toggleVisualizer, sessionId = sessionId, sleep = sleep,
                onSleep = viewModel::sleepAfter, onBack = { viewModel.show(Screen.Library) },
                onLyrics = { viewModel.show(Screen.Lyrics) },
                onArtist = {
                    current?.let { viewModel.open(Route.ArtistPage(it.shownArtist)) }
                    viewModel.show(Screen.Library)
                },
                onFixInfo = {
                    current?.let { openFix(it.id) }
                    viewModel.show(Screen.Library)
                }, simple = useMode == UseMode.Simple)
        }
        AnimatedVisibility(screen == Screen.Lyrics || screen == Screen.Sync,
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            LyricsScreen(ui, current, viewModel.player, lyrics, onBack = { viewModel.show(Screen.Player) },
                onShift = viewModel::shiftLyrics, onRetry = { viewModel.loadLyrics(force = true) },
                onSearch = viewModel::searchLyrics, onChoose = { viewModel.chooseLyrics(it); tell("Letra escolhida") },
                onSync = { viewModel.show(Screen.Sync) }, onVoiceSync = viewModel::syncByVoice)
        }
        val toSync = (lyrics as? LyricsUi.Shown)?.lyrics
        if (screen == Screen.Sync && toSync != null) {
            SyncEditor(ui, current, toSync, viewModel.player,
                onDone = { lrc -> viewModel.saveSynced(lrc, "manual"); viewModel.show(Screen.Lyrics); tell("Letra sincronizada salva") },
                onCancel = { viewModel.show(Screen.Lyrics) })
        }
    }

    voice?.let { state ->
        AlertDialog(
            onDismissRequest = { if (!state.running) viewModel.closeVoice() },
            title = { Text("Sincronizar pela voz") },
            text = {
                Column {
                    Text(state.text)
                    if (state.running) {
                        if (state.progress >= 0f) {
                            LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth().padding(top = 16.dp))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                        }
                        Text("Roda no celular, sem enviar nada. Pode levar um ou dois minutos.", Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                if (state.running) TextButton(onClick = viewModel::cancelVoice) { Text("Cancelar") }
                else TextButton(onClick = viewModel::closeVoice) { Text("OK") }
            },
        )
    }
    if (explainVisualizer) {
        AlertDialog(
            onDismissRequest = { explainVisualizer = false },
            title = { Text("Visualizador") },
            text = {
                Text("Para desenhar as barras, o Android pede a permissão de gravar áudio: é assim que um app lê " +
                    "o som que ele mesmo está tocando. O microfone não é usado e nada é gravado ou enviado.")
            },
            confirmButton = {
                TextButton(onClick = { explainVisualizer = false; askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Continuar")
                }
            },
            dismissButton = { TextButton(onClick = { explainVisualizer = false }) { Text("Agora não") } },
        )
    }
    addingToPlaylist?.let { list ->
        AddToPlaylistDialog(
            playlists,
            onPick = { playlist ->
                viewModel.addToPlaylist(playlist.id, list)
                addingToPlaylist = null
                viewModel.clearSelection()
                tell(addedText(playlist, list))
            },
            onCreate = { name ->
                viewModel.createPlaylist(name, list)
                addingToPlaylist = null
                viewModel.clearSelection()
                tell("Playlist “$name” criada com ${if (list.size == 1) "1 música" else "${list.size} músicas"}")
            },
            onDismiss = { addingToPlaylist = null },
        )
    }
    deletingPlaylist?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deletingPlaylist = null },
            title = { Text("Excluir “${playlist.name}”?") },
            text = { Text("A playlist some, mas as músicas continuam no celular.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(playlist.id)
                    deletingPlaylist = null
                    tell("Playlist “${playlist.name}” excluída")
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingPlaylist = null }) { Text("Cancelar") } },
        )
    }
    editingPlaylist?.let { playlist ->
        EditPlaylistDialog(playlist, onDismiss = { editingPlaylist = null }) { name, description, image, remove ->
            viewModel.editPlaylist(playlist.id, name, description, image, remove)
            editingPlaylist = null
            tell("Playlist salva")
        }
    }
    naming?.let { (title, done) ->
        val initial = (route as? Route.PlaylistPage)?.let { page -> playlists.firstOrNull { it.id == page.id }?.name }
        NameDialog(title, if (title.startsWith("Renomear")) initial.orEmpty() else "",
            if (title.startsWith("Renomear")) "Renomear" else "Criar",
            onDone = { done(it); naming = null }, onDismiss = { naming = null })
    }
}

/** What adding to a playlist did: songs already in it are skipped, so only the new ones count. */
private fun addedText(playlist: io.github.atrzad.ayomusica.data.Playlist, list: List<Song>): String {
    val already = playlist.songIds.toSet()
    val fresh = list.count { it.id !in already }
    val skipped = list.size - fresh
    return when {
        fresh == 0 -> if (list.size == 1) "Já estava em “${playlist.name}”" else "Todas já estavam em “${playlist.name}”"
        fresh == 1 -> "1 música adicionada a “${playlist.name}”"
        else -> "$fresh músicas adicionadas a “${playlist.name}”"
    } + if (fresh > 0 && skipped > 0) " ($skipped já estavam)" else ""
}

private fun titleOf(route: Route, playlists: List<io.github.atrzad.ayomusica.data.Playlist>): String = when (route) {
    is Route.Home -> route.tab.title
    is Route.AlbumPage -> "Álbum"
    is Route.ArtistPage -> "Artista"
    is Route.GenrePage -> "Gênero"
    is Route.FolderPage -> "Pasta"
    is Route.PlaylistPage -> playlists.firstOrNull { it.id == route.id }?.name ?: "Playlist"
    is Route.AutoPage -> route.list.title
    Route.Settings -> "Configurações"
    is Route.SettingsOf -> route.page.title
    is Route.FixSong -> "Corrigir informações"
    is Route.PickSongs -> "Adicionar músicas"
}
