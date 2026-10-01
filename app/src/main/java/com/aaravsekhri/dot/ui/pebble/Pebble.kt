package com.aaravsekhri.dot.ui.pebble

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens

/**
 * Pebble — Dot's guide character.
 *
 * Drawn natively from the approved silhouette: an asymmetrical rounded
 * teardrop, wider and heavier at the base, with a slight shoulder up and to
 * the left. Deliberately not a circle.
 *
 * The face is "Pebble Gaze with catchlight": two warm off-white eye discs, a
 * dark pupil sitting low and slightly outward inside each, and a small
 * off-white catchlight up and to the left of that pupil. The offset between
 * pupil and catchlight is what makes Pebble read as looking at you.
 */

/** Silhouette and face are authored in this square coordinate space. */
private const val VIEW_BOX = 100f

/**
 * The approved sizes Pebble appears at. Pupil and catchlight grow
 * proportionally as Pebble shrinks, so its identity survives at navigation and
 * highlight sizes instead of dissolving into featureless dots.
 */
@Immutable
enum class PebbleSize(
    val dp: Dp,
    internal val pupilRadius: Float,
    internal val catchlightRadius: Float,
) {
    /** Ask, idle — the character you tap to talk. */
    Large(DotDimens.PebbleLarge, pupilRadius = 4.6f, catchlightRadius = 1.8f),

    /** Ask, listening — Pebble draws back inside the rings. */
    Medium(DotDimens.PebbleListening, pupilRadius = 4.6f, catchlightRadius = 1.8f),

    /** Bottom navigation icon. */
    Navigation(DotDimens.PebbleNav, pupilRadius = 5.4f, catchlightRadius = 2.0f),

    /** Inline with running text, e.g. the Learn steps row. */
    Inline(DotDimens.PebbleInline, pupilRadius = 5.5f, catchlightRadius = 1.9f),

    /** Perched on a highlighted action. */
    TinyHighlight(DotDimens.PebbleHighlight, pupilRadius = 5.5f, catchlightRadius = 1.9f),
}

/** What Pebble is doing. Affects posture, never identity. */
@Immutable
enum class PebbleMood {
    /** At rest, facing forward. */
    Idle,

    /** Leaning in slightly while it takes in what you are saying. */
    Listening,

    /** Perched beside the thing it wants you to notice. */
    Highlighting,
}

/** Degrees Pebble tips by, about a pivot low in its body so it leans from the base. */
private fun PebbleMood.leanDegrees(): Float = if (this == PebbleMood.Listening) 4f else 0f

/**
 * @param contentDescription what Pebble means here. Pass null for the purely
 *   decorative instances — a navigation icon whose label already says "Ask",
 *   or a mark inside an illustration — so it is not announced twice.
 */
@Composable
fun Pebble(
    modifier: Modifier = Modifier,
    size: PebbleSize = PebbleSize.Large,
    mood: PebbleMood = PebbleMood.Idle,
    contentDescription: String? = null,
) {
    val description = contentDescription
    val semantics = Modifier.clearAndSetSemantics {
        if (description != null) this.contentDescription = description
    }
    Canvas(modifier.size(size.dp).then(semantics)) {
        drawPebbleMark(
            topLeft = Offset.Zero,
            diameter = this.size.minDimension,
            pupilRadius = size.pupilRadius,
            catchlightRadius = size.catchlightRadius,
            leanDegrees = mood.leanDegrees(),
        )
    }
}

/**
 * Draws Pebble into an arbitrary square within the current [DrawScope].
 *
 * Illustrations use this to set Pebble alongside other marks in one canvas.
 * [pupilRadius] and [catchlightRadius] are in board units (of 100), matching
 * the values [PebbleSize] carries.
 */
