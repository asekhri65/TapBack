package com.aaravsekhri.dot.guide.overlay

import com.aaravsekhri.dot.guide.model.Bounds

/** Which corner of the outline Pebble is sitting on. */
enum class PebbleCorner { TopEnd, TopStart, BottomEnd, BottomStart }

/** Where Pebble goes, and which corner that turned out to be. */
data class PebblePlacement(val bounds: Bounds, val corner: PebbleCorner)

/**
 * Chooses a corner for Pebble that does not cover anything worth reading.
 *
 * Once a highlight covers a whole setting rather than a single widget, the
 * outline contains the setting's title and its line of explanation. Pebble sits
 * on the edge of that outline, and where it sits matters: the top-left corner
 * lands squarely on a left-aligned title.
 *
 * So the top-right is tried first, and each candidate is checked against the
 * text it might cover before being accepted. Pure geometry, so the rule is
 * unit tested rather than judged by eye.
 */
object PebbleAnchor {

    /**
     * @param region the outline Pebble is perching on.
     * @param viewport the area it must stay inside.
     * @param avoid bounds of anything Pebble must not cover — the setting's
     *   own title, description and controls.
     * @param size Pebble's width and height in pixels.
     * @param straddle how far Pebble sits outside the outline's edge.
     */
    fun choose(
        region: Bounds,
        viewport: Bounds,
        avoid: List<Bounds>,
        size: Int,
        straddle: Int,
    ): PebblePlacement {
        // Corners are taken where they fall. They are deliberately no longer
        // nudged back inside the viewport: sliding Pebble along a row to keep
        // it on screen breaks the one thing it is for, which is indicating a
        // particular corner of a particular control. A corner that is off the
        // edge is clipped with the rest of the outline.
        val candidates = listOf(
            PebbleCorner.TopEnd to topEnd(region, size, straddle),
            PebbleCorner.TopStart to topStart(region, size, straddle),
            PebbleCorner.BottomEnd to bottomEnd(region, size, straddle),
            PebbleCorner.BottomStart to bottomStart(region, size, straddle),
        )

        // The first corner that covers nothing wins. Top-right is tried first
        // because titles and descriptions are left-aligned.
        candidates.forEach { (corner, bounds) ->
            if (avoid.none { bounds.intersects(it) }) {
                return PebblePlacement(bounds, corner)
            }
        }

        // Nothing was clean — a setting whose text fills its whole area. Take
        // the corner that covers least, so Pebble clips an edge rather than
        // sitting in the middle of a sentence.
        val leastBad = candidates.minByOrNull { (_, bounds) ->
            avoid.sumOf { overlapArea(bounds, it) }
        } ?: candidates.first()

        return PebblePlacement(leastBad.second, leastBad.first)
    }

    private fun topEnd(region: Bounds, size: Int, straddle: Int): Bounds {
        val left = region.right - size + straddle
        val top = region.top - straddle
        return Bounds(left, top, left + size, top + size)
    }

    private fun topStart(region: Bounds, size: Int, straddle: Int): Bounds {
        val left = region.left - straddle
        val top = region.top - straddle
        return Bounds(left, top, left + size, top + size)
    }

    private fun bottomEnd(region: Bounds, size: Int, straddle: Int): Bounds {
        val left = region.right - size + straddle
        val top = region.bottom - size + straddle
        return Bounds(left, top, left + size, top + size)
    }

    private fun bottomStart(region: Bounds, size: Int, straddle: Int): Bounds {
        val left = region.left - straddle
        val top = region.bottom - size + straddle
        return Bounds(left, top, left + size, top + size)
    }

    private fun overlapArea(a: Bounds, b: Bounds): Int {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return width * height
    }
}
