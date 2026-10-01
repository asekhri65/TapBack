package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.overlay.PebbleCorner
import com.aaravsekhri.dot.guide.overlay.VisiblePebbleAnchorResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Pebble can sit and still be seen.
 *
 * The rule these enforce is blunt on purpose: if Pebble cannot be put
 * somewhere a person will see it, the outline is not drawn either. An orange
 * rectangle with nothing to explain it is decoration, and decoration around a
 * control nobody has been told to tap is worse than a cue.
 */
class VisiblePebbleAnchorTest {

    private val viewport = Bounds(0, 135, 1170, 2400)
    private val size = 102
    private val straddle = 51

    private fun resolve(
        target: Bounds,
        avoid: List<Bounds> = emptyList(),
        occluders: List<Bounds> = emptyList(),
    ) = VisiblePebbleAnchorResolver.resolve(target, viewport, avoid, occluders, size, straddle)

    @Test
    fun `a target in clear space gets the preferred top-right corner`() {
        val anchor = resolve(Bounds(0, 1200, 1170, 1517))

        assertNotNull(anchor)
        assertEquals(PebbleCorner.TopEnd, anchor!!.corner)
    }

    @Test
    fun `a target clipped at the top falls back to a corner that is on screen`() {
        // Its top is 200 pixels above the viewport, so the top corners are gone.
        val target = Bounds(0, viewport.top - 200, 1170, viewport.top + 117)

        val anchor = resolve(target)

        assertNotNull("must find somewhere visible", anchor)
        assertTrue(
            "must not choose a corner that is off the top",
            anchor!!.bounds.bottom > viewport.top,
        )
    }

    @Test
    fun `a target clipped at the bottom falls back to an upper corner`() {
        val target = Bounds(0, viewport.bottom - 117, 1170, viewport.bottom + 200)

        val anchor = resolve(target)

        assertNotNull(anchor)
        assertEquals(PebbleCorner.TopEnd, anchor!!.corner)
    }

    @Test
    fun `Pebble is never placed where the instruction card would hide it`() {
        val target = Bounds(0, 1200, 1170, 1517)
        // A card covering the whole upper area, including both top corners.
        val card = Bounds(0, 135, 1170, 1300)

        val anchor = resolve(target, occluders = listOf(card))

        assertNotNull(anchor)
        assertTrue(
            "chose a corner behind the card",
            !card.intersects(anchor!!.bounds),
        )
    }

    @Test
    fun `Pebble stays on the outline it belongs to`() {
        val target = Bounds(0, 1200, 1170, 1517)

        val anchor = resolve(target)!!

        assertTrue(
            "must touch its own target",
            anchor.bounds.intersects(target.inflate(8)),
        )
    }

    @Test
    fun `a target entirely off the top has no valid anchor`() {
        val target = Bounds(0, -600, 1170, -283)

        assertNull(resolve(target))
    }

    @Test
    fun `a target buried under the card has no valid anchor`() {
        val target = Bounds(0, 1200, 1170, 1517)
        val card = Bounds(0, 1000, 1170, 1800)

        assertNull(resolve(target, occluders = listOf(card)))
    }

    @Test
    fun `covering a few words is preferred to not being on screen at all`() {
        val target = Bounds(0, 1200, 1170, 1517)
        // Text filling the whole row: no corner is clean.
        val everywhere = listOf(Bounds(0, 1200, 1170, 1517))

        val anchor = resolve(target, avoid = everywhere)

        assertNotNull("a covered Pebble beats an absent one", anchor)
    }

    @Test
    fun `Pebble always keeps its full size`() {
        listOf(
            Bounds(0, viewport.top - 200, 1170, viewport.top + 117),
            Bounds(0, 1200, 1170, 1517),
            Bounds(0, viewport.bottom - 117, 1170, viewport.bottom + 200),
        ).forEach { target ->
            val anchor = resolve(target)!!
            assertEquals("at $target", size, anchor.bounds.width)
            assertEquals("at $target", size, anchor.bounds.height)
        }
    }
}
