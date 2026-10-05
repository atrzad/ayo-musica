package io.github.atrzad.ayomusica.sync

import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.data.fold
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * The song identity shared with the desktop and the server: artist and title folded (no accents, lower case, single
 * spaces) and whole seconds — "seu pereira e coletivo 401|obsoleto|223". Same song: artist and title equal, seconds
 * 3 apart at most.
 */
object SongKeys {
    private val SPACES = Regex("\\s+")
    fun norm(text: String) = fold(text).replace(SPACES, " ").trim()
    fun of(artist: String, title: String, durationMs: Long) = "${norm(artist)}|${norm(title)}|${durationMs / 1000}"
    fun of(song: Song) = of(song.artist, song.title, song.durationMs)

    /**
     * "artist|title" without the seconds: the key of what is said *about* a song (like, lyrics, correction, plays), the
     * same on every device even when the files differ by a second.
     */
    fun name(key: String): String = split(key)?.first ?: key

    /** "artist|title" and the seconds (null for a key without seconds). */
    fun split(key: String): Pair<String, Long>? {
        val bar = key.lastIndexOf('|')
        if (bar <= 0 || key.indexOf('|') == bar) return null
        return key.substring(0, bar) to (key.substring(bar + 1).toLongOrNull() ?: return null)
    }
}

/** Finds this device's song for a key: a song of the phone first, else the cloud's. */
class Matcher(entries: List<Pair<Song, String>>) {
    private val byName = HashMap<String, MutableList<Triple<Song, Long, Boolean>>>()

    init {
        for ((song, key) in entries) {
            val (name, seconds) = SongKeys.split(key) ?: continue
            byName.getOrPut(name) { mutableListOf() } += Triple(song, seconds, song.inCloud)
        }
    }

    /** For "artist|title|seconds" the closest duration (3 s at most); for "artist|title", the phone's copy first. */
    fun find(key: String): Song? {
        val split = SongKeys.split(key)
        if (split == null) return byName[key]?.minWithOrNull(compareBy { it.third })?.first
        val (name, seconds) = split
        return byName[name]?.filter { kotlin.math.abs(it.second - seconds) <= 3 }
            ?.minWithOrNull(compareBy({ it.third }, { kotlin.math.abs(it.second - seconds) }))?.first
    }
}

/** What the engine reads from and writes to the app (each part keeps its own storage). */
class SyncHooks(
    /** Every song this device knows with its key: the phone's (by their original tags) and the cloud's. */
    val songs: () -> List<Pair<Song, String>>,
    /** Records of this device, by "kind\u0000key", as they are now (plays: only this device's). */
    val export: (keys: SyncKeys) -> Map<String, JsonElement>,
    /** Applies a record from another device; false when it is about a song this device does not have. */
    val apply: (kind: String, key: String, value: JsonElement?, matcher: Matcher) -> Boolean,
    /** Sends a picture the records point to (once per SHA-256). */
    val uploadBlob: (sha: String) -> Unit = {},
)

/** Keys of this device's songs (by song id) for the records, and the matcher for keys coming from elsewhere. */
class SyncKeys(private val full: Map<Long, String>, val matcher: Matcher) {
    /** "artist|title|seconds": songs listed in playlists. */
    fun full(id: Long): String? = full[id]
    /** "artist|title": likes, lyrics, corrections, plays. */
    fun name(id: Long): String? = full[id]?.let(SongKeys::name)
}

@Serializable
private data class SyncState(
    val server: String = "",
    val email: String = "",
    val since: Long = 0,
    /** Record id → hash of the value last agreed with the server. */
    val snapshot: Map<String, String> = emptyMap(),
    /** Records from other devices about songs this device does not have yet (applied when they show up). */
    val held: Map<String, String> = emptyMap(),
    /** Play counts of the other devices: songKey@device → {plays, skips, lastPlayed}. */
    val remotePlays: Map<String, String> = emptyMap(),
    val uploadedBlobs: Set<String> = emptySet(),
)

data class SyncResult(val sent: Int, val received: Int, val held: Int)

