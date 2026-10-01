package com.aaravsekhri.dot.guide.visual

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.motion.RejectionReason
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Measures how wrong the predicted position of the verified target was.
 *
 * This is all that is left of the local matcher's old job, and the reduction
 * is the point. In the first experiment it was asked "where did the target
 * go?", given a window four hundred pixels tall, and set loose on a list whose
 * rows are deliberately identical. It answered confidently and, often enough
 * to be dangerous, one row out.
 *
 * Now something that actually knows how far the screen moved says where the
 * target should be, and this only reports the remainder. A residual is small
 * by definition; anything large is not a correction but a different opinion
 * about which row this is, and it is refused rather than reported.
 *
 * It never chooses a region, never names a target, and cannot return anything
 * but a displacement and a confidence.
 */
class LocalDriftTracker(
    private val estimator: TemplateMatchEstimator = TemplateMatchEstimator(),
    private val minimumConfidence: Float = DEFAULT_MINIMUM_CONFIDENCE,
    private val minimumUniqueness: Float = DEFAULT_MINIMUM_UNIQUENESS,
    private val maximumResidualPx: Float = DEFAULT_MAXIMUM_RESIDUAL_PX,
) {

    /** What one measurement concluded. */
    sealed interface Result {
        data class Measured(
            val residualX: Float,
            val residualY: Float,
            val confidence: Float,
            val uniqueness: Float,
            val latencyMs: Long,
        ) : Result

        data class Refused(val reason: RejectionReason, val latencyMs: Long) : Result

        /** Nothing to measure against yet. */
        data object NoReference : Result
    }

    private var referenceFrame: CapturedFrame? = null
    private var referenceRegion: Bounds? = null
    private var referenceIdentity: TargetIdentity? = null

    val hasReference: Boolean get() = referenceFrame != null

    /** The identity the current reference belongs to, for change detection. */
    val identity: TargetIdentity? get() = referenceIdentity

    /**
     * Takes a new reference from [frame] for a target semantics has verified.
     *
     * The template is taken from inside Dot's highlight ring. On a device where
     * the projection composites accessibility overlays, an edge-to-edge
     * template would be partly a picture of Dot's own outline — which follows
     * the rendered position and would report confident zero movement forever.
     */
    fun reference(target: VerifiedTarget, frame: CapturedFrame): Boolean {
        val rect = target.regionInScreen
            .toFrameRect(frame.transform)
            .insetBy((TEMPLATE_INSET_PX * frame.scale).roundToInt())

        if (!estimator.initialize(frame, rect).succeeded) {
            forget()
            return false
        }
        referenceFrame = frame
        referenceRegion = target.regionInScreen
        referenceIdentity = target.identity
        return true
    }

    fun forget() {
        referenceFrame = null
        referenceRegion = null
        referenceIdentity = null
    }

    /**
     * How far [predictedRegion] is from where the target actually appears.
     *
     * Measured against the reference frame rather than the previous one, so
     * error does not compound frame after frame. [hint] decides how far around
     * the prediction to look, and a narrow hint is also a hint too narrow to
     * contain the neighbouring row.
     */
    fun measure(frame: CapturedFrame, predictedRegion: Bounds, hint: SearchHint): Result {
        val reference = referenceFrame ?: return Result.NoReference
        val origin = referenceRegion ?: return Result.NoReference
        if (frame.timestampNanos <= reference.timestampNanos) {
            return Result.Refused(RejectionReason.Stale, 0L)
        }

        val transform = frame.transform
        val scale = transform.scale
        val predictedDx = ((predictedRegion.left - origin.left) * scale).roundToInt()
        val predictedDy = ((predictedRegion.top - origin.top) * scale).roundToInt()

        val estimate = estimator.estimate(
            previousFrame = reference,
            currentFrame = frame,
            currentTarget = origin.toFrameRect(transform)
                .insetBy((TEMPLATE_INSET_PX * scale).roundToInt()),
            hint = hint.copy(predictedDx = predictedDx, predictedDy = predictedDy),
        )

        if (estimate.confidence < minimumConfidence) {
            return Result.Refused(RejectionReason.LowConfidence, estimate.processingTimeMs)
        }
        // A strong peak with an equally strong rival means several rows matched
        // this well. That is the one-row mistake waiting to happen.
        if (estimate.uniquenessMargin < minimumUniqueness) {
            return Result.Refused(RejectionReason.LowConfidence, estimate.processingTimeMs)
        }

        val residualX = (estimate.deltaX - predictedDx) / scale
        val residualY = (estimate.deltaY - predictedDy) / scale
        if (abs(residualX) > maximumResidualPx || abs(residualY) > maximumResidualPx) {
            return Result.Refused(RejectionReason.Implausible, estimate.processingTimeMs)
        }

        return Result.Measured(
            residualX = residualX,
            residualY = residualY,
            confidence = estimate.confidence,
            uniqueness = estimate.uniquenessMargin,
            latencyMs = estimate.processingTimeMs,
        )
    }

    /** Keeps Dot's own drawing out of the match, where a device composites it. */
    fun setExcludedRegions(regions: List<FrameRect>) = estimator.setExcludedRegions(regions)

    companion object {
        const val DEFAULT_MINIMUM_CONFIDENCE = 0.55f
        const val DEFAULT_MINIMUM_UNIQUENESS = 0.08f

        /**
         * Beyond this the answer is not drift.
         *
         * A Settings row is roughly 190 screen pixels tall on the reference
         * device, so this cannot accommodate a one-row jump however confident
         * the correlation looks.
         */
        const val DEFAULT_MAXIMUM_RESIDUAL_PX = 70f

        /** Screen pixels trimmed off each edge, clearing Dot's highlight stroke. */
        const val TEMPLATE_INSET_PX = 24f
    }
}
