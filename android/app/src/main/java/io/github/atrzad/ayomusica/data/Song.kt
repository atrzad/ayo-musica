package io.github.atrzad.ayomusica.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import java.text.Normalizer

const val UNKNOWN_ARTIST = "Artista desconhecido"
const val UNKNOWN_ALBUM = "Álbum desconhecido"

/** One audio file from the phone's media library (MediaStore). */
@Serializable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String = "",
    val durationMs: Long,
    val track: Int = 0,
    val disc: Int = 0,
    val year: Int = 0,
    val dateAdded: Long = 0,
    val relativePath: String = "",
    val displayName: String = "",
    val genre: String = "",
    /** Official cover downloaded by the analyzer (a file in the app's storage), shown instead of the file's. */
    val coverFile: String? = null,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    /** Where the cover comes from: the analyzer's official cover, or the song file's own picture. */
    val artUri: Uri get() = coverFile?.let { Uri.fromFile(java.io.File(it)) } ?: uri
    val shownGenre: String get() = genre.ifBlank { "Sem gênero" }
    val folder: String get() = relativePath.trimEnd('/').ifBlank { "Pasta principal" }
    val shownArtist: String get() = artist.ifBlank { UNKNOWN_ARTIST }
    val shownAlbum: String get() = album.ifBlank { UNKNOWN_ALBUM }
    // Computed once per song (big libraries sort and group thousands of them).
    val albumKey: String by lazy { "${fold(albumArtist.ifBlank { artist })}|${fold(album)}" }
    val searchText: String by lazy { fold("$title $artist $album $albumArtist $displayName") }
    val titleKey: String by lazy { fold(title) }
    val albumTitleKey: String by lazy { fold(album) }
    val fileKey: String by lazy { fold(displayName) }
}

data class Album(val key: String, val title: String, val artist: String, val year: Int, val songs: List<Song>) {
    val cover: Song get() = songs.first()
}

data class Artist(val name: String, val songs: List<Song>, val albumCount: Int)

/** A genre or a folder: a name and its songs. */
data class Group(val name: String, val songs: List<Song>)

/** "São Paulo" → "sao paulo": search without accents or case. */
private val MARKS = Regex("\\p{M}+")
private val SPACES = Regex("\\s+")

fun fold(text: String): String =
    MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase().trim()

/** Sort by a key computed once per item (sortedBy alone recomputes it at every comparison). */
private inline fun <T> List<T>.sortedByKey(crossinline key: (T) -> String): List<T> =
    map { it to key(it) }.sortedBy { it.second }.map { it.first }

/** Albums and artists from the song list; tracks in disc/track order. */
object Grouping {
    fun albums(songs: List<Song>): List<Album> = songs.groupBy { it.albumKey }.map { (key, tracks) ->
        val ordered = tracks.sortedWith(compareBy({ it.disc }, { it.track }, { it.titleKey }))
        val artist = ordered.firstNotNullOfOrNull { it.albumArtist.ifBlank { null } }
            ?: ordered.map { it.shownArtist }.distinct().let { if (it.size == 1) it[0] else "Vários artistas" }
        Album(key, ordered.first().shownAlbum, artist, ordered.maxOf { it.year }, ordered)
    }.sortedByKey { fold(it.title) }

    fun artists(songs: List<Song>): List<Artist> = songs.groupBy { it.shownArtist }.map { (name, tracks) ->
        Artist(name, tracks.sortedWith(compareBy({ it.albumTitleKey }, { it.disc }, { it.track })),
            tracks.map { it.albumKey }.distinct().size)
    }.sortedByKey { fold(it.name) }

    fun genres(songs: List<Song>): List<Group> =
        songs.groupBy { it.shownGenre }.map { (name, tracks) -> Group(name, tracks.sortedBy { it.titleKey }) }
            .sortedByKey { fold(it.name) }

    fun folders(songs: List<Song>): List<Group> =
        songs.groupBy { it.folder }.map { (name, tracks) -> Group(name, tracks.sortedBy { it.fileKey }) }
            .sortedByKey { fold(it.name) }

    fun search(songs: List<Song>, query: String): List<Song> {
        val words = fold(query).split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return songs
        return songs.filter { song -> words.all { song.searchText.contains(it) } }
    }
}

fun durationText(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
