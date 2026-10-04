package io.github.atrzad.ayomusica.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.atrzad.ayomusica.data.Stats
import io.github.atrzad.ayomusica.ui.MainActivity

/** Plays in the background, with notification, lock-screen, headset and Bluetooth controls. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var store: QueueStore
    private lateinit var stats: Stats
    private val listening = Listening()

    override fun onCreate() {
        super.onCreate()
        io.github.atrzad.ayomusica.util.AppLog.init(this)
        store = QueueStore(this)
        stats = Stats.get(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,  // pause for calls and other apps (audio focus)
            )
            .setHandleAudioBecomingNoisy(true)  // headphones unplugged → pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        restore(player)
        PlayerHub.attach(player)
        AudioEffects.attach(this, player.audioSessionId)
        listening.start(player.currentMediaItem, player)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED)) saveSoon(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = listening.playing(isPlaying)

            private var lastIndex = player.currentMediaItemIndex
            private var skipping = 0

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listening.finish(reason)
                listening.start(mediaItem, player)
                if (skipBlocked(player, mediaItem, reason)) return
                lastIndex = player.currentMediaItemIndex
            }

            /**
             * Shuffle blacklist: a song on it that comes up in shuffle (on its own or with next/previous) is passed over,
             * in the direction the person was going. Picking it from a list still plays it (shuffle is off then).
             */
            private fun skipBlocked(player: Player, item: MediaItem?, reason: Int): Boolean {
                val id = item?.mediaId?.toLongOrNull()
                val passing = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                if (!player.shuffleModeEnabled || !passing || id == null || !stats.isNoShuffle(id) ||
                    skipping >= player.mediaItemCount) {
                    skipping = 0
                    return false
                }
                skipping++
                val timeline = player.currentTimeline
                val backward = !timeline.isEmpty && lastIndex in 0 until timeline.windowCount &&
                    player.currentMediaItemIndex == timeline.getPreviousWindowIndex(lastIndex, Player.REPEAT_MODE_OFF, true)
                when {
                    backward && player.hasPreviousMediaItem() -> player.seekToPreviousMediaItem()
                    player.hasNextMediaItem() -> player.seekToNextMediaItem()
                    else -> { skipping = 0; return false }
                }
                return true
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                io.github.atrzad.ayomusica.util.AppLog.e("Player", "erro ao tocar ${player.currentMediaItem?.mediaId}: " +
                    error.errorCodeName, error)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) SleepTimer.songEnded()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                PlayerHub.sessionChanged(audioSessionId)
                AudioEffects.attach(this@PlaybackService, audioSessionId)
            }
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(this)))
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private companion object {
        const val MAX_SAVED = 3000
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            player?.let(::save)
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            listening.finish(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            save(player)
            SleepTimer.cancel()
            AudioEffects.release()
            PlayerHub.detach()
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Counts a play after half the song (or 4 min) of real listening; skipping earlier counts a skip. */
    private inner class Listening {
        private var id: Long? = null
        private var durationMs = 0L
        private var listenedMs = 0L
        private var since = 0L

        fun start(item: MediaItem?, player: Player) {
            id = item?.mediaId?.toLongOrNull()
            durationMs = item?.mediaMetadata?.durationMs ?: 0
            listenedMs = 0
            since = if (player.isPlaying) SystemClock.elapsedRealtime() else 0
        }

        fun playing(isPlaying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (since > 0) listenedMs += now - since
            since = if (isPlaying) now else 0
        }

        fun finish(reason: Int) {
            playing(false)
            val song = id ?: return
            when {
                durationMs > 0 && Stats.counts(listenedMs, durationMs) -> stats.played(song)
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && listenedMs > 1000 -> stats.skipped(song)
            }
            id = null
        }
    }

    private fun restore(player: Player) {
        val saved = store.read() ?: return
        player.setMediaItems(saved.items.map { it.toMediaItem() }, saved.index.coerceIn(0, saved.items.size - 1),
            saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat
        player.prepare()  // ready, but paused
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSave: Runnable? = null

    /** Several events come together (new queue + new song + playing): save once, a moment later. */
    private fun saveSoon(player: Player) {
        pendingSave?.let(handler::removeCallbacks)
        pendingSave = Runnable { save(player) }.also { handler.postDelayed(it, 800) }
    }

    /**
     * The queue is saved in the background, at most [MAX_SAVED] songs around the current one (playing a whole
     * 12 000-song library must not write megabytes on every song change).
     */
    private fun save(player: Player) {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        val first = (current - MAX_SAVED / 6).coerceIn(0, maxOf(0, count - MAX_SAVED))
        val last = minOf(count, first + MAX_SAVED)
        val items = (first until last).mapNotNull { SavedItem.of(player.getMediaItemAt(it)) }
        val queue = SavedQueue(items, (current - first).coerceAtLeast(0), player.currentPosition.coerceAtLeast(0),
            player.shuffleModeEnabled, player.repeatMode)
        writer.execute { runCatching { store.write(queue) } }
    }

    private inner class Callback : MediaSession.Callback {
        /** Items coming from the app's own screens carry their file in requestMetadata.mediaUri. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
            }.toMutableList(),
        )

        /** "Resume" in the system media controls after a restart plays the saved queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = store.read() ?: return Futures.immediateFailedFuture(IllegalStateException("Fila vazia"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                saved.items.map { it.toMediaItem() }, saved.index, saved.positionMs))
        }
    }
}
