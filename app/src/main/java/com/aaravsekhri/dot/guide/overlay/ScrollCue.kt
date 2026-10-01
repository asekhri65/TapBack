package com.aaravsekhri.dot.guide.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.ui.pebble.Pebble
import com.aaravsekhri.dot.ui.pebble.PebbleMood
import com.aaravsekhri.dot.ui.pebble.PebbleSize
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.SchibstedGrotesk
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/**
 * The cue shown when the target is further down (or up) the page.
 *
 * It sits near the middle of the screen with Pebble and says, in words, what
 * to do. An arrow on its own would leave someone guessing what it refers to,
 * so the words carry the meaning and the chevrons only reinforce the
 * direction.
 *
 * Drawn in a window that cannot be touched, so the scroll it is asking for
 * passes straight through it. Dot never scrolls the page itself.
 */

private val CueShape = RoundedCornerShape(22.dp)
private val CueBorder = Color(0xFFE7E9EC)

private val CueText = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.W600,
    fontSize = 19.sp,
    lineHeight = 25.sp,
    letterSpacing = (-0.012).em,
)

@Composable
fun ScrollCue(
    direction: ScrollDirection,
    message: String,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val downwards = direction == ScrollDirection.Down

    // A slow, shallow drift in the direction being asked for. Calm enough to
    // read past, obvious enough to follow.
    val travel = if (reducedMotion) {
        0f
    } else {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "scroll-cue")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cue-drift",
        )
        value
    }

    val drift by rememberUpdatedState(travel * DRIFT_DP * if (downwards) 1f else -1f)

    Column(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = CueShape,
                clip = false,
                ambientColor = DotColors.Ink.copy(alpha = 0.30f),
                spotColor = DotColors.Ink.copy(alpha = 0.30f),
            )
            .background(DotColors.Surface, CueShape)
            .border(1.dp, CueBorder, CueShape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pebble leans the way it is asking you to go.
            Pebble(
                size = PebbleSize.TinyHighlight,
                mood = if (downwards) PebbleMood.Listening else PebbleMood.Idle,
                modifier = Modifier.graphicsLayer { translationY = drift.dp.toPx() },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = CueText,
                color = DotColors.Ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Spacer(Modifier.height(10.dp))

        Chevrons(
            downwards = downwards,
            modifier = Modifier.graphicsLayer { translationY = drift.dp.toPx() },
        )
    }
}

/** Two stacked chevrons, the second fainter, pointing the way. */
@Composable
private fun Chevrons(downwards: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 34.dp, height = 22.dp)) {
        val stroke = Stroke(
            width = 2.6.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val inset = 5.dp.toPx()
        val chevronWidth = size.width - inset * 2

        repeat(2) { index ->
            val verticalStart = index * (size.height * 0.5f)
            val tipY = if (downwards) verticalStart + size.height * 0.4f else verticalStart
            val baseY = if (downwards) verticalStart else verticalStart + size.height * 0.4f

            drawPath(
                Path().apply {
                    moveTo(inset, baseY)
                    lineTo(inset + chevronWidth / 2f, tipY)
                    lineTo(inset + chevronWidth, baseY)
                },
                color = DotColors.RustOutline,
                alpha = if (index == 0) 1f else 0.45f,
                style = stroke,
            )
        }
    }
}

/** How far the cue drifts, in dp. Small on purpose. */
private const val DRIFT_DP = 4f

@Preview(name = "Scroll down", showBackground = true, backgroundColor = 0xFFEFEFEF)
@Composable
private fun PreviewScrollDown() {
    ScrollCue(direction = ScrollDirection.Down, message = "Scroll down a little")
}

@Preview(name = "Scroll up", showBackground = true, backgroundColor = 0xFFEFEFEF)
@Composable
private fun PreviewScrollUp() {
    ScrollCue(direction = ScrollDirection.Up, message = "Scroll up a little")
}
