package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.positional.PositionalGuideController
import com.aaravsekhri.dot.guide.positional.PositionalGuideState
import com.aaravsekhri.dot.guide.positional.ScanCandidate
import com.aaravsekhri.dot.guide.positional.ScreenContext
import com.aaravsekhri.dot.guide.positional.ScrollGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll session, as a latch.
 *
 * The invariant these all serve: at any moment Dot is showing exactly one of
 * an outline over a page standing still, or a card over a page that is moving.
 * Never both, and never neither once a scroll has been recognised.
 *
 * The session is deliberately hard to leave. Accessibility scroll events arrive
 * unevenly — 121ms apart in the middle of an ordinary drag, but 368ms apart
 * during a very slow one and 356ms between the two a fling produces — and an
 * earlier version ended the session in each of those gaps and put the outline
 * back over a page the person was still moving.
 */
class ScrollSessionTest {

    private val ms = 1_000_000L
    private val now = 1_000L * ms

    private val target = TargetIdentity(
        windowId = 1,
        viewId = "android:id/title",
        normalizedText = "display and touch",
        normalizedDescription = "",
        className = "android.widget.LinearLayout",
    )

    private val viewport = Bounds(0, 0, 1170, 2532)
    private val list = Bounds(0, 200, 1170, 2200)
    private val rowHeight = 317

    private fun context() = ScreenContext(
        viewport = viewport,
        occluders = emptyList(),
        pebbleSizePx = 102,
        pebbleStraddlePx = 51,
        contentBounds = list,
    )

    private fun candidate(top: Int = 1000) = ScanCandidate(
        identity = target,
        reportedBounds = Bounds(0, top, 1170, top + rowHeight),
        titleVerified = true,
    )

    /** What accessibility reports for a row whose true top is [top]. */
    private fun reported(top: Int): Bounds {
        val bottom = top + rowHeight
        return when {
            bottom <= list.top -> Bounds(0, list.top, 1170, bottom)
            top >= list.bottom -> Bounds(0, top, 1170, list.bottom)
            else -> Bounds(0, maxOf(top, list.top), 1170, minOf(bottom, list.bottom))
        }
    }

    private fun settled(): PositionalGuideController {
        val controller = PositionalGuideController()
        controller.onScanResult(candidate(), context(), controller.generation, now)
        return controller
    }

    private fun PositionalGuideController.frame() = output().let {
        when {
            it.outline != null && it.guidance != null -> "BOTH"
            it.outline != null -> "outline"
            it.guidance != null -> it.guidance!!.message.name
            else -> "BLANK"
        }
    }

    // -------------------------------------------------- the session latches

    @Test
    fun `the first movement swaps outline for card in one transition`() {
        val controller = settled()
        assertEquals("outline", controller.frame())
        val before = controller.generation

        controller.onScrollActivity(now)

        assertEquals("StopHere", controller.frame())
        assertEquals("one transition, not two", before + 1, controller.generation)
    }

    @Test
    fun `a one pixel movement is enough to start a session`() {
        val controller = settled()

        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(999), now + 10 * ms)

