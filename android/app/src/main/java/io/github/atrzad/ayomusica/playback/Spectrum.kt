package io.github.atrzad.ayomusica.playback

import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

/** Turns the FFT that Android's Visualizer delivers into bars (0..1), spaced like the ear hears. */
object Spectrum {
    private const val FLOOR_DB = 8.0
    private const val RANGE_DB = 38.0

    /**
     * [fft] is Visualizer's format: fft[0] = DC, fft[1] = Nyquist, then (real, imaginary) pairs.
     * [samplingRateHz] is the output rate (Visualizer.getSamplingRate() / 1000).
     */
    fun bands(fft: ByteArray, samplingRateHz: Int, count: Int, lowHz: Double = 50.0, highHz: Double = 16_000.0): FloatArray {
        val bins = fft.size / 2
        if (bins < 2 || samplingRateHz <= 0) return FloatArray(count)
        val binHz = samplingRateHz.toDouble() / fft.size
        val result = FloatArray(count)
        val ratio = highHz / lowHz
        for (band in 0 until count) {
            val from = lowHz * ratio.pow(band.toDouble() / count)
            val to = lowHz * ratio.pow((band + 1).toDouble() / count)
            var first = (from / binHz).toInt().coerceIn(1, bins - 1)
            val last = (to / binHz).toInt().coerceIn(first, bins - 1)
            var peak = 0.0
            while (first <= last) {
                peak = maxOf(peak, hypot(fft[2 * first].toDouble(), fft[2 * first + 1].toDouble()))
                first++
            }
            val db = 20 * log10(peak + 1e-6)
            result[band] = ((db - FLOOR_DB) / RANGE_DB).coerceIn(0.0, 1.0).toFloat()
        }
        return result
    }

    /** Fast attack, slow release: bars jump up and fall smoothly. */
    fun smooth(previous: FloatArray, target: FloatArray): FloatArray =
        FloatArray(target.size) { i ->
            val old = previous.getOrElse(i) { 0f }
            if (target[i] > old) target[i] else old * 0.82f + target[i] * 0.18f
        }
}
