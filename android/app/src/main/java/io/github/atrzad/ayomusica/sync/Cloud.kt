package io.github.atrzad.ayomusica.sync

import android.content.Context
import android.net.Uri
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/** A song in the cloud, as the server describes it. */
@Serializable
data class CloudTrack(
    val id: Long,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: Int = 0,
    val track: Int = 0,
    val disc: Int = 0,
    val durationMs: Long = 0,
    val size: Long = 0,
    val mime: String = "audio/mpeg",
    val songKey: String = "",
    val cover: Boolean = false,
    val origin: String = "",
    val deleted: Boolean = false,
    val added: Long = 0,
)

@Serializable
private data class CloudCache(val rev: Long = 0, val server: String = "", val tracks: List<CloudTrack> = emptyList())

/**
 * The cloud library: the server's track list kept on the phone (asked again only for what changed), the songs
 * downloaded for listening offline, and sending this phone's songs up.
 */
class Cloud(private val context: Context) {
    private val folder = File(context.filesDir, "cloud").apply { mkdirs() }
    private val cacheFile = File(folder, "tracks.json")
    val downloads = File(context.filesDir, "downloads").apply { mkdirs() }
    private val state = MutableStateFlow(read())
    private val tracksState = MutableStateFlow(state.value.tracks)
    val tracks: StateFlow<List<CloudTrack>> = tracksState
    private val downloadedState = MutableStateFlow(downloadedIds())
    /** Track ids saved on this phone. */
    val downloaded: StateFlow<Set<Long>> = downloadedState
    /** Downloads and uploads in progress: track or song id → 0..1. */
    val transfers = MutableStateFlow<Map<Long, Float>>(emptyMap())

    private fun read(): CloudCache = runCatching { Api.json.decodeFromString<CloudCache>(cacheFile.readText()) }
        .getOrDefault(CloudCache())

    private fun downloadedIds(): Set<Long> = downloads.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toLongOrNull() }.toSet()

    /** Asks the server what changed since the last time (all of it after switching servers). */
    fun refresh() {
        var cache = state.value.takeIf { it.server == Account.server } ?: CloudCache(server = Account.server)
        val byId = cache.tracks.associateBy { it.id }.toMutableMap()
        var rounds = 0
        while (rounds++ < 100) {
            val answer = Api.get("/api/tracks?since=${cache.rev}")
            val changed = answer["tracks"]?.jsonArray.orEmpty().map { Api.json.decodeFromJsonElement(CloudTrack.serializer(), it) }
            for (track in changed) if (track.deleted) byId.remove(track.id) else byId[track.id] = track
            cache = cache.copy(rev = answer["rev"]?.jsonPrimitive?.content?.toLongOrNull() ?: cache.rev)
            if (answer["more"]?.jsonPrimitive?.content != "true") break
        }
        cache = cache.copy(tracks = byId.values.sortedBy { it.id })
        state.value = cache
        tracksState.value = cache.tracks
        runCatching {
            val temp = File(folder, "tracks.json.tmp")
            temp.writeText(Api.json.encodeToString(CloudCache.serializer(), cache))
            temp.renameTo(cacheFile)
        }
    }

    fun clear() {
        state.value = CloudCache()
        tracksState.value = emptyList()
        cacheFile.delete()
    }

    fun audioUrl(id: Long) = "${Account.server}/api/tracks/$id/audio"

    fun downloadedFile(id: Long): File? = downloads.listFiles()?.firstOrNull { it.nameWithoutExtension == id.toString() }

    /** A cloud track as a song of the library: negative id (never a phone song's), played from the file if downloaded. */
    fun song(track: CloudTrack, downloaded: Boolean): Song = Song(
        id = -track.id, title = track.title.ifBlank { "Sem título" }, artist = track.artist, album = track.album,
        albumId = -track.id, albumArtist = track.albumArtist, durationMs = track.durationMs, track = track.track,
        disc = track.disc, year = track.year, dateAdded = track.added / 1000, relativePath = "Nuvem",
        displayName = track.title, genre = track.genre, cloudId = track.id,
        cloudFile = if (downloaded) downloadedFile(track.id)?.absolutePath else null, cloudCover = track.cover,
        syncKey = track.songKey,
    )

    fun download(track: CloudTrack): Boolean {
        val ext = when (track.mime) { "audio/flac" -> "flac"; "audio/mp4" -> "m4a"; "audio/ogg" -> "ogg"; "audio/wav" -> "wav"; else -> "mp3" }
        val target = File(downloads, "${track.id}.$ext")
        transfers.update { it + (track.id to 0f) }
        try {
            val ok = Api.download("/api/tracks/${track.id}/audio", target) { got, total ->
                if (total > 0) transfers.update { it + (track.id to got.toFloat() / total) }
            }
            if (ok) downloadedState.value = downloadedIds()
            AppLog.i("Nuvem", "baixou ${track.id} (${track.title}): $ok")
            return ok
        } finally {
            transfers.update { it - track.id }
        }
    }

    fun removeDownload(id: Long) {
        downloadedFile(id)?.delete()
        downloadedState.value = downloadedIds()
    }

    /** Sends a song of this phone to the cloud (skipped when the same file is already there). */
    fun upload(song: Song): CloudTrack? {
        val resolver = context.contentResolver
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(song.uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        } ?: return null
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val existing = runCatching { Api.get("/api/tracks/sha/$sha") }.getOrNull()
        if (existing == null) {
            transfers.update { it + (song.id to 0f) }
            try {
                Api.upload("/api/tracks/upload", resolver.getType(song.uri) ?: "audio/mpeg", size,
                    mapOf("X-Filename" to Uri.encode(song.displayName.ifBlank { "${song.title}.mp3" })),
                    progress = { sent -> transfers.update { it + (song.id to sent.toFloat() / size) } }) {
                    resolver.openInputStream(song.uri) ?: throw java.io.IOException("Não deu para ler ${song.title}.")
                }
            } finally {
                transfers.update { it - song.id }
            }
            AppLog.i("Nuvem", "enviou ${song.id} (${song.title}), ${size / 1024} KB")
        }
        return null
    }

    fun delete(id: Long) {
        Api.delete("/api/tracks/$id")
        removeDownload(id)
    }
}