        assertNull(controller.output().outline)
        assertNotNull(controller.output().guidance)
    }

    @Test
    fun `further movement keeps the same session`() {
        val controller = settled()
        controller.onScrollActivity(now)
        val sessionStart = controller.state

        repeat(20) { controller.onScrollActivity(now + it * 120L * ms) }

        assertTrue(
            "still the same kind of state, never re-entered",
            controller.state::class == sessionStart::class,
        )
    }

    @Test
    fun `gaps between scroll events never restore the outline`() {
        val controller = settled()
        controller.onScrollActivity(now)

        // The widest gaps actually measured: a very slow drag and a fling.
        listOf(368L, 356L, 254L, 188L).fold(now) { at, gap ->
            val next = at + gap * ms
            controller.onTick(next)
            assertNull("a ${gap}ms gap must not end the session", controller.output().outline)
            controller.onScrollActivity(next)
            next
        }
    }

    @Test
    fun `inertia keeps the card up`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(600), now + 50 * ms)

        // A fling: two events far apart, then nothing.
        controller.onTick(now + 200 * ms)
        assertNotNull(controller.output().guidance)
        controller.onScrollActivity(now + 356 * ms)
        controller.onTick(now + 500 * ms)
        assertNotNull(controller.output().guidance)
        assertNull(controller.output().outline)
    }

    @Test
    fun `no frame of a session has an outline, and none is blank`() {
        val controller = settled()
        controller.onScrollActivity(now)

        var at = now
        val seen = mutableSetOf<String>()
        repeat(40) { step ->
            at += 90 * ms
            when (step % 4) {
                0 -> controller.onScrollActivity(at)
                1 -> controller.onScrollEvidence(reported(1000 - step * 20), at)
                2 -> controller.onScrollEvidence(null, at)
                else -> controller.onTick(at)
            }
            seen += controller.frame()
        }

        assertTrue("an outline appeared during a scroll: $seen", "outline" !in seen)
        assertTrue("a frame had nothing on it: $seen", "BLANK" !in seen)
        assertTrue("both were shown at once: $seen", "BOTH" !in seen)
    }

    // ------------------------------------------------ settling and handover

    @Test
    fun `the last message stays up through the checks`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(2600), now + 50 * ms)
        assertEquals("KeepScrollingDown", controller.frame())

        controller.onTick(now + 700 * ms)
        assertEquals("the same words, not a blank", "KeepScrollingDown", controller.frame())

        controller.onScanResult(candidate(900), context(), controller.generation, now)
        assertEquals("one reading is not enough", "KeepScrollingDown", controller.frame())
    }

    @Test
    fun `movement during the checks cancels the outline coming back`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onTick(now + 700 * ms)
        controller.onScanResult(candidate(900), context(), controller.generation, now)
        val stale = controller.generation

        controller.onScrollActivity(now + 800 * ms)
        controller.onScanResult(candidate(903), context(), stale, now + 800 * ms)

        assertNull("a reading from before the movement may not draw", controller.output().outline)
        assertNotNull(controller.output().guidance)
    }

    @Test
    fun `two agreeing readings swap card for outline in one step`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(1000), now + 50 * ms)
        controller.onTick(now + 700 * ms)

        controller.onScanResult(candidate(900), context(), controller.generation, now)
        controller.onScanResult(candidate(903), context(), controller.generation, now)

        assertEquals("outline", controller.frame())
        assertNull("and the card goes in the same change", controller.output().guidance)
    }

    @Test
    fun `a stale reading cannot put an outline inside a session`() {
        val controller = settled()
        val stale = controller.generation
        controller.onScrollActivity(now)

        controller.onScanResult(candidate(), context(), stale, now)

        assertNull(controller.output().outline)
        assertNotNull(controller.output().guidance)
    }

    // ----------------------------------------------------------- direction

    @Test
    fun `below produces keep scrolling down`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(2600), now + 50 * ms)
        assertEquals(ScrollGuidance.KeepScrollingDown, controller.output().guidance!!.message)
    }

    @Test
    fun `above produces scroll up`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(-900), now + 50 * ms)
        assertEquals(ScrollGuidance.ScrollUp, controller.output().guidance!!.message)
    }

    @Test
    fun `in view produces stop here`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(1000), now + 50 * ms)
        assertEquals(ScrollGuidance.StopHere, controller.output().guidance!!.message)
    }

    @Test
    fun `nothing known produces stop for a moment`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(null, now + 50 * ms)
        assertEquals(ScrollGuidance.StopForAMoment, controller.output().guidance!!.message)
    }

    @Test
    fun `a target proven below stays below when it can no longer be read`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(2600), now + 50 * ms)
        assertEquals(ScrollGuidance.KeepScrollingDown, controller.output().guidance!!.message)

        // Off the end of a long list: the node stops being returned at all.
        repeat(10) { controller.onScrollEvidence(null, now + (100 + it * 100) * ms) }

        assertEquals(
            "a fact does not stop being true because it got harder to check",
            ScrollGuidance.KeepScrollingDown,
            controller.output().guidance!!.message,
        )
    }

    @Test
    fun `a target proven above stays above when it can no longer be read`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(-900), now + 50 * ms)

        repeat(10) { controller.onScrollEvidence(null, now + (100 + it * 100) * ms) }

        assertEquals(ScrollGuidance.ScrollUp, controller.output().guidance!!.message)
    }

    @Test
    fun `passing the target turns the message round`() {
        val controller = settled()
        controller.onScrollActivity(now)

        controller.onScrollEvidence(reported(2600), now + 50 * ms)
        assertEquals(ScrollGuidance.KeepScrollingDown, controller.output().guidance!!.message)
        controller.onScrollEvidence(reported(1000), now + 150 * ms)
        assertEquals(ScrollGuidance.StopHere, controller.output().guidance!!.message)
        controller.onScrollEvidence(reported(-900), now + 250 * ms)
        assertEquals(ScrollGuidance.ScrollUp, controller.output().guidance!!.message)

        // And the retained edge is the new one, not the old.
        controller.onScrollEvidence(null, now + 350 * ms)
        assertEquals(ScrollGuidance.ScrollUp, controller.output().guidance!!.message)
    }

    @Test
    fun `a target seen in view and then lost claims no direction`() {
        val controller = settled()
        controller.onScrollActivity(now)
        controller.onScrollEvidence(reported(1000), now + 50 * ms)

        controller.onScrollEvidence(null, now + 150 * ms)

        assertEquals(
            "it was here a moment ago; which way it went is not known",
            ScrollGuidance.StopForAMoment,
            controller.output().guidance!!.message,
        )
    }

    // ------------------------------------------------------ what is not a scroll

    @Test
    fun `a value change never starts a session`() {
        val controller = settled()

        controller.onSameScreenControlChange()

        assertEquals("outline", controller.frame())
        assertTrue(controller.state is PositionalGuideState.TargetStable)
    }

    @Test
    fun `navigation never leaves an instruction behind`() {
        val controller = settled()
        controller.onScrollActivity(now)
        assertNotNull(controller.output().guidance)

        controller.onNavigation()

        assertNull(controller.output().outline)
        assertNull("nothing to say about a page that has gone", controller.output().guidance)
    }
}
