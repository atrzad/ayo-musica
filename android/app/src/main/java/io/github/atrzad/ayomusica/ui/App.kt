package io.github.atrzad.ayomusica.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.playback.PlayerHub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun App(viewModel: MusicViewModel) {
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
    Main(viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(viewModel: MusicViewModel) {
    val ui by viewModel.player.ui.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val sleep by viewModel.sleep.collectAsStateWithLifecycle()
    val visualizer by viewModel.visualizer.collectAsStateWithLifecycle()
    val sessionId by PlayerHub.audioSessionId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var explainVisualizer by remember { mutableStateOf(false) }
    var choosingTheme by remember { mutableStateOf(false) }
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        viewModel.setVisualizer(ok)
    }
    fun toggleVisualizer() {
        when {
            visualizer -> viewModel.setVisualizer(false)
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> viewModel.setVisualizer(true)
            else -> explainVisualizer = true
        }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf<List<Song>?>(null) }
    var naming by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun tell(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val route = routes.last()
    val currentId = ui.current?.mediaId
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
            tell(if (stats[song.id]?.favorite == true) "Tirada das favoritas" else "Adicionada às favoritas")
        },
    )
    val play: (List<Song>, Int, Boolean) -> Unit = { list, start, shuffle -> viewModel.play(list, start, shuffle) }

    BackHandler(enabled = searching || routes.size > 1) {
        if (searching) {
            searching = false
            viewModel.query.value = ""
        } else viewModel.back()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (routes.size > 1) IconButton(onClick = { viewModel.back() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar")
                        }
                    },
                    title = {
                        if (searching) {
                            TextField(query, { viewModel.query.value = it }, placeholder = { Text("Buscar músicas") },
                                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent))
                        } else {
                            Text(when (route) {
                                is Route.Home -> "Ayo Música"
                                is Route.AlbumPage -> "Álbum"
                                is Route.ArtistPage -> "Artista"
                                is Route.PlaylistPage -> "Playlist"
                                is Route.AutoPage -> route.list.title
                            })
                        }
                    },
                    actions = {
                        if (searching) {
                            IconButton(onClick = { searching = false; viewModel.query.value = "" }) {
                                Icon(Icons.Rounded.Close, "Fechar a busca")
                            }
                        } else {
                            IconButton(onClick = { searching = true; viewModel.open(Route.Home(Tab.Songs)) }) {
                                Icon(Icons.Rounded.Search, "Buscar")
                            }
                            (route as? Route.PlaylistPage)?.let { page ->
                                val playlist = playlists.firstOrNull { it.id == page.id }
                                IconButton(onClick = {
                                    naming = "Renomear playlist" to { name: String -> viewModel.renamePlaylist(page.id, name) }
                                }) { Icon(Icons.Rounded.Edit, "Renomear") }
                                IconButton(onClick = {
                                    viewModel.deletePlaylist(page.id)
                                    tell("Playlist “${playlist?.name.orEmpty()}” excluída")
                                }) { Icon(Icons.Rounded.Delete, "Excluir playlist") }
                            }
                            if (route is Route.Home) {
                                IconButton(onClick = { choosingTheme = true }) { Icon(Icons.Rounded.Palette, "Tema") }
                                IconButton(onClick = viewModel::refresh) {
                                    Icon(Icons.Rounded.Refresh, "Atualizar biblioteca")
                                }
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    MiniPlayer(ui, viewModel.player) { expanded = true }
                    NavigationBar {
                        val selected = (routes.first() as? Route.Home)?.tab
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == selected && routes.size == 1,
                                onClick = { viewModel.open(Route.Home(tab)) },
                                icon = {
                                    Icon(when (tab) {
                                        Tab.Songs -> Icons.Rounded.MusicNote
                                        Tab.Albums -> Icons.Rounded.Album
                                        Tab.Artists -> Icons.Rounded.Person
                                        Tab.Playlists -> Icons.AutoMirrored.Rounded.PlaylistPlay
                                    }, null)
                                },
                                label = { Text(tab.title) },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (route) {
                    is Route.Home -> when (route.tab) {
                        Tab.Songs -> SongsScreen(results, currentId, query.isNotBlank(), actions, play)
                        Tab.Albums -> AlbumsScreen(albums) { viewModel.open(Route.AlbumPage(it.key)) }
                        Tab.Artists -> ArtistsScreen(artists) { viewModel.open(Route.ArtistPage(it.name)) }
                        Tab.Playlists -> PlaylistsScreen(playlists, { viewModel.playlistSongs(it).size },
                            onOpen = { viewModel.open(Route.PlaylistPage(it.id)) },
                            onCreate = { naming = "Nova playlist" to { name: String -> viewModel.createPlaylist(name) } },
                            autoCount = { list -> list.songs(results, stats).size },
                            onAuto = { viewModel.open(Route.AutoPage(it)) })
                    }
                    is Route.AlbumPage -> albums.firstOrNull { it.key == route.key }
                        ?.let { AlbumScreen(it, currentId, actions, play) }
                    is Route.ArtistPage -> artists.firstOrNull { it.name == route.name }
                        ?.let { ArtistScreen(it, currentId, actions, play) }
                    is Route.PlaylistPage -> playlists.firstOrNull { it.id == route.id }
                        ?.let { PlaylistScreen(it, viewModel.playlistSongs(it), currentId, actions, play) }
                    is Route.AutoPage -> AutoListScreen(route.list, route.list.songs(viewModel.songs.value, stats),
                        currentId, actions, play)
                }
            }
        }
        AnimatedVisibility(expanded && ui.current != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            BackHandler { expanded = false }
            val current = viewModel.songOf(ui.current)
            ExpandedPlayer(
                ui, viewModel.player, lyrics,
                favorite = current != null && stats[current.id]?.favorite == true,
                onFavorite = { current?.let(viewModel::toggleFavorite) },
                visualizer = visualizer,
                onVisualizer = ::toggleVisualizer,
                sessionId = sessionId,
                sleep = sleep,
                onSleep = viewModel::sleepAfter,
                onCollapse = { expanded = false },
                onArtist = {
                    viewModel.songOf(ui.current)?.let { viewModel.open(Route.ArtistPage(it.shownArtist)) }
                    expanded = false
                },
                onShiftLyrics = viewModel::shiftLyrics,
                onRetryLyrics = { viewModel.loadLyrics(force = true) },
            )
        }
    }

    addingToPlaylist?.let { songs ->
        AddToPlaylistDialog(
            playlists,
            onPick = { playlist ->
                viewModel.addToPlaylist(playlist.id, songs)
                addingToPlaylist = null
                tell("Adicionada a “${playlist.name}”")
            },
            onCreate = { name ->
                viewModel.createPlaylist(name, songs)
                addingToPlaylist = null
                tell("Playlist “$name” criada")
            },
            onDismiss = { addingToPlaylist = null },
        )
    }
    if (choosingTheme) {
        ThemeSheet(theme, mode, MaterialTheme.colorScheme.background.luminance() < 0.3f,
            onTheme = viewModel::setTheme, onMode = viewModel::setMode, onDismiss = { choosingTheme = false })
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
    naming?.let { (title, done) ->
        val initial = (route as? Route.PlaylistPage)?.let { page -> playlists.firstOrNull { it.id == page.id }?.name }
        NameDialog(title, if (title.startsWith("Renomear")) initial.orEmpty() else "",
            if (title.startsWith("Renomear")) "Renomear" else "Criar",
            onDone = { done(it); naming = null }, onDismiss = { naming = null })
    }
}
