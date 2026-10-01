package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.tracking.ReconcileOutcome
import com.aaravsekhri.dot.guide.tracking.RenderedTargetTracker
import com.aaravsekhri.dot.guide.tracking.offsetBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides whether the outline is attached to the content
 * or chasing it.
 *
 * Scroll events carry the exact distance the content moved, so the drawn
 * rectangle can move with it on the very next frame instead of waiting for a
 * tree scan. These check the sums, the coalescing, and — most importantly —
 * that a scan describing an older screen can never drag the outline backwards.
 */
class RenderedTargetTrackerTest {

    private val identity = TargetIdentity(
        windowId = 1,
        viewId = "com.android.settings:id/seekbar",
        normalizedText = "font size",
        normalizedDescription = "",
        className = "android.widget.SeekBar",
    )

    private val start = Bounds(0, 1000, 1080, 1400)

    private fun tracker() = RenderedTargetTracker().apply { adopt(identity, start) }

    // --------------------------------------------------------- scroll motion

    @Test
    fun `five consecutive deltas move the outline by their cumulative inverse`() {
        val tracker = tracker()

        listOf(40, 40, 20, 60, 40).forEach { delta ->
            tracker.onScrollDelta(0, delta)
            tracker.applyPendingDeltas()
        }

        // Content moved down the list by 200; the target moved up by 200.
        assertEquals(Bounds(0, 800, 1080, 1200), tracker.geometry?.renderedRegion)
        assertEquals(200f, tracker.geometry?.appliedScrollY)
    }

    @Test
    fun `several events before one frame are coalesced into a single move`() {
        val tracker = tracker()

        // A burst arriving between two vsyncs.
        tracker.onScrollDelta(0, 30)
        tracker.onScrollDelta(0, 30)
        tracker.onScrollDelta(0, 30)
        val generationBefore = tracker.geometry!!.generation

        val moved = tracker.applyPendingDeltas()

        assertEquals(Bounds(0, 910, 1080, 1310), moved)
        // One movement, not three.
        assertEquals(generationBefore + 1, tracker.geometry?.generation)
        assertNull("nothing left over", tracker.applyPendingDeltas())
    }

    @Test
    fun `scrolling back the other way reverses immediately`() {
        val tracker = tracker()
        tracker.onScrollDelta(0, 150)
        tracker.applyPendingDeltas()

        tracker.onScrollDelta(0, -150)
        tracker.applyPendingDeltas()

        assertEquals(start, tracker.geometry?.renderedRegion)
    }

    @Test
    fun `horizontal deltas move the outline sideways`() {
        val tracker = tracker()

        tracker.onScrollDelta(80, 0)
        tracker.applyPendingDeltas()

        assertEquals(Bounds(-80, 1000, 1000, 1400), tracker.geometry?.renderedRegion)
    }

    @Test
    fun `a zero delta is not a movement`() {
        val tracker = tracker()

        assertTrue(!tracker.onScrollDelta(0, 0))
        assertNull(tracker.applyPendingDeltas())
        assertEquals(start, tracker.geometry?.renderedRegion)
    }

    @Test
    fun `the outline keeps its size while it moves`() {
        val tracker = tracker()

        tracker.onScrollDelta(0, 275)
        val moved = tracker.applyPendingDeltas()!!

        assertEquals(start.width, moved.width)
        assertEquals(start.height, moved.height)
    }

    // --------------------------------------------------------- reconciliation

    @Test
    fun `a scan that agrees with what is drawn changes nothing`() {
        val tracker = tracker()
        val mark = tracker.markScanStart()

        val outcome = tracker.reconcile(identity, start, mark, tolerancePx = 3)

        assertEquals(ReconcileOutcome.Agreed, outcome)
        assertEquals(start, tracker.geometry?.renderedRegion)
    }

    @Test
    fun `a scan within tolerance does not nudge the outline`() {
        val tracker = tracker()
        val mark = tracker.markScanStart()

        val outcome = tracker.reconcile(identity, start.offsetBy(0, 2), mark, tolerancePx = 3)

        assertEquals(ReconcileOutcome.Agreed, outcome)
        assertEquals(start, tracker.geometry?.renderedRegion)
    }

    @Test
    fun `drift is corrected in place`() {
        val tracker = tracker()
        val mark = tracker.markScanStart()

        // The tree says it is 12px higher than where it is being drawn.
        val outcome = tracker.reconcile(identity, start.offsetBy(0, -12), mark, tolerancePx = 3)

        assertEquals(ReconcileOutcome.Corrected, outcome)
        assertEquals(Bounds(0, 988, 1080, 1388), tracker.geometry?.renderedRegion)
    }

    @Test
    fun `a stale scan cannot drag the outline back to where the target was`() {
        val tracker = tracker()

        // A scan begins, describing the world at y=1000.
        val mark = tracker.markScanStart()
        val staleResult = start

        // The person keeps scrolling while it runs.
        tracker.onScrollDelta(0, 300)
        tracker.applyPendingDeltas()
        assertEquals(700, tracker.geometry?.renderedRegion?.top)

        // The scan finally returns. Its answer is aged by the motion it missed
        // rather than being applied as though nothing had happened.
        val outcome = tracker.reconcile(identity, staleResult, mark, tolerancePx = 3)

        assertEquals(ReconcileOutcome.Agreed, outcome)
        assertEquals("must not jump backwards", 700, tracker.geometry?.renderedRegion?.top)
    }

    @Test
    fun `a scan is held back while newer motion is still waiting for a frame`() {
        val tracker = tracker()
        val mark = tracker.markScanStart()

        // Banked, not yet drawn.
        tracker.onScrollDelta(0, 90)

        val outcome = tracker.reconcile(identity, start, mark, tolerancePx = 3)

        assertEquals(ReconcileOutcome.RejectedAsStale, outcome)
        // And the banked motion survives to be drawn on the next frame.
        assertEquals(Bounds(0, 910, 1080, 1310), tracker.applyPendingDeltas())
    }

    @Test
    fun `a scan for a different control is refused`() {
        val tracker = tracker()
        val other = identity.copy(viewId = "com.android.settings:id/display_size_slider")

        val outcome = tracker.reconcile(other, start, tracker.markScanStart(), tolerancePx = 3)

        assertEquals(ReconcileOutcome.DifferentTarget, outcome)
    }

    @Test
    fun `a region that grew is adopted at its new size`() {
        val tracker = tracker()
        val mark = tracker.markScanStart()

        // The control got taller because the font size went up.
        val taller = Bounds(0, 1000, 1080, 1520)
        val outcome = tracker.reconcile(identity, taller, mark, tolerancePx = 3)

        assertEquals(ReconcileOutcome.Corrected, outcome)
        assertEquals(taller, tracker.geometry?.renderedRegion)
    }

    // ----------------------------------------------------------- rebinding

    @Test
    fun `rebinding to a rebuilt page keeps following without starting over`() {
        val tracker = tracker()
        tracker.onScrollDelta(0, 100)
        tracker.applyPendingDeltas()

        // Same control, new window id after a font-size rebuild.
        val rebuilt = identity.copy(windowId = 99)
        tracker.rebind(rebuilt, Bounds(0, 880, 1080, 1300))

        assertEquals(rebuilt, tracker.geometry?.identity)
        assertEquals(Bounds(0, 880, 1080, 1300), tracker.geometry?.renderedRegion)
    }

    @Test
    fun `forgetting a target leaves nothing behind to draw`() {
        val tracker = tracker()

        tracker.forget()

        assertNull(tracker.geometry)
        assertTrue(!tracker.onScrollDelta(0, 50))
    }
}
