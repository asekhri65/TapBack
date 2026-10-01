package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.motion.AlignmentState
import com.aaravsekhri.dot.guide.motion.MotionPredictor
import com.aaravsekhri.dot.guide.motion.TargetPose
import com.aaravsekhri.dot.guide.motion.TimedMotionMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Turning a measurement of the past into a position for now.
 *
 * **Research code.** Nothing in the production guide calls the predictor;
 * these tests are kept because the behaviour they pin down is what a future
 * attempt would need to reproduce, not because anything ships on it.
 *
 * The property being tested throughout is not that the predictor is clever. It
 * is that a measurement taken 50 ms ago, drawn without compensation, puts the
 * outline exactly 50 ms behind the page — and that compensating for it does
 * not then let the predictor keep guessing once the assumption it rests on has
 * stopped being true.
 */
class MotionPredictorTest {

    private val ms = 1_000_000L
    private val predictor = MotionPredictor()

    private fun startAt(y: Float, t: Long) = predictor.placeAt(TargetPose(0f, y), t)

    @Test
    fun `an exact scroll delta moves the target by exactly that much`() {
        startAt(1000f, 0)
        predictor.onAccessibilityDelta(0f, -120f, 16 * ms)

        val pose = predictor.predict(16 * ms)!!.pose
        assertEquals(880f, pose.topPx, 0.01f)
    }

    @Test
    fun `prediction carries the target forward over a known measurement delay`() {
        startAt(1000f, 0)
        // Two deltas 16 ms apart: 120 px in 16 ms is 7500 px per second.
        predictor.onAccessibilityDelta(0f, -120f, 16 * ms)
        predictor.onAccessibilityDelta(0f, -120f, 32 * ms)

        // A display frame 16 ms after the last measurement should be ahead of
        // it, not sitting on it.
        val atMeasurement = predictor.predict(32 * ms)!!
        val aheadOfIt = predictor.predict(48 * ms)!!

        assertTrue(
            "prediction must move on between measurements",
            aheadOfIt.pose.topPx < atMeasurement.pose.topPx,
        )
        assertEquals(AlignmentState.PREDICTED_HIGH_CONFIDENCE, aheadOfIt.alignment)
        assertTrue(
            "at=${atMeasurement.pose.topPx} ahead=${aheadOfIt.pose.topPx}",
            abs(aheadOfIt.pose.topPx - atMeasurement.pose.topPx) < 200f,
        )
    }

    @Test
    fun `a visual measurement is placed at the frame it describes, not at arrival`() {
        startAt(1000f, 0)
        // The frame was captured at 20 ms; we only found out at 70 ms.
        predictor.onVisualMeasurement(
            TimedMotionMeasurement(
                frameTimestampNanos = 20 * ms,
                processingCompletedNanos = 70 * ms,
                dx = 0f,
                dy = -100f,
                confidence = 0.9f,
            ),
        )

        val atArrival = predictor.predict(70 * ms)!!
        // Fifty milliseconds of motion happened while that frame was being
        // processed. Drawing the measurement as-is would ignore all of it.
        assertTrue(
            "must be carried past the measured position, got ${atArrival.pose.topPx}",
            atArrival.pose.topPx < 930f,
        )
    }

    @Test
    fun `a direction reversal stops the old velocity immediately`() {
        startAt(1000f, 0)
        predictor.onAccessibilityDelta(0f, -120f, 16 * ms)
        predictor.onAccessibilityDelta(0f, -120f, 32 * ms)
        assertTrue(predictor.velocity < 0f)

        predictor.onAccessibilityDelta(0f, 30f, 48 * ms)

        assertEquals(1L, predictor.reversals)
        // The very next frame must not continue travelling the old way. It may
        // travel the new way, which is what the reversing delta just measured.
        val after = predictor.predict(64 * ms)!!
        assertTrue("went back the old way to ${after.pose.topPx}", after.pose.topPx >= 790f)
    }

    @Test
    fun `extrapolation is bounded in time`() {
        startAt(1000f, 0)
        predictor.onAccessibilityDelta(0f, -120f, 16 * ms)
        predictor.onAccessibilityDelta(0f, -120f, 32 * ms)

        val far = predictor.predict(32 * ms + 400 * ms)!!
        assertFalse("must not still be drawing", far.alignment.drawable)
    }

