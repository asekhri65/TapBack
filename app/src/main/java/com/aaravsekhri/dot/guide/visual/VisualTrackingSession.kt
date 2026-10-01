package com.aaravsekhri.dot.guide.visual

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.motion.MotionSignal
import com.aaravsekhri.dot.guide.motion.MotionSource
import com.aaravsekhri.dot.guide.motion.TrackingMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * One enhanced-tracking session: capture in, motion signals out.
 *
 * Deliberately not a decision maker. It produces two kinds of statement —
 * "the content moved this far" and "the target is this far from where you
 * predicted" — and hands them to the fusion, which decides what, if anything,
 * to believe. Nothing here can choose a target, advance a step, or move the
 * production highlight on its own.
 *
 * Frames are processed on a background dispatcher and superseded rather than
 * queued, so a slow frame delays only itself.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class VisualTrackingSession(
    private val context: Context,
    private val globalEstimator: GlobalMotionEstimator = GlobalMotionEstimator(),
    val local: LocalDriftTracker = LocalDriftTracker(),
    val metrics: TrackingMetrics = TrackingMetrics(),
    private val onSignal: (MotionSignal) -> Unit,
) {
    val mask = OverlayMask()

    private var frameSource: MediaProjectionFrameSource? = null
    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    /** The newest frame, kept only so a handover can take its reference from it. */
    @Volatile
    private var latestFrame: CapturedFrame? = null

    @Volatile
    private var previousFrame: CapturedFrame? = null

    /** Drops already reported, so the counter is not double counted. */
    private var countedDrops = 0L

    /** The part of the screen that is app content: viewport minus system bars. */
    @Volatile
    var contentRegion: Bounds? = null

    /** Where the fusion currently believes the target is. Set once per frame. */
    @Volatile
    var predictedRegion: Bounds? = null

    /** How far around the prediction to look; narrowed when sources agree. */
    @Volatile
    var searchHint: SearchHint = SearchHint.SingleSource

    /** True while a verified target is being followed, for the tracking clock. */
    @Volatile
    private var tracking: Boolean = false

    @Volatile
    var lastFrameTimestampNanos: Long = 0L
        private set

    val isRunning: Boolean get() = frameSource != null

    fun start(
        permission: ProjectionPermission,
        displayWidth: Int,
        displayHeight: Int,
        densityDpi: Int,
    ): Boolean {
        stop()

        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sessionScope

        val source = MediaProjectionFrameSource(
            context = context.applicationContext,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            densityDpi = densityDpi,
            onProjectionStopped = { stop() },
        )
        frameSource = source
        metrics.onCaptureStarted()

        collectJob = sessionScope.launch {
            source.start(permission)
            source.frames.collectLatest { frame -> onFrame(frame) }
        }
        return true
    }

    /**
     * Takes a visual reference for a target accessibility has just verified.
     *
     * Refusing is ordinary: a featureless region, or one sitting under Dot's
     * own card, simply means this guide runs without the visual layer.
     */
    fun onVerifiedTarget(target: VerifiedTarget): Boolean {
        val frame = latestFrame ?: return false
        if (mask.excludes(target.regionInScreen)) {
            GuideLog.d("visual") { "target sits under Dot's card; no visual reference" }
            return false
        }
        val accepted = local.reference(target, frame)
        if (accepted && !tracking) {
            tracking = true
            metrics.onTrackingStarted()
        }
        VisualTrackingCoordinator.publish(
            if (accepted) VisualTrackingState.Tracking(
                TrackedVisualTarget(target, target.regionInScreen, 0f, 0f, 1f, frame.timestampNanos),
            ) else VisualTrackingState.WaitingForVerifiedTarget,
        )
        return accepted
    }

    /** Drops the visual reference: a navigation, or a target no longer trusted. */
    fun releaseTarget() {
        local.forget()
        previousFrame = null
        globalEstimator.reset()
        if (tracking) {
            tracking = false
            metrics.onTrackingStopped()
        }
        VisualTrackingCoordinator.publish(VisualTrackingState.WaitingForVerifiedTarget)
    }

    // -------------------------------------------------------------- frames

    private fun onFrame(frame: CapturedFrame) {
        metrics.onCapturedFrame(frame.luminance.size, frame.conversionNanos)
        // Frames the reader offered while the previous was still in flight.
        // Counted here so the drop rate reflects the capture pipeline rather
        // than only what reached this far.
        frameSource?.droppedFrames?.let { total ->
            while (countedDrops < total) {
                metrics.onDroppedFrame()
                countedDrops++
            }
        }
        lastFrameTimestampNanos = frame.timestampNanos
        latestFrame = frame

        val previous = previousFrame
        previousFrame = frame
        if (previous == null) return

        mask.inFrameCoordinates(frame.transform).let(local::setExcludedRegions)

        if (tracking) metrics.onEligibleFrame()

        estimateGlobalMotion(previous, frame)
        measureLocalDrift(frame)
    }

    /**
     * What the page did between two frames.
     *
     * This runs whether or not there is a target, because it is the only
     * signal that survives a fling — accessibility goes quiet during inertia
     * and the local matcher has nothing near enough to match.
     */
    private fun estimateGlobalMotion(previous: CapturedFrame, frame: CapturedFrame) {
        val region = (contentRegion ?: return).toFrameRect(frame.transform)
        val excluded = mask.inFrameCoordinates(frame.transform)
        val result = globalEstimator.estimate(previous, frame, region, excluded)
        metrics.onProcessedFrame(result.processingTimeMs)

        if (result.kind != GlobalMotionKind.Scrolling || result.confidence <= 0f) return

        val transform = frame.transform
        onSignal(
            MotionSignal(
                source = MotionSource.GLOBAL_VISUAL_MOTION,
                dx = transform.captureToScreenDistanceX(result.dx),
                dy = transform.captureToScreenDistanceY(result.dy),
                confidence = result.confidence,
                timestampNanos = frame.timestampNanos,
            ),
        )
        metrics.onAcceptedEstimate()
    }

    /** How far the prediction was out, when there is a prediction to check. */
    private fun measureLocalDrift(frame: CapturedFrame) {
        if (!local.hasReference) return
        val predicted = predictedRegion ?: return

        when (val result = local.measure(frame, predicted, searchHint)) {
            is LocalDriftTracker.Result.Measured -> onSignal(
                MotionSignal(
                    source = MotionSource.LOCAL_TARGET_MATCH,
                    dx = result.residualX,
                    dy = result.residualY,
                    confidence = result.confidence,
                    timestampNanos = frame.timestampNanos,
                ),
            )

            is LocalDriftTracker.Result.Refused -> metrics.onRejected(result.reason)
            LocalDriftTracker.Result.NoReference -> Unit
        }
    }

    // ---------------------------------------------------------- lifecycle

    fun stop() {
        collectJob?.cancel()
        collectJob = null

        scope?.launch { frameSource?.stop() }
        scope?.cancel()
        scope = null

        frameSource = null
        latestFrame = null
        previousFrame = null
        countedDrops = 0L
        globalEstimator.reset()
        local.forget()
        mask.clear()
        if (tracking) {
            tracking = false
            metrics.onTrackingStopped()
        }
        metrics.onCaptureStopped()

        GuideLog.d("visual") { "session ended | ${metrics.summary()}" }
        VisualTrackingCoordinator.publish(VisualTrackingState.Inactive)
        VisualTrackingCoordinator.clear()
    }

    /** Numeric only, safe to log. */
    fun metricsSummary(): String = metrics.summary()
}
