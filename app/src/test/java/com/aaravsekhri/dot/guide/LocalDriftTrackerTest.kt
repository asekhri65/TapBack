package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.motion.RejectionReason
import com.aaravsekhri.dot.guide.visual.LocalDriftTracker
import com.aaravsekhri.dot.guide.visual.SearchHint
import com.aaravsekhri.dot.guide.visual.VerifiedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The local matcher, in its reduced role.
 *
 * It is handed a prediction and asked only how wrong it was. The tests that
 * matter are the ones proving it cannot answer the larger question it used to
 * be asked — which row is this — however confident the correlation looks.
 */
class LocalDriftTrackerTest {

    private val scale = 1f / 3f
    private val rowTop = 100

    /** Screen-space bounds of the synthetic row, at the frame's scale. */
    private fun rowBounds(frameTop: Int) = Bounds(
        left = (10 / scale).toInt(),
        top = (frameTop / scale).toInt(),
        right = ((SyntheticFrames.WIDTH - 10) / scale).toInt(),
        bottom = ((frameTop + 40) / scale).toInt(),
    )

    private fun target(frameTop: Int) = VerifiedTarget(
        identity = TargetIdentity(
            windowId = 1,
            viewId = "row",
            normalizedText = "display and touch",
            normalizedDescription = "",
            className = "android.widget.LinearLayout",
        ),
        regionInScreen = rowBounds(frameTop),
        generation = 1L,
    )

    @Test
    fun `an accurate prediction leaves no residual`() {
        val tracker = LocalDriftTracker()
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        assertTrue(tracker.reference(target(rowTop), before))

        val after = SyntheticFrames.translated(before, dy = -10)
        val predicted = rowBounds(rowTop - 10)

        val result = tracker.measure(after, predicted, SearchHint.Corroborated)

        val measured = result as LocalDriftTracker.Result.Measured
        assertEquals(0f, measured.residualY, 0.01f)
    }

    @Test
    fun `a prediction that is out by a few pixels is corrected`() {
        val tracker = LocalDriftTracker()
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        tracker.reference(target(rowTop), before)

        val after = SyntheticFrames.translated(before, dy = -12)
        // Predict only -8 frame pixels: 16 screen pixels short.
        val predicted = rowBounds(rowTop - 8)

        val result = tracker.measure(after, predicted, SearchHint.SingleSource)

        val measured = result as LocalDriftTracker.Result.Measured
        assertEquals(-16f, measured.residualY, 4f)
    }

    @Test
    fun `the search is centred on the prediction, not on the old position`() {
        val tracker = LocalDriftTracker()
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        tracker.reference(target(rowTop), before)

        // Far beyond any radius the hint allows, so only a prediction-centred
        // search can possibly find it.
        val after = SyntheticFrames.translated(before, dy = -60)
        val predicted = rowBounds(rowTop - 60)

        val result = tracker.measure(after, predicted, SearchHint.Corroborated)

        val measured = result as LocalDriftTracker.Result.Measured
        assertEquals(0f, measured.residualY, 0.01f)
    }

    @Test
    fun `a neighbouring row cannot be adopted however similar it looks`() {
        val tracker = LocalDriftTracker()
        // Two rows that look alike, one row-height apart.
        val before = SyntheticFrames.listScreen(rowTop = rowTop, distractorTop = rowTop + 48)
        tracker.reference(target(rowTop), before)

        val after = SyntheticFrames.translated(before, dy = -48)
        // The prediction did not move at all, so the distractor now sits
        // exactly where the target used to be. A broad search would take it.
        val predicted = rowBounds(rowTop)

        val result = tracker.measure(after, predicted, SearchHint.Corroborated)

        if (result is LocalDriftTracker.Result.Measured) {
            assertTrue(
                "adopted a row ${result.residualY} px away",
                abs(result.residualY) < 40f,
            )
        }
    }

    @Test
    fun `an implausible residual is refused rather than reported`() {
        val tracker = LocalDriftTracker(maximumResidualPx = 20f)
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        tracker.reference(target(rowTop), before)

        val after = SyntheticFrames.translated(before, dy = -40)
        val predicted = rowBounds(rowTop)

        val result = tracker.measure(after, predicted, SearchHint(radiusX = 6, radiusY = 60))

        assertTrue(result is LocalDriftTracker.Result.Refused)
        assertEquals(
            RejectionReason.Implausible,
            (result as LocalDriftTracker.Result.Refused).reason,
        )
    }

    @Test
    fun `a featureless region cannot become a reference`() {
        val tracker = LocalDriftTracker()
        val flat = SyntheticFrames.listScreen(rowTop = 250)

        // A patch of plain background, nowhere near the drawn row.
        val empty = VerifiedTarget(
            identity = target(rowTop).identity,
            regionInScreen = Bounds(40, 40, 200, 160),
            generation = 1L,
        )

        assertFalse(tracker.reference(empty, flat))
        assertFalse(tracker.hasReference)
    }

    @Test
    fun `without a reference there is nothing to measure`() {
        val tracker = LocalDriftTracker()
        val frame = SyntheticFrames.listScreen(rowTop = rowTop)

        assertEquals(
            LocalDriftTracker.Result.NoReference,
            tracker.measure(frame, rowBounds(rowTop), SearchHint.Corroborated),
        )
    }

    @Test
    fun `a frame no newer than the reference is refused as stale`() {
        val tracker = LocalDriftTracker()
        val before = SyntheticFrames.listScreen(rowTop = rowTop, timestampNanos = 5_000L)
        tracker.reference(target(rowTop), before)

        val same = SyntheticFrames.listScreen(rowTop = rowTop, timestampNanos = 5_000L)
        val result = tracker.measure(same, rowBounds(rowTop), SearchHint.Corroborated)

        assertEquals(
            RejectionReason.Stale,
            (result as LocalDriftTracker.Result.Refused).reason,
        )
    }

    @Test
    fun `forgetting the reference stops all measurement`() {
        val tracker = LocalDriftTracker()
        val before = SyntheticFrames.listScreen(rowTop = rowTop)
        tracker.reference(target(rowTop), before)
        tracker.forget()

        assertFalse(tracker.hasReference)
        assertEquals(
            LocalDriftTracker.Result.NoReference,
            tracker.measure(
                SyntheticFrames.translated(before, dy = -4),
                rowBounds(rowTop),
                SearchHint.Corroborated,
            ),
        )
    }
}