/** Data sync: what changed here goes up, what changed elsewhere comes down; the newest change wins (on the server). */
class SyncEngine(
    private val stateFile: File,
    private val hooks: SyncHooks,
    private val device: () -> String,
    private val transport: (since: Long, changes: List<JsonObject>) -> JsonObject,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Api.json
    private var state = read()

    private fun read(): SyncState = runCatching { json.decodeFromString<SyncState>(stateFile.readText()) }.getOrDefault(SyncState())

    private fun save() {
        stateFile.parentFile?.mkdirs()
        val temp = File(stateFile.parentFile, "${stateFile.name}.tmp")
        temp.writeText(json.encodeToString(SyncState.serializer(), state))
        temp.renameTo(stateFile)
    }

    /** Starting over (another account or server): everything here will be sent and merged again. */
    fun reset(server: String, email: String) {
        state = SyncState(server = server, email = email)
        save()
    }

    fun belongsTo(server: String, email: String) = state.server == server && state.email == email

    /** Other devices' play counts per song key, summed: plays, skips, last time played. */
    fun remotePlays(): Map<String, Triple<Int, Int, Long>> {
        val sums = HashMap<String, Triple<Int, Int, Long>>()
        for ((key, text) in state.remotePlays) {
            val songKey = key.substringBeforeLast('@')
            val value = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val old = sums[songKey] ?: Triple(0, 0, 0L)
            sums[songKey] = Triple(old.first + value.int("plays"), old.second + value.int("skips"),
                maxOf(old.third, value["lastPlayed"]?.jsonPrimitive?.longOrNull ?: 0))
        }
        return sums
    }

    @Synchronized
    fun sync(): SyncResult {
        val me = device()
        val songs = hooks.songs()
        val keys = songs.associate { (song, key) -> song.id to key }
        val matcher = Matcher(songs)
        // A device joining the account first takes what is already there (the account wins), then adds its own.
        if (state.since == 0L && state.snapshot.isEmpty()) {
            val (snapshot, held, plays, since) = exchange(emptyList(), matcher, me, state.snapshot.toMutableMap(),
                state.held.toMutableMap(), state.remotePlays.toMutableMap(), state.since)
            state = state.copy(since = since, snapshot = snapshot, held = held, remotePlays = plays)
        }
        // Records about songs that showed up since: apply them now.
        val stillHeld = state.held.filter { (id, text) ->
            val (kind, key) = id.split('\u0000', limit = 2)
            !hooks.apply(kind, key, json.parseToJsonElement(text).takeIf { it != JsonNull }, matcher)
        }
        val current = hooks.export(SyncKeys(keys, matcher)) + stillHeld.mapValues { json.parseToJsonElement(it.value) }
        val at = now()
        val changes = mutableListOf<JsonObject>()
        val sentHashes = HashMap<String, String?>()
        for ((id, value) in current) {
            val hash = hash(value)
            if (state.snapshot[id] == hash) continue
            changes += change(id, value, false, at)
            sentHashes[id] = hash
            blobsOf(value).forEach(::uploadOnce)
        }
        for (id in state.snapshot.keys) {
            if (id in current) continue
            if (id.startsWith("plays\u0000") && !id.endsWith("@$me")) continue  // other devices' counts are never ours to delete
            changes += change(id, JsonNull, true, at)
            sentHashes[id] = null
        }
        val snapshot = state.snapshot.toMutableMap()
        sentHashes.forEach { (id, hash) -> if (hash == null) snapshot.remove(id) else snapshot[id] = hash }
        val before = received
        val (newSnapshot, held, remotePlays, since) = exchange(changes, matcher, me, snapshot, stillHeld.toMutableMap(),
            state.remotePlays.toMutableMap(), state.since)
        state = state.copy(since = since, snapshot = newSnapshot, held = held, remotePlays = remotePlays)
        save()
        return SyncResult(changes.size, received - before, held.size)
    }

    private var received = 0

    private data class Exchanged(val snapshot: Map<String, String>, val held: Map<String, String>,
                                 val plays: Map<String, String>, val since: Long)

    /** Sends [changes] and applies what comes back (in pages). */
    private fun exchange(changes: List<JsonObject>, matcher: Matcher, me: String, snapshot: MutableMap<String, String>,
                         held: MutableMap<String, String>, remotePlays: MutableMap<String, String>, start: Long): Exchanged {
        var since = start
        var pending: List<JsonObject> = changes
        var rounds = 0
        while (rounds++ < 200) {
            val answer = transport(since, pending)
            pending = emptyList()
            for (element in answer["changes"]?.jsonArray.orEmpty()) {
                val item = element.jsonObject
                val kind = item.text("kind")
                val key = item.text("key")
                val id = "$kind\u0000$key"
                val deleted = item["deleted"]?.jsonPrimitive?.content == "true"
                val value = item["value"]?.takeIf { !deleted && it != JsonNull }
                if (item.text("device") == me) continue  // our own change coming back
                received++
                if (kind == "plays") {
                    if (key.endsWith("@$me")) continue
                    if (value == null) remotePlays.remove(key) else remotePlays[key] = value.toString()
                    continue
                }
                if (value == null) snapshot.remove(id) else snapshot[id] = hash(value)
                held.remove(id)
                if (!hooks.apply(kind, key, value, matcher) && value != null) held[id] = value.toString()
            }
            since = answer["rev"]?.jsonPrimitive?.longOrNull ?: since
            if (answer["more"]?.jsonPrimitive?.content != "true") break
        }
        return Exchanged(snapshot, held, remotePlays, since)
    }

    private fun uploadOnce(sha: String) {
        if (sha in state.uploadedBlobs) return
        hooks.uploadBlob(sha)
        state = state.copy(uploadedBlobs = state.uploadedBlobs + sha)
    }

    private fun change(id: String, value: JsonElement, deleted: Boolean, at: Long): JsonObject {
        val (kind, key) = id.split('\u0000', limit = 2)
        return buildJsonObject {
            put("kind", kind)
            put("key", key)
            put("value", value)
            put("deleted", deleted)
            put("at", at)
        }
    }

    private fun blobsOf(value: JsonElement): List<String> =
        ((value as? JsonObject)?.get("cover") as? JsonPrimitive)?.content?.takeIf { it.length == 64 }?.let(::listOf).orEmpty()

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    companion object {
        fun hash(value: JsonElement): String =
            MessageDigest.getInstance("SHA-1").digest(value.toString().toByteArray()).joinToString("") { "%02x".format(it) }

        fun id(kind: String, key: String) = "$kind\u0000$key"
    }
}
