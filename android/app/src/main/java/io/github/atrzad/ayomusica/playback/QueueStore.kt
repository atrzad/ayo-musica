package io.github.atrzad.ayomusica.playback

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedQueue(val items: List<SavedItem> = emptyList(), val index: Int = 0, val positionMs: Long = 0,
                      val shuffle: Boolean = false, val repeat: Int = 0)

/** The queue, current song and position, so the app opens where it stopped (paused). */
class QueueStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "queue.json"))

    private val json = Json { ignoreUnknownKeys = true }

    fun read(): SavedQueue? = runCatching { json.decodeFromString<SavedQueue>(file.readText()) }.getOrNull()
        ?.takeIf { it.items.isNotEmpty() }

    fun write(queue: SavedQueue) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(queue))
        temp.renameTo(file)
    }
}
