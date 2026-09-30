package io.github.atrzad.ayomusica.playback

import android.app.PendingIntent
import android.content.Intent
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
import io.github.atrzad.ayomusica.ui.MainActivity

/** Plays in the background, with notification, lock-screen, headset and Bluetooth controls. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var store: QueueStore

    override fun onCreate() {
        super.onCreate()
        store = QueueStore(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,  // pause for calls and other apps (audio focus)
            )
            .setHandleAudioBecomingNoisy(true)  // headphones unplugged → pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        restore(player)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED)) save(player)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            player?.let(::save)
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            save(player)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun restore(player: Player) {
        val saved = store.read() ?: return
        player.setMediaItems(saved.items.map { it.toMediaItem() }, saved.index.coerceIn(0, saved.items.size - 1),
            saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat
        player.prepare()  // ready, but paused
    }

    private fun save(player: Player) {
        val items = (0 until player.mediaItemCount).mapNotNull { SavedItem.of(player.getMediaItemAt(it)) }
        runCatching {
            store.write(SavedQueue(items, player.currentMediaItemIndex.coerceAtLeast(0),
                player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode))
        }
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
