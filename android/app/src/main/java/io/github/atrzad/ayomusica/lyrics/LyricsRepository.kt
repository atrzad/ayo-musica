package io.github.atrzad.ayomusica.lyrics

import android.content.Context
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.fold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What is remembered per song: the lyrics found, when we last looked, and the user's delay. */
@Serializable
data class LyricsEntry(
    val synced: String = "",
    val plain: String = "",
    val source: String = "",
    val checkedAt: Long = 0,
    val offsetMs: Long = 0,
)

class LyricsRepository(private val folder: File, private val lrcLib: LrcLib = LrcLib()) {
    constructor(context: Context) : this(File(context.filesDir, "lyrics"))

    sealed interface Result {
        data class Found(val lyrics: Lyrics) : Result
        data object Missing : Result
        data object Offline : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Embedded lyrics first (when synced), then the cache, then LRCLIB. */
    suspend fun find(song: Song, embedded: Lyrics?, online: Boolean): Result = withContext(Dispatchers.IO) {
        val entry = read(song)
        val offset = entry?.offsetMs ?: 0
        if (embedded != null && embedded.synced) return@withContext Result.Found(embedded.copy(offsetMs = offset))
        if (entry != null && entry.synced.isNotBlank()) {
            return@withContext Result.Found(Lyrics.parse(entry.synced, entry.source).copy(offsetMs = offset))
        }
        val recentlyChecked = entry != null && System.currentTimeMillis() - entry.checkedAt < RETRY_MS
        var plain = embedded ?: entry?.plain?.takeIf { it.isNotBlank() }?.let { Lyrics.parse(it, entry.source) }
        if (online && !recentlyChecked && song.title.isNotBlank()) {
            try {
                val found = lrcLib.find(song.artist, song.title, song.album, song.durationMs)
                write(song, LyricsEntry(found?.synced.orEmpty(), found?.plain.orEmpty(), "lrclib",
                    System.currentTimeMillis(), offset))
                if (found != null && found.synced.isNotBlank()) {
                    return@withContext Result.Found(Lyrics.parse(found.synced, "lrclib").copy(offsetMs = offset))
                }
                if (plain == null && found != null && found.plain.isNotBlank()) plain = Lyrics.parse(found.plain, "lrclib")
            } catch (_: IOException) {
                if (plain == null) return@withContext Result.Offline
            }
        }
        plain?.let { Result.Found(it) } ?: Result.Missing
    }

    suspend fun setOffset(song: Song, offsetMs: Long) = withContext(Dispatchers.IO) {
        write(song, (read(song) ?: LyricsEntry()).copy(offsetMs = offsetMs))
    }

    suspend fun forget(song: Song) = withContext(Dispatchers.IO) {
        val offset = read(song)?.offsetMs ?: 0
        write(song, LyricsEntry(offsetMs = offset))
    }

    private fun file(song: Song): File {
        val key = "${fold(song.artist)}|${fold(song.title)}|${song.durationMs / 1000}"
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(folder, digest.joinToString("") { "%02x".format(it) }.take(24) + ".json")
    }

    private fun read(song: Song): LyricsEntry? = runCatching {
        file(song).takeIf { it.exists() }?.let { json.decodeFromString<LyricsEntry>(it.readText()) }
    }.getOrNull()

    private fun write(song: Song, entry: LyricsEntry) {
        folder.mkdirs()
        file(song).writeText(json.encodeToString(entry))
    }

    companion object {
        const val RETRY_MS = 7L * 24 * 3600 * 1000
    }
}
