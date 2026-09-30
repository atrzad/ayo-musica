package io.github.atrzad.ayomusica.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Equalizer and bass boost on the player's audio session (Android's own audio effects). */
object AudioEffects {
    data class State(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val bandsHz: List<Int> = emptyList(),
        val levels: List<Int> = emptyList(),        // millibels
        val minLevel: Int = -1500,
        val maxLevel: Int = 1500,
        val presets: List<String> = emptyList(),
        val preset: Int = CUSTOM,
        val bassSupported: Boolean = false,
        val bass: Int = 0,                          // 0..1000
    )

    const val CUSTOM = -1
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(State())
    val ui: StateFlow<State> = state

    fun attach(context: Context, sessionId: Int) {
        release()
        val saved = context.getSharedPreferences("audio_effects", Context.MODE_PRIVATE).also { prefs = it }
        if (sessionId == 0) return
        val eq = runCatching { Equalizer(0, sessionId) }.getOrNull() ?: return
        equalizer = eq
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        val bands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        // Some devices pad the names with NULs.
        val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()).filter { c -> c >= ' ' }.trim() }
        val preset = saved.getInt("preset", CUSTOM)
        val levels = if (preset in presets.indices) {
            runCatching { eq.usePreset(preset.toShort()) }
            (0 until bands).map { eq.getBandLevel(it.toShort()).toInt() }
        } else {
            (0 until bands).map { band -> saved.getInt("band$band", 0).coerceIn(range[0].toInt(), range[1].toInt()) }
                .also { it.forEachIndexed { band, level -> runCatching { eq.setBandLevel(band.toShort(), level.toShort()) } } }
        }
        val enabled = saved.getBoolean("enabled", false)
        runCatching { eq.enabled = enabled }
        val bass = saved.getInt("bass", 0)
        bassBoost?.let { boost -> runCatching { boost.setStrength(bass.toShort()); boost.enabled = enabled && bass > 0 } }
        state.value = State(
            available = true, enabled = enabled,
            bandsHz = (0 until bands).map { eq.getCenterFreq(it.toShort()) / 1000 },
            levels = levels, minLevel = range[0].toInt(), maxLevel = range[1].toInt(),
            presets = presets, preset = preset,
            bassSupported = bassBoost?.strengthSupported == true, bass = bass,
        )
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        state.value = state.value.copy(available = false)
    }

    fun setEnabled(on: Boolean) {
        runCatching { equalizer?.enabled = on }
        runCatching { bassBoost?.enabled = on && state.value.bass > 0 }
        state.value = state.value.copy(enabled = on)
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
    }

    fun usePreset(index: Int) {
        val eq = equalizer ?: return
        runCatching { eq.usePreset(index.toShort()) }
        val levels = state.value.levels.indices.map { eq.getBandLevel(it.toShort()).toInt() }
        state.value = state.value.copy(preset = index, levels = levels)
        prefs?.edit()?.putInt("preset", index)?.apply()
        if (!state.value.enabled) setEnabled(true)
    }

    fun setLevel(band: Int, millibels: Int) {
        val eq = equalizer ?: return
        val level = millibels.coerceIn(state.value.minLevel, state.value.maxLevel)
        runCatching { eq.setBandLevel(band.toShort(), level.toShort()) }
        val levels = state.value.levels.toMutableList().also { it[band] = level }
        state.value = state.value.copy(levels = levels, preset = CUSTOM)
        prefs?.edit()?.let { editor ->
            editor.putInt("preset", CUSTOM)
            levels.forEachIndexed { index, value -> editor.putInt("band$index", value) }
            editor.apply()
        }
        if (!state.value.enabled) setEnabled(true)
    }

    fun setBass(strength: Int) {
        val value = strength.coerceIn(0, 1000)
        bassBoost?.let { boost -> runCatching { boost.setStrength(value.toShort()); boost.enabled = state.value.enabled && value > 0 } }
        state.value = state.value.copy(bass = value)
        prefs?.edit()?.putInt("bass", value)?.apply()
    }

    fun reset() {
        state.value.levels.indices.forEach { setLevel(it, 0) }
        setBass(0)
    }
}
