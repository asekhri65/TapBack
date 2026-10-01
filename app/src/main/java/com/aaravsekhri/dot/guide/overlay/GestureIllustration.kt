package com.aaravsekhri.dot.guide.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.guide.ai.FingerDirection
import com.aaravsekhri.dot.guide.ai.Gesture
import com.aaravsekhri.dot.guide.ai.GestureFrame
import com.aaravsekhri.dot.guide.ai.GestureMotion
import com.aaravsekhri.dot.guide.ai.NavigationStyle
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/**
 * A miniature phone showing the movement an instruction asks for.
 *
 * The same drawing wherever a gesture is asked for — Dot's scrolling
 * guidance, the model's scroll and navigation steps, the start-of-guide
 * "go Home" — and always from [Gesture] metadata, never from the wording.
 *
 * A fingertip appears, travels the way the gesture goes, fades out where it
 * ended and returns unseen; the page inside moves with the finger, the way a
 * real page does, and wraps rather than jumping back. Nothing ever moves
 * backwards in view. With reduced motion on, it holds one still frame with
 * the path drawn, which says the same thing without moving.
 *
 * Decorative: the card's sentence carries the meaning for TalkBack.
 */
@Composable
fun GestureIllustration(
    gesture: Gesture,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val still = rememberReducedMotion() || !animate || gesture.isNeutral()
    val cycle = if (still) {
        STILL
    } else {
        val transition = rememberInfiniteTransition(label = "gesture")
        val t by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(GestureMotion.cycleMs(gesture.holdMs()), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "gesture-cycle",
        )
        t
    }
    val frame = if (still) GestureFrame(1f, 1f) else GestureMotion.frame(cycle, gesture.holdMs())

    Canvas(modifier.size(width = 52.dp, height = 72.dp).clearAndSetSemantics { }) {
        val phone = drawPhone()
        when (gesture) {
            is Gesture.Slide -> drawSlide(phone, gesture.finger, frame, still)
            is Gesture.Home -> when (gesture.navigation) {
                NavigationStyle.Gestures -> drawEdgeSwipe(phone, fromBottom = true, frame, still)
                NavigationStyle.ThreeButton -> drawButtons(phone, twoButton = false, press = Press.Home, frame)
                NavigationStyle.TwoButton -> drawButtons(phone, twoButton = true, press = Press.Home, frame)
                NavigationStyle.Unknown -> drawNeutralBottom(phone)
            }
            is Gesture.Back -> when (gesture.navigation) {
                NavigationStyle.Gestures -> drawEdgeSwipe(phone, fromBottom = false, frame, still)
                NavigationStyle.ThreeButton -> drawButtons(phone, twoButton = false, press = Press.Back, frame)
                NavigationStyle.TwoButton -> drawButtons(phone, twoButton = true, press = Press.Back, frame)
                NavigationStyle.Unknown -> drawNeutralBottom(phone)
            }
            is Gesture.Recents -> when (gesture.navigation) {
                // Up from the bottom edge, not as far as Home goes, and held.
                NavigationStyle.Gestures, NavigationStyle.TwoButton ->
                    drawEdgeSwipe(phone, fromBottom = true, frame, still, reach = 0.62f, hold = true)
                NavigationStyle.ThreeButton -> drawButtons(phone, twoButton = false, press = Press.Recents, frame)
                NavigationStyle.Unknown -> drawNeutralBottom(phone)
            }
        }
    }
}

/** How long the finger stays down at the end, for the gesture that needs it. */
private fun Gesture.holdMs(): Long = when (this) {
    is Gesture.Recents -> if (navigation == NavigationStyle.ThreeButton) 0 else com.aaravsekhri.dot.guide.ai.GesturePlanner.RECENTS_HOLD_MS
    else -> 0
}

/**
 * Unknown navigation is drawn still and neutral: Dot does not know which of
 * two movements this phone uses, and animating either would be a guess.
 */
private fun Gesture.isNeutral() = when (this) {
    is Gesture.Home -> navigation == NavigationStyle.Unknown
    is Gesture.Back -> navigation == NavigationStyle.Unknown
    is Gesture.Recents -> navigation == NavigationStyle.Unknown
    is Gesture.Slide -> false
}

private const val STILL = 0f

