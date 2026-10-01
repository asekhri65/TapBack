package com.aaravsekhri.dot.guide.overlay


import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aaravsekhri.dot.guide.positional.ScrollGuidance
import com.aaravsekhri.dot.ui.pebble.Pebble
import com.aaravsekhri.dot.ui.pebble.PebbleMood
import com.aaravsekhri.dot.ui.pebble.PebbleSize
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.SchibstedGrotesk
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/**
 * What Dot says while the page is moving.
 *
 * One short sentence and one picture. It replaces the outline for the duration
 * of a scroll, because an outline drawn from a position that is already out of
 * date points at the wrong row, and a person who is moving a list does not need
 * a rectangle — they need to know whether they have arrived.
 *
 * A single card that changes its wording, never a card that comes and goes:
 * the message crossfades inside it and Pebble stays put, so a run of scrolling
 * shows one calm object rather than a sequence of things appearing.
 *
 * Drawn in a window that cannot be touched, so the scroll it is talking about
 * passes straight through it and the control underneath stays tappable.
 */
private val CardShape = RoundedCornerShape(24.dp)
private val CardBorder = Color(0xFFE7E9EC)

private val CardText = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.W600,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.012).em,
)

@Composable
fun GuidanceCard(
    message: ScrollGuidance,
    text: String,
    /** True only while the page is moving; see [GuidanceSlot]. */
    animate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()

    // Nothing on the card itself moves — not the card, not Pebble. The one
    // moving thing is the gesture demonstration, which shows the finger.
    Row(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = CardShape,
                clip = false,
                ambientColor = DotColors.Ink.copy(alpha = 0.30f),
                spotColor = DotColors.Ink.copy(alpha = 0.30f),
            )
            .background(DotColors.Surface, CardShape)
            .border(1.dp, CardBorder, CardShape)
            .padding(horizontal = 22.dp, vertical = 16.dp)
            // One announcement per message, not one per scroll event: the text
            // only changes when the guidance does, so TalkBack speaks when
            // there is something new to say and stays quiet otherwise.
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Pebble(
            size = PebbleSize.TinyHighlight,
            mood = message.pebbleMood(),
        )
        Spacer(Modifier.width(14.dp))

        // The wording crossfades in place. Deliberately Crossfade rather than
        // AnimatedContent: the latter lays the outgoing and incoming text side
        // by side while it runs, which made the card visibly widen and leave a
        // ghost of its own edge every time the message changed.
        Crossfade(
            targetState = text,
            animationSpec = tween(if (reducedMotion) 0 else CROSSFADE_MS),
            label = "guidance-text",
        ) { shown ->
            Text(text = shown, style = CardText, color = DotColors.Ink)
        }

        // The same demonstration as every other gesture Dot asks for. The
        // content lies below, so the finger — and the page with it — goes
        // up; the picture shows exactly that. "Stop" messages show nothing
        // moving, because nothing should.
        message.finger()?.let { finger ->
            Spacer(Modifier.width(14.dp))
            GestureIllustration(
                gesture = com.aaravsekhri.dot.guide.ai.Gesture.Slide(finger),
                animate = animate,
            )
        }
    }
}

/** Which way the finger goes for this message, or null when it should be still. */
private fun ScrollGuidance.finger(): com.aaravsekhri.dot.guide.ai.FingerDirection? = when (this) {
    ScrollGuidance.KeepScrollingDown -> com.aaravsekhri.dot.guide.ai.FingerDirection.Up
    ScrollGuidance.ScrollUp -> com.aaravsekhri.dot.guide.ai.FingerDirection.Down
    ScrollGuidance.StopHere, ScrollGuidance.StopForAMoment -> null
}

private fun ScrollGuidance.pebbleMood(): PebbleMood = when (this) {
    // Leaning into the movement it is asking for.
    ScrollGuidance.KeepScrollingDown, ScrollGuidance.ScrollUp -> PebbleMood.Listening
    // Arrived: Pebble settles and looks at the thing.
    ScrollGuidance.StopHere -> PebbleMood.Highlighting
    // Nothing to point at yet, and no pretending otherwise.
    ScrollGuidance.StopForAMoment -> PebbleMood.Idle
}

/** Long enough to read as a change, short enough not to feel like waiting. */
private const val CROSSFADE_MS = 160

@Preview(name = "Keep scrolling down", showBackground = true, backgroundColor = 0xFFEFEFEF)
@Composable
private fun PreviewDown() {
    GuidanceCard(ScrollGuidance.KeepScrollingDown, "Keep scrolling down")
}

@Preview(name = "Stop here", showBackground = true, backgroundColor = 0xFFEFEFEF)
@Composable
private fun PreviewStop() {
    GuidanceCard(ScrollGuidance.StopHere, "Stop here")
}

@Preview(name = "Uncertain", showBackground = true, backgroundColor = 0xFFEFEFEF)
@Composable
private fun PreviewUncertain() {
    GuidanceCard(ScrollGuidance.StopForAMoment, "Stop for a moment")
}
