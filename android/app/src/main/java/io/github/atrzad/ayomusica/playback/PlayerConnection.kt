package io.github.atrzad.ayomusica.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class QueueEntry(val index: Int, val item: MediaItem)

data class PlayerUi(
    val connected: Boolean = false,
    val current: MediaItem? = null,
    val index: Int = -1,
    val queueSize: Int = 0,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

/** The screens' handle on the playback service. */
class PlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private val state = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = state

    suspend fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val connected = MediaController.Builder(context, token).buildAsync().await()
        controller = connected
        connected.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // Rebuilding the play order walks the whole queue: only when the queue or shuffle changed.
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) order = null
                refresh()
            }
        })
        refresh()
    }

    fun release() {
        filling?.cancel()
        controller?.release()
        controller = null
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0

    private var order: List<QueueEntry>? = null

    /** The queue in play order (respects shuffle); built only when the queue sheet asks for it. */
    fun queue(): List<QueueEntry> = controller?.let(::playOrder).orEmpty()

    private fun playOrder(player: Player): List<QueueEntry> = order ?: buildList {
        val timeline = player.currentTimeline
        if (!timeline.isEmpty) {
            var index = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
            while (index != C.INDEX_UNSET && size < timeline.windowCount) {
                add(QueueEntry(index, player.getMediaItemAt(index)))
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
    }.also { order = it }

    private fun refresh() {
        val player = controller ?: return
        state.value = PlayerUi(
            connected = true,
            current = player.currentMediaItem,
            index = player.currentMediaItemIndex,
            queueSize = player.mediaItemCount,
            isPlaying = player.isPlaying,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            speed = player.playbackParameters.speed,
        )
    }

    private var filling: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /**
     * Big lists (a whole library) start right away with the chosen song and the next few hundred; the rest
     * joins the queue in the background in small batches, so the screen never freezes.
     */
    fun play(items: List<MediaItem>, start: Int = 0, shuffle: Boolean = false) {
        val player = controller ?: return
        if (items.isEmpty()) return
        filling?.cancel()
        val first = if (shuffle) items.indices.random() else start.coerceIn(0, items.lastIndex)
        player.shuffleModeEnabled = shuffle
        if (items.size <= WINDOW) {
            player.setMediaItems(items, first, 0)
            player.prepare()
            player.play()
            return
        }
        val end = minOf(items.size, first + WINDOW)
        player.setMediaItems(items.subList(first, end), 0, 0)
        player.prepare()
        player.play()
        filling = scope.launch {
            var from = end
            while (from < items.size) {           // what comes after
                kotlinx.coroutines.delay(60)
                val to = minOf(items.size, from + BATCH)
                controller?.addMediaItems(items.subList(from, to)) ?: return@launch
                from = to
            }
            var to = first
            while (to > 0) {                      // and what came before, in front
                kotlinx.coroutines.delay(60)
                val from2 = maxOf(0, to - BATCH)
                controller?.addMediaItems(0, items.subList(from2, to)) ?: return@launch
                to = from2
            }
        }
    }

    private companion object {
        const val WINDOW = 300
        const val BATCH = 500
    }

    fun toggle() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()

    /** Back to the start of the song after 3 s, like other players; otherwise the previous song. */
    fun previous() {
        val player = controller ?: return
        if (player.currentPosition > 3000) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    private var lastBack = 0L

    /** The "back" gesture: once restarts the song, a second time right after goes to the previous one. */
    fun back() {
        val player = controller ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBack < 3000) {
            player.seekToPreviousMediaItem()
            lastBack = 0
        } else {
            player.seekTo(0)
            lastBack = now
        }
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms.coerceAtLeast(0))

    fun pause() = controller?.pause()

    fun setShuffle(on: Boolean) { controller?.shuffleModeEnabled = on }

    /** 0.5× to 2×; the pitch stays the same. */
    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 2f)) }

    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playNext(items: List<MediaItem>) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return play(items)
        player.addMediaItems(player.currentMediaItemIndex + 1, items)
    }

    fun enqueue(items: List<MediaItem>) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return play(items)
        player.addMediaItems(items)
    }

    fun jumpTo(index: Int) {
        val player = controller ?: return
        player.seekTo(index, 0)
        player.play()
    }

    fun removeAt(index: Int) = controller?.removeMediaItem(index)

    fun move(from: Int, to: Int) = controller?.moveMediaItem(from, to)

    fun clearQueue() = controller?.clearMediaItems()
}
