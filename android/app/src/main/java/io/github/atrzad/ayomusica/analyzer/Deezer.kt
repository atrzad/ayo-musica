package io.github.atrzad.ayomusica.analyzer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@Serializable data class DeezerArtist(val name: String = "")
@Serializable data class DeezerAlbumRef(
    val id: Long = 0, val title: String = "", val cover_xl: String? = null, val cover_big: String? = null,
    val cover_medium: String? = null,
)
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
class Deezer(private val http: Http = Http(170)) : MetadataSource {
    constructor(get: (String) -> String?) : this(Http(0, get))

    override val source = Source.Deezer
    private val json = Json { ignoreUnknownKeys = true }
    private val albums = mutableMapOf<Long, DeezerAlbum?>()

    fun tracks(query: String): List<DeezerTrack> {
        val body = http.text("https://api.deezer.com/search?limit=15&q=" + URLEncoder.encode(query, "UTF-8")) ?: return emptyList()
        return runCatching { json.decodeFromString<DeezerSearch>(body).data }.getOrDefault(emptyList())
    }

    fun album(id: Long): DeezerAlbum? = synchronized(albums) { albums[id] } ?: run {
        val album = http.text("https://api.deezer.com/album/$id")?.let { runCatching { json.decodeFromString<DeezerAlbum>(it) }.getOrNull() }
        synchronized(albums) { albums[id] = album }
        album
    }

    override fun search(title: String, artist: String): List<Candidate> {
        if (title.isBlank() && artist.isBlank()) return emptyList()
        var results = if (artist.isNotBlank() && title.isNotBlank()) tracks("artist:\"$artist\" track:\"$title\"") else emptyList()
        if (results.isEmpty()) results = tracks("$artist $title".trim())
        return results.map(::candidate)
    }

    override fun complete(candidate: Candidate): Candidate {
        val album = candidate.ref.toLongOrNull()?.let(::album) ?: return candidate
        return candidate.copy(
            albumArtist = album.artist.name.ifBlank { candidate.albumArtist },
            year = album.release_date.take(4).toIntOrNull() ?: candidate.year,
            genre = album.genres.data.firstOrNull()?.name ?: candidate.genre,
        )
    }

    companion object {
        fun candidate(track: DeezerTrack) = Candidate(
            Source.Deezer, track.id.toString(), track.title, track.artist.name, track.album.title,
            seconds = track.duration, thumb = track.album.cover_medium ?: track.album.cover_big,
            covers = listOfNotNull(track.album.cover_xl, track.album.cover_big), ref = track.album.id.toString(),
        )
    }
}
