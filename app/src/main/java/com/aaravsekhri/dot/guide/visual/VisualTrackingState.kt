package com.aaravsekhri.dot.guide.visual

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds

/**
 * A target whose identity accessibility has already established.
 *
 * The visual tracker cannot construct one of these: it only ever receives one.
 * That is the whole safety boundary — vision moves a region that semantics
 * chose, and has no means of choosing a different one.
 */
data class VerifiedTarget(
    val identity: TargetIdentity,
    val regionInScreen: Bounds,
    /** Which semantic generation verified it; older ones cannot update newer. */
    val generation: Long,
)

/** A verified target as the visual tracker currently sees it. */
data class TrackedVisualTarget(
    val verified: VerifiedTarget,
    /** Where it is now, in screen pixels, after accumulated visual motion. */
    val regionInScreen: Bounds,
    val cumulativeDeltaX: Float,
    val cumulativeDeltaY: Float,
    val lastConfidence: Float,
    val frameTimestampNanos: Long,
)

/** Where visual tracking stands. */
sealed interface VisualTrackingState {

    /** Not running. */
    data object Inactive : VisualTrackingState

    /** Running, but accessibility has not handed over a verified target. */
    data object WaitingForVerifiedTarget : VisualTrackingState

    /** Following a target frame to frame. */
    data class Tracking(val target: TrackedVisualTarget) : VisualTrackingState

    /**
     * Confidence has dropped. The overlay freezes briefly rather than
     * following an estimate that may be wrong.
     */
    data class Uncertain(val lastTarget: TrackedVisualTarget?) : VisualTrackingState

    /** Given up. The highlight comes down and the scroll cue takes over. */
    data object Lost : VisualTrackingState
}
