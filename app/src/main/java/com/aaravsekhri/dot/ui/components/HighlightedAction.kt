package com.aaravsekhri.dot.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.aaravsekhri.dot.ui.pebble.Pebble
import com.aaravsekhri.dot.ui.pebble.PebbleMood
import com.aaravsekhri.dot.ui.pebble.PebbleSize
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.util.DotDuration
import com.aaravsekhri.dot.ui.util.DotEasing
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/** Which corner Pebble perches on. */
enum class PebblePerch { TopStart, TopEnd }

/**
 * Wraps a control that Pebble is pointing at: a rust outline drawn 8dp outside
 * the control, a faint rust wash inside it, and Pebble itself perched small on
 * one corner.
 *
 * Pebble arrives first with a short pop in place, and the outline is drawn
 * around the target a beat later — so you follow Pebble to the thing, rather
 * than having the whole highlight appear at once.
 *
 * The outline is decorative; whatever it surrounds carries its own label, and
 * the highlight is never the only thing distinguishing the primary action.
 */
@Composable
fun HighlightedAction(
    modifier: Modifier = Modifier,
    highlighted: Boolean = true,
    perch: PebblePerch = PebblePerch.TopStart,
    haloRadius: Dp = DotDimens.HaloRadius,
    /** False where Pebble is already the thing to look at nearby — a celebration — so there is one Pebble, not two. */
    showPebble: Boolean = true,
    /**
     * The Pebble to perch, when the place it perches wants its own — a card
     * whose Pebble moves the way the card does. Null perches the standard one.
     */
    pebble: (@Composable (Modifier) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val reducedMotion = rememberReducedMotion()

    // Pebble pops into place; the halo is drawn around the target afterwards.
    val popProgress = remember { Animatable(if (highlighted) 0f else 1f) }
    val haloAlpha = remember { Animatable(if (highlighted) 0f else 1f) }

    // Keyed on being highlighted, not on reduced motion: the overlay card
    // keeps a still, unseen copy of itself for measuring, and when that copy
    // becomes the one shown its Pebble is already in place — it must not
    // arrive a second time.
    LaunchedEffect(highlighted) {
        if (!highlighted) {
            popProgress.snapTo(1f)
            haloAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        if (reducedMotion) {
            popProgress.snapTo(1f)
            haloAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        popProgress.snapTo(0f)
        haloAlpha.snapTo(0f)
        launch {
            popProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = DotDuration.POP_IN_PLACE,
                    delayMillis = DotDuration.POP_DELAY,
                    easing = DotEasing.Pop,
                ),
            )
        }
        launch {
            haloAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = DotDuration.FADE_IN,
                    delayMillis = DotDuration.HALO_DELAY,
                ),
            )
        }
    }

    Box(
        modifier = modifier.drawBehind {
            if (!highlighted) return@drawBehind
            val inset = DotDimens.HaloInset.toPx()
            val strokeWidth = DotDimens.HaloStroke.toPx()
            val topLeft = Offset(-inset, -inset)
            val haloSize = Size(size.width + inset * 2, size.height + inset * 2)
            val corner = CornerRadius(haloRadius.toPx())
            val alpha = haloAlpha.value

            drawRoundRect(
                color = DotColors.HaloFill,
                topLeft = topLeft,
                size = haloSize,
                cornerRadius = corner,
                alpha = alpha,
            )
            drawRoundRect(
                color = DotColors.RustOutline,
                topLeft = topLeft,
                size = haloSize,
                cornerRadius = corner,
                alpha = alpha,
                style = Stroke(width = strokeWidth),
            )
        },
    ) {
        content()

        if (highlighted && showPebble) {
            val alignment =
                if (perch == PebblePerch.TopStart) Alignment.TopStart else Alignment.TopEnd
            val xOffset = if (perch == PebblePerch.TopStart) (-4).dp else 1.dp

            val perched = Modifier
                .align(alignment)
                .offset(x = xOffset, y = (-25).dp)
                .graphicsLayer {
                    // pop in place: 0.85 -> 1, fading in, anchored at its centre
                    val p = popProgress.value
                    scaleX = 0.85f + 0.15f * p
                    scaleY = 0.85f + 0.15f * p
                    this.alpha = p
                }
            if (pebble != null) {
                pebble(perched)
            } else {
                Pebble(size = PebbleSize.TinyHighlight, mood = PebbleMood.Highlighting, modifier = perched)
            }
        }
    }
}

/**
 * The one action Dot recommends, wherever it appears: the rust outline with
 * Pebble perched on its top corner.
 *
 * Every recommended action inside Dot goes through this — "Start lesson",
 * "Resume", "Continue" between lessons, "Try again later" when a task has
 * stalled, "I'm done" once there is something to be done with — so they all
 * look and move the same, and nothing is decorated by hand. One per card or
 * screen. Neutral questions (whether someone already has an account) never
 * use it: recommending an answer is not Dot's place.
 *
 * Needs [PreferredActionHeadroom] of space above it inside whatever contains
 * it, because Pebble sits partly above the button.
 *
 * @param emphasized false draws the button plainly, for the same layout
 *   without a recommendation.
 */
@Composable
fun PreferredAction(
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
    /** See [HighlightedAction]: false beside a Pebble that is already celebrating. */
    showPebble: Boolean = true,
    /** See [HighlightedAction]. */
    pebble: (@Composable (Modifier) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) = HighlightedAction(
    modifier = modifier,
    highlighted = emphasized,
    perch = PebblePerch.TopEnd,
    haloRadius = DotDimens.HaloLessonRadius,
    showPebble = showPebble,
    pebble = pebble,
    content = content,
)

/** Room above a [PreferredAction] for Pebble, which perches partly above it. */
val PreferredActionHeadroom: Dp = 28.dp

@Preview(showBackground = true, backgroundColor = 0xFFFBFBFC, widthDp = 390, heightDp = 160)
@Composable
private fun PreviewHighlightedAction() {
    Box(Modifier.offset(24.dp, 50.dp)) {
        HighlightedAction {
            PrimaryButton(text = "Show me how", onClick = {})
        }
    }
}
