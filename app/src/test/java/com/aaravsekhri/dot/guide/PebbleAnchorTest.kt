package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.overlay.PebbleAnchor
import com.aaravsekhri.dot.guide.overlay.PebbleCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Now that the outline covers a whole setting, it contains the words that
 * explain the setting — so where Pebble sits is the difference between marking
 * something and sitting on top of it.
 */
class PebbleAnchorTest {

    private val viewport = Bounds(0, 100, 1080, 2300)
    private val size = 100
    private val straddle = 50

    /** A Font size setting: left-aligned title and subtitle above a slider. */
    private val region = Bounds(40, 1350, 1040, 1730)
    private val title = Bounds(60, 1400, 500, 1480)
    private val subtitle = Bounds(60, 1490, 900, 1560)
    private val slider = Bounds(170, 1590, 910, 1680)
    private val contents = listOf(title, subtitle, slider)

    @Test
    fun `Pebble prefers the top-right, away from left-aligned text`() {
        val placement = PebbleAnchor.choose(region, viewport, contents, size, straddle)

        assertEquals(PebbleCorner.TopEnd, placement.corner)
    }

    @Test
    fun `Pebble does not cover the title`() {
        val placement = PebbleAnchor.choose(region, viewport, contents, size, straddle)

        assertTrue("clear of the title", !placement.bounds.intersects(title))
    }

    @Test
    fun `Pebble does not cover the subtitle`() {
        val placement = PebbleAnchor.choose(region, viewport, contents, size, straddle)

        assertTrue("clear of the subtitle", !placement.bounds.intersects(subtitle))
    }

    @Test
    fun `Pebble does not cover the control itself`() {
        val placement = PebbleAnchor.choose(region, viewport, contents, size, straddle)

        assertTrue("clear of the slider", !placement.bounds.intersects(slider))
    }

    @Test
    fun `Pebble moves to another corner when the preferred one is occupied`() {
        // A setting whose text runs the full width, so the top-right is taken.
        val wideTitle = Bounds(40, 1350, 1040, 1450)
        val placement = PebbleAnchor.choose(
            region,
            viewport,
            listOf(wideTitle),
            size,
            straddle,
        )

        assertTrue("found a clear corner", !placement.bounds.intersects(wideTitle))
    }

    @Test
    fun `Pebble keeps its corner when the target reaches the top edge`() {
        val atTop = Bounds(40, 110, 1040, 400)

        val placement = PebbleAnchor.choose(atTop, viewport, emptyList(), size, straddle)

        // Deliberately allowed off the edge, to be clipped with the outline.
        // Sliding Pebble down to stay visible would leave it sitting inside a
        // row it is supposed to be marking the corner of, and during a scroll
        // it would creep along the row while the outline moved past it.
        assertEquals(atTop.top - straddle, placement.bounds.top)
        assertEquals(size, placement.bounds.height)
    }

    @Test
    fun `Pebble keeps its corner when the target runs to the right edge`() {
        val atEdge = Bounds(40, 1000, 1080, 1300)

        val placement = PebbleAnchor.choose(atEdge, viewport, emptyList(), size, straddle)

        assertEquals(atEdge.right - size + straddle, placement.bounds.left)
        assertEquals(size, placement.bounds.width)
    }

    @Test
    fun `an unavoidable overlap picks the least bad corner rather than giving up`() {
        // Text filling the whole region: no corner is clean.
        val everywhere = listOf(Bounds(0, 1300, 1080, 1780))

        val placement = PebbleAnchor.choose(region, viewport, everywhere, size, straddle)

        assertEquals(size, placement.bounds.width)
        assertEquals(size, placement.bounds.height)
    }
}
