package io.github.atrzad.ayomusica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shuffle as two arrows: parallel when off, crossed when on. Switching animates the arrows
 * crossing or untangling (as in the sketch).
 */
@Composable
fun ShuffleIcon(on: Boolean, color: Color, size: Dp = 26.dp) {
    val crossed by animateFloatAsState(if (on) 1f else 0f, tween(450), label = "shuffle")
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun lerp(a: Float, b: Float) = a + (b - a) * crossed
        // Off: two horizontal arrows. On: one goes down, the other up, crossing in the middle.
        arrow(Offset(w * 0.12f, h * lerp(0.32f, 0.28f)), Offset(w * 0.86f, h * lerp(0.32f, 0.72f)), color)
        arrow(Offset(w * 0.12f, h * lerp(0.68f, 0.72f)), Offset(w * 0.86f, h * lerp(0.68f, 0.28f)), color)
    }
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color) {
    val stroke = size.width * 0.085f
    drawLine(color, from, to, stroke, StrokeCap.Round)
    val angle = atan2(to.y - from.y, to.x - from.x)
    val head = size.width * 0.2f
    for (side in listOf(-1, 1)) {
        val a = angle + Math.PI.toFloat() + side * 0.6f
        drawLine(color, to, Offset(to.x + cos(a) * head, to.y + sin(a) * head), stroke, StrokeCap.Round)
    }
}
