package com.aaravsekhri.dot.ui.pebble

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/** What Pebble is waiting on, which decides how it waits. */
enum class PebbleWait {
    /** Working something out: looks left, then right, then back. */
    Thinking,

    /** Reading the screen: a slower look across it. */
    Looking,

    /** Something else is taking its time: glances down, as at a progress bar. */
    External,

    /** Asking the page to hold still: keeps still itself, and only blinks. */
    Still,
}

/**
 * Pebble, waiting.
 *
 * Small and calm on purpose: a gentle bounce in place with a soft shadow
 * beneath it, a gaze that travels and comes back, and an occasional blink.
 * The bounce is a few percent of Pebble's height and eased at both ends, so
 * it reads as alive rather than busy; nothing flashes or re-enters, and the
 * card around it does not move. Asked to hold still, Pebble keeps still
 * itself. With reduced motion Pebble holds one expressive pose — looking
 * aside, as though considering — resting on its shadow, and nothing animates.
 */
@Composable
fun WaitingPebble(
    wait: PebbleWait,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDescription: String? = null,
    /** Set on a card, where tests look for its one Pebble. */
    tag: String? = null,
) {
    val semantics = Modifier.clearAndSetSemantics {
        if (tag != null) testTag = tag
        if (contentDescription != null) this.contentDescription = contentDescription
    }
    val still = rememberReducedMotion()
    if (still) {
        Canvas(modifier.size(size).then(semantics)) {
            val diameter = this.size.minDimension * BODY
            val left = (this.size.width - diameter) / 2f
            val top = this.size.height - diameter - this.size.height * SHADOW_ROOM
            drawShadow(this.size.width / 2f, this.size.height, diameter, lift = 0f)
            drawPebbleMark(
                topLeft = Offset(left, top),
                diameter = diameter,
                pupilRadius = PebbleSize.Inline.pupilRadius,
                catchlightRadius = PebbleSize.Inline.catchlightRadius,
                gazeX = if (wait == PebbleWait.Still) 0f else -GAZE,
                gazeY = if (wait == PebbleWait.External) GAZE * 0.6f else 0f,
            )
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "pebble-wait")
    // The gaze: out, held, across, held, home. Eased at each end so the eyes
    // settle rather than stop dead. Still waits without looking about.
    val gaze by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = CYCLE_MS
                when (wait) {
                    PebbleWait.Thinking -> {
                        0f at 0
                        -GAZE at 700
                        -GAZE at 1600
                        GAZE at 2500
                        GAZE at 3400
                        0f at 4100
                    }
                    PebbleWait.Looking -> {
                        0f at 0
                        -GAZE at 1400
                        GAZE at 3200
                        0f at 4400
                    }
                    PebbleWait.External -> {
                        0f at 0
                        GAZE * 0.5f at 900
                        GAZE * 0.5f at 3300
                        0f at 4200
                    }
                    PebbleWait.Still -> 0f at 0
                }
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "gaze",
    )
    val glanceDown by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = CYCLE_MS
                if (wait == PebbleWait.External) {
                    0f at 0
                    GAZE * 0.7f at 900
                    GAZE * 0.7f at 3300
                    0f at 4200
                } else {
                    0f at 0
                }
            },
        ),
        label = "glance",
    )
    // A blink roughly once a cycle, quick, and at a moment the eyes are home.
    val eyeOpen by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = CYCLE_MS
                1f at 0
                1f at 4300
                0.1f at 4380
                1f at 4480
            },
        ),
        label = "blink",
    )
    // The bounce: up and down again, eased at both ends, then a rest on the
    // ground before the next. Still waits without bouncing.
    val lift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = BOUNCE_MS
                if (wait == PebbleWait.Still) {
                    0f at 0
                } else {
                    0f at 0 using FastOutSlowInEasing
                    1f at 420 using FastOutSlowInEasing
                    0f at 840
                    0f at BOUNCE_MS
                }
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "bounce",
    )

    Canvas(modifier.size(size).then(semantics)) {
        val diameter = this.size.minDimension * BODY
        val left = (this.size.width - diameter) / 2f
        val rest = this.size.height - diameter - this.size.height * SHADOW_ROOM
        val up = lift * this.size.height * BOUNCE_HEIGHT
        drawShadow(this.size.width / 2f, this.size.height, diameter, lift)
        // A slight squash on the ground, none in the air: soft, not springy.
        val squash = 1f - (1f - lift) * 0.02f
        scale(scaleX = 1f / squash, scaleY = squash, pivot = Offset(left + diameter / 2f, rest + diameter)) {
            drawPebbleMark(
                topLeft = Offset(left, rest - up),
                diameter = diameter,
                pupilRadius = PebbleSize.Inline.pupilRadius,
                catchlightRadius = PebbleSize.Inline.catchlightRadius,
                gazeX = gaze,
                gazeY = glanceDown,
                eyeOpen = eyeOpen,
            )
        }
    }
}

/** Pebble's soft shadow on the card, smaller and fainter as it lifts. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShadow(cx: Float, bottom: Float, diameter: Float, lift: Float) {
    val w = diameter * (0.72f - 0.14f * lift)
    val h = diameter * 0.12f
    drawOval(
        color = com.aaravsekhri.dot.ui.theme.DotColors.Ink.copy(alpha = 0.14f - 0.06f * lift),
        topLeft = Offset(cx - w / 2f, bottom - h - 1f),
        size = androidx.compose.ui.geometry.Size(w, h),
    )
}

/** Pebble's body, as a share of the square: the rest is room to bounce and for the shadow. */
private const val BODY = 0.84f
private const val SHADOW_ROOM = 0.07f

/** How high the bounce goes, as a share of the square. */
private const val BOUNCE_HEIGHT = 0.07f
private const val BOUNCE_MS = 1_600

/** How far the pupils travel, in board units: enough to read as looking, not rolling. */
private const val GAZE = 2.6f
private const val CYCLE_MS = 4800
