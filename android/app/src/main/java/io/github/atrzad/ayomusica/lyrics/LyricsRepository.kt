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

/** What is remembered per song: the lyrics found or chosen, when we last looked, and the user's delay. */
@Serializable
data class LyricsEntry(
    val synced: String = "",
    val plain: String = "",
    val source: String = "",
    val checkedAt: Long = 0,
    val offsetMs: Long = 0,
)

class LyricsRepository(private val folder: File, private val finder: LyricsFinder = LyricsFinder()) {
    constructor(context: Context) : this(File(context.filesDir, "lyrics"))

    sealed interface Result {
        data class Found(val lyrics: Lyrics) : Result
        data object Missing : Result
        data object Offline : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * In order: lyrics the person chose or synced (saved), synced lyrics inside the file, the cache,
     * then LRCLIB. Plain lyrics are kept as the last resort (and for syncing).
     */
    suspend fun find(song: Song, embedded: Lyrics?, online: Boolean): Result = withContext(Dispatchers.IO) {
        val entry = read(song)
        val offset = entry?.offsetMs ?: 0
        if (entry != null && entry.synced.isNotBlank() && entry.source in CHOSEN) {
            return@withContext Result.Found(Lyrics.parse(entry.synced, entry.source).copy(offsetMs = offset))
        }
        if (embedded != null && embedded.synced) return@withContext Result.Found(embedded.copy(offsetMs = offset))
        if (entry != null && entry.synced.isNotBlank()) {
            return@withContext Result.Found(Lyrics.parse(entry.synced, entry.source).copy(offsetMs = offset))
        }
        val recentlyChecked = entry != null && System.currentTimeMillis() - entry.checkedAt < RETRY_MS
        var plain = entry?.plain?.takeIf { it.isNotBlank() && entry.source in CHOSEN }?.let { Lyrics.parse(it, entry.source) }
            ?: embedded ?: entry?.plain?.takeIf { it.isNotBlank() }?.let { Lyrics.parse(it, entry.source) }
        if (online && !recentlyChecked && song.title.isNotBlank()) {
            try {
                val found = finder.find(song)
                write(song, LyricsEntry(found?.syncedLyrics.orEmpty(), found?.plainLyrics.orEmpty(), found?.source ?: "lrclib",
                    System.currentTimeMillis(), offset))
                if (found != null && found.synced) {
                    return@withContext Result.Found(Lyrics.parse(found.syncedLyrics, found.source).copy(offsetMs = offset))
                }
                if (plain == null && found != null && !found.plainLyrics.isNullOrBlank()) {
                    plain = Lyrics.parse(found.plainLyrics, found.source)
                }
            } catch (error: IOException) {
                io.github.atrzad.ayomusica.util.AppLog.w("Letras", "busca on-line falhou para ${song.id}: ${error.message}")
                if (plain == null) return@withContext Result.Offline
            }
        }
        plain?.let { Result.Found(it) } ?: Result.Missing
    }

    /** Lyrics picked in the manual search, or synced by hand or by voice. */
    suspend fun save(song: Song, synced: String, plain: String, source: String) = withContext(Dispatchers.IO) {
        val offset = read(song)?.offsetMs ?: 0
        write(song, LyricsEntry(synced, plain, source, System.currentTimeMillis(), if (synced.isNotBlank()) 0 else offset))
    }

    suspend fun search(query: String, song: Song?): List<LrcLibResult> = withContext(Dispatchers.IO) {
        finder.search(query, song)
    }

    suspend fun setOffset(song: Song, offsetMs: Long) = withContext(Dispatchers.IO) {
        write(song, (read(song) ?: LyricsEntry()).copy(offsetMs = offsetMs))
    }

    /** The lyrics the person chose or synced for this song (what sync shares), or null. */
    fun chosen(song: Song): LyricsEntry? = read(song)?.takeIf { it.source in CHOSEN && (it.synced.isNotBlank() || it.plain.isNotBlank()) }

    /** Lyrics chosen or synced on another device. */
    fun putChosen(song: Song, entry: LyricsEntry) = write(song, entry.copy(checkedAt = System.currentTimeMillis()))

    /** Another device dropped its choice: back to searching. */
    fun dropChosen(song: Song) {
        if (read(song)?.source in CHOSEN) write(song, LyricsEntry())
    }

    /** Forget what was found automatically (choices and syncs made by the person stay). */
    suspend fun forget(song: Song) = withContext(Dispatchers.IO) {
        val entry = read(song) ?: return@withContext
        if (entry.source !in CHOSEN) write(song, LyricsEntry(offsetMs = entry.offsetMs))
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
        const val RETRY_MS = 3L * 24 * 3600 * 1000
        /** Sources that came from the person: never replaced by an automatic search. */
        val CHOSEN = setOf("escolhida", "manual", "voz", "transcrita")
    }
}
