package com.aaravsekhri.dot.guide.motion

import kotlin.math.abs
import kotlin.math.sign

/**
 * A measurement, with both of the times that matter kept apart.
 *
 * [frameTimestampNanos] is when the content was in this position.
 * [processingCompletedNanos] is when anyone found out. The gap between them is
 * the reason an outline drawn from a measurement is already behind the content
 * it describes, and it was previously discarded.
 */
data class TimedMotionMeasurement(
    val frameTimestampNanos: Long,
    val processingCompletedNanos: Long,
    val dx: Float,
    val dy: Float,
    val confidence: Float,
)

/** A pose for one display frame, with the grounds for trusting it. */
data class PredictedPose(
    val pose: TargetPose,
    val alignment: AlignmentState,
    /** How far ahead of the last measurement this was carried. */
    val extrapolatedNanos: Long,
    val extrapolatedPx: Float,
    val velocityY: Float,
    val confidence: Float,
)

/**
 * Estimates where the target is *now*, rather than where it was measured.
 *
 * **Research code.** Nothing in the production guide calls this. It is kept
 * because the measurements it produced are worth having and the approach may
 * be worth revisiting on a platform that exposes the other app's rendering,
 * but manual testing showed that prediction on top of Android's cross-app
 * signals flickers, loses the target after scrolling, and occasionally lands
 * on a neighbouring row. The shipped guide does not follow scrolls at all.
 *
 * The blue-tape effect this is chasing is impossible to get by applying
 * measurements when they arrive. A captured frame describes the screen 30 to
 * 80 milliseconds ago; drawing it on arrival guarantees the outline trails the
 * content by exactly that much, and no amount of smoothing fixes a constant
 * delay — smoothing adds to it.
 *
 * So position and velocity are tracked with an alpha-beta filter, and the pose
 * handed to the renderer is the filter's estimate *at the upcoming display
 * frame's timestamp*. While the finger moves at a roughly steady speed — which
 * is what a drag is, over the tens of milliseconds involved — that estimate is
 * very nearly right, and the outline appears attached.
 *
 * What this must never do is keep extrapolating once the assumption fails. A
 * reversal, a fling, a gap in measurements or a drop in confidence all end
 * extrapolation immediately, and the caller hides the outline rather than
 * drawing a confident guess over the wrong row.
 */
