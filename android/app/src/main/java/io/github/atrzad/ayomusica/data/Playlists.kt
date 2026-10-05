package io.github.atrzad.ayomusica.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long> = emptyList(),
    val description: String = "",
    /** A picture chosen for the playlist (a copy in the app's storage); null shows the first song's cover. */
    val coverFile: String? = null,
    /** The same playlist on every device of the account (sync). */
    val uuid: String = "",
    /** Synced songs this device does not have (kept, in order, so they are not lost when it syncs back). */
    val remoteKeys: List<String> = emptyList(),
)

/** Playlists kept in a small JSON file in the app's private storage. */
class Playlists(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "playlists.json"))

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val state = MutableStateFlow(read())
    val all: StateFlow<List<Playlist>> = state

    private fun read(): List<Playlist> = runCatching {
        if (file.exists()) json.decodeFromString<List<Playlist>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private suspend fun save(playlists: List<Playlist>) {
        state.value = playlists
        withContext(Dispatchers.IO) {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(playlists))
            temp.renameTo(file)
        }
    }

    suspend fun create(name: String, songIds: List<Long> = emptyList()): Playlist {
        val playlist = Playlist((state.value.maxOfOrNull { it.id } ?: 0) + 1, name.trim(), songIds.distinct(),
            uuid = java.util.UUID.randomUUID().toString())
        save(state.value + playlist)
        return playlist
    }

    /** Playlists made before sync get their uuid. */
    suspend fun ensureUuids() {
        if (state.value.none { it.uuid.isBlank() }) return
        save(state.value.map { if (it.uuid.isBlank()) it.copy(uuid = java.util.UUID.randomUUID().toString()) else it })
    }

    /** A playlist from another device: created or replaced here (keeping this device's local id). */
    suspend fun putSynced(uuid: String, name: String, description: String, songIds: List<Long>, remoteKeys: List<String>,
                          coverFile: String?) {
        val existing = state.value.firstOrNull { it.uuid == uuid }
        if (existing == null) {
            save(state.value + Playlist((state.value.maxOfOrNull { it.id } ?: 0) + 1, name, songIds, description, coverFile, uuid,
                remoteKeys))
        } else {
            update(existing.id) { it.copy(name = name, songIds = songIds, description = description, coverFile = coverFile,
                remoteKeys = remoteKeys) }
        }
    }

    suspend fun deleteSynced(uuid: String) {
        state.value.firstOrNull { it.uuid == uuid }?.let { delete(it.id) }
    }

    suspend fun rename(id: Long, name: String) = update(id) { it.copy(name = name.trim()) }

    suspend fun edit(id: Long, name: String, description: String, coverFile: String?) =
        update(id) { it.copy(name = name.trim(), description = description.trim(), coverFile = coverFile) }

    suspend fun delete(id: Long) {
        state.value.firstOrNull { it.id == id }?.coverFile?.let { File(it).delete() }
        save(state.value.filterNot { it.id == id })
    }

    /** Songs already in the playlist are skipped. */
    suspend fun add(id: Long, songIds: List<Long>) =
        update(id) { playlist -> playlist.copy(songIds = playlist.songIds + songIds.filterNot { it in playlist.songIds }) }

    suspend fun removeAt(id: Long, position: Int) =
        update(id) { it.copy(songIds = it.songIds.filterIndexed { index, _ -> index != position }) }

    suspend fun move(id: Long, from: Int, to: Int) = update(id) {
        val ids = it.songIds.toMutableList()
        if (from in ids.indices && to in ids.indices) ids.add(to, ids.removeAt(from))
        it.copy(songIds = ids)
    }

    private suspend fun update(id: Long, change: (Playlist) -> Playlist) =
        save(state.value.map { if (it.id == id) change(it) else it })
}
