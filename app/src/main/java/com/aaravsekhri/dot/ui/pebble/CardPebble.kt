package com.aaravsekhri.dot.ui.pebble

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.util.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * How Pebble moves on a card, chosen by what the card is doing.
 *
 * Every one of them is small and calm, runs inside a fixed square that never
 * changes size, and never moves the words beside it. With reduced motion
 * each is a still, expressive pose instead.
 */
enum class PebbleMotion {
    /** Instructions and teaching: a gentle bob, and a blink now and then. */
    Bob,

    /** A question: a curious tilt of the head, and a look to one side. */
    Curious,

    /** Recovery, a pause, something that went wrong: slow, calm breathing. */
    Reassure,
}

/** What tests look for: every card has exactly one Pebble in it, tagged this. */
const val CARD_PEBBLE_TAG = "card-pebble"

/**
 * Pebble on a card, moving the way [motion] says.
 *
 * Draws only inside its own [size] square: the bob and the tilt stay within
 * it, so the card around it measures the same whether Pebble is up or down,
 * and nothing about it takes a touch. The animation belongs to this
 * composable, not to what the card says, so a card whose words change keeps
 * Pebble moving where it was rather than starting again.
 */
@Composable
fun CardPebble(
    motion: PebbleMotion,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    contentDescription: String? = null,
) {
    val semantics = Modifier.clearAndSetSemantics {
        testTag = CARD_PEBBLE_TAG
        if (contentDescription != null) this.contentDescription = contentDescription
    }
    if (rememberReducedMotion()) {
        Canvas(modifier.size(size).then(semantics)) {
            drawCardPebble(
                lift = 0f,
                lean = if (motion == PebbleMotion.Curious) -7f else 0f,
                gazeX = if (motion == PebbleMotion.Curious) -2.6f else 0f,
                gazeY = if (motion == PebbleMotion.Reassure) 1.4f else 0f,
                eyeOpen = if (motion == PebbleMotion.Reassure) 0.8f else 1f,
                breathe = 0f,
            )
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "card-pebble")
    val lift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = BOB_MS
                if (motion == PebbleMotion.Bob) {
                    0f at 0 using FastOutSlowInEasing
                    1f at BOB_MS / 2 using FastOutSlowInEasing
                    0f at BOB_MS
                } else {
                    0f at 0
                }
            },
        ),
        label = "lift",
    )
    val lean by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = TILT_MS
                if (motion == PebbleMotion.Curious) {
                    0f at 0
                    -8f at 700 using FastOutSlowInEasing
                    -8f at 2100
                    0f at 2800 using FastOutSlowInEasing
                    0f at TILT_MS
                } else {
                    0f at 0
                }
            },
        ),
        label = "lean",
    )
    val gaze by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = TILT_MS
                when (motion) {
                    PebbleMotion.Curious -> {
                        0f at 0
                        -2.6f at 700
                        -2.6f at 2100
                        0f at 2800
                    }
                    else -> 0f at 0
                }
            },
        ),
        label = "gaze",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = BREATH_MS
                if (motion == PebbleMotion.Reassure) {
                    0f at 0 using FastOutSlowInEasing
                    1f at BREATH_MS / 2 using FastOutSlowInEasing
                    0f at BREATH_MS
                } else {
                    0f at 0
                }
            },
        ),
        label = "breathe",
    )
    // A blink roughly every four and a half seconds; slower and softer when reassuring.
    val eyeOpen by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = BLINK_CYCLE_MS
                1f at 0
                1f at BLINK_CYCLE_MS - 220
                (if (motion == PebbleMotion.Reassure) 0.25f else 0.1f) at BLINK_CYCLE_MS - 140
                1f at BLINK_CYCLE_MS - 20
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "blink",
    )
    Canvas(modifier.size(size).then(semantics)) {
        drawCardPebble(lift = lift, lean = lean, gazeX = gaze, gazeY = 0f, eyeOpen = eyeOpen, breathe = breathe)
    }
}

/** Pebble within its square: body a little under full size, the rest room to move and for its shadow. */
private fun DrawScope.drawCardPebble(lift: Float, lean: Float, gazeX: Float, gazeY: Float, eyeOpen: Float, breathe: Float) {
    val diameter = size.minDimension * BODY
    val left = (size.width - diameter) / 2f
    val rest = size.height - diameter - size.height * SHADOW_ROOM
    val up = lift * size.height * BOB_HEIGHT
    drawGroundShadow(size.width / 2f, size.height, diameter, lift)
    val grow = 1f + breathe * BREATH_SCALE
    scale(grow, grow, pivot = Offset(left + diameter / 2f, rest + diameter)) {
        drawPebbleMark(
            topLeft = Offset(left, rest - up),
            diameter = diameter,
            pupilRadius = PebbleSize.TinyHighlight.pupilRadius,
            catchlightRadius = PebbleSize.TinyHighlight.catchlightRadius,
            leanDegrees = lean,
            gazeX = gazeX,
            gazeY = gazeY,
            eyeOpen = eyeOpen,
        )
    }
}

