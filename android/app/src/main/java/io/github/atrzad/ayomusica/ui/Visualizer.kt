package io.github.atrzad.ayomusica.ui

import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.atrzad.ayomusica.playback.Spectrum

private const val BARS = 40

/** Monochrome bars of what is playing, drawn behind the full-screen player (like the desktop's CAVA). */
@Composable
fun SpectrumBackground(sessionId: Int, modifier: Modifier = Modifier, alpha: Float = 0.11f) {
    var levels by remember { mutableStateOf(FloatArray(BARS)) }
    DisposableEffect(sessionId) {
        val visualizer = if (sessionId == 0) null else runCatching { Visualizer(sessionId) }.getOrNull()
        visualizer?.runCatching {
            captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) = Unit
                // The rate comes with the data (in mHz): a late callback may arrive after release().
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft == null || rate <= 0) return
                    levels = Spectrum.smooth(levels, Spectrum.bands(fft, rate / 1000, BARS))
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            enabled = true
        }
        onDispose {
            visualizer?.runCatching {
                enabled = false
                release()
            }
        }
    }
    val color = MaterialTheme.colorScheme.primary.copy(alpha = alpha * 1.3f)  // the theme's accent
    Canvas(modifier) {
        val gap = size.width / BARS * 0.25f
        val width = size.width / BARS - gap
        levels.forEachIndexed { index, level ->
            val height = (level * size.height).coerceAtLeast(2f)
            drawRoundRect(color, Offset(index * (width + gap) + gap / 2, size.height - height), Size(width, height),
                CornerRadius(width / 3))
        }
    }
}
