package com.aaravsekhri.dot.guide.overlay

import androidx.compose.ui.geometry.Offset
import com.aaravsekhri.dot.guide.model.Bounds

/**
 * Where Pebble can sit and still be seen.
 *
 * The old anchor chooser answered a different question — which corner covers
 * the least text — and answered it against the whole target rectangle whether
 * or not that rectangle was on screen. When a row scrolled to the top edge,
 * the preferred top-right corner went off the top with it and Pebble simply
 * was not there: an orange rectangle with nothing to explain it.
 *
 * This resolves the question that actually matters. A corner is only a
 * candidate if Pebble would be *wholly* visible there, would still touch the
 * outline it belongs to, and would not sit on the words the person needs to
 * read or behind Dot's own card or the keyboard. Corners are tried in the
 * approved order; then the same corners drawn back inside the screen; then
 * the visible side edges, so a row clipped top and bottom still has
 * somewhere; and last, tucked just inside a corner of the outline. Pebble
 * moves; the outline never changes shape to make room for it.
 *
 * If nothing qualifies the answer is null, and the caller must show no outline
 * at all rather than one without Pebble. That is deliberate: the outline alone
 * is decoration, and decoration around a control nobody has been told to tap
 * is worse than a cue that says which way to scroll.
 */
object VisiblePebbleAnchorResolver {

    /** A resolved anchor: where Pebble goes, and on which corner. */
    data class Anchor(val bounds: Bounds, val corner: PebbleCorner)

    /**
     * @param target the target's *full* rectangle, not its visible part.
     * @param viewport the usable screen area.
     * @param avoid text inside the target that Pebble should not cover.
     * @param occluders Dot's own layers, which hide whatever is behind them.
     */
    fun resolve(
        target: Bounds,
        viewport: Bounds,
        avoid: List<Bounds>,
        occluders: List<Bounds>,
        size: Int,
        straddle: Int,
    ): Anchor? {
        val candidates = orderedCandidates(target, viewport, size, straddle)

        // First pass: a corner that is visible and covers nothing worth reading.
        candidates.forEach { candidate ->
            if (isVisible(candidate.bounds, viewport, size) &&
                touches(candidate.bounds, target) &&
                occluders.none { it.intersects(candidate.bounds) } &&
                avoid.none { it.intersects(candidate.bounds) }
            ) {
                return candidate
            }
        }

        // Second pass: visible and unobstructed by Dot's own furniture, even if
        // it overlaps some of the target's text. Covering a few words is a far
        // smaller problem than Pebble not being on screen.
        candidates.forEach { candidate ->
            if (isVisible(candidate.bounds, viewport, size) &&
                touches(candidate.bounds, target) &&
                occluders.none { it.intersects(candidate.bounds) }
            ) {
                return candidate
            }
        }

        return null
    }

    /** The approved order: corners first, then the visible side edges. */
    private fun orderedCandidates(
        target: Bounds,
        viewport: Bounds,
        size: Int,
        straddle: Int,
    ): List<Anchor> {
        val corners = listOf(
            Anchor(topEnd(target, size, straddle), PebbleCorner.TopEnd),
            Anchor(topStart(target, size, straddle), PebbleCorner.TopStart),
            Anchor(bottomEnd(target, size, straddle), PebbleCorner.BottomEnd),
            Anchor(bottomStart(target, size, straddle), PebbleCorner.BottomStart),
        )

        // The same corners, drawn back inside the display.
        //
        // A settings row runs the full width of the screen, so a Pebble that
        // straddles its right border sits three-quarters off the edge — a
        // sliver of orange that reads as a rendering artefact rather than as
        // Pebble. Pulled inward it still overlaps the border it belongs to,
        // and it is unmistakably there. Only the horizontal position is
        // adjusted: moving it vertically would let it wander along the row.
        val pulledIn = corners.map { anchor ->
            val left = anchor.bounds.left.coerceIn(
                viewport.left,
                (viewport.right - size).coerceAtLeast(viewport.left),
            )
            Anchor(
                Bounds(left, anchor.bounds.top, left + size, anchor.bounds.bottom),
                anchor.corner,
            )
        }

        // The middle of whichever part of the target is on screen, so a row
        // clipped at both ends still has a place for Pebble that is on it.
        val visibleTop = maxOf(target.top, viewport.top)
        val visibleBottom = minOf(target.bottom, viewport.bottom)
        val midY = (visibleTop + visibleBottom) / 2 - size / 2
        val edges = listOf(
            Anchor(
                Bounds(target.right - size + straddle, midY, target.right + straddle, midY + size),
                PebbleCorner.TopEnd,
            ),
            Anchor(
                Bounds(target.left - straddle, midY, target.left - straddle + size, midY + size),
                PebbleCorner.TopStart,
            ),
        )
        // Tucked just inside the outline's own corners: for a control that
        // fills the screen edge to edge, where every straddling position is
        // partly off it.
        val tucked = listOf(
            Anchor(Bounds(target.right - size, target.top, target.right, target.top + size), PebbleCorner.TopEnd),
            Anchor(Bounds(target.left, target.top, target.left + size, target.top + size), PebbleCorner.TopStart),
            Anchor(Bounds(target.right - size, target.bottom - size, target.right, target.bottom), PebbleCorner.BottomEnd),
            Anchor(Bounds(target.left, target.bottom - size, target.left + size, target.bottom), PebbleCorner.BottomStart),
        ).map { anchor ->
            val left = anchor.bounds.left.coerceIn(viewport.left, (viewport.right - size).coerceAtLeast(viewport.left))
            val top = anchor.bounds.top.coerceIn(viewport.top, (viewport.bottom - size).coerceAtLeast(viewport.top))
            Anchor(Bounds(left, top, left + size, top + size), anchor.corner)
        }
        return corners + pulledIn + edges + tucked
    }

    /**
     * All of Pebble on screen.
     *
     * A Pebble cut off by the screen's edge read, on a real phone, as a
     * rendering fault rather than as Pebble; the point is that the person can
     * see what is pointing at the control, whole.
     */
    private fun isVisible(bounds: Bounds, viewport: Bounds, size: Int): Boolean =
        bounds.width >= size && bounds.height >= size && viewport.contains(bounds)

    /** Pebble has to be on the outline it belongs to, not merely near it. */
    private fun touches(bounds: Bounds, target: Bounds): Boolean =
        bounds.intersects(target.inflate(TOUCH_SLACK_PX))

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

    /** Pebble straddles the border, so "touching" allows a little overhang. */
    private const val TOUCH_SLACK_PX = 4
}
