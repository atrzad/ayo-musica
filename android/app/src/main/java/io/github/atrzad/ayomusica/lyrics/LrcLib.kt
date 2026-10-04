package io.github.atrzad.ayomusica.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    /** Where it came from: "lrclib", "netease" or "lyricsovh" (LRCLIB's own answers leave it out). */
    val source: String = "lrclib",
) {
    val synced: Boolean get() = !syncedLyrics.isNullOrBlank()
    val hasLyrics: Boolean get() = synced || !plainLyrics.isNullOrBlank()
}

/** lrclib.net: free, open lyrics database (synced when available). No key needed. */
class LrcLib(private val get: (String) -> String? = ::httpGet) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Exact lookup; null when there is no such song. Throws IOException when offline. */
    fun get(artist: String, title: String, album: String = "", seconds: Long = 0): LrcLibResult? {
        val body = get(url("get", buildMap {
            put("artist_name", artist); put("track_name", title)
            if (album.isNotBlank()) put("album_name", album)
            if (seconds > 0) put("duration", seconds.toString())
        })) ?: return null
        return runCatching { json.decodeFromString<LrcLibResult>(body) }.getOrNull()?.takeIf { it.id != 0L }
    }

    /** Search by fields or by free text (`q`). */
    fun search(params: Map<String, String>): List<LrcLibResult> {
        val body = get(url("search", params)) ?: return emptyList()
        return runCatching { json.decodeFromString<List<LrcLibResult>>(body) }.getOrDefault(emptyList())
    }

    fun search(query: String): List<LrcLibResult> = search(mapOf("q" to query))

    companion object {
        const val BASE = "https://lrclib.net/api"
        const val USER_AGENT = "AyoMusica-Android (https://github.com/atrzad/ayo-musica)"

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
