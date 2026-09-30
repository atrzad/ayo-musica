package io.github.atrzad.ayomusica.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Stops the music after some minutes (lowering the volume first) or at the end of the song. */
object SleepTimer {
    sealed interface Mode {
        data object Off : Mode
        data class Minutes(val endsAt: Long) : Mode
        data object EndOfSong : Mode
    }

    private const val FADE_MS = 8_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val state = MutableStateFlow<Mode>(Mode.Off)
    val mode: StateFlow<Mode> = state

    fun start(minutes: Int) {
        cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        state.value = Mode.Minutes(endsAt)
        job = scope.launch {
            delay((endsAt - System.currentTimeMillis() - FADE_MS).coerceAtLeast(0))
            val player = PlayerHub.player
            if (player != null && player.isPlaying) {
                val volume = player.volume
                val steps = 40
                for (step in 1..steps) {
                    player.volume = volume * (1 - step / steps.toFloat())
                    delay(FADE_MS / steps)
                }
                player.pause()
                player.volume = volume
            }
            state.value = Mode.Off
        }
    }

    fun endOfSong() {
        cancel()
        PlayerHub.player?.pauseAtEndOfMediaItems = true
        state.value = Mode.EndOfSong
    }

    /** Called by the service when a song ends while waiting for the end of the song. */
    internal fun songEnded() {
        if (state.value == Mode.EndOfSong) cancel()
    }

    fun cancel() {
        job?.cancel()
        job = null
        PlayerHub.player?.pauseAtEndOfMediaItems = false
        state.value = Mode.Off
    }
}
