package io.github.atrzad.ayomusica.analyzer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable data class DeezerArtist(val name: String = "")
@Serializable data class DeezerAlbumRef(val id: Long = 0, val title: String = "", val cover_xl: String? = null, val cover_big: String? = null)
@Serializable data class DeezerTrack(
    val id: Long = 0, val title: String = "", val title_short: String = "", val duration: Int = 0,
    val artist: DeezerArtist = DeezerArtist(), val album: DeezerAlbumRef = DeezerAlbumRef(),
)
@Serializable data class DeezerSearch(val data: List<DeezerTrack> = emptyList())
@Serializable data class DeezerGenre(val name: String = "")
@Serializable data class DeezerGenres(val data: List<DeezerGenre> = emptyList())
@Serializable data class DeezerAlbum(
    val id: Long = 0, val title: String = "", val release_date: String = "", val artist: DeezerArtist = DeezerArtist(),
    val genres: DeezerGenres = DeezerGenres(),
)

/** Deezer's public API (no key): search tracks and read album details. About 6 requests a second at most. */
class Deezer(private val get: (String) -> String? = ::httpGet) {
    private val json = Json { ignoreUnknownKeys = true }
    private val albums = mutableMapOf<Long, DeezerAlbum?>()
    private var last = 0L

    private fun fetch(url: String): String? {
        val wait = 170 - (System.currentTimeMillis() - last)
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
        return get(url)
    }

    fun search(query: String): List<DeezerTrack> {
        val body = fetch("https://api.deezer.com/search?limit=15&q=" + URLEncoder.encode(query, "UTF-8")) ?: return emptyList()
        return runCatching { json.decodeFromString<DeezerSearch>(body).data }.getOrDefault(emptyList())
    }

    fun album(id: Long): DeezerAlbum? = albums.getOrPut(id) {
        fetch("https://api.deezer.com/album/$id")?.let { runCatching { json.decodeFromString<DeezerAlbum>(it) }.getOrNull() }
    }

    companion object {
        fun httpGet(address: String): String? {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "AyoMusica-Android (https://github.com/atrzad/ayo-musica)")
            try {
                return when (val code = connection.responseCode) {
                    200 -> connection.inputStream.bufferedReader().use { it.readText() }
                    404 -> null
                    else -> throw IOException("Deezer respondeu $code")
                }
            } finally {
                connection.disconnect()
            }
        }

        fun download(address: String, target: java.io.File): Boolean = runCatching {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            try {
                if (connection.responseCode != 200) return false
                target.parentFile?.mkdirs()
                connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                true
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
