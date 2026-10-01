package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.overlay.CardDensity
import com.aaravsekhri.dot.guide.overlay.CardEdge
import com.aaravsekhri.dot.guide.overlay.CardPlacement
import com.aaravsekhri.dot.guide.overlay.CardSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the instruction card goes.
 *
 * The rule that matters most: the card is never silently on top of the thing
 * it tells somebody to press. It moves, it gets smaller, or it says what to
 * do — in that order.
 */
class CardPlacementTest {

    // A 1080 x 2400 phone less its bars and an edge gap.
    private val area = Bounds(32, 160, 1048, 2280)
    private val heights = mapOf(CardDensity.Full to 520, CardDensity.Compact to 330)

    private fun card(spot: CardSpot, h: Int = heights.getValue(spot.density)) =
        Bounds(area.left, spot.top, area.right, spot.top + h)

    private fun button(top: Int, bottom: Int) = Bounds(60, top, 1020, bottom)

    @Test
    fun `a button near the bottom sends the card to the top`() {
        val post = button(2100, 2240)
        val r = CardPlacement.choose(area, heights, listOf(post), current = null)
        assertTrue(r.clear)
        assertEquals(area.top, r.spot.top)
        assertEquals(CardDensity.Full, r.spot.density)
        assertFalse(card(r.spot).intersects(post))
    }

    @Test
    fun `a button near the top sends the card to the bottom`() {
        val search = button(200, 330)
        val r = CardPlacement.choose(area, heights, listOf(search), current = null)
        assertEquals(area.bottom - 520, r.spot.top)
        assertFalse(card(r.spot).intersects(search))
    }

    @Test
    fun `a card that is already clear stays where it is`() {
        // At the top, clear of a button in the middle-lower half: the bottom
        // would also be "preferred", but moving it would be a hop for nothing.
        val atTop = CardSpot(CardDensity.Full, area.top)
        val r = CardPlacement.choose(area, heights, listOf(button(900, 1000)), current = atTop)
        assertEquals(atTop, r.spot)
    }

    @Test
    fun `a target that scrolls under the card moves the card, and only then`() {
        val atBottom = CardSpot(CardDensity.Full, area.bottom - 520)
        val before = CardPlacement.choose(area, heights, listOf(button(500, 620)), current = atBottom)
        assertEquals("clear: stays", atBottom, before.spot)
        // Same control, new position only: now under the card.
        val after = CardPlacement.choose(area, heights, listOf(button(1900, 2020)), current = before.spot)
        assertEquals(area.top, after.spot.top)
    }

    @Test
    fun `with both edges blocked the full card uses a clear gap between controls`() {
        val high = button(300, 700)
        val low = button(1750, 2200)
        val r = CardPlacement.choose(area, heights, listOf(high, low), current = null)
        assertTrue(r.clear)
        assertEquals(CardDensity.Full, r.spot.density)
        assertFalse(card(r.spot).intersects(high))
        assertFalse(card(r.spot).intersects(low))
    }

    @Test
    fun `when no full card fits a compact card with the whole instruction does`() {
        // Two ordinary controls leave a 400px gap: too little for 520, enough for 330.
        val high = button(200, 560)
        val low = button(960, 2280)
        val r = CardPlacement.choose(area, heights, listOf(high, low), current = null)
        assertTrue(r.clear)
        assertEquals(CardDensity.Compact, r.spot.density)
        assertFalse(card(r.spot).intersects(high))
        assertFalse(card(r.spot).intersects(low))
    }

    @Test
    fun `when nothing fits it says so rather than pretending`() {
        val upper = button(170, 1200)
        val lower = button(1210, 2270)
        val r = CardPlacement.choose(area, heights, listOf(upper, lower), current = null)
        assertFalse(r.clear)
        assertEquals(CardDensity.Compact, r.spot.density)
    }

    @Test
    fun `the keyboard shrinks the space and the card goes above the field, not onto it`() {
        // Keyboard from 1500 down; a text field just above it.
        val withKeyboard = Bounds(area.left, area.top, area.right, 1492)
        val field = button(1300, 1440)
        val r = CardPlacement.choose(withKeyboard, heights, listOf(field), current = CardSpot(CardDensity.Full, 1492 - 520))
        assertTrue(r.clear)
        assertEquals(withKeyboard.top, r.spot.top)
        assertFalse(card(r.spot).intersects(field))
    }

    @Test
    fun `large text makes the card taller and the same rules still hold`() {
        val big = mapOf(CardDensity.Full to 900, CardDensity.Compact to 600)
        val middle = button(1000, 1150)
        val r = CardPlacement.choose(area, big, listOf(middle), current = null)
        assertTrue(r.clear)
        assertFalse(Bounds(area.left, r.spot.top, area.right, r.spot.top + big.getValue(r.spot.density)).intersects(middle))
    }

