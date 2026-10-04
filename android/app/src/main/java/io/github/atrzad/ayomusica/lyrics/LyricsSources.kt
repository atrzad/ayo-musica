package io.github.atrzad.ayomusica.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true }

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

/** GET with optional headers → body; null on 404; IOException otherwise (no connection, blocked...). */
internal fun httpGet(address: String, headers: Map<String, String> = emptyMap()): String? {
    val connection = URL(address).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", LrcLib.USER_AGENT)
    headers.forEach(connection::setRequestProperty)
    try {
        return when (val code = connection.responseCode) {
            200 -> connection.inputStream.bufferedReader().use { it.readText() }
            404 -> null
            else -> throw IOException("${connection.url.host} respondeu $code")
        }
    } finally {
        connection.disconnect()
    }
}

// ── NetEase Cloud Music ─────────────────────────────────────────────────────

@Serializable data class NeteaseArtist(val name: String = "")
@Serializable data class NeteaseAlbum(val name: String = "")
@Serializable data class NeteaseSong(
    val id: Long = 0, val name: String = "", val artists: List<NeteaseArtist> = emptyList(),
    val album: NeteaseAlbum = NeteaseAlbum(), val duration: Long = 0,
)
@Serializable data class NeteaseSongs(val songs: List<NeteaseSong> = emptyList())
@Serializable data class NeteaseSearch(val result: NeteaseSongs = NeteaseSongs())
@Serializable data class NeteaseText(val lyric: String = "")
@Serializable data class NeteaseLyric(val lrc: NeteaseText = NeteaseText())

/**
 * NetEase Cloud Music: a huge catalog with synced lyrics (Western and Brazilian songs too), no key. The search gives
 * title, artist and duration; the lyrics take one more request per song, so only the likely ones are fetched.
 */
class Netease(private val get: (String) -> String? = { httpGet(it, HEADERS) }) {
    fun search(query: String, limit: Int = 8): List<NeteaseSong> {
        if (query.isBlank()) return emptyList()
        val body = get("https://music.163.com/api/search/get?type=1&limit=$limit&s=" + encode(query)) ?: return emptyList()
        return runCatching { lenient.decodeFromString<NeteaseSearch>(body).result.songs }.getOrDefault(emptyList())
    }

    /** The song's synced lyrics, cleaned; null when it has none. */
    fun lyrics(id: Long): String? {
        val body = get("https://music.163.com/api/song/lyric?lv=1&kv=1&tv=-1&id=$id") ?: return null
        val raw = runCatching { lenient.decodeFromString<NeteaseLyric>(body).lrc.lyric }.getOrDefault("")
        return clean(raw).takeIf { Lyrics.parse(it).synced && Lyrics.parse(it).lines.size >= 3 }
    }

    /** A search result as a lyrics result (to score it before fetching the lyrics). */
    fun result(song: NeteaseSong, synced: String? = null) = LrcLibResult(
        id = song.id, trackName = song.name, artistName = song.artists.joinToString(", ") { it.name },
        albumName = song.album.name, duration = song.duration / 1000.0, syncedLyrics = synced, source = SOURCE,
    )

    companion object {
        const val SOURCE = "netease"
        private val HEADERS = mapOf("Referer" to "https://music.163.com/")
        // Credit lines NetEase puts before the lyrics ("作词 : ...", "Composer: ...").
        private val CREDITS = Regex("""^\[[\d:.]+\]\s*(作词|作曲|编曲|制作人|制作|监制|混音|录音|和声|吉他|贝斯|鼓|混音师|母带|""" +
            """Lyricist|Composer|Arranger|Producer|Lyrics by|Written by)\s*[:：]""", RegexOption.IGNORE_CASE)

        fun clean(lrc: String): String = lrc.lines()
            .filterNot { CREDITS.containsMatchIn(it.trim()) }
            .joinToString("\n").trim()
    }
}

// ── lyrics.ovh ──────────────────────────────────────────────────────────────

@Serializable data class OvhLyrics(val lyrics: String = "")

/** lyrics.ovh: plain lyrics by artist and title (no search, no times). A last resort, and text to sync by voice. */
class LyricsOvh(private val get: (String) -> String? = { httpGet(it) }) {
    fun find(artist: String, title: String): LrcLibResult? {
        if (artist.isBlank() || title.isBlank()) return null
        val body = get("https://api.lyrics.ovh/v1/${encode(artist).replace("+", "%20")}/${encode(title).replace("+", "%20")}")
            ?: return null
        val text = runCatching { lenient.decodeFromString<OvhLyrics>(body).lyrics }.getOrDefault("")
            .replace("\r\n", "\n").trim()
        if (text.length < 40) return null
        return LrcLibResult(id = "$artist|$title".hashCode().toLong(), trackName = title, artistName = artist,
            plainLyrics = text, source = SOURCE)
    }

    companion object {
        const val SOURCE = "lyricsovh"
    }
}
