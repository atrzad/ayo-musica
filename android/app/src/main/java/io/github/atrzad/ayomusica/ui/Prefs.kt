package io.github.atrzad.ayomusica.ui

import android.content.Context
import io.github.atrzad.ayomusica.ui.theme.MONO
import io.github.atrzad.ayomusica.ui.theme.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the app is used: normal (drag the round button to change tabs), in the car, or simplified. */
enum class UseMode(val title: String, val detail: String) {
    Normal("Normal", "Segure e arraste o botão redondo para trocar de aba."),
    Car("Modo carro", "Tela deitada, só a música e botões grandes, fácil de usar dirigindo. A tela fica ligada."),
    Simple("Modo simplificado", "Sem distrações: barra fixa de abas, sem segurar e arrastar. Bom para o ônibus."),
}

enum class Tab(val title: String) {
    Songs("Músicas"), Playlists("Playlists"), Albums("Álbuns"), Artists("Artistas"), Genres("Gêneros"), Folders("Pastas")
}

/** The person's choices, kept between sessions. */
class Prefs(context: Context) {
    private val store = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private fun <T> flow(value: T) = MutableStateFlow(value)

    private val _useMode = flow(enumOr(store.getString("use_mode", null), UseMode.Normal))
    val useMode: StateFlow<UseMode> = _useMode
    private val _tabs = flow(readTabs())
    /** The tabs shown, in order (Página inicial). */
    val tabs: StateFlow<List<Tab>> = _tabs
    private val _startTab = flow(enumOr(store.getString("start_tab", null), Tab.Songs))
    val startTab: StateFlow<Tab> = _startTab
    private val _theme = flow(store.getString("theme", MONO)!!)
    val theme: StateFlow<String> = _theme
    private val _mode = flow(enumOr(store.getString("mode", null), Mode.Auto))
    val mode: StateFlow<Mode> = _mode
    private val _visualizer = flow(store.getBoolean("visualizer", false))
    val visualizer: StateFlow<Boolean> = _visualizer
    private val _tutorialSeen = flow(store.getBoolean("tutorial_seen", false))
    val tutorialSeen: StateFlow<Boolean> = _tutorialSeen
    private val _fullscreen = flow(store.getBoolean("fullscreen", false))
    /** Hide the status and navigation bars. */
    val fullscreen: StateFlow<Boolean> = _fullscreen
    private val _shazam = flow(store.getBoolean("shazam", true))
    /** Recognize songs by their sound with Shazam (only a fingerprint is sent). */
    val shazam: StateFlow<Boolean> = _shazam
    private val _acoustidKey = flow(store.getString("acoustid_key", "")!!)
    /** The person's AcoustID application key (free); blank: AcoustID is not used. */
    val acoustidKey: StateFlow<String> = _acoustidKey
    private val _lyricsOnline = flow(store.getBoolean("lyrics_online", true))
    val lyricsOnline: StateFlow<Boolean> = _lyricsOnline

    fun setUseMode(value: UseMode) { _useMode.value = value; store.edit().putString("use_mode", value.name).apply() }
    fun setTheme(value: String) { _theme.value = value; store.edit().putString("theme", value).apply() }
    fun setMode(value: Mode) { _mode.value = value; store.edit().putString("mode", value.name).apply() }
    fun setVisualizer(on: Boolean) { _visualizer.value = on; store.edit().putBoolean("visualizer", on).apply() }
    fun setTutorialSeen(seen: Boolean) { _tutorialSeen.value = seen; store.edit().putBoolean("tutorial_seen", seen).apply() }
    fun setFullscreen(on: Boolean) { _fullscreen.value = on; store.edit().putBoolean("fullscreen", on).apply() }
    fun setShazam(on: Boolean) { _shazam.value = on; store.edit().putBoolean("shazam", on).apply() }
    fun setAcoustidKey(key: String) { _acoustidKey.value = key.trim(); store.edit().putString("acoustid_key", key.trim()).apply() }
    fun setLyricsOnline(on: Boolean) { _lyricsOnline.value = on; store.edit().putBoolean("lyrics_online", on).apply() }

    /** The settings that follow the account to every device (the rest — mode, full screen — belong to each device). */
    fun synced(): Map<String, String> = mapOf(
        "theme" to theme.value, "mode" to mode.value.name, "tabs" to tabs.value.joinToString(",") { it.name },
        "startTab" to startTab.value.name, "lyricsOnline" to lyricsOnline.value.toString(), "shazam" to shazam.value.toString(),
        "acoustidKey" to acoustidKey.value,
    )

    fun applySynced(name: String, value: String) {
        when (name) {
            "theme" -> setTheme(value)
            "mode" -> enumValues<io.github.atrzad.ayomusica.ui.theme.Mode>().firstOrNull { it.name == value }?.let(::setMode)
            "tabs" -> setTabs(value.split(",").mapNotNull { n -> Tab.entries.firstOrNull { it.name == n } })
            "startTab" -> Tab.entries.firstOrNull { it.name == value }?.let(::setStartTab)
            "lyricsOnline" -> setLyricsOnline(value == "true")
            "shazam" -> setShazam(value == "true")
            "acoustidKey" -> setAcoustidKey(value)
        }
    }

    fun setTabs(value: List<Tab>) {
        val tabs = value.distinct().ifEmpty { listOf(Tab.Songs) }
        _tabs.value = tabs
        store.edit().putString("tabs", tabs.joinToString(",") { it.name }).apply()
        if (_startTab.value !in tabs) setStartTab(tabs.first())
    }

    fun setStartTab(value: Tab) { _startTab.value = value; store.edit().putString("start_tab", value.name).apply() }

    private fun readTabs(): List<Tab> = store.getString("tabs", null)?.split(",")
        ?.mapNotNull { name -> Tab.entries.firstOrNull { it.name == name } }?.ifEmpty { null }
        ?: listOf(Tab.Songs, Tab.Playlists, Tab.Albums, Tab.Artists)

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
