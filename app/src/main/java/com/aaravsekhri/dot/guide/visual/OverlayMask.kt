package com.aaravsekhri.dot.guide.visual

import com.aaravsekhri.dot.guide.model.Bounds
import kotlin.math.roundToInt

/**
 * The parts of the captured screen that are Dot's own drawing.
 *
 * MediaProjection captures the display as composited, so on a device where
 * Dot's accessibility overlay is included, the orange outline, Pebble, the
 * chevrons and the instruction card all appear in the frames. Left alone, the
 * tracker would happily lock onto the most distinctive thing in view — which
 * would be its own outline, moving perfectly with the thing it is supposed to
 * be measuring, and reporting beautiful confident zero motion forever.
 *
 * So every rectangle Dot draws is excluded from matching. What the emulator
 * actually captures is measured rather than assumed; the mask is applied
 * either way, because it costs nothing when the overlay is absent.
 */
class OverlayMask {

    private val regions = mutableListOf<Bounds>()

    /** Replaces the mask with Dot's current overlay geometry, in screen pixels. */
    fun set(vararg overlayRegions: Bounds?) {
        regions.clear()
        overlayRegions.filterNotNull().filter { !it.isEmpty }.forEach(regions::add)
    }

    fun clear() = regions.clear()

    /** The mask in reduced-frame coordinates, padded to cover anti-aliasing. */
    fun inFrameCoordinates(
        transform: CaptureTransform,
        paddingPx: Int = DEFAULT_PADDING_PX,
    ): List<FrameRect> = regions.map { region ->
        transform.screenToCapture(region.inflate(paddingPx))
    }

    /** True when [candidate] would touch anything Dot drew. */
    fun excludes(candidate: Bounds): Boolean = regions.any { it.intersects(candidate) }

    val isEmpty: Boolean get() = regions.isEmpty()

    private companion object {
        /** A little beyond the drawn edge, to cover the outline's own stroke. */
        const val DEFAULT_PADDING_PX = 12
    }
}
