package com.aaravsekhri.dot.guide.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.overlay.PebbleCorner

/**
 * The target's true shape, which nothing downstream may change.
 *
 * Two things made the outline shrink near an edge, and only one of them was
 * in the renderer.
 *
 * The renderer used to clamp each edge into the viewport independently, so a
 * row halfway off the top had its top pinned to the boundary while its bottom
 * stayed put. That is fixed by splitting shape from position: motion can only
 * produce a [TargetPose], and a pose has no width.
 *
 * The other cause is upstream and is the reason the first fix did not hold.
 * `AccessibilityNodeInfo.getBoundsInScreen` reports the *visible* rectangle,
 * so a partly scrolled-off row genuinely arrives here already shortened. Taken
 * at face value that becomes a new, smaller canonical height, and the outline
 * shrinks with the row. [reconstructFrom] exists to refuse that: once a
 * target's real size is known, a later clipped reading is put back to full
 * size rather than believed.
 *
 * [pebbleAnchorOffsets] holds every corner up front so the anchor resolver can
 * choose a visible one without recomputing geometry, and so Pebble is always
 * carried by the same translation as the outline.
 */
data class CanonicalTargetGeometry(
    val widthPx: Float,
    val heightPx: Float,
    val pebbleAnchorOffsets: Map<PebbleCorner, Offset>,
) {
    /** The outline's true screen rectangle at [pose], clipped by nothing. */
    fun fullRect(pose: TargetPose): Rect =
        Rect(pose.leftPx, pose.topPx, pose.leftPx + widthPx, pose.topPx + heightPx)

    /** Pebble's rectangle on [corner], carried by the same translation. */
    fun pebbleRect(pose: TargetPose, corner: PebbleCorner, size: Float): Rect? {
        val offset = pebbleAnchorOffsets[corner] ?: return null
        val left = pose.leftPx + offset.x
        val top = pose.topPx + offset.y
        return Rect(left, top, left + size, top + size)
    }

    /**
     * Whether [reported] is plausibly this same shape, allowing for a row that
     * is partly off screen and therefore reported shorter than it is.
     */
    fun matches(reported: Bounds, tolerancePx: Float = MATCH_TOLERANCE_PX): Boolean {
        if (kotlin.math.abs(reported.width - widthPx) > tolerancePx) return false
        // Shorter is expected when clipped; taller never is.
        return reported.height <= heightPx + tolerancePx
    }

    companion object {
        const val MATCH_TOLERANCE_PX = 8f

        fun of(bounds: Bounds, anchors: Map<PebbleCorner, Offset>) = CanonicalTargetGeometry(
            widthPx = bounds.width.toFloat(),
            heightPx = bounds.height.toFloat(),
            pebbleAnchorOffsets = anchors,
        )
    }
}

/** Where the canonical shape sits. The only thing that may change while tracking. */
data class TargetPose(val leftPx: Float, val topPx: Float) {
    fun translatedBy(dx: Float, dy: Float) = TargetPose(leftPx + dx, topPx + dy)

    companion object {
        fun of(bounds: Bounds) = TargetPose(bounds.left.toFloat(), bounds.top.toFloat())
    }
}

/**
 * The pose a clipped reading really describes.
 *
 * Android reports only the visible part of a partly scrolled row. Which edge
 * was cut decides how to put it back: a row clipped at the top still has its
 * true bottom, so the full top is the bottom minus the canonical height, and
 * the reverse for one clipped at the bottom.
 */
fun CanonicalTargetGeometry.reconstructFrom(reported: Bounds, viewport: Bounds): TargetPose {
    val clippedAtTop = reported.top <= viewport.top && reported.height < heightPx - 1f
    val clippedAtBottom = reported.bottom >= viewport.bottom && reported.height < heightPx - 1f

    val top = when {
        clippedAtTop -> reported.bottom - heightPx
        clippedAtBottom -> reported.top.toFloat()
        else -> reported.top.toFloat()
    }
    val left = when {
        reported.left <= viewport.left && reported.width < widthPx - 1f ->
            reported.right - widthPx
        else -> reported.left.toFloat()
    }
    return TargetPose(left, top)
}

/**
 * Whether the outline may be drawn at all.
 *
 * A rectangle that cannot be shown to sit on its row is not a slightly
 * imprecise highlight, it is a confident instruction to tap the wrong thing.
 */
enum class AlignmentState {
    ALIGNED,
    PREDICTED_HIGH_CONFIDENCE,
    STALE,
    OCCLUDED,
    OFFSCREEN,
    LOST,
    ;

    val drawable: Boolean get() = this == ALIGNED || this == PREDICTED_HIGH_CONFIDENCE
}
