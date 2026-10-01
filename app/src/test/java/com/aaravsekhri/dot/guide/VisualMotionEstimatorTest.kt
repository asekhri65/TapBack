package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.visual.FrameRect
import com.aaravsekhri.dot.guide.visual.TemplateMatchEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The estimator measured against translations it was actually given.
 *
 * Synthetic frames make this exact: a row is drawn, the whole screen is shifted
 * by a known number of pixels, and the answer is compared with the truth. What
 * matters as much as accuracy is the refusals — a list of similar rows must
 * produce low confidence rather than a confident wrong answer.
 */
class VisualMotionEstimatorTest {

    private val estimator = TemplateMatchEstimator()

    /**
     * The row sits at y=100..140 in a 120x300 synthetic screen. The patch is
     * inset from the frame edges so there is room to detect sideways motion —
     * a patch flush against the edge can only be matched by what slides in from
     * outside it.
     */
    private val rowTop = 100
    private val target = FrameRect(10, rowTop, SyntheticFrames.WIDTH - 10, rowTop + 40)

    // ------------------------------------------------------------ accuracy

    @Test
    fun `a pure vertical translation is recovered exactly`() {
        val before = SyntheticFrames.listScreen(rowTop = rowTop)

        listOf(-40, -12, -3, 3, 12, 40).forEach { dy ->
            val after = SyntheticFrames.translated(before, dy = dy)
            val estimate = estimator.estimate(before, after, target)

            assertEquals("dy=$dy", dy.toFloat(), estimate.deltaY, 0f)
            assertTrue("dy=$dy confidence ${estimate.confidence}", estimate.confidence > 0.7f)
        }
    }

    @Test
    fun `horizontal movement is not assumed away`() {
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        val after = SyntheticFrames.translated(before, dy = 10, dx = 5)

        val estimate = estimator.estimate(before, after, target)

        assertEquals(10f, estimate.deltaY, 0f)
        assertEquals(5f, estimate.deltaX, 0f)
    }

    @Test
    fun `translation survives moderate sensor noise`() {
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        val noisy = SyntheticFrames.translated(
            SyntheticFrames.listScreen(rowTop = rowTop, noise = 12),
            dy = 18,
        )

        val estimate = estimator.estimate(before, noisy, target)

        assertTrue(
            "recovered ${estimate.deltaY} within tolerance",
            abs(estimate.deltaY - 18f) <= 2f,
        )
    }

    @Test
    fun `a uniform brightness change does not read as movement`() {
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        // Every pixel brightened: correlation is zero-mean and normalised, so
        // this must not look like the row going anywhere.
        val brighter = com.aaravsekhri.dot.guide.visual.CapturedFrame(
            before.width,
            before.height,
            ByteArray(before.luminance.size) { i ->
                ((before.luminance[i].toInt() and 0xFF) / 2 + 40).toByte()
            },
            before.transform,
            before.timestampNanos + 16_000_000L,
        )

        val estimate = estimator.estimate(before, brighter, target)

        assertEquals(0f, estimate.deltaY, 0f)
    }

    // ---------------------------------------------------------- refusals

    @Test
    fun `a visually similar distractor produces low confidence, not a wrong answer`() {
        // Two near-identical rows, and the screen shifted far enough that the
        // distractor now sits where the target was.
        val before = SyntheticFrames.listScreen(
            rowTop = rowTop,
            distractorTop = rowTop + 60,
            distractorSeed = 1,
        )
        val after = SyntheticFrames.translated(before, dy = -60)

        val estimate = estimator.estimate(before, after, target)

        // Either it finds the true motion, or it admits it cannot tell. What it
        // must never do is confidently report the distractor's position.
        val foundTruth = abs(estimate.deltaY - (-60f)) < 2f
        assertTrue(
            "must not be confidently wrong (dy=${estimate.deltaY} conf=${estimate.confidence})",
            foundTruth || estimate.confidence < TemplateMatchEstimator.DEFAULT_MINIMUM_CORRELATION,
        )
    }

    @Test
    fun `a featureless region cannot be initialized`() {
        val flat = com.aaravsekhri.dot.guide.visual.CapturedFrame(
            SyntheticFrames.WIDTH,
            SyntheticFrames.HEIGHT,
            ByteArray(SyntheticFrames.WIDTH * SyntheticFrames.HEIGHT) { 200.toByte() },
            SyntheticFrames.transformFor(SyntheticFrames.WIDTH, SyntheticFrames.HEIGHT, 1f / 3f),
            1_000L,
        )

        val result = estimator.initialize(flat, target)

        assertFalse("nothing to track in a blank region", result.succeeded)
    }

    @Test
    fun `a textured region initializes`() {
        val screen = SyntheticFrames.listScreen(rowTop = rowTop)

        assertTrue(estimator.initialize(screen, target).succeeded)
    }

    @Test
    fun `an empty or off-frame target is refused`() {
        val screen = SyntheticFrames.listScreen(rowTop = rowTop)

        assertFalse(estimator.initialize(screen, FrameRect(0, 500, 120, 540)).succeeded)
        assertFalse(estimator.initialize(screen, FrameRect(10, 10, 10, 10)).succeeded)
    }

    // ------------------------------------------------------ overlay masking

    @Test
    fun `Dot's own overlay is excluded from matching`() {
        val screen = SyntheticFrames.listScreen(rowTop = rowTop)
        val masked = TemplateMatchEstimator().apply {
            setExcludedRegions(listOf(FrameRect(0, rowTop - 10, SyntheticFrames.WIDTH, rowTop + 50)))
        }

        val result = masked.initialize(screen, target)

        assertFalse(
            "a target under Dot's own overlay cannot be tracked from it",
            result.succeeded,
        )
    }

    @Test
    fun `a masked region is never chosen as the match`() {
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        val after = SyntheticFrames.translated(before, dy = 20)

        // Mask exactly where the true answer lies.
        val masked = TemplateMatchEstimator().apply {
            setExcludedRegions(
                listOf(FrameRect(0, rowTop + 15, SyntheticFrames.WIDTH, rowTop + 65)),
            )
        }
        val estimate = masked.estimate(before, after, target)

        assertTrue(
            "must not report the masked position",
            estimate.deltaY != 20f || estimate.confidence == 0f,
        )
    }

    @Test
    fun `processing time is reported for every estimate`() {
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        val after = SyntheticFrames.translated(before, dy = 8)

        val estimate = estimator.estimate(before, after, target)

        assertTrue(estimate.processingTimeMs >= 0)
        assertEquals(after.timestampNanos, estimate.timestampNanos)
    }
}
