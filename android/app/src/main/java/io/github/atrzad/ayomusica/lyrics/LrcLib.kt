package io.github.atrzad.ayomusica.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToLong

@Serializable
data class LrcLibResult(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/** lrclib.net: free, open lyrics database (synced when available). No key needed. */
class LrcLib(private val get: (String) -> String? = ::httpGet) {
    data class Found(val synced: String, val plain: String, val instrumental: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    /** Best match, or null. Throws IOException when offline. */
    fun find(artist: String, title: String, album: String = "", durationMs: Long = 0): Found? {
        val seconds = if (durationMs > 0) (durationMs / 1000.0).roundToLong() else 0
        val exact = get(url("get", buildMap {
            put("artist_name", artist); put("track_name", title)
            if (album.isNotBlank()) put("album_name", album)
            if (seconds > 0) put("duration", seconds.toString())
        }))
        if (exact != null) {
            runCatching { json.decodeFromString<LrcLibResult>(exact) }.getOrNull()
                ?.takeIf { it.id != 0L }?.let { return pick(listOf(it), seconds) }
        }
        val results = get(url("search", mapOf("track_name" to title, "artist_name" to artist))) ?: return null
        return pick(runCatching { json.decodeFromString<List<LrcLibResult>>(results) }.getOrDefault(emptyList()), seconds)
    }

    companion object {
        const val BASE = "https://lrclib.net/api"
        const val USER_AGENT = "AyoMusica-Android/0.2.0 (https://github.com/atrzad/ayo-musica)"

        fun pick(results: List<LrcLibResult>, seconds: Long): Found? {
            val fitting = results.filter { seconds <= 0 || it.duration == null || abs(it.duration - seconds) <= 5 }
            val chosen = fitting.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: fitting.firstOrNull() ?: return null
            return Found(chosen.syncedLyrics.orEmpty(), chosen.plainLyrics.orEmpty(), chosen.instrumental)
        }

        fun url(path: String, params: Map<String, String>): String =
            "$BASE/$path?" + params.entries.joinToString("&") { (key, value) ->
                "$key=" + URLEncoder.encode(value, "UTF-8")
            }

        /** GET → body, or null on 404. */
        fun httpGet(address: String): String? {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                return when (val code = connection.responseCode) {
                    200 -> connection.inputStream.bufferedReader().use { it.readText() }
                    404 -> null
                    else -> throw IOException("LRCLIB respondeu $code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