/**
 * Planning: Pebble hops across its own band beneath the words.
 *
 * It comes in from the left, makes about four soft hops to the right, leaves
 * by the right edge, and comes round again from the left unseen, so the loop
 * has no jump in it. The band is fixed in height and clipped to itself: the
 * hops never reach the words above or anything outside the card. Nothing
 * waits for the loop to finish — when the answer comes, the card that
 * replaces this one replaces it at once.
 *
 * With reduced motion, Pebble stands mid-hop in the band, looking the way it
 * was going.
 */
@Composable
fun TravelingHopPebble(
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
) {
    val semantics = Modifier.clearAndSetSemantics { testTag = CARD_PEBBLE_TAG }
    val band = modifier.fillMaxWidth().height(height).then(semantics)
    if (rememberReducedMotion()) {
        Canvas(band) {
            val d = size.height * HOP_BODY
            drawHopper(x = size.width * 0.42f, d = d, hop = 0.35f)
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "planning-hop")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(HOP_LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "travel",
    )
    Canvas(band) {
        val d = size.height * HOP_BODY
        clipRect {
            // The travel takes most of the loop; the rest is Pebble out of
            // sight on the left, getting ready to come round again.
            val travel = (t / TRAVEL_SHARE).coerceAtMost(1f)
            if (t >= TRAVEL_SHARE) return@clipRect
            val hops = travel * HOPS
            val hop = hops - hops.toInt()
            // Forward mostly while in the air, a moment's settle on each landing.
            val stride = (hops.toInt() + FastOutSlowInEasing.transform(hop)) / HOPS
            val x = -d + stride * (size.width + 2 * d)
            drawHopper(x = x, d = d, hop = hop)
        }
    }
}

/** One hopping Pebble: [x] its left edge, [hop] 0..1 through the current hop. */
private fun DrawScope.drawHopper(x: Float, d: Float, hop: Float) {
    val air = sin(PI * hop).toFloat()
    val ground = size.height - size.height * HOP_SHADOW_ROOM
    val y = ground - d - air * size.height * HOP_HEIGHT
    // Squashed a touch on landing and take-off, never in the air.
    val squash = 1f - (1f - air) * 0.05f
    drawGroundShadow(x + d / 2f, size.height, d, air)
    scale(scaleX = 1f / squash, scaleY = squash, pivot = Offset(x + d / 2f, ground)) {
        drawPebbleMark(
            topLeft = Offset(x, y),
            diameter = d,
            pupilRadius = PebbleSize.Inline.pupilRadius,
            catchlightRadius = PebbleSize.Inline.catchlightRadius,
            // Looking where it is going, leaning into the hop a little.
            leanDegrees = 5f * air,
            gazeX = 2.4f,
        )
    }
}

/** A soft shadow on the card under Pebble, smaller and fainter as it rises. */
private fun DrawScope.drawGroundShadow(cx: Float, bottom: Float, diameter: Float, lift: Float) {
    val w = diameter * (0.7f - 0.16f * abs(lift))
    val h = diameter * 0.12f
    drawOval(
        color = DotColors.Ink.copy(alpha = 0.13f - 0.06f * abs(lift)),
        topLeft = Offset(cx - w / 2f, bottom - h - 1f),
        size = Size(w, h),
    )
}

private const val BODY = 0.86f
private const val SHADOW_ROOM = 0.06f
private const val BOB_HEIGHT = 0.05f
private const val BOB_MS = 2_600
private const val TILT_MS = 5_200
private const val BREATH_MS = 3_800
private const val BREATH_SCALE = 0.035f
private const val BLINK_CYCLE_MS = 4_600

/** The planning loop: about four hops across, then a moment unseen before the next pass. */
private const val HOPS = 4f
private const val HOP_LOOP_MS = 3_400
private const val TRAVEL_SHARE = 0.88f
private const val HOP_BODY = 0.62f
private const val HOP_HEIGHT = 0.22f
private const val HOP_SHADOW_ROOM = 0.08f
