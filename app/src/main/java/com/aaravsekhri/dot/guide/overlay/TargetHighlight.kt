package com.aaravsekhri.dot.guide.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.ui.pebble.Pebble
import com.aaravsekhri.dot.ui.pebble.PebbleMood
import com.aaravsekhri.dot.ui.pebble.PebbleSize
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.util.DotDuration
import com.aaravsekhri.dot.ui.util.DotEasing
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/** Geometry of the highlight, shared by placement and drawing. */
object HighlightMetrics {
    /**
     * How far outside the control the outline sits. Restrained: enough to
     * clear the control's own border, not so much that it reaches the next
     * one — with the outline now going round a field's whole box, eight was
     * more than the box needed.
     */
    val Padding: Dp = 6.dp
    val CornerRadius: Dp = 20.dp
    val StrokeWidth: Dp = 2.dp

    /** Dash length of the outline round information to read. */
    val ReadingDash: Dp = 8.dp

    /** Pebble's size where it perches on the outline. */
    val PebbleSizeDp: Dp = 34.dp

    /** How far Pebble sits outside the outline's edge. */
    val PebbleStraddle: Dp = 17.dp
}

/**
 * The rust outline around the setting being pointed at, with Pebble on one
 * corner of it.
 *
 * Drawn in screen coordinates inside a window that covers the display and
 * cannot be touched. Two things follow from that, both deliberate:
 *
 * Every tap and every scroll passes through to the app underneath — the window
 * carries `FLAG_NOT_TOUCHABLE`, so it is incapable of taking input.
 *
 * And because the window never moves, following a target is a redraw inside a
 * live composition rather than a series of window repositions.
 *
 * The position is drawn exactly as given, with no animation between updates.
 * An earlier version eased each new rectangle over seventy milliseconds, which
 * seemed gentle in isolation and was in fact the whole problem: every scroll
 * started a fresh catch-up from wherever the outline had got to, so the
 * content moved and the outline followed afterwards. Small scrolls produced
 * small late corrections and large scrolls produced large ones. Whatever
 * position arrives here is already the best estimate of where the row is right
 * now, and the only correct thing to do with it is draw it.
 *
 * @param targetRect the outline's true screen rectangle. Never clamped to the
 *   viewport: parts outside it are clipped by the canvas, so a row leaving the
 *   screen slides out rather than shrinking.
 * @param pebbleRect where Pebble goes, carried by the same translation.
 * @param appearanceKey changes only for a genuinely different target, so
 *   following one that has moved never replays its entrance.
 * @param occluders Dot's own layers — the instruction card — cut out of the
 *   drawing, so no part of the outline is painted over furniture Dot put there.
 * @param clip the scrolling area the outline's pixels are confined to. The
 *   rectangle itself is never intersected with it: at half visible a person
 *   sees half of a full-size outline, which is what a row sliding under a
 *   toolbar looks like, rather than a small complete one.
 */
