package com.aaravsekhri.dot.guide.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the tracker actually did, measured against the time it was doing it.
 *
 * The first experiment divided every count by the whole session, including the
 * long stretches with no target and nothing to process, and so reported "0.6
 * processed frames per second" for a pipeline that was in fact keeping up with
 * capture whenever it had work. That number was not pessimistic, it was
 * meaningless, and it very nearly sent the design in the wrong direction.
 *
 * So there are two clocks here. One runs while capture is active; one runs only
 * while a verified target is being tracked. Each rate is divided by the clock
 * that applies to it, and rates that have no elapsed time report nothing rather
 * than zero.
 *
 * Nothing derived from a frame's *content* is recorded — only counts, durations
 * and displacements.
 */
class TrackingMetrics(
    private val clock: () -> Long = MotionClock::nowNanos,
) {

    // Explicit flags rather than a zero sentinel: a monotonic clock is allowed
    // to read zero, and a "not started" that means "started at the origin" is
    // a bug that only shows up on the one machine where it does.
    private var capturing = false
    private var captureStartedAt = 0L
    private var captureActiveNanos = 0L

    private var trackingTarget = false
    private var trackingStartedAt = 0L
    private var trackingActiveNanos = 0L

    // A third clock, for the only question a person can actually perceive:
    // while the page was moving, how often did the outline move with it?
    // Measuring that against the whole tracking window buries it under the
    // seconds spent holding a still screen.
    private var moving = false
    private var movingStartedAt = 0L
    private var movingActiveNanos = 0L
    private var movingDisplayed = 0L

    private var captured = 0L
    private var eligible = 0L
    private var processed = 0L
    private var accepted = 0L
    private var displayed = 0L
    private var dropped = 0L

    private val estimatorLatencyMs = ArrayList<Long>(512)
    private val frameAgeMs = ArrayList<Long>(512)
    private val conversionUs = ArrayList<Long>(512)

    /**
     * How far the drawn outline sat from the verified row, every time the two
     * could be compared.
     *
     * This is the number that decides whether the effect works. An outline
     * that moves constantly but stays fifty pixels behind is not attached to
     * anything; one that holds a steady small offset is.
     */
    private val attachmentErrorPx = ArrayList<Int>(256)
    private val reversalErrorPx = ArrayList<Int>(32)

    private var reconcileErrorTotal = 0L
    private var reconcileErrorCount = 0L
    var maxTrailingPx = 0
        private set

    var lostEvents = 0L
        private set
    private var lostPending = false
    private var lostAt = 0L
    private val hideLatencyMs = ArrayList<Long>(32)

    var rejectedLowConfidence = 0L
        private set
    var rejectedImplausible = 0L
        private set
    var rejectedStale = 0L
        private set

    /** The largest single frame held, in kilobytes, for the memory line. */
    var peakFrameKb = 0
        private set

    // ------------------------------------------------------------- clocks

    fun onCaptureStarted() {
        if (capturing) return
        capturing = true
        captureStartedAt = clock()
    }

    fun onCaptureStopped() {
        if (capturing) {
            captureActiveNanos += clock() - captureStartedAt
            capturing = false
        }
        onTrackingStopped()
    }

    fun onTrackingStarted() {
        if (trackingTarget) return
        trackingTarget = true
        trackingStartedAt = clock()
    }

    fun onTrackingStopped() {
        if (trackingTarget) {
            trackingActiveNanos += clock() - trackingStartedAt
            trackingTarget = false
        }
        onMovingStopped()
    }

    /** Content has started moving, and is expected to keep moving. */
    fun onMovingStarted() {
        if (moving) return
        moving = true
        movingStartedAt = clock()
    }

    fun onMovingStopped() {
        if (!moving) return
        movingActiveNanos += clock() - movingStartedAt
        moving = false
    }

    // -------------------------------------------------------------- counts

    fun onCapturedFrame(sizeBytes: Int, conversionNanos: Long = 0L) {
        captured++
        peakFrameKb = max(peakFrameKb, sizeBytes / 1024)
        if (conversionNanos > 0) conversionUs += conversionNanos / 1_000
    }

    /** A frame that arrived while a target was being tracked. */
    fun onEligibleFrame() {
        eligible++
    }

    fun onProcessedFrame(latencyMs: Long) {
        processed++
        estimatorLatencyMs += latencyMs
    }

    fun onAcceptedEstimate() {
        accepted++
    }

    /**
     * Movement reached the overlay.
     *
     * [frameAgeMs] is how old the frame that caused it was by the time it was
     * drawn — the end-to-end latency a person would perceive as lag.
     */
    fun onDisplayedUpdate(frameAgeMs: Long) {
        displayed++
        if (moving) movingDisplayed++
        if (frameAgeMs >= 0) this.frameAgeMs += frameAgeMs
    }

    fun onDroppedFrame() {
        dropped++
    }

    fun onRejected(reason: RejectionReason) {
        when (reason) {
            RejectionReason.LowConfidence -> rejectedLowConfidence++
            RejectionReason.Implausible -> rejectedImplausible++
            RejectionReason.Stale -> rejectedStale++
        }
    }

    /** How far the drawn outline was from the authoritative bounds. */
    fun onReconciled(errorPx: Int, duringReversal: Boolean = false) {
        val magnitude = abs(errorPx)
        reconcileErrorTotal += magnitude
        reconcileErrorCount++
        maxTrailingPx = max(maxTrailingPx, magnitude)
        attachmentErrorPx += magnitude
        if (duringReversal) reversalErrorPx += magnitude
    }

    fun percentileAttachmentErrorPx(percentile: Int): Int =
        percentileOfInt(attachmentErrorPx, percentile)

    fun percentileConversionUs(percentile: Int): Long = percentileOf(conversionUs, percentile)

    fun maxReversalErrorPx(): Int = reversalErrorPx.maxOrNull() ?: 0

    fun onLost() {
        lostEvents++
        lostPending = true
        lostAt = clock()
    }

    /** The outline came off the screen after a loss. */
    fun onOutlineHidden() {
        if (!lostPending) return
        hideLatencyMs += (clock() - lostAt) / 1_000_000
        lostPending = false
    }

    // --------------------------------------------------------------- rates

    private fun captureSeconds(): Double {
        val running = if (capturing) clock() - captureStartedAt else 0L
        return (captureActiveNanos + running) / 1_000_000_000.0
    }

    private fun trackingSeconds(): Double {
        val running = if (trackingTarget) clock() - trackingStartedAt else 0L
        return (trackingActiveNanos + running) / 1_000_000_000.0
    }

    fun capturedFps(): Double = rate(captured, captureSeconds())
    fun droppedFps(): Double = rate(dropped, captureSeconds())
    fun eligibleFps(): Double = rate(eligible, trackingSeconds())
    fun processedFps(): Double = rate(processed, trackingSeconds())
    fun acceptedFps(): Double = rate(accepted, trackingSeconds())
    fun displayedFps(): Double = rate(displayed, trackingSeconds())

    /** Geometry updates per second while the page was actually moving. */
    fun movingDisplayedFps(): Double = rate(movingDisplayed, movingSeconds())

    private fun movingSeconds(): Double {
        val running = if (moving) clock() - movingStartedAt else 0L
        return (movingActiveNanos + running) / 1_000_000_000.0
    }

    fun percentileLatencyMs(percentile: Int): Long = percentileOf(estimatorLatencyMs, percentile)
    fun percentileFrameAgeMs(percentile: Int): Long = percentileOf(frameAgeMs, percentile)

    fun meanReconcileErrorPx(): Double =
        if (reconcileErrorCount == 0L) 0.0 else reconcileErrorTotal.toDouble() / reconcileErrorCount

    fun meanHideLatencyMs(): Long =
        if (hideLatencyMs.isEmpty()) 0L else hideLatencyMs.sum() / hideLatencyMs.size

    fun maxHideLatencyMs(): Long = hideLatencyMs.maxOrNull() ?: 0L

    private fun rate(count: Long, seconds: Double): Double =
        if (seconds <= 0.05) 0.0 else count / seconds

    private fun percentileOfInt(values: List<Int>, percentile: Int): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = ((percentile / 100.0) * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun percentileOf(values: List<Long>, percentile: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = ((percentile / 100.0) * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** Numbers only. Safe to log, by construction. */
    fun summary(): String = buildString {
        append("captureS=").append(format(captureSeconds()))
        append(" trackS=").append(format(trackingSeconds()))
        append(" capFps=").append(format(capturedFps()))
        append(" eligFps=").append(format(eligibleFps()))
        append(" procFps=").append(format(processedFps()))
        append(" acceptFps=").append(format(acceptedFps()))
        append(" dispFps=").append(format(displayedFps()))
        append(" moveS=").append(format(movingSeconds()))
        append(" moveDispFps=").append(format(movingDisplayedFps()))
        append(" dropFps=").append(format(droppedFps()))
        append(" estP50=").append(percentileLatencyMs(50))
        append(" estP95=").append(percentileLatencyMs(95))
        append(" ageP50=").append(percentileFrameAgeMs(50))
        append(" ageP95=").append(percentileFrameAgeMs(95))
        append(" convP50us=").append(percentileConversionUs(50))
        append(" convP95us=").append(percentileConversionUs(95))
        append(" attachP50=").append(percentileAttachmentErrorPx(50))
        append(" attachP95=").append(percentileAttachmentErrorPx(95))
        append(" errMean=").append(format(meanReconcileErrorPx()))
        append(" errMax=").append(maxTrailingPx)
        append(" revErrMax=").append(maxReversalErrorPx())
        append(" lost=").append(lostEvents)
        append(" hideMeanMs=").append(meanHideLatencyMs())
        append(" hideMaxMs=").append(maxHideLatencyMs())
        append(" rejConf=").append(rejectedLowConfidence)
        append(" rejImpl=").append(rejectedImplausible)
        append(" rejStale=").append(rejectedStale)
        append(" peakFrameKb=").append(peakFrameKb)
    }

    private fun format(value: Double): String = ((value * 10).roundToInt() / 10.0).toString()
}

/** Why an estimate was refused, counted separately so causes stay legible. */
enum class RejectionReason { LowConfidence, Implausible, Stale }
