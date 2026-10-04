package io.github.atrzad.ayomusica.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the analyzer found for a song; shown by the app instead of the file's tags (the file stays untouched). */
@Serializable
data class SongOverride(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: Int = 0,
    val genre: String = "",
    val coverFile: String? = null,
    val source: String = "deezer",
    /** Saved on "Corrigir informações" (not accepted from the analyzer). */
    val byHand: Boolean = false,
)

class Overrides(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "overrides.json"))

    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(read())
    val all: StateFlow<Map<Long, SongOverride>> = state

    private fun read(): Map<Long, SongOverride> = runCatching {
        if (file.exists()) json.decodeFromString<Map<Long, SongOverride>>(file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun save(map: Map<Long, SongOverride>) {
        state.value = map
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(map))
        temp.renameTo(file)
    }

    @Synchronized fun put(id: Long, value: SongOverride) = save(state.value + (id to value))

    @Synchronized fun remove(id: Long) {
        state.value[id]?.coverFile?.let { File(it).delete() }
        save(state.value - id)
    }

    companion object {
        fun apply(song: Song, override: SongOverride?): Song = if (override == null) song else song.copy(
            title = override.title.ifBlank { song.title },
            artist = override.artist.ifBlank { song.artist },
            album = override.album.ifBlank { song.album },
            albumArtist = override.albumArtist.ifBlank { song.albumArtist },
            year = override.year.takeIf { it > 0 } ?: song.year,
            genre = override.genre.ifBlank { song.genre },
            coverFile = override.coverFile?.takeIf { File(it).exists() } ?: song.coverFile,
        )
    }
}