@Composable
internal fun TargetHighlight(
    layer: androidx.compose.runtime.State<HighlightLayer?>,
    appearanceKey: Int,
    /**
     * The target's size, read in composition rather than from [layer].
     *
     * It changes only when the target does, so reading it here costs one
     * recomposition per target. Reading it from [layer] would cost one per
     * position, which is the whole thing this arrangement exists to avoid.
     */
    canonical: com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry?,
    modifier: Modifier = Modifier,
    occluders: List<Rect> = emptyList(),
    /**
     * False while Dot's card has its own emphasised action — "I'm done
     * typing my first name" — so the outline stays, restrained, and one
     * Pebble is the thing to look at, not two.
     */
    showPebble: androidx.compose.runtime.State<Boolean> = androidx.compose.runtime.mutableStateOf(true),
    /**
     * True for information to read — opening hours, an address — rather than
     * a control to tap: a dashed line with no fill and no Pebble, so "look
     * here" never looks like "tap here".
     */
    reading: androidx.compose.runtime.State<Boolean> = androidx.compose.runtime.mutableStateOf(false),
) {
    val reducedMotion = rememberReducedMotion()

    val pop = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(appearanceKey, reducedMotion) {
        if (reducedMotion) {
            pop.snapTo(1f)
        } else {
            pop.snapTo(0f)
            pop.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = DotDuration.POP_IN_PLACE,
                    easing = DotEasing.Pop,
                ),
            )
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // The list's edge and Dot's own card, applied once around
            // everything. Both are static for the whole of a scroll, so the
            // expensive part of the drawing never changes while a target moves.
            .drawWithContent {
                val current = layer.value
                val canvas = drawContext.canvas
                canvas.save()
                current?.clip?.toRect()?.let {
                    canvas.nativeCanvas.clipRect(it.left, it.top, it.right, it.bottom)
                }
                occluders.forEach {
                    canvas.nativeCanvas.clipOutRect(it.left, it.top, it.right, it.bottom)
                }
                drawContent()
                canvas.restore()
            },
    ) {
        // The outline is rasterised once, at the origin, and moved by changing
        // its layer's translation. That is the difference between a target that
        // follows a scroll and one that cannot keep up: redrawing a full-screen
        // surface for every new position cost more than a display frame, so
        // most positions were superseded before they were ever shown. A
        // translation is a transform on an already-drawn layer.
        //
        // The translation is read inside the graphicsLayer block, so a new
        // position re-runs neither composition nor layout.
        // The outline is rasterised once, at its own size, and moved by
        // changing its layer's translation. Two things matter here and both
        // were measured: a full-screen layer costs three million pixels to
        // composite every time it moves, where one the size of the row costs a
        // tenth of that; and re-drawing rather than translating cost more than
        // a display frame, so most positions were superseded before they were
        // ever shown.
        //
        // The translation is read inside the graphicsLayer block, so a new
        // position re-runs neither composition nor layout.
        if (canonical != null) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            Canvas(
                Modifier
                    .size(
                        width = with(density) { canonical.widthPx.toDp() },
                        height = with(density) { canonical.heightPx.toDp() },
                    )
                    .graphicsLayer {
                        val current = layer.value
                        translationX = current?.pose?.leftPx ?: 0f
                        translationY = current?.pose?.topPx ?: 0f
                        alpha = if (current == null) 0f else pop.value
                    },
            ) {
                val current = layer.value ?: return@Canvas

                // Debug-only, and deliberately loud. Two separate causes have
                // shrunk this rectangle before — a clamp in the renderer and a
                // clipped reading from accessibility — and both looked like a
                // rendering quirk rather than a dimension being replaced.
                if (GuideLog.isEnabled) {
                    val drawn = current.outline
                    val tolerance = 1f
                    check(abs(drawn.width - canonical.widthPx) < tolerance) {
                        "outline width ${drawn.width} != canonical ${canonical.widthPx}"
                    }
                    check(abs(drawn.height - canonical.heightPx) < tolerance) {
                        "outline height ${drawn.height} != canonical ${canonical.heightPx}"
                    }
                    DrawTrace.record(System.nanoTime(), drawn, canonical)
                }

                // Never rounder than a pill: on a short control the standard
                // radius met in the middle and pinched the ends.
                val radius = CornerRadius(minOf(HighlightMetrics.CornerRadius.toPx(), canonical.heightPx / 2f))
                if (reading.value) {
                    val dash = HighlightMetrics.ReadingDash.toPx()
                    drawRoundRect(
                        color = DotColors.RustOutline,
                        topLeft = Offset.Zero,
                        size = Size(canonical.widthPx, canonical.heightPx),
                        cornerRadius = radius,
                        style = Stroke(
                            width = HighlightMetrics.StrokeWidth.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.6f)),
                        ),
                    )
                    return@Canvas
                }
                drawRoundRect(
                    color = DotColors.HaloFill,
                    topLeft = Offset.Zero,
                    size = Size(canonical.widthPx, canonical.heightPx),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = DotColors.RustOutline,
                    topLeft = Offset.Zero,
                    size = Size(canonical.widthPx, canonical.heightPx),
                    cornerRadius = radius,
                    style = Stroke(width = HighlightMetrics.StrokeWidth.toPx()),
                )
            }
        }

        // Pebble stays on the corner it was given and clips with the outline.
        // It is not moved to a visible edge to keep it on screen: a Pebble that
        // slides along the row it is pointing at is no longer pointing at it.
        Pebble(
            size = PebbleSize.TinyHighlight,
            mood = PebbleMood.Highlighting,
            modifier = Modifier.graphicsLayer {
                val current = layer.value
                val rect = current?.pebbleRect
                if (rect == null || !showPebble.value || reading.value) {
                    alpha = 0f
                    return@graphicsLayer
                }
                translationX = rect.left
                translationY = rect.top
                val progress = pop.value
                scaleX = 0.85f + 0.15f * progress
                scaleY = 0.85f + 0.15f * progress
                alpha = progress
            },
        )
    }
}

/**
 * A record of what was actually put on screen, and when.
 *
 * Debug builds only, and deliberately cheap: six numbers into a preallocated
 * array, no allocation and no formatting. It lives inside the drawing because
 * that is the only place that knows the moment a position reached the screen —
 * recording it when the position was accepted instead measures a different,
 * earlier event and flatters the result by a frame.
 *
 * Nothing here describes what the control is or says.
 */
internal object DrawTrace {
    private const val CAPACITY = 8192
    private const val FIELDS = 6
    private val buffer = LongArray(CAPACITY * FIELDS)
    private var count = 0

    fun record(atNanos: Long, rect: Rect, canonical: com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry) {
        if (count >= CAPACITY) return
        val base = count * FIELDS
        buffer[base] = atNanos
        buffer[base + 1] = rect.left.toLong()
        buffer[base + 2] = rect.top.toLong()
        buffer[base + 3] = rect.right.toLong()
        buffer[base + 4] = rect.bottom.toLong()
        buffer[base + 5] = canonical.widthPx.toLong() * 100_000 + canonical.heightPx.toLong()
        count++
    }

    fun reset() {
        count = 0
    }

    /** Where the outline stands at this moment, in the same format. */
    fun report(layer: HighlightLayer) {
        val rect = layer.outline
        GuideLog.d("overlay") {
            "draw outline ${System.nanoTime()} ${rect.left.toInt()} ${rect.top.toInt()} " +
                "${rect.right.toInt()} ${rect.bottom.toInt()} canonical " +
                "${layer.canonical.widthPx.toInt()}x${layer.canonical.heightPx.toInt()}"
        }
    }

    /** Writes everything collected, in the format the acceptance harness reads. */
    fun dump() {
        for (index in 0 until count) {
            val base = index * FIELDS
            val packed = buffer[base + 5]
            GuideLog.d("overlay") {
                "draw outline ${buffer[base]} ${buffer[base + 1]} ${buffer[base + 2]} " +
                    "${buffer[base + 3]} ${buffer[base + 4]} canonical " +
                    "${packed / 100_000}x${packed % 100_000}"
            }
        }
        GuideLog.d("overlay") { "draw trace complete: $count frames" }
        count = 0
    }
}
