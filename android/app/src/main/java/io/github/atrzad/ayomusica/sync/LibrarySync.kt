package io.github.atrzad.ayomusica.sync

import io.github.atrzad.ayomusica.analyzer.AnalysisStore
import io.github.atrzad.ayomusica.data.Overrides
import io.github.atrzad.ayomusica.data.Playlists
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.SongOverride
import io.github.atrzad.ayomusica.data.Stats
import io.github.atrzad.ayomusica.lyrics.LyricsEntry
import io.github.atrzad.ayomusica.lyrics.LyricsRepository
import io.github.atrzad.ayomusica.ui.Prefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * The app's side of sync: turns playlists, likes, plays, lyrics, corrections, ignored songs and settings into records
 * and back. [localSongs] are this phone's songs by their *file* tags (corrections apart), [shownSongs] as shown
 * (lyrics files are named after what is shown), [cloudSongs] the cloud's.
 */
class LibrarySync(
    private val filesDir: File,
    private val playlists: Playlists,
    private val stats: Stats,
    private val lyrics: LyricsRepository,
    private val overrides: Overrides,
    private val analysis: AnalysisStore,
    private val prefs: Prefs,
    private val localSongs: () -> List<Song>,
    private val shownSongs: () -> Map<Long, Song>,
    private val cloudSongs: () -> List<Song>,
) {
    private val blobs = File(filesDir, "sync-blobs").apply { mkdirs() }
    private val shaCache = HashMap<String, Pair<Long, String>>()  // file path → (modified, sha)

    val hooks = SyncHooks(
        songs = { localSongs().map { it to SongKeys.of(it) } + cloudSongs().map { it to SongKeys.of(it) } },
        export = ::export,
        apply = ::apply,
        uploadBlob = ::uploadBlob,
    )

    private fun export(keys: SyncKeys): Map<String, JsonElement> = buildMap {
        runBlocking { playlists.ensureUuids() }
        for (playlist in playlists.all.value) {
            val local = playlist.songIds.mapNotNull(keys::full)
            // Unchanged here since it came from another device: send its list as it came (keys of the other files).
            val remote = playlist.remoteKeys
            val songs = if (remote.isNotEmpty() && remote.mapNotNull { keys.matcher.find(it)?.id } == playlist.songIds) remote
                else local + remote.filter { keys.matcher.find(it) == null }
            put(SyncEngine.id("playlist", playlist.uuid), buildJsonObject {
                put("name", playlist.name)
                put("description", playlist.description)
                put("songs", JsonArray(songs.map(::JsonPrimitive)))
                put("cover", playlist.coverFile?.let(::sha) ?: "")
            })
        }
        for ((id, stat) in stats.all.value) {
            val key = keys.name(id) ?: continue
            if (stat.favorite || stat.noShuffle) put(SyncEngine.id("flags", key), buildJsonObject {
                put("favorite", stat.favorite)
                put("noShuffle", stat.noShuffle)
            })
            if (stat.plays > 0 || stat.skips > 0) put(SyncEngine.id("plays", "$key@${Account.deviceId}"), buildJsonObject {
                put("plays", stat.plays)
                put("skips", stat.skips)
                put("lastPlayed", stat.lastPlayed)
            })
        }
        val shown = shownSongs()
        for (song in localSongs()) {
            val key = keys.name(song.id) ?: continue
            val entry = shown[song.id]?.let(lyrics::chosen) ?: continue
            put(SyncEngine.id("lyrics", key), buildJsonObject {
                put("synced", entry.synced)
                put("plain", entry.plain)
                put("source", entry.source)
                put("offsetMs", entry.offsetMs)
            })
        }
        for ((id, fix) in overrides.all.value) {
            val key = keys.name(id) ?: continue
            put(SyncEngine.id("fix", key), buildJsonObject {
                put("title", fix.title); put("artist", fix.artist); put("album", fix.album)
                put("albumArtist", fix.albumArtist); put("year", fix.year); put("genre", fix.genre)
                put("source", fix.source); put("byHand", fix.byHand)
                put("cover", fix.coverFile?.let(::sha) ?: "")
            })
        }
        for (id in analysis.data.value.ignored) keys.name(id)?.let { put(SyncEngine.id("ignored", it), JsonPrimitive(true)) }
        for ((name, value) in prefs.synced()) put(SyncEngine.id("pref", name), JsonPrimitive(value))
    }

    private fun apply(kind: String, key: String, value: JsonElement?, matcher: Matcher): Boolean {
        val obj = value as? JsonObject
        when (kind) {
            "playlist" -> runBlocking {
                if (obj == null) return@runBlocking playlists.deleteSynced(key)
                val keys = obj["songs"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                val ids = keys.mapNotNull { matcher.find(it)?.id }
                val cover = obj.text("cover").takeIf { it.length == 64 }?.let { fetchBlob(it, "playlist-covers", key) }
                playlists.putSynced(key, obj.text("name"), obj.text("description"), ids, keys, cover)
            }
            "pref" -> (value as? JsonPrimitive)?.content?.let { prefs.applySynced(key, it) }
            else -> {
                val song = matcher.find(key) ?: return false
                when (kind) {
                    "flags" -> {
                        stats.setFavorite(song.id, obj?.bool("favorite") == true)
                        stats.setNoShuffle(listOf(song.id), obj?.bool("noShuffle") == true)
                    }
                    "lyrics" -> {
                        val shown = shownSongs()[song.id] ?: song
                        if (obj == null) lyrics.dropChosen(shown)
                        else lyrics.putChosen(shown, LyricsEntry(obj.text("synced"), obj.text("plain"), obj.text("source"),
                            offsetMs = obj.text("offsetMs").toLongOrNull() ?: 0))
                    }
                    "fix" -> if (obj == null) overrides.remove(song.id) else {
                        val cover = obj.text("cover").takeIf { it.length == 64 }?.let { fetchBlob(it, "covers", "${song.id}") }
                        overrides.put(song.id, SongOverride(obj.text("title"), obj.text("artist"), obj.text("album"),
                            obj.text("albumArtist"), obj.text("year").toIntOrNull() ?: 0, obj.text("genre"), cover,
                            obj.text("source").ifBlank { "sync" }, obj.bool("byHand")))
                    }
                    "ignored" -> if (value == null || value == JsonNull) analysis.unignore(song.id) else analysis.ignore(song.id)
                }
            }
        }
        return true
    }

    /** SHA-256 of a picture file (cached while the file is unchanged). */
    private fun sha(path: String): String? {
        val file = File(path).takeIf { it.exists() } ?: return null
        shaCache[path]?.takeIf { it.first == file.lastModified() }?.let { return it.second }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        File(blobs, sha).takeIf { !it.exists() }?.let { file.copyTo(it) }  // kept by its hash, ready to upload
        shaCache[path] = file.lastModified() to sha
        return sha
    }

    private fun uploadBlob(sha: String) {
        val file = File(blobs, sha).takeIf { it.exists() } ?: return
        Api.upload("/api/blobs/$sha", "image/jpeg", file.length(), method = "PUT") { file.inputStream() }
    }

    /** A picture from another device, saved where its kind of pictures live. */
    private fun fetchBlob(sha: String, folder: String, owner: String): String? {
        val target = File(filesDir, "$folder/sync-$owner-${sha.take(12)}.jpg")
        if (target.exists()) return target.absolutePath
        val ok = runCatching { Api.download("/api/blobs/$sha", target) }.getOrDefault(false)
        return if (ok) target.absolutePath.also { File(blobs, sha).takeIf { b -> !b.exists() }?.let { b -> target.copyTo(b) } } else null
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.content == "true"
}
