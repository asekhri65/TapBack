package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.motion.FusionVerdict
import com.aaravsekhri.dot.guide.motion.HybridMotionEstimator
import com.aaravsekhri.dot.guide.motion.MotionSignal
import com.aaravsekhri.dot.guide.motion.MotionSource
import com.aaravsekhri.dot.guide.motion.TrackingMetrics
import com.aaravsekhri.dot.guide.tracking.RenderedTargetTracker
import com.aaravsekhri.dot.guide.visual.VisualTrackingCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when capture is refused, never granted, or taken away.
 *
 * Enhanced tracking is an improvement layered on a guide that already works.
 * Every one of these paths has to end with that guide still running, which is
 * a stronger claim than "it does not crash".
 */
class CaptureFallbackTest {

    private val identity = TargetIdentity(
        windowId = 1,
        viewId = "row",
        normalizedText = "display and touch",
        normalizedDescription = "",
        className = "android.widget.LinearLayout",
    )

    @After
    fun tearDown() {
        VisualTrackingCoordinator.setExperimentEnabled(false)
        VisualTrackingCoordinator.clear()
    }

    @Test
    fun `declining capture leaves no permission and nothing running`() {
        VisualTrackingCoordinator.onConsentResult(null)

        assertNull(VisualTrackingCoordinator.permission.value)
        assertEquals(
            com.aaravsekhri.dot.guide.visual.VisualTrackingState.Inactive,
            VisualTrackingCoordinator.state.value,
        )
    }

    @Test
    fun `accessibility alone still moves the outline when there is no capture`() {
        val hybrid = HybridMotionEstimator()
        val rendered = RenderedTargetTracker()
        rendered.adopt(identity, Bounds(60, 1000, 1110, 1190))

        hybrid.offer(
            MotionSignal(MotionSource.ACCESSIBILITY_SCROLL_DELTA, 0f, -140f, 1f, 1_000L),
        )
        val fused = hybrid.fuse(2_000L)
        assertEquals(FusionVerdict.Confident, fused.verdict)

        rendered.onContentMotion(fused.motion!!.dx, fused.motion!!.dy)
        val moved = rendered.applyPendingDeltas()!!

        // Content went up the screen, so the target went with it.
        assertEquals(860, moved.top)
        assertEquals(1050, moved.bottom)
    }

    @Test
    fun `capture ending mid-guide does not take the fused motion with it`() {
        val hybrid = HybridMotionEstimator()
        hybrid.offer(MotionSignal(MotionSource.GLOBAL_VISUAL_MOTION, 0f, -80f, 0.9f, 1_000L))
        hybrid.fuse(2_000L)

        // Projection stops: no more visual signals, accessibility carries on.
        hybrid.offer(
            MotionSignal(MotionSource.ACCESSIBILITY_SCROLL_DELTA, 0f, -60f, 1f, 3_000L),
        )
        val fused = hybrid.fuse(4_000L)

        assertEquals(FusionVerdict.Confident, fused.verdict)
        assertEquals(-60f, fused.motion!!.dy, 0f)
        assertEquals(setOf(MotionSource.ACCESSIBILITY_SCROLL_DELTA), fused.motion!!.sources)
    }

    @Test
    fun `sub-pixel movement accumulates instead of being thrown away`() {
        val rendered = RenderedTargetTracker()
        rendered.adopt(identity, Bounds(0, 100, 100, 200))

        // Eight frames of two-thirds of a pixel is five and a third pixels.
        repeat(8) {
            rendered.onContentMotion(0f, -0.667f)
            rendered.applyPendingDeltas()
        }

        val top = rendered.geometry!!.renderedRegion.top
        assertTrue("moved to $top", top in 94..95)
    }

    @Test
    fun `rates are measured against the time they were earned in`() {
        var now = 0L
        val metrics = TrackingMetrics(clock = { now })

        metrics.onCaptureStarted()
        now += 10_000_000_000L
        repeat(300) { metrics.onCapturedFrame(64) }

        // Only one of those ten seconds had a target to track.
        metrics.onTrackingStarted()
        now += 1_000_000_000L
        repeat(28) { metrics.onProcessedFrame(6L) }
        metrics.onTrackingStopped()

        assertEquals(27.3, metrics.capturedFps(), 0.5)
        // The old, session-averaged figure here would have been 2.8.
        assertEquals(28.0, metrics.processedFps(), 1.0)
    }

    @Test
    fun `percentiles come from what actually happened`() {
        var now = 0L
        val metrics = TrackingMetrics(clock = { now })
        metrics.onTrackingStarted()
        listOf(4L, 5L, 6L, 7L, 40L).forEach(metrics::onProcessedFrame)

        assertEquals(6L, metrics.percentileLatencyMs(50))
        assertEquals(40L, metrics.percentileLatencyMs(95))
    }

    @Test
    fun `a metrics summary carries numbers and nothing else`() {
        val metrics = TrackingMetrics(clock = { 0L })
        val summary = metrics.summary()

        assertTrue(summary.isNotEmpty())
        assertTrue(
            "summary must be plain measurements: $summary",
            summary.none { it.isLetter() && it.isUpperCase() && summary.contains("frame=") },
        )
    }
}
