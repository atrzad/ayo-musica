package io.github.atrzad.ayomusica.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SongStats(
    val plays: Int = 0,
    val skips: Int = 0,
    val lastPlayed: Long = 0,
    val favorite: Boolean = false,
    /** On the shuffle blacklist: never comes up while listening in shuffle (still plays when picked). */
    val noShuffle: Boolean = false,
)

/** Plays, skips, last time played and favorites per song (by MediaStore id), in a JSON file. */
class Stats(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(read())
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val all: StateFlow<Map<Long, SongStats>> = state

    private fun read(): Map<Long, SongStats> = runCatching {
        if (file.exists()) json.decodeFromString<Map<Long, SongStats>>(file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun change(id: Long, update: (SongStats) -> SongStats) {
        synchronized(this) {
            state.value = state.value + (id to update(state.value[id] ?: SongStats()))
        }
        val snapshot = state.value
        writer.launch {
            synchronized(file) {
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(json.encodeToString(snapshot))
                temp.renameTo(file)
            }
        }
    }

    fun played(id: Long, now: Long = System.currentTimeMillis()) = change(id) { it.copy(plays = it.plays + 1, lastPlayed = now) }

    fun skipped(id: Long) = change(id) { it.copy(skips = it.skips + 1) }

    fun setFavorite(id: Long, favorite: Boolean) = change(id) { it.copy(favorite = favorite) }

    fun isFavorite(id: Long) = state.value[id]?.favorite == true

    fun setNoShuffle(ids: Collection<Long>, on: Boolean) {
        synchronized(this) {
            state.value = state.value + ids.associateWith { id -> (state.value[id] ?: SongStats()).copy(noShuffle = on) }
        }
        change(ids.firstOrNull() ?: return) { it }  // writes the file
    }

    fun isNoShuffle(id: Long) = state.value[id]?.noShuffle == true

    companion object {
        /** A play counts after half the song or 4 minutes, like the desktop app; earlier means a skip. */
        fun counts(listenedMs: Long, durationMs: Long) = listenedMs >= minOf(durationMs / 2, 4 * 60_000L).coerceAtLeast(10_000)

        @Volatile private var instance: Stats? = null

        fun get(context: Context): Stats = instance ?: synchronized(this) {
            instance ?: Stats(File(context.applicationContext.filesDir, "stats.json")).also { instance = it }
        }
    }
}

/** The automatic lists shown with the playlists. */
enum class AutoList(val title: String) {
    Favorites("Curtidas"), MostPlayed("Mais tocadas"), Recent("Tocadas recentemente"), Added("Adicionadas recentemente"),
    NoShuffle("Fora do aleatório");

    fun songs(library: List<Song>, stats: Map<Long, SongStats>, limit: Int = 100): List<Song> = when (this) {
        Favorites -> library.filter { stats[it.id]?.favorite == true }
        MostPlayed -> library.filter { (stats[it.id]?.plays ?: 0) > 0 }
            .sortedWith(compareByDescending<Song> { stats[it.id]?.plays ?: 0 }.thenByDescending { stats[it.id]?.lastPlayed ?: 0 })
            .take(limit)
        Recent -> library.filter { (stats[it.id]?.lastPlayed ?: 0) > 0 }
            .sortedByDescending { stats[it.id]?.lastPlayed ?: 0 }.take(limit)
        Added -> library.sortedByDescending { it.dateAdded }.take(limit)
        NoShuffle -> library.filter { stats[it.id]?.noShuffle == true }
    }
}
