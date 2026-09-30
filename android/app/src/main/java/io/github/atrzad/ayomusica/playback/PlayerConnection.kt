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

data class QueueEntry(val index: Int, val item: MediaItem)

data class PlayerUi(
    val connected: Boolean = false,
    val current: MediaItem? = null,
    val index: Int = -1,
    val queue: List<QueueEntry> = emptyList(),  // in play order (respects shuffle)
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
            override fun onEvents(player: Player, events: Player.Events) = refresh()
        })
        refresh()
    }

    fun release() {
        controller?.release()
        controller = null
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0

    private fun refresh() {
        val player = controller ?: return
        val timeline = player.currentTimeline
        val order = mutableListOf<QueueEntry>()
        if (!timeline.isEmpty) {
            var index = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
            while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
                order += QueueEntry(index, player.getMediaItemAt(index))
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
        state.value = PlayerUi(
            connected = true,
            current = player.currentMediaItem,
            index = player.currentMediaItemIndex,
            queue = order,
            isPlaying = player.isPlaying,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            speed = player.playbackParameters.speed,
        )
    }

    fun play(items: List<MediaItem>, start: Int = 0, shuffle: Boolean = false) {
        val player = controller ?: return
        if (items.isEmpty()) return
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(items, if (shuffle) items.indices.random() else start, 0)
        player.prepare()
        player.play()
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

    fun seekTo(ms: Long) = controller?.seekTo(ms.coerceAtLeast(0))

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