private enum class Press { Home, Back, Recents }

/** The screen area inside the phone outline. */
private data class Screen(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun DrawScope.drawPhone(): Screen {
    val stroke = 2.dp.toPx()
    val inset = stroke / 2f
    drawRoundRect(
        color = DotColors.Surface,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(9.dp.toPx()),
    )
    drawRoundRect(
        color = DotColors.Muted.copy(alpha = 0.55f),
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(9.dp.toPx()),
        style = Stroke(stroke),
    )
    val pad = 4.dp.toPx()
    return Screen(pad, pad, size.width - pad, size.height - pad)
}

private fun DrawScope.fingertip(at: Offset, alpha: Float) {
    if (alpha <= 0f) return
    val r = 6.dp.toPx()
    drawCircle(DotColors.Rust.copy(alpha = 0.18f * alpha), radius = r * 1.8f, center = at)
    drawCircle(DotColors.Rust.copy(alpha = 0.95f * alpha), radius = r, center = at)
}

/** The path a still frame shows, faint, from where the finger starts. */
private fun DrawScope.trail(from: Offset, to: Offset) {
    drawLine(
        DotColors.Rust.copy(alpha = 0.35f),
        from,
        to,
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawSlide(
    screen: Screen,
    finger: FingerDirection,
    frame: GestureFrame,
    still: Boolean,
) {
    val vertical = finger == FingerDirection.Up || finger == FingerDirection.Down
    val sign = if (finger == FingerDirection.Up || finger == FingerDirection.Left) -1f else 1f

    // The page: identical, evenly spaced lines that move with the finger by
    // exactly one spacing per cycle, so the reset lands on an identical
    // picture — seamless, never a jump back.
    val gap = (if (vertical) screen.height else screen.width) / 3.2f
    val shift = if (still) 0f else sign * gap * frame.progress
    val line = DotColors.Faint.copy(alpha = 0.45f)
    val thickness = 4.dp.toPx()
    clipRect(screen.left, screen.top, screen.right, screen.bottom) {
        for (i in -2..5) {
            if (vertical) {
                val y = screen.top + gap * (i + 0.5f) + shift
                drawLine(
                    line,
                    Offset(screen.left + screen.width * 0.18f, y),
                    Offset(screen.right - screen.width * 0.18f, y),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round,
                )
            } else {
                val x = screen.left + gap * (i + 0.5f) + shift
                drawLine(
                    line,
                    Offset(x, screen.top + screen.height * 0.3f),
                    Offset(x, screen.bottom - screen.height * 0.3f),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round,
                )
            }
        }
    }

    val cx = (screen.left + screen.right) / 2f
    val cy = (screen.top + screen.bottom) / 2f
    val reach = (if (vertical) screen.height else screen.width) * 0.26f
    val start = if (vertical) Offset(cx, cy - sign * reach) else Offset(cx - sign * reach, cy)
    val end = if (vertical) Offset(cx, cy + sign * reach) else Offset(cx + sign * reach, cy)
    if (still) trail(start, end)
    fingertip(lerp(start, end, frame.progress), frame.alpha)
}

/** Home from the bottom edge, or back from the side edge. */
private fun DrawScope.drawEdgeSwipe(
    screen: Screen,
    fromBottom: Boolean,
    frame: GestureFrame,
    still: Boolean,
    /** How far up the screen a swipe from the bottom goes, as a fraction of it. */
    reach: Float = 0.55f,
    /** Drawn with the ring that means "keep your finger there". */
    hold: Boolean = false,
) {
    // The gesture bar, where the Home swipe begins.
    val barY = screen.bottom - 3.dp.toPx()
    drawLine(
        DotColors.Muted.copy(alpha = 0.6f),
        Offset(screen.left + screen.width * 0.3f, barY),
        Offset(screen.right - screen.width * 0.3f, barY),
        strokeWidth = 2.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
    val start: Offset
    val end: Offset
    if (fromBottom) {
        start = Offset((screen.left + screen.right) / 2f, screen.bottom + 1.dp.toPx())
        end = Offset(start.x, screen.bottom - screen.height * reach)
    } else {
        val y = screen.top + screen.height * 0.45f
        start = Offset(screen.left - 1.dp.toPx(), y)
        end = Offset(screen.left + screen.width * 0.55f, y)
        // A small "back" chevron at the edge the swipe starts from.
        val c = 4.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(screen.left + c * 1.6f, y - c)
                lineTo(screen.left + c * 0.6f, y)
                lineTo(screen.left + c * 1.6f, y + c)
            },
            DotColors.Muted.copy(alpha = 0.55f),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    if (still) trail(start, end)
    // Held: a still ring round the fingertip where it stops. Shown in the
    // still frame too, which is the only way a picture can say "and wait".
    if (hold && (still || frame.holding)) holdRing(end, frame.alpha)
    fingertip(lerp(start, end, frame.progress), frame.alpha)
}

private fun DrawScope.holdRing(at: Offset, alpha: Float) {
    if (alpha <= 0f) return
    drawCircle(DotColors.Rust.copy(alpha = 0.5f * alpha), radius = 10.dp.toPx(), center = at, style = Stroke(1.8.dp.toPx()))
}

/** Navigation buttons, and a tap on the one that is needed. */
private fun DrawScope.drawButtons(screen: Screen, twoButton: Boolean, press: Press, frame: GestureFrame) {
    val y = screen.bottom - screen.height * 0.12f
    val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val muted = DotColors.Muted.copy(alpha = 0.7f)
    val g = 3.6.dp.toPx()
    val backX = screen.left + screen.width * 0.2f
    val homeX = (screen.left + screen.right) / 2f
    val recentX = screen.right - screen.width * 0.2f

    drawPath(
        Path().apply {
            moveTo(backX + g * 0.7f, y - g)
            lineTo(backX - g * 0.7f, y)
            lineTo(backX + g * 0.7f, y + g)
            close()
        },
        muted,
        style = stroke,
    )
    if (twoButton) {
        drawRoundRect(
            muted,
            topLeft = Offset(homeX - g * 1.6f, y - g * 0.55f),
            size = Size(g * 3.2f, g * 1.1f),
            cornerRadius = CornerRadius(g),
            style = stroke,
        )
    } else {
        drawCircle(muted, radius = g, center = Offset(homeX, y), style = stroke)
        drawRect(muted, topLeft = Offset(recentX - g * 0.8f, y - g * 0.8f), size = Size(g * 1.6f, g * 1.6f), style = stroke)
    }

    // A tap: the fingertip lands, a ring spreads, both fade. Only ever
    // outward — a ring that shrank back would read as something else.
    val at = Offset(
        when (press) {
            Press.Back -> backX
            Press.Home -> homeX
            Press.Recents -> recentX
        },
        y,
    )
    if (frame.alpha > 0f) {
        drawCircle(
            DotColors.Rust.copy(alpha = 0.35f * frame.alpha * (1f - frame.progress * 0.6f)),
            radius = g * (1.4f + 1.4f * frame.progress),
            center = at,
            style = Stroke(1.8.dp.toPx()),
        )
    }
    fingertip(at, frame.alpha)
}

/** Unknown navigation: the bottom of the screen, still, where both ways live. */
private fun DrawScope.drawNeutralBottom(screen: Screen) {
    drawRoundRect(
        DotColors.Rust.copy(alpha = 0.14f),
        topLeft = Offset(screen.left, screen.bottom - screen.height * 0.22f),
        size = Size(screen.width, screen.height * 0.22f),
        cornerRadius = CornerRadius(5.dp.toPx()),
    )
    drawLine(
        DotColors.Muted.copy(alpha = 0.6f),
        Offset(screen.left + screen.width * 0.3f, screen.bottom - 3.dp.toPx()),
        Offset(screen.right - screen.width * 0.3f, screen.bottom - 3.dp.toPx()),
        strokeWidth = 2.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

@Preview(showBackground = true)
@Composable
private fun PreviewSlideUp() = GestureIllustration(Gesture.Slide(FingerDirection.Up), animate = false)

@Preview(showBackground = true)
@Composable
private fun PreviewHomeSwipe() = GestureIllustration(Gesture.Home(NavigationStyle.Gestures), animate = false)

@Preview(showBackground = true)
@Composable
private fun PreviewHomeButton() = GestureIllustration(Gesture.Home(NavigationStyle.ThreeButton), animate = false)
