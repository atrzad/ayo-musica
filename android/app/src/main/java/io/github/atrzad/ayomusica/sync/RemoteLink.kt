package io.github.atrzad.ayomusica.sync

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.github.atrzad.ayomusica.data.MediaLibrary
import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.playback.syncKey
import io.github.atrzad.ayomusica.playback.toMediaItem
import io.github.atrzad.ayomusica.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * This device on the account, like a Spotify Connect speaker: tells the server what it plays (so another device can
 * continue it or show it) and does what the remote control asks. Runs inside the playback service.
 */
class RemoteLink(private val context: Context, private val player: Player) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var publishJob: Job? = null
    @Volatile private var library: Pair<Long, Matcher>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED)) publishSoon()
        }
    }

    fun start() {
        player.addListener(listener)
        // Read the phone's songs ahead, so "tocar aqui" from another device starts right away.
        scope.launch(Dispatchers.IO) { if (Account.current.value.signedIn) matcher() }
        scope.launch { listen() }
        scope.launch {
            while (isActive) {  // while playing, the position stays fresh for "continuar"
                delay(20_000)
                if (player.isPlaying) publishSoon(0)
            }
        }
        publishSoon()
    }

    fun stop() {
        player.removeListener(listener)
        scope.cancel()
    }

    private fun publishSoon(waitMs: Long = 1500) {
        if (!Account.current.value.signedIn) return
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(waitMs)
            val state = snapshot()
            withContext(Dispatchers.IO) {
                runCatching {
                    Api.post("/api/player/state", buildJsonObject {
                        put("device", Account.deviceId)
                        put("name", Account.deviceName)
                        put("platform", "android")
                        put("state", state)
                    })
                }
            }
        }
    }

    /** What is playing now, with the queue around it (the items say their song key and cloud id). */
    private fun snapshot(): JsonElement {
        val current = player.currentMediaItem ?: return JsonPrimitive(null as String?)
        val index = player.currentMediaItemIndex
        val from = maxOf(0, index - 50)
        val to = minOf(player.mediaItemCount, index + 250)
        return buildJsonObject {
            put("item", item(current))
            put("positionMs", player.currentPosition)
            put("durationMs", player.duration.takeIf { it > 0 } ?: 0)
            put("playing", player.isPlaying)
            put("shuffle", player.shuffleModeEnabled)
            put("repeat", player.repeatMode)
            put("index", index - from)
            put("at", System.currentTimeMillis())
            put("queue", buildJsonArray { for (i in from until to) add(item(player.getMediaItemAt(i))) })
        }
    }

    private fun item(media: MediaItem): JsonObject {
        val meta = media.mediaMetadata
        val id = media.mediaId.toLongOrNull() ?: 0
        return buildJsonObject {
            put("key", media.syncKey())
            put("cloudId", if (id < 0) -id else 0)
            put("title", meta.title?.toString().orEmpty())
            put("artist", meta.artist?.toString().orEmpty())
            put("album", meta.albumTitle?.toString().orEmpty())
            put("durationMs", meta.durationMs ?: 0)
        }
    }

    /** Waits for the remote control's commands (long poll; being here also says "online"). */
    private suspend fun listen() {
        var failures = 0
        while (scope.isActive) {
            if (!Account.current.value.signedIn) { delay(10_000); continue }
            val answer = withContext(Dispatchers.IO) {
                runCatching {
                    Api.get("/api/player/commands?device=${Account.deviceId}&platform=android&wait=25&name=" +
                        android.net.Uri.encode(Account.deviceName), timeoutMs = 40_000)
                }
            }
            val commands = answer.getOrNull()?.get("commands")?.jsonArray
            if (commands == null) {
                failures++
                delay(minOf(60_000L, 2_000L * failures))  // offline: try again, a little slower each time
                continue
            }
            failures = 0
            for (command in commands) runCatching { run(command.jsonObject) }
                .onFailure { AppLog.w("Remoto", "comando falhou", it) }
        }
    }

    private suspend fun run(command: JsonObject) {
        val args = command["args"] as? JsonObject
        fun arg(name: String) = (args?.get(name) as? JsonPrimitive)?.content
        when ((command["action"] as? JsonPrimitive)?.content) {
            "play" -> player.play()
            "pause" -> player.pause()
            "toggle" -> if (player.isPlaying) player.pause() else player.play()
            "next" -> player.seekToNextMediaItem()
            "previous" -> player.seekToPreviousMediaItem()
            "seek" -> arg("positionMs")?.toLongOrNull()?.let(player::seekTo)
            "shuffle" -> player.shuffleModeEnabled = arg("on") == "true"
            "repeat" -> arg("mode")?.toIntOrNull()?.let { player.repeatMode = it }
            "volume" -> arg("volume")?.toFloatOrNull()?.let { player.volume = it.coerceIn(0f, 1f) }
            "playQueue" -> playQueue(args ?: return)
            "refresh" -> return  // someone else's state changed (for screens that show it)
        }
        AppLog.i("Remoto", "comando ${(command["action"] as? JsonPrimitive)?.content} de ${(command["from"] as? JsonPrimitive)?.content}")
        publishSoon(500)
    }

    /** Another device sent its queue here ("tocar neste aparelho"): this phone's copies first, else the cloud's. */
    private suspend fun playQueue(args: JsonObject) {
        val items = args["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (items.isEmpty()) return
        val matcher = withContext(Dispatchers.IO) { matcher() }
        val media = items.map { item ->
            val key = (item["key"] as? JsonPrimitive)?.content.orEmpty()
            matcher.find(key)?.toMediaItem() ?: cloudItem(item)
        }
        val index = (args["index"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, media.lastIndex) ?: 0
        val position = (args["positionMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
        player.setMediaItems(media, index, position)
        player.prepare()
        if ((args["play"] as? JsonPrimitive)?.content != "false") player.play()
    }

    private fun cloudItem(item: JsonObject): MediaItem {
        val cloudId = (item["cloudId"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
        fun text(name: String) = (item[name] as? JsonPrimitive)?.content.orEmpty()
        val uri = android.net.Uri.parse("${Account.server}/api/tracks/$cloudId/audio")
        return MediaItem.Builder().setMediaId((-cloudId).toString()).setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(text("title")).setArtist(text("artist")).setAlbumTitle(text("album"))
                .setDurationMs(text("durationMs").toLongOrNull()).setArtworkUri(android.net.Uri.parse("${Account.server}/api/tracks/$cloudId/cover"))
                .setIsPlayable(true).setIsBrowsable(false).build())
            .build()
    }

    /** The phone's songs by key (their own tags, as the other devices know them), read again every 30 minutes. */
    @Synchronized private fun matcher(): Matcher {
        library?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return it.second }
        val songs = runCatching { kotlinx.coroutines.runBlocking { MediaLibrary(context).load() } }.getOrDefault(emptyList())
        val matcher = Matcher(songs.map { it to SongKeys.of(it) })
        library = System.currentTimeMillis() to matcher
        return matcher
    }
}