    @Test
    fun `a small screen in landscape still finds room or says it cannot`() {
        val landscape = Bounds(32, 60, 2300, 1020)
        val field = button(420, 560)
        val r = CardPlacement.choose(landscape, heights, listOf(field), current = null)
        if (r.clear) {
            assertFalse(Bounds(landscape.left, r.spot.top, landscape.right, r.spot.top + heights.getValue(r.spot.density)).intersects(field))
        }
    }

    @Test
    fun `a control taller than the space beside any card loses only an end, never its middle`() {
        // A drawing pad from just under the top to near the bottom.
        val pad = button(230, 2200)
        val r = CardPlacement.choose(area, heights, listOf(pad), current = null, minReachable = 290)
        assertTrue(r.clear)
        val covered = card(r.spot)
        val middle = (pad.top + pad.bottom) / 2
        assertFalse("the middle stays reachable", middle in covered.top until covered.bottom)
        assertTrue(r.reason.contains("edge of a large control"))
    }

    @Test
    fun `an ordinary control is never partly covered, even if an end would be easy to give up`() {
        val post = button(1500, 1700)
        val squeezed = Bounds(area.left, 1200, area.right, 2000) // only 800px of room
        val r = CardPlacement.choose(squeezed, heights, listOf(post), current = null, minReachable = 290)
        if (r.clear) assertFalse(card(r.spot).intersects(post))
    }

    @Test
    fun `where the person moved it is kept for that step, while it covers nothing the step needs`() {
        val pinned = CardSpot(CardDensity.Full, area.top, CardEdge.Top)
        val kept = CardPlacement.choose(area, heights, listOf(button(1500, 1600)), current = null, pinned = pinned)
        assertEquals(pinned, kept.spot)
        assertTrue(kept.clear)
    }

    @Test
    fun `a Move never wins over the control the step needs`() {
        val pinned = CardSpot(CardDensity.Full, area.top, CardEdge.Top)
        val target = button(200, 300)
        val r = CardPlacement.choose(area, heights, listOf(target), current = null, pinned = pinned)
        assertTrue(r.clear)
        assertFalse(card(r.spot).intersects(target))
        assertTrue(r.reason, r.reason.contains("not where it was moved"))
    }

    @Test
    fun `the keyboard opening after a Move takes the card above it, and off the box being typed in`() {
        val pinnedLow = CardSpot(CardDensity.Full, area.bottom - 520, CardEdge.Bottom)
        // Keyboard up from 1500; the box just above it.
        val withKeyboard = Bounds(area.left, area.top, area.right, 1492)
        val kept = CardPlacement.choose(withKeyboard, heights, listOf(button(300, 420)), current = pinnedLow, pinned = pinnedLow)
        assertEquals("held against the bottom edge, which is now the keyboard", 1492 - 520, kept.spot.top)
        val box = button(1300, 1440)
        val moved = CardPlacement.choose(withKeyboard, heights, listOf(box), current = kept.spot, pinned = pinnedLow)
        assertFalse(card(moved.spot).intersects(box))
        assertTrue(moved.spot.top + 520 <= 1492)
    }

    @Test
    fun `a target and a gesture path are kept clear together`() {
        val target = button(250, 380)
        val path = Bounds(440, 1300, 640, 2000)
        val r = CardPlacement.choose(area, heights, listOf(target, path), current = null)
        assertTrue(r.clear)
        assertFalse(card(r.spot).intersects(target))
        assertFalse(card(r.spot).intersects(path))
    }

    @Test
    fun `what helps is kept in view when that costs nothing, and given up when it would cover the target`() {
        val field = button(900, 1040)
        val next = button(2100, 2220)
        val both = CardPlacement.choose(area, heights, listOf(field), current = null, prefer = listOf(next))
        assertTrue(both.clear)
        assertFalse("the form's Next stays in view", card(both.spot).intersects(next))
        assertEquals(area.top, both.spot.top)

        val high = button(200, 330)
        val sacrificed = CardPlacement.choose(area, heights, listOf(high), current = null, prefer = listOf(next))
        assertTrue(sacrificed.clear)
        assertFalse("never the target", card(sacrificed.spot).intersects(high))
    }

    @Test
    fun `a taller card replacing a shorter one grows from its edge, and moves if it would now cover the target`() {
        val atBottom = CardSpot(CardDensity.Full, area.bottom - 300, CardEdge.Bottom)
        val short = mapOf(CardDensity.Full to 300, CardDensity.Compact to 220)
        val target = button(1560, 1700)
        val first = CardPlacement.choose(area, short, listOf(target), current = atBottom)
        assertEquals(atBottom, first.spot)
        val tall = mapOf(CardDensity.Full to 700, CardDensity.Compact to 420)
        val r = CardPlacement.choose(area, tall, listOf(target), current = first.spot)
        assertTrue(r.clear)
        assertFalse(Bounds(area.left, r.spot.top, area.right, r.spot.top + tall.getValue(r.spot.density)).intersects(target))
    }