class MotionPredictor(
    var config: Config = Config(),
) {

    data class Config(
        /** How strongly a measurement corrects position. */
        val alpha: Float = 0.7f,
        /** How strongly a measurement corrects velocity. */
        val beta: Float = 0.35f,
        /**
         * Never carry a pose further ahead than this.
         *
         * Generous on purpose. A slow drag produces only four or five scroll
         * events a second, so the gap between measurements is routinely two
         * hundred milliseconds; a tighter bound would hide the outline through
         * the middle of every gentle scroll. The distance cap below is what
         * actually protects against a bad guess.
         */
        val maxExtrapolationNanos: Long = 250_000_000L,
        /**
         * Nor further than this, however fast the content seemed to be going.
         *
         * Set below the height of a settings row on purpose. An extrapolation
         * error smaller than a row cannot put the outline around the middle of
         * the next one, which is the specific way a late highlight becomes a
         * wrong instruction rather than merely an imprecise one.
         */
        val maxExtrapolationPx: Float = 150f,
        /**
         * Beyond this the estimate is too old to draw from at all.
         *
         * Measured gaps between scroll events during a slow drag run to about
         * a hundred and fifty milliseconds, so a tighter bound would declare a
         * perfectly ordinary scroll stale several times a second. The distance
         * cap is what keeps a long gap from becoming a wrong answer.
         */
        val stalenessNanos: Long = 220_000_000L,
        /** Below this, extrapolation stops and only measurements are drawn. */
        val minimumPredictionConfidence: Float = 0.55f,
        /**
         * How wrong the velocity estimate may be, as a fraction.
         *
         * Multiplied by the age of the newest measurement, this gives an
         * estimate of how far the drawn position could be from the truth —
         * which is the number that decides whether to draw at all.
         */
        val velocityUncertainty: Float = 0.35f,
        /**
         * The most position error worth drawing through.
         *
         * Half a settings row. Beyond this the outline is no longer slightly
         * late, it is arguably around the wrong control, and a wrong
         * instruction is worse than no instruction.
         */
        val maxPositionErrorPx: Float = 90f,
        /** Velocity decays to nothing over this long without a measurement. */
        val velocityHalfLifeNanos: Long = 120_000_000L,
        /** Pixels per second below which the content counts as still. */
        val stillVelocity: Float = 40f,
    )

    private var hasPose = false
    private var x = 0f
    private var y = 0f
    private var velocityX = 0f
    private var velocityY = 0f

    /** The time the filter's state describes — a frame time, not arrival time. */
    private var stateTimeNanos = 0L

    /** When the newest measurement actually reached us, for staleness. */
    private var lastArrivalNanos = 0L
    private var confidence = 0f

    var reversals = 0L
        private set

    val velocity: Float get() = velocityY

    fun reset() {
        hasPose = false
        x = 0f
        y = 0f
        velocityX = 0f
        velocityY = 0f
        stateTimeNanos = 0L
        lastArrivalNanos = 0L
        confidence = 0f
    }

    /**
     * Places the target from an authority that cannot be wrong about position.
     *
     * Used for a semantic reading. Velocity is kept: the page is very likely
     * still moving, and throwing the velocity away here would produce a visible
     * stall on the next frame.
     */
    fun correctTo(pose: TargetPose, atNanos: Long) {
        x = pose.leftPx
        y = pose.topPx
        stateTimeNanos = atNanos
        lastArrivalNanos = atNanos
        confidence = 1f
        hasPose = true
    }

    /** Places the target and stops it dead. Used when a new target is adopted. */
    fun placeAt(pose: TargetPose, atNanos: Long) {
        correctTo(pose, atNanos)
        velocityX = 0f
        velocityY = 0f
    }

    /**
     * An exact displacement from the platform, which arrives almost at once.
     *
     * Applied in full rather than filtered: a scroll delta is not an estimate,
     * it is the number the scrolling view itself used.
     */
    fun onAccessibilityDelta(dx: Float, dy: Float, atNanos: Long) {
        if (!hasPose) return
        detectReversal(dy)

        val dt = (atNanos - stateTimeNanos).coerceAtLeast(0L)
        if (dt > 0) {
            val seconds = dt / 1_000_000_000f
            velocityX = blendVelocity(velocityX, dx / seconds)
            velocityY = blendVelocity(velocityY, dy / seconds)
        }
        x += dx
        y += dy
        stateTimeNanos = atNanos
        lastArrivalNanos = atNanos
        confidence = 1f
    }

    /**
     * A visual measurement, which describes a moment already past.
     *
     * The filter is run forward to the frame's timestamp first, so the
     * measurement is compared against where the target was *then* rather than
     * where it is believed to be now.
     */
    fun onVisualMeasurement(measurement: TimedMotionMeasurement) {
        if (!hasPose) return
        if (measurement.frameTimestampNanos <= stateTimeNanos) return
        detectReversal(measurement.dy)

        val dt = measurement.frameTimestampNanos - stateTimeNanos
        val seconds = dt / 1_000_000_000f
        if (seconds <= 0f) return

        val predictedX = x + velocityX * seconds
        val predictedY = y + velocityY * seconds
        val measuredX = x + measurement.dx
        val measuredY = y + measurement.dy

        val residualX = measuredX - predictedX
        val residualY = measuredY - predictedY

        x = predictedX + config.alpha * residualX
        y = predictedY + config.alpha * residualY
        velocityX += config.beta * residualX / seconds
        velocityY += config.beta * residualY / seconds

        stateTimeNanos = measurement.frameTimestampNanos
        lastArrivalNanos = measurement.processingCompletedNanos
        confidence = measurement.confidence
    }

    /**
     * The pose to draw at [displayTimeNanos], and whether it may be drawn.
     *
     * Extrapolation is bounded three ways — by time, by distance, and by
     * confidence — because every one of those bounds corresponds to a way the
     * constant-velocity assumption stops being true.
     */
    fun predict(displayTimeNanos: Long, motionExpected: Boolean = true): PredictedPose? {
        if (!hasPose) return null

        val age = displayTimeNanos - stateTimeNanos
        val sinceArrival = displayTimeNanos - lastArrivalNanos

        // A page nobody is touching is not a tracking failure. Silence is only
        // suspicious while the content is believed to be moving; at rest it
        // means the last position is still the right one, and an earlier
        // version that could not tell the difference took the outline off a
        // perfectly stationary row after a fifth of a second.
        if (!motionExpected) {
            velocityX = 0f
            velocityY = 0f
            return PredictedPose(
                pose = TargetPose(x, y),
                alignment = AlignmentState.ALIGNED,
                extrapolatedNanos = 0L,
                extrapolatedPx = 0f,
                velocityY = 0f,
                confidence = confidence,
            )
        }

        // How far the drawn position could be from the truth, given how fast
        // the page is going and how old the newest measurement is. This is the
        // honest limit of the whole arrangement: past a certain speed no
        // amount of prediction can place a rectangle from data this old, and
        // the only correct thing left is to stop drawing it.
        val sinceArrivalSeconds = sinceArrival / 1_000_000_000f
        val estimatedErrorPx =
            abs(velocityY) * sinceArrivalSeconds * config.velocityUncertainty
        if (estimatedErrorPx > config.maxPositionErrorPx) {
            return PredictedPose(
                pose = TargetPose(x, y),
                alignment = AlignmentState.STALE,
                extrapolatedNanos = age,
                extrapolatedPx = estimatedErrorPx,
                velocityY = velocityY,
                confidence = confidence,
            )
        }

        // Nothing has been heard for long enough that the content could have
        // done anything at all.
        if (sinceArrival > config.stalenessNanos) {
            return PredictedPose(
                pose = TargetPose(x, y),
                alignment = AlignmentState.STALE,
                extrapolatedNanos = age,
                extrapolatedPx = 0f,
                velocityY = velocityY,
                confidence = confidence,
            )
        }

        val still = abs(velocityY) < config.stillVelocity && abs(velocityX) < config.stillVelocity
        val mayExtrapolate = !still &&
            confidence >= config.minimumPredictionConfidence &&
            age in 0..config.maxExtrapolationNanos

        if (!mayExtrapolate) {
            // Sitting on the last known position. That is honest when the
            // content is still, and when it is not, the caller finds out from
            // the state rather than from a rectangle that looks convincing.
            val alignment = if (still || age <= 0) {
                AlignmentState.ALIGNED
            } else if (age > config.maxExtrapolationNanos) {
                AlignmentState.STALE
            } else {
                AlignmentState.ALIGNED
            }
            return PredictedPose(
                pose = TargetPose(x, y),
                alignment = alignment,
                extrapolatedNanos = age,
                extrapolatedPx = 0f,
                velocityY = velocityY,
                confidence = confidence,
            )
        }

        // Velocity is ramped down across the extrapolation window rather than
        // held flat. Without this the predictor travels at full speed right up
        // to its limit and then stops dead, which is what a finger held still
        // after a drag looks like from here: no more events, and an outline
        // that keeps going. Coasting to a halt is both closer to what the
        // content does and far less alarming when the guess is wrong.
        val seconds = age / 1_000_000_000f
        val window = config.maxExtrapolationNanos.toFloat()
        val decay = (1f - age / window).coerceIn(0f, 1f)
        val effective = 0.5f * (1f + decay)
        var offsetX = velocityX * seconds * effective
        var offsetY = velocityY * seconds * effective

        val distance = maxOf(abs(offsetX), abs(offsetY))
        if (distance > config.maxExtrapolationPx) {
            // Further than this and the assumption is carrying more weight
            // than it can. Stop where the cap is and report it as stale, so
            // the outline comes off rather than being placed on a guess.
            val limit = config.maxExtrapolationPx / distance
            offsetX *= limit
            offsetY *= limit
            return PredictedPose(
                pose = TargetPose(x + offsetX, y + offsetY),
                alignment = AlignmentState.STALE,
                extrapolatedNanos = age,
                extrapolatedPx = config.maxExtrapolationPx,
                velocityY = velocityY,
                confidence = confidence,
            )
        }

        return PredictedPose(
            pose = TargetPose(x + offsetX, y + offsetY),
            alignment = AlignmentState.PREDICTED_HIGH_CONFIDENCE,
            extrapolatedNanos = age,
            extrapolatedPx = distance,
            velocityY = velocityY,
            confidence = confidence,
        )
    }

    /**
     * A change of direction ends the old extrapolation at once.
     *
     * Carrying the previous velocity even one frame past a reversal throws the
     * outline the wrong way, which is far more visible than being briefly
     * still — the eye forgives a pause and does not forgive a lurch.
     */
    private fun detectReversal(dy: Float) {
        if (dy == 0f || velocityY == 0f) return
        if (sign(dy) != sign(velocityY)) {
            velocityY = 0f
            velocityX = 0f
            reversals++
        }
    }

    /** New velocity, with a little of the old one, to ride out sampling jitter. */
    private fun blendVelocity(previous: Float, measured: Float): Float =
        previous * (1f - VELOCITY_BLEND) + measured * VELOCITY_BLEND

    private companion object {
        const val VELOCITY_BLEND = 0.6f
    }
}
