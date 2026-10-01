package com.aaravsekhri.dot.guide.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.ui.pebble.drawPebbleMark
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.util.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pebble, pleased: the approved character, glancing up, with one small hop
 * and a small burst of confetti from behind it — once.
 *
 * Played once per [eventId] and only when [animate]: a card drawn again for
 * the same completion (after Dot's own screen took it down, say) shows the
 * still pose, never the burst again. With reduced motion, always the still
 * pose: Pebble looking up, a few pieces of confetti resting round it. Nothing
 * loops, nothing flashes, and nothing waits for it: the card's buttons work
 * from the first frame.
 *
 * Drawn only inside its own small square, beside the card's words, so the
 * celebration never spreads over the app behind the card.
 *
 * @param fuller a slightly fuller burst, for finishing a whole course.
 */
@Composable
internal fun CelebratingPebble(
    eventId: Long,
    animate: Boolean,
    fuller: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    /** Set on a card, where tests look for its one Pebble. */
    tag: String? = null,
) {
    val still = rememberReducedMotion() || !animate
    // 0 → 1 over the burst; 1 is the resting pose, which is also the still one.
    val progress = remember(eventId) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(eventId) {
        if (!still && progress.value < 1f) {
            progress.animateTo(1f, tween(BURST_MS, easing = LinearEasing))
        }
    }
    val pieces = remember(fuller) { confetti(if (fuller) 22 else 12) }
    Box(modifier.size(size).clearAndSetSemantics { if (tag != null) testTag = tag }) {
        Canvas(Modifier.size(size)) {
            val t = progress.value
            val center = Offset(this.size.width / 2f, this.size.height * 0.56f)
            val reach = this.size.minDimension * 0.5f

            // Confetti: out from behind Pebble, drifting down a little as it
            // goes, then fading. At rest (and for reduced motion) a few
            // pieces stay, small and still, so the pose still reads as glad.
            pieces.forEachIndexed { i, piece ->
                val resting = i % 3 == 0
                val travel = if (t >= 1f) (if (resting) 0.9f else 0f) else FastOutSlowInEasing.transform(t)
                val alpha = when {
                    t >= 1f -> if (resting) 0.9f else 0f
                    t > 0.7f -> 1f - (t - 0.7f) / 0.3f * (if (resting) 0.1f else 1f)
                    else -> 1f
                }
                if (alpha <= 0f) return@forEachIndexed
                val distance = reach * piece.distance * travel
                val at = center + Offset(cos(piece.angle) * distance, sin(piece.angle) * distance + reach * 0.18f * travel * travel)
                rotate(piece.spin * 360f * travel, pivot = at) {
                    drawRoundRect(
                        color = piece.colour.copy(alpha = alpha),
                        topLeft = at - Offset(3.dp.toPx(), 1.6.dp.toPx()),
                        size = Size(6.dp.toPx(), 3.2.dp.toPx()),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                    )
                }
            }

            // Pebble: one small hop, up and back, looking up.
            val hop = if (t >= 1f) 0f else sin(Math.PI * (t / 0.55f).coerceAtMost(1f)).toFloat() * this.size.height * 0.08f
            val diameter = this.size.minDimension * 0.62f
            drawPebbleMark(
                topLeft = Offset((this.size.width - diameter) / 2f, this.size.height - diameter - this.size.height * 0.06f - hop),
                diameter = diameter,
                pupilRadius = 5.2f,
                catchlightRadius = 2.1f,
                gazeY = -2.5f,
            )
        }
    }
}

private data class Piece(val angle: Float, val distance: Float, val spin: Float, val colour: Color)

/** A fixed, even spread upwards and outwards: the same burst every time, nothing random. */
private fun confetti(count: Int): List<Piece> {
    val colours = listOf(DotColors.Rust, Color(0xFFE8A33D), Color(0xFF5B8DEF), Color(0xFF58B38A), DotColors.Rust.copy(alpha = 0.7f))
    return (0 until count).map { i ->
        // Upper half-circle, from left to right, a little past horizontal.
        val angle = (Math.PI * (1.08 + 0.84 * i / (count - 1).coerceAtLeast(1))).toFloat()
        Piece(
            angle = angle,
            distance = 0.62f + 0.3f * ((i * 37) % 10) / 10f,
            spin = if (i % 2 == 0) 0.6f else -0.8f,
            colour = colours[i % colours.size],
        )
    }
}

private const val BURST_MS = 900