    @Test
    fun `a compact card that is clear stays compact, rather than switching back and forth`() {
        val compact = CardSpot(CardDensity.Compact, area.top, CardEdge.Top)
        val r = CardPlacement.choose(area, heights, listOf(button(1500, 1600)), current = compact)
        assertEquals(compact, r.spot)
        var spot: CardSpot? = compact
        repeat(4) { spot = CardPlacement.choose(area, heights, listOf(button(1500, 1600)), current = spot).spot }
        assertEquals(compact, spot)
    }

    @Test
    fun `a target that moves a few pixels into the finger margin does not move a card already in place`() {
        val atTop = CardSpot(CardDensity.Full, area.top, CardEdge.Top)
        val margin = 30
        // The control itself ends 8px below the card; its finger margin reaches into it.
        val control = Bounds(60, area.top + 520 + 8, 1020, area.top + 700)
        val widened = Bounds(control.left - margin, control.top - margin, control.right + margin, control.bottom + margin)
        val stays = CardPlacement.choose(area, heights, listOf(widened), current = atTop, hysteresis = 24)
        assertEquals(atTop, stays.spot)
        // Onto the control itself: it moves, at once.
        val onIt = Bounds(60, area.top + 300, 1020, area.top + 420)
        val moves = CardPlacement.choose(area, heights, listOf(onIt), current = atTop, hysteresis = 24)
        assertFalse(card(moves.spot).intersects(onIt))
    }

    @Test
    fun `a gap between controls is used only when neither edge will do, even compact`() {
        val high = button(170, 400)
        val low = button(2050, 2280)
        val r = CardPlacement.choose(area, heights, listOf(high, low), current = null)
        assertTrue(r.clear)
        assertEquals(CardEdge.Between, r.spot.edge)
        val onlyHigh = CardPlacement.choose(area, heights, listOf(high), current = null)
        assertEquals(CardEdge.Bottom, onlyHigh.spot.edge)
    }

    @Test
    fun `the card's real bounds are checked against what must stay usable`() {
        val target = button(500, 600)
        assertTrue(CardPlacement.covers(area, Bounds(area.left, 400, area.right, 920), listOf(target)))
        assertFalse(CardPlacement.covers(area, Bounds(area.left, 700, area.right, 1220), listOf(target)))
    }

    @Test
    fun `each asker keeps its own regions, an empty list clears only its own, and a new step clears all`() {
        val keep = com.aaravsekhri.dot.guide.overlay.KeepClear()
        keep.set(com.aaravsekhri.dot.guide.overlay.ClearSource.Target, listOf(button(100, 200)))
        keep.set(com.aaravsekhri.dot.guide.overlay.ClearSource.Gesture, listOf(button(1800, 2200)))
        keep.set(com.aaravsekhri.dot.guide.overlay.ClearSource.Helpful, listOf(button(900, 1000)))
        assertEquals(2, keep.essential.size)
        assertEquals(1, keep.helpful.size)
        assertTrue(keep.set(com.aaravsekhri.dot.guide.overlay.ClearSource.Gesture, emptyList()))
        assertEquals(listOf(button(100, 200)), keep.essential)
        assertFalse("the same again is no change", keep.set(com.aaravsekhri.dot.guide.overlay.ClearSource.Target, listOf(button(100, 200))))
        keep.clear()
        assertTrue(keep.essential.isEmpty() && keep.helpful.isEmpty())
    }

    @Test
    fun `placing again from its own answer changes nothing`() {
        // No oscillation: the answer is a fixed point of the policy.
        val regions = listOf(button(300, 700), button(1750, 2200))
        var spot: CardSpot? = null
        val seen = mutableListOf<CardSpot>()
        repeat(5) {
            spot = CardPlacement.choose(area, heights, regions, current = spot).spot
            seen += spot!!
        }
        assertEquals(1, seen.distinct().size)
    }

    @Test
    fun `with nothing to avoid a new card starts at the bottom`() {
        val r = CardPlacement.choose(area, heights, emptyList(), current = null)
        assertEquals(area.bottom - 520, r.spot.top)
    }

    @Test
    fun `regions outside the card's space are ignored`() {
        // A control under the navigation bar is not something the card can cover.
        val below = Bounds(0, 2350, 1080, 2400)
        val r = CardPlacement.choose(area, heights, listOf(below), current = null)
        assertTrue(r.clear)
        assertEquals(area.bottom - 520, r.spot.top)
    }
}
