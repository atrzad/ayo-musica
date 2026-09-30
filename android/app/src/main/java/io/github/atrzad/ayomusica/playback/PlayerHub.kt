package io.github.atrzad.ayomusica.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The service's player, for the parts of the app that need more than the MediaController offers
 * (audio effects, the visualizer, the sleep timer). Same process; use from the main thread.
 */
object PlayerHub {
    var player: ExoPlayer? = null
        internal set

    private val session = MutableStateFlow(0)
    /** The player's audio session (0 while there is none). */
    val audioSessionId: StateFlow<Int> = session

    internal fun attach(exoPlayer: ExoPlayer) {
        player = exoPlayer
        session.value = exoPlayer.audioSessionId
    }

    internal fun sessionChanged(id: Int) {
        session.value = id
    }

    internal fun detach() {
        player = null
        session.value = 0
    }
}
