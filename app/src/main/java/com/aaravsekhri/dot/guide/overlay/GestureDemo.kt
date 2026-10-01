package com.aaravsekhri.dot.guide.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.guide.ai.GestureMotion
import com.aaravsekhri.dot.guide.ai.GesturePlan
import com.aaravsekhri.dot.ui.pebble.Pebble
import com.aaravsekhri.dot.ui.pebble.PebbleMood
import com.aaravsekhri.dot.ui.pebble.PebbleSize
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/**
 * Where to put a finger, and which way to move it, on the screen itself.
 *
 * Pebble appears where the finger should start, travels the short path the
 * gesture takes with a fading trail behind it, fades out where it ends,
 * returns to the start unseen, pauses, and does it again — the same timing as
 * the card's own drawing ([GestureMotion]), so it never visibly moves the
 * wrong way. With reduced motion, Pebble waits at the start beside a still
 * line and arrow to the end.
 *
 * Drawn on the full-screen layer that cannot be touched: the person's own
 * swipe goes straight through it to the app underneath.
 */
@Composable
internal fun GestureDemo(plan: State<GesturePlan?>) {
    val p = plan.value ?: return
    val start = Offset(p.start.x.toFloat(), p.start.y.toFloat())
    val end = Offset(p.end.x.toFloat(), p.end.y.toFloat())
    val still = rememberReducedMotion()
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        if (still) {
            StillPath(start, end)
            if (p.holdMs > 0) HoldRing(end, 1f)
            PebbleAt(start, alpha = 1f)
        } else {
            // A new path starts its cycle afresh; the same one carries on.
            key(p) {
                val transition = rememberInfiniteTransition(label = "gesture")
                val t by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(GestureMotion.cycleMs(p.holdMs), easing = LinearEasing),
                        RepeatMode.Restart,
                    ),
                    label = "gesture-cycle",
                )
                val frame = GestureMotion.frame(t, p.holdMs)
                val at = start + (end - start) * frame.progress
                Trail(start, at, frame.alpha)
                // Held at the end before letting go: a still ring says "stay".
                if (frame.holding) HoldRing(end, frame.alpha)
                PebbleAt(at, frame.alpha)
            }
        }
    }
}

/** The path so far: faint where it began, stronger near Pebble. */
@Composable
private fun Trail(from: Offset, to: Offset, alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (alpha <= 0f || (to - from).getDistance() < 1f) return@Canvas
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(DotColors.Rust.copy(alpha = 0f), DotColors.Rust.copy(alpha = 0.38f * alpha)),
                start = from,
                end = to,
            ),
            start = from,
            end = to,
            strokeWidth = 12.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** For reduced motion: a soft ring where the finger starts, a still line and an arrowhead. */
@Composable
private fun StillPath(from: Offset, to: Offset) {
    Canvas(Modifier.fillMaxSize()) {
        val colour = DotColors.Rust.copy(alpha = 0.6f)
        drawCircle(colour, radius = 26.dp.toPx(), center = from, style = Stroke(2.dp.toPx()))
        drawLine(
            colour, from, to,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx())),
        )
        val direction = (to - from).let { it / it.getDistance().coerceAtLeast(1f) }
        val side = Offset(-direction.y, direction.x)
        val size = 14.dp.toPx()
        drawLine(colour, to, to - direction * size + side * size * 0.7f, 4.dp.toPx(), StrokeCap.Round)
        drawLine(colour, to, to - direction * size - side * size * 0.7f, 4.dp.toPx(), StrokeCap.Round)
    }
}

/** Where the finger stays down, for a gesture held before it is let go. */
@Composable
private fun HoldRing(at: Offset, alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(DotColors.Rust.copy(alpha = 0.55f * alpha), radius = 34.dp.toPx(), center = at, style = Stroke(3.dp.toPx()))
    }
}

/** Pebble, centred on the fingertip. */
@Composable
private fun PebbleAt(at: Offset, alpha: Float) {
    val half = with(LocalDensity.current) { (PebbleSize.TinyHighlight.dp / 2).roundToPx() }
    Pebble(
        size = PebbleSize.TinyHighlight,
        mood = PebbleMood.Highlighting,
        modifier = Modifier
            .offset { IntOffset(at.x.toInt() - half, at.y.toInt() - half) }
            .graphicsLayer { this.alpha = alpha },
    )
}
