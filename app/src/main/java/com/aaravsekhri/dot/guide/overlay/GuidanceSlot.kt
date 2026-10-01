package com.aaravsekhri.dot.guide.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.guide.model.Insets
import com.aaravsekhri.dot.guide.positional.ScrollGuidance
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/**
 * Where the guidance card sits, and how it gets there.
 *
 * The card belongs to the edge away from the target, so the thing being talked
 * about is never underneath the thing talking. When the target moves from one
 * end of the screen to the other the card swaps ends, and it slides rather than
 * jumping — a short, controlled move, which is the one piece of motion in this
 * design. The outline never moves at all; it appears where the control is and
 * disappears when the page starts moving.
 *
 * Laid out inside the full-screen layer rather than in a window of its own.
 * A window would have to be repositioned to change edges, which is a jump, and
 * repositioning windows is what this whole design moved away from.
 */
@Composable
internal fun GuidanceSlot(
    guidance: GuidanceLayer?,
    insets: Insets,
    label: (ScrollGuidance) -> String,
    /** Which edge the step's own instruction card is on, and how tall it is. */
    stepCardAtBottom: Boolean,
    stepCardHeightPx: Int,
    modifier: Modifier = Modifier,
    /** Where the card comes to rest, in screen pixels, once it has measured itself. */
    onRest: (com.aaravsekhri.dot.guide.model.Bounds) -> Unit = {},
) {
    if (guidance == null) return
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current
    val screenHeight = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }

    // 0 at the top, 1 at the bottom. Animated, so swapping ends is a move
    // rather than a reappearance.
    val target = if (guidance.atBottom) 1f else 0f
    val position by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else EDGE_MOVE_MS),
        label = "guidance-edge",
    )

    // Clear of the system bars, and clear of the step's own card when they end
    // up at the same end of the screen. Two cards overlapping is worse than
    // either of them alone, and the step card is the one a person may need to
    // read or press.
    val stepCard = with(density) { stepCardHeightPx.toDp() }
    val topPadding = with(density) { insets.top.toDp() } + EDGE_MARGIN +
        (if (!stepCardAtBottom) stepCard else 0.dp)
    val bottomPadding = with(density) { insets.bottom.toDp() } + EDGE_MARGIN +
        (if (stepCardAtBottom) stepCard else 0.dp)

    // Where it rests, from its measured size and the same paddings it is
    // drawn with, so what Dot knows it covers is what it really covers —
    // not a band assumed to be tall enough.
    var measured by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    if (measured.height > 0) {
        val restTop = with(density) { topPadding.toPx() + target * (screenHeight - measured.height - topPadding.toPx() - bottomPadding.toPx()) }.toInt()
        val side = with(density) { SIDE_MARGIN.roundToPx() }
        val rest = com.aaravsekhri.dot.guide.model.Bounds(side, restTop, side + measured.width, restTop + measured.height)
        androidx.compose.runtime.SideEffect { onRest(rest) }
    }

    Box(
        modifier
            .fillMaxSize()
            .padding(
                start = SIDE_MARGIN,
                end = SIDE_MARGIN,
                top = topPadding,
                bottom = bottomPadding,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        GuidanceCard(
            message = guidance.message,
            text = label(guidance.message),
            animate = guidance.animate,
            modifier = Modifier.onSizeChanged { measured = it }.graphicsLayer {
                // Travelling between the two edges of the padded area. Read in
                // the layer block, so the slide costs a transform rather than a
                // re-layout of the card on every frame of it.
                translationY = position * (screenHeight - size.height -
                    topPadding.toPx() - bottomPadding.toPx())
            },
        )
    }
}

/** Short and controlled: long enough to read as a move, not long enough to wait for. */
private const val EDGE_MOVE_MS = 220

/** Kept clear of the very edge so the card never sits under a system bar. */
private val EDGE_MARGIN = 16.dp
private val SIDE_MARGIN = 20.dp