    @Test
    fun `extrapolation is bounded well inside one row height`() {
        val fast = MotionPredictor(
            MotionPredictor.Config(maxExtrapolationPx = 50f, stalenessNanos = 500 * ms),
        )
        fast.placeAt(TargetPose(0f, 1000f), 0)
        fast.onAccessibilityDelta(0f, -400f, 10 * ms)
        fast.onAccessibilityDelta(0f, -400f, 20 * ms)

        val ahead = fast.predict(80 * ms)!!
        assertTrue("capped at the limit", abs(ahead.pose.topPx - 200f) <= 50f + 0.01f)
        assertEquals(AlignmentState.STALE, ahead.alignment)
    }

    @Test
    fun `a fast page with old data hides rather than guessing`() {
        startAt(1000f, 0)
        // Seven and a half thousand pixels a second, last heard from 100 ms ago.
        predictor.onAccessibilityDelta(0f, -120f, 16 * ms)
        predictor.onAccessibilityDelta(0f, -120f, 32 * ms)

        val late = predictor.predict(132 * ms)!!
        assertFalse(
            "error is beyond half a row; must not be drawn",
            late.alignment.drawable,
        )
    }

    @Test
    fun `a slow page with equally old data is still drawn`() {
        startAt(1000f, 0)
        // Three hundred pixels a second: a hundred milliseconds of uncertainty
        // is about ten pixels, which is nothing.
        predictor.onAccessibilityDelta(0f, -5f, 16 * ms)
        predictor.onAccessibilityDelta(0f, -5f, 32 * ms)

        assertTrue(predictor.predict(132 * ms)!!.alignment.drawable)
    }

    @Test
    fun `a stale estimate hides rather than drifting onto another row`() {
        startAt(1000f, 0)
        predictor.onAccessibilityDelta(0f, -120f, 16 * ms)

        val muchLater = predictor.predict(16 * ms + 600 * ms)!!

        assertEquals(AlignmentState.STALE, muchLater.alignment)
        assertFalse(muchLater.alignment.drawable)
    }

    @Test
    fun `a page nobody has touched is drawn exactly where it is`() {
        startAt(1000f, 0)

        val later = predictor.predict(40 * ms)!!

        assertEquals(AlignmentState.ALIGNED, later.alignment)
        assertEquals(0f, later.extrapolatedPx, 0f)
        assertEquals(1000f, later.pose.topPx, 0f)
    }

    @Test
    fun `a finger held still after a drag coasts to a stop rather than running on`() {
        startAt(1000f, 0)
        predictor.onAccessibilityDelta(0f, -60f, 16 * ms)
        predictor.onAccessibilityDelta(0f, -60f, 32 * ms)

        // No further events: the finger stopped but has not lifted.
        val early = predictor.predict(48 * ms)!!
        val slightlyLater = predictor.predict(64 * ms)!!
        val late = predictor.predict(240 * ms)!!

        assertTrue("must keep moving at first", early.pose.topPx < 880f)
        assertTrue("and must decelerate", (early.pose.topPx - slightlyLater.pose.topPx) < 60f)
        // Eventually the guess is too uncertain to draw, and it says so rather
        // than continuing to slide.
        assertFalse("must stop being drawn, not keep going", late.alignment.drawable)
        assertTrue("and must never run away", abs(late.pose.topPx - 880f) <= 150f)
    }

    @Test
    fun `low confidence stops prediction without stopping drawing`() {
        val cautious = MotionPredictor(
            MotionPredictor.Config(minimumPredictionConfidence = 0.9f),
        )
        cautious.placeAt(TargetPose(0f, 1000f), 0)
        cautious.onVisualMeasurement(
            TimedMotionMeasurement(16 * ms, 16 * ms, 0f, -120f, confidence = 0.6f),
        )

        val pose = cautious.predict(32 * ms)!!
        assertEquals(0f, pose.extrapolatedPx, 0f)
    }

    @Test
    fun `a semantic correction is taken as the truth about position`() {
        startAt(1000f, 0)
        predictor.onAccessibilityDelta(0f, -120f, 16 * ms)
        predictor.correctTo(TargetPose(0f, 700f), 20 * ms)

        assertEquals(700f, predictor.predict(20 * ms)!!.pose.topPx, 0.01f)
    }

    @Test
    fun `nothing is predicted before a target has been placed`() {
        assertNull(MotionPredictor().predict(1000))
    }

    @Test
    fun `a reset forgets the target entirely`() {
        startAt(1000f, 0)
        predictor.reset()
        assertNull(predictor.predict(16 * ms))
    }
}