internal fun DrawScope.drawPebbleMark(
    topLeft: Offset,
    diameter: Float,
    pupilRadius: Float,
    catchlightRadius: Float,
    leanDegrees: Float = 0f,
    /**
     * Where Pebble is looking, in board units: pupil and catchlight move
     * together, so it is still the approved gaze, turned. Zero is the
     * approved resting pose.
     */
    gazeX: Float = 0f,
    gazeY: Float = 0f,
    /** 1 for open, towards 0 through a blink. The eye closes about its own centre. */
    eyeOpen: Float = 1f,
) {
    val unit = diameter / VIEW_BOX
    fun px(vx: Float, vy: Float) = Offset(topLeft.x + vx * unit, topLeft.y + vy * unit)

    rotate(degrees = leanDegrees, pivot = px(50f, 92f)) {
        drawPath(path = pebbleSilhouette(topLeft, unit), color = DotColors.Rust)

        // left and right eye: disc centre, pupil centre, catchlight centre (x only —
        // all three share a fixed y, so the gaze is consistent at every size).
        listOf(
            Triple(37f, 38.0f, 36.5f),
            Triple(63f, 64.0f, 62.5f),
        ).forEach { (discX, pupilX, catchX) ->
            scale(scaleX = 1f, scaleY = eyeOpen.coerceIn(0.08f, 1f), pivot = px(discX, 47f)) {
                drawCircle(DotColors.EyeWhite, radius = 9.5f * unit, center = px(discX, 47f))
                drawCircle(DotColors.Ink, radius = pupilRadius * unit, center = px(pupilX + gazeX, 49.5f + gazeY))
                drawCircle(
                    DotColors.EyeWhite,
                    radius = catchlightRadius * unit,
                    center = px(catchX + gazeX, 48.1f + gazeY),
                )
            }
        }
    }
}

/**
 * The approved silhouette: a broad, slightly tilted teardrop that settles
 * heavier on its left base and rises to a soft shoulder at the top.
 */
private fun pebbleSilhouette(topLeft: Offset, unit: Float): Path {
    fun x(v: Float) = topLeft.x + v * unit
    fun y(v: Float) = topLeft.y + v * unit
    return Path().apply {
        moveTo(x(46f), y(5f))
        cubicTo(x(61f), y(5f), x(71f), y(11f), x(79f), y(21f))
        cubicTo(x(87f), y(31f), x(94f), y(42f), x(94f), y(57f))
        cubicTo(x(94f), y(78f), x(76f), y(95f), x(52f), y(95f))
        cubicTo(x(28f), y(95f), x(8f), y(79f), x(8f), y(57f))
        cubicTo(x(8f), y(42f), x(14f), y(30f), x(24f), y(20f))
        cubicTo(x(31f), y(13f), x(36f), y(5f), x(46f), y(5f))
        close()
    }
}

@Preview(name = "Pebble — large, idle", showBackground = true, backgroundColor = 0xFFFBFBFC)
@Composable
private fun PreviewPebbleLarge() {
    Pebble(size = PebbleSize.Large, contentDescription = "Your guide")
}

@Preview(name = "Pebble — medium, listening", showBackground = true, backgroundColor = 0xFFFBFBFC)
@Composable
private fun PreviewPebbleListening() {
    Pebble(size = PebbleSize.Medium, mood = PebbleMood.Listening, contentDescription = "Your guide")
}

@Preview(name = "Pebble — tiny highlight", showBackground = true, backgroundColor = 0xFFFBFBFC)
@Composable
private fun PreviewPebbleHighlight() {
    Pebble(size = PebbleSize.TinyHighlight, mood = PebbleMood.Highlighting)
}

@Preview(name = "Pebble — navigation", showBackground = true, backgroundColor = 0xFFFBFBFC)
@Composable
private fun PreviewPebbleNavigation() {
    Pebble(size = PebbleSize.Navigation)
}

@Preview(name = "Pebble — inline", showBackground = true, backgroundColor = 0xFFFBFBFC)
@Composable
private fun PreviewPebbleInline() {
    Pebble(size = PebbleSize.Inline)
}
