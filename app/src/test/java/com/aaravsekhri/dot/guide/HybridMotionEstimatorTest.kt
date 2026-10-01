package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.motion.FusionVerdict
import com.aaravsekhri.dot.guide.motion.HybridMotionEstimator
import com.aaravsekhri.dot.guide.motion.MotionSignal
import com.aaravsekhri.dot.guide.motion.MotionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what the screen did.
 *
 * These are the rules that keep the outline off the wrong row, so they are
 * tested as rules rather than inferred from how the thing looks on a device.
 */
class HybridMotionEstimatorTest {

    private val ms = 1_000_000L
    private var now = 1_000L * ms

    private fun scroll(dx: Float = 0f, dy: Float, at: Long = now) = MotionSignal(
        source = MotionSource.ACCESSIBILITY_SCROLL_DELTA,
        dx = dx,
        dy = dy,
        confidence = 1f,
        timestampNanos = at,
    )

    private fun global(dy: Float, confidence: Float = 0.9f, at: Long = now) = MotionSignal(
        source = MotionSource.GLOBAL_VISUAL_MOTION,
        dx = 0f,
        dy = dy,
        confidence = confidence,
        timestampNanos = at,
    )

    private fun local(dy: Float, confidence: Float = 0.9f, at: Long = now) = MotionSignal(
        source = MotionSource.LOCAL_TARGET_MATCH,
        dx = 0f,
        dy = dy,
        confidence = confidence,
        timestampNanos = at,
    )

    @Test
    fun `a scroll delta moves the content the way the delta says`() {
        val estimator = HybridMotionEstimator()
        // Content travelling up the screen is a negative dy by this convention.
        estimator.offer(scroll(dy = -120f))

        val result = estimator.fuse(now + ms)

        assertEquals(FusionVerdict.Confident, result.verdict)
        assertEquals(-120f, result.motion!!.dy, 0f)
        assertEquals(setOf(MotionSource.ACCESSIBILITY_SCROLL_DELTA), result.motion!!.sources)
    }

    @Test
    fun `several deltas before one display frame become a single movement`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(scroll(dy = -30f))
        estimator.offer(scroll(dy = -25f))
        estimator.offer(scroll(dy = -45f))

        val result = estimator.fuse(now + ms)

