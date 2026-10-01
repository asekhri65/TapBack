package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.visual.FrameRect
import com.aaravsekhri.dot.guide.visual.GlobalMotionEstimator
import com.aaravsekhri.dot.guide.visual.GlobalMotionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the page moved, and by how much — never which row is which.
 *
 * The cases that matter are the two the first experiment got wrong: a fling
 * longer than any sensible search window, and a page transition that is not a
 * translation at all.
 */
class GlobalMotionEstimatorTest {

    private var fakeClock = 0L
    private val estimator = GlobalMotionEstimator(clock = { fakeClock })

    private val region = FrameRect(0, 0, SyntheticFrames.TALL_WIDTH, SyntheticFrames.TALL_HEIGHT)

    @Test
    fun `a small scroll is recovered exactly`() {
        val before = SyntheticFrames.scrollingList(offset = 0)
        val after = SyntheticFrames.scrollingList(offset = -8)

        val result = estimator.estimate(before, after, region)

        assertEquals(GlobalMotionKind.Scrolling, result.kind)
        assertEquals(-8f, result.dy, 0f)
        assertTrue("inliers ${result.inliers}/${result.blocks}", result.inlierRatio >= 0.6f)
    }

    @Test
    fun `a fling longer than any single search window is still recovered`() {
        // 48 frame pixels at quarter scale is 192 screen pixels between two
        // frames — well past what the local matcher could ever see.
        val before = SyntheticFrames.scrollingList(offset = 0)
        val after = SyntheticFrames.scrollingList(offset = -48)

        val result = estimator.estimate(before, after, region)

        assertEquals(
            "got=${result.dy} kind=${result.kind} inl=${result.inliers}/${result.blocks}",
            -48f,
            result.dy,
            0f,
        )
        assertEquals(GlobalMotionKind.Scrolling, result.kind)
    }

    @Test
    fun `displacements are recovered at every pyramid level`() {
        listOf(-3, -12, -24, -40, 16, 36).forEach { dy ->
            val before = SyntheticFrames.scrollingList(offset = 0)
            val after = SyntheticFrames.scrollingList(offset = dy)

            val result = estimator.estimate(before, after, region)

            assertEquals(
                "dy=$dy got=${result.dy} kind=${result.kind} inl=${result.inliers}/${result.blocks}",
                dy.toFloat(),
                result.dy,
                0f,
            )
            assertEquals("dy=$dy", GlobalMotionKind.Scrolling, result.kind)
        }
    }

    @Test
    fun `a page transition is not reported as a scroll`() {
        val before = SyntheticFrames.scrollingList(offset = 0)
        // The halves of the screen go different ways: no one translation
        // explains this, and pretending otherwise would move the outline.
        val after = SyntheticFrames.divergent(before, topDy = -30, bottomDy = 34)

        val result = estimator.estimate(before, after, region)

        assertTrue(
            "kind=${result.kind} confidence=${result.confidence}",
            result.kind != GlobalMotionKind.Scrolling,
        )
        assertEquals(0f, result.confidence, 0f)
    }

    @Test
    fun `an unchanged screen reports no movement`() {
        val before = SyntheticFrames.scrollingList(offset = 0)
        val after = SyntheticFrames.scrollingList(offset = 0, timestampNanos = 2_000L)

        val result = estimator.estimate(before, after, region)

        assertEquals(0f, result.dy, 0f)
        assertEquals(0f, result.dx, 0f)
    }

    @Test
    fun `excluded regions contribute no bands at all`() {
        val before = SyntheticFrames.scrollingList(offset = 0)
        val after = SyntheticFrames.scrollingList(offset = -8)

        val everything = listOf(FrameRect(0, 0, SyntheticFrames.TALL_WIDTH, SyntheticFrames.TALL_HEIGHT))
        val result = estimator.estimate(before, after, region, everything)

        assertEquals(0, result.blocks)
        assertEquals(0f, result.confidence, 0f)
    }

    @Test
    fun `frames of different sizes are refused rather than guessed at`() {
        val before = SyntheticFrames.scrollingList(offset = 0)
        val after = SyntheticFrames.listScreen(rowTop = 40)

        val result = estimator.estimate(before, after, region)

        assertEquals(0f, result.confidence, 0f)
        assertEquals(GlobalMotionKind.Indeterminate, result.kind)
    }

    @Test
    fun `processing time is measured and reported`() {
        val before = SyntheticFrames.scrollingList(offset = 0)
        val after = SyntheticFrames.scrollingList(offset = -8)
        fakeClock = 100L
        val estimate = GlobalMotionEstimator(clock = { fakeClock.also { fakeClock += 7 } })
            .estimate(before, after, region)

        assertTrue(estimate.processingTimeMs >= 0)
    }
}
