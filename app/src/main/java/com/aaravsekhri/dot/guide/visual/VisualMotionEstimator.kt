package com.aaravsekhri.dot.guide.visual

/** What one frame-to-frame estimate concluded. */
data class MotionEstimate(
    val deltaX: Float,
    val deltaY: Float,
    /** 0f to 1f. Below the tracker's threshold the estimate is not used. */
    val confidence: Float,
    val processingTimeMs: Long,
    val timestampNanos: Long,
    /**
     * How far clear the winning position was of the next plausible one.
     *
     * On a list of near identical rows this is the number that matters. A
     * perfect-looking peak with no margin means several rows matched equally
     * well, which is precisely when a confident answer would be a one-row
     * mistake.
     */
    val uniquenessMargin: Float = 0f,
) {
    companion object {
        fun none(timestampNanos: Long, processingTimeMs: Long) =
            MotionEstimate(0f, 0f, 0f, processingTimeMs, timestampNanos)
    }
}

/**
 * Where to look, and how far around it.
 *
 * The local matcher no longer goes hunting. Something that actually knows how
 * far the screen moved — a scroll delta, or the global estimator — says where
 * the target should be, and this only has to decide how wrong that was. The
 * radius shrinks when two sources agree, because a confident prediction needs
 * only a nudge, and a narrow window is also a window a neighbouring row cannot
 * fit inside.
 */
data class SearchHint(
    val predictedDx: Int = 0,
    val predictedDy: Int = 0,
    val radiusX: Int = DEFAULT_RADIUS_X,
    val radiusY: Int = DEFAULT_RADIUS_Y,
) {
    companion object {
        const val DEFAULT_RADIUS_X = 12
        const val DEFAULT_RADIUS_Y = 100

        /** Both motion sources agreed: only drift is left to find. */
        val Corroborated = SearchHint(radiusX = 4, radiusY = 8)

        /** One source only: allow for its error, but not for a whole row. */
        val SingleSource = SearchHint(radiusX = 6, radiusY = 20)
    }
}

/** Whether a target could be locked onto at all. */
data class TrackingInitializationResult(
    val succeeded: Boolean,
    /** How distinctive the reference patch is. Featureless patches fail. */
    val confidence: Float,
    val reason: String? = null,
)

/**
 * Estimates how far an already-identified region moved between two frames.
 *
 * The division of labour matters: accessibility decides *what* the target is,
 * and this decides only *where it went*. An estimator that could choose a
 * different region would be able to reintroduce the failure the semantic gate
 * exists to prevent, so it is not given the opportunity — it receives a region
 * and returns a displacement, and has no vocabulary for anything else.
 */
interface VisualMotionEstimator {

    /**
     * Prepares to follow [verifiedTarget], whose identity accessibility has
     * already confirmed.
     */
    fun initialize(
        frame: CapturedFrame,
        verifiedTarget: FrameRect,
    ): TrackingInitializationResult

    /**
     * How far the region moved between the two frames.
     *
     * [hint] centres and bounds the search. Its radii are in frame pixels.
     */
    fun estimate(
        previousFrame: CapturedFrame,
        currentFrame: CapturedFrame,
        currentTarget: FrameRect,
        hint: SearchHint = SearchHint(),
    ): MotionEstimate
}