        assertEquals(-100f, result.motion!!.dy, 0f)
        // And consumed: the next frame must not replay them.
        assertNull(estimator.fuse(now + 2 * ms).motion)
    }

    @Test
    fun `agreeing sources produce one confident movement, not their average`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(scroll(dy = -100f))
        estimator.offer(global(dy = -108f))

        val result = estimator.fuse(now + ms)

        assertEquals(FusionVerdict.Confident, result.verdict)
        // The exact number wins outright; -104 would be the average.
        assertEquals(-100f, result.motion!!.dy, 0f)
        assertTrue(MotionSource.GLOBAL_VISUAL_MOTION in result.motion!!.sources)
    }

    @Test
    fun `sources that contradict each other produce uncertainty, not a blend`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(scroll(dy = -100f))
        estimator.offer(global(dy = 140f))

        val result = estimator.fuse(now + ms)

        assertEquals(FusionVerdict.Uncertain, result.verdict)
        assertNull(result.motion)
        assertEquals(1L, estimator.disagreements)
    }

    @Test
    fun `persistent disagreement stays uncertain rather than resolving itself`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(scroll(dy = -100f))
        estimator.offer(global(dy = 140f))

        assertEquals(FusionVerdict.Uncertain, estimator.fuse(now + ms).verdict)
        val later = now + 200 * ms
        assertTrue(estimator.uncertainDurationNanos(later) >= 190 * ms)
    }

    @Test
    fun `global motion carries the target when no scroll events arrive`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(global(dy = -260f))

        val result = estimator.fuse(now + ms)

        assertEquals(FusionVerdict.Tentative, result.verdict)
        assertEquals(-260f, result.motion!!.dy, 0f)
        assertEquals(setOf(MotionSource.GLOBAL_VISUAL_MOTION), result.motion!!.sources)
    }

    @Test
    fun `vision cannot carry the target forever without confirmation`() {
        val estimator = HybridMotionEstimator(
            HybridMotionEstimator.Config(confirmationWindowNanos = 50 * ms),
        )
        estimator.offer(global(dy = -60f))
        assertEquals(FusionVerdict.Tentative, estimator.fuse(now + ms).verdict)

        val late = now + 200 * ms
        estimator.offer(global(dy = -60f, at = late))
        assertEquals(FusionVerdict.Uncertain, estimator.fuse(late + ms).verdict)
    }

    @Test
    fun `a semantic reconciliation is the confirmation vision was waiting for`() {
        val estimator = HybridMotionEstimator(
            HybridMotionEstimator.Config(confirmationWindowNanos = 50 * ms),
        )
        estimator.offer(global(dy = -60f))
        estimator.fuse(now + ms)

        val late = now + 200 * ms
        estimator.offer(
            MotionSignal(MotionSource.SEMANTIC_RECONCILIATION, 0f, 0f, 1f, late),
        )
        estimator.offer(global(dy = -60f, at = late))

        assertEquals(FusionVerdict.Tentative, estimator.fuse(late + ms).verdict)
    }

    @Test
    fun `a local match may nudge the outline but never move it`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(scroll(dy = -100f))
        estimator.offer(local(dy = 6f))

        val result = estimator.fuse(now + ms)

        assertEquals(-94f, result.motion!!.dy, 0f)
        assertTrue(MotionSource.LOCAL_TARGET_MATCH in result.motion!!.sources)
    }

    @Test
    fun `a local match cannot jump the outline by a whole row`() {
        val estimator = HybridMotionEstimator(
            HybridMotionEstimator.Config(rowHeightPx = 190f),
        )
        estimator.offer(scroll(dy = -100f))
        estimator.offer(local(dy = 190f))

        val result = estimator.fuse(now + ms)

        // The scroll still applies; the row-sized "correction" does not.
        assertEquals(-100f, result.motion!!.dy, 0f)
        assertTrue(MotionSource.LOCAL_TARGET_MATCH in result.rejected)
        assertEquals(1L, estimator.suppressedRowJumps)
    }

    @Test
    fun `an unsupported row jump is refused even with nothing else to go on`() {
        val estimator = HybridMotionEstimator(
            HybridMotionEstimator.Config(rowHeightPx = 190f),
        )
        estimator.offer(local(dy = -188f))

        val result = estimator.fuse(now + ms)

        assertNull(result.motion)
        assertTrue(MotionSource.LOCAL_TARGET_MATCH in result.rejected)
    }

    @Test
    fun `a frame older than movement already drawn cannot drag the outline back`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(scroll(dy = -100f))
        estimator.fuse(now + 10 * ms)

        // A frame captured before that fuse, arriving after it.
        estimator.offer(global(dy = 100f, at = now))

        assertNull(estimator.fuse(now + 11 * ms).motion)
    }

    @Test
    fun `a stale scroll delta is dropped rather than applied late`() {
        val estimator = HybridMotionEstimator(
            HybridMotionEstimator.Config(scrollFreshnessNanos = 20 * ms),
        )
        estimator.offer(scroll(dy = -100f))

        val result = estimator.fuse(now + 500 * ms)

        assertEquals(FusionVerdict.Starved, result.verdict)
        assertNull(result.motion)
    }

    @Test
    fun `a low-confidence visual estimate is not worth acting on`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(global(dy = -80f, confidence = 0.2f))

        assertEquals(FusionVerdict.Starved, estimator.fuse(now + ms).verdict)
    }

    @Test
    fun `silence from every source is starvation, and the caller is told`() {
        val estimator = HybridMotionEstimator()
        val result = estimator.fuse(now)

        assertEquals(FusionVerdict.Starved, result.verdict)
        assertNotNull(estimator.uncertainDurationNanos(now + 100 * ms))
    }

    @Test
    fun `a reset forgets everything a previous screen said`() {
        val estimator = HybridMotionEstimator()
        estimator.offer(scroll(dy = -300f))
        estimator.reset()

        assertNull(estimator.fuse(now + ms).motion)
    }
}
