package com.aaravsekhri.dot.guide.motion

/**
 * Where a statement about movement came from.
 *
 * The order matters: each source answers a different question, and none of
 * them may answer another's. Only accessibility semantics establish *which*
 * control is the target. Everything here only ever says *how far the screen
 * moved*, which is a separate question and a much safer one to be wrong about.
 */
enum class MotionSource {
    /** The exact distance a scrollable reported. Immediate, but not continuous. */
    ACCESSIBILITY_SCROLL_DELTA,

    /** Dominant translation between two captured frames. Continuous, approximate. */
    GLOBAL_VISUAL_MOTION,

    /** Where the verified target's own appearance moved to. Correction only. */
    LOCAL_TARGET_MATCH,

    /** A fresh read of the accessibility tree. Authoritative, and late. */
    SEMANTIC_RECONCILIATION,
}

/**
 * One source's account of how the content moved, in screen pixels.
 *
 * [dx] and [dy] describe the *content's* movement, consistently across every
 * source: a positive [dy] means content travelled down the screen. The drawn
 * target follows content, so it moves by the same amount — the inversion of
 * the platform's scroll delta happens where the signal is created, once, and
 * never again after that.
 *
 * @param timestampNanos monotonic, from the same clock for every source, so
 *   freshness can actually be compared.
 */
data class MotionSignal(
    val source: MotionSource,
    val dx: Float,
    val dy: Float,
    val confidence: Float,
    val timestampNanos: Long,
)

/**
 * What the sources, taken together, say the content did.
 *
 * [sources] is the set that actually contributed, not the set that spoke. A
 * signal that was rejected, was stale, or merely agreed with a better one is
 * not listed, so the field can be trusted when reading traces.
 */
data class FusedMotionEstimate(
    val dx: Float,
    val dy: Float,
    val confidence: Float,
    val sources: Set<MotionSource>,
    val timestampNanos: Long,
    /**
     * When the content was actually in this position.
     *
     * For a scroll delta that is essentially now; for a captured frame it is
     * tens of milliseconds ago. Keeping it is what lets the renderer draw
     * where the row *is* rather than where it *was*, and losing it is why the
     * outline used to trail by exactly the measurement latency.
     */
    val sourceTimestampNanos: Long = timestampNanos,
)

/**
 * How much the fused answer can be relied on, which decides what is drawn.
 *
 * This is a separate judgement from the displacement itself. Movement can be
 * known precisely and still be untrustworthy — for instance when two sources
 * both sound confident and contradict each other.
 */
enum class FusionVerdict {
    /** Two independent sources agree, or accessibility spoke plainly. Draw it. */
    Confident,

    /** One source only. Draw it, but confirmation is owed shortly. */
    Tentative,

    /** Sources contradict each other, or confirmation never came. Hold still. */
    Uncertain,

    /** Nothing fresh from anywhere. Give up the target. */
    Starved,
}

/** The fused movement together with the judgement about it. */
data class FusionResult(
    val verdict: FusionVerdict,
    val motion: FusedMotionEstimate?,
    /** Set when a rule refused a signal, for tracing and for tests. */
    val rejected: Set<MotionSource> = emptySet(),
)
