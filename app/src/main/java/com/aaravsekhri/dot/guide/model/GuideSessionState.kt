package com.aaravsekhri.dot.guide.model

/** Why a guide needed to fall back to recovery. */
enum class RecoveryReason {
    /** The target could not be found on the screen that is in front. */
    TargetNotFound,

    /** The person asked for help with "I don't see that". */
    UserAskedForHelp,

    /** Something other than the expected app is in front. */
    LeftExpectedApp,
}

/** Why a guide stopped without finishing. */
enum class FailureReason {
    /** Dot's accessibility service went away mid-guide. */
    ServiceUnavailable,

    /** The session could not be trusted after the process was recreated. */
    SessionNotRecoverable,
}

/**
 * Where a guide session currently stands.
 *
 * Every overlay decision is derived from this, so there is one place to look
 * when asking what Dot is showing and why.
 */
sealed interface GuideSessionState {

    /** No guide running. Dot reads nothing and shows nothing. */
    data object Inactive : GuideSessionState

    /** A guide has been asked for; Settings is being opened. */
    data class Starting(val guideId: String) : GuideSessionState

    /**
     * Looking for the current step's target on whatever is in front.
     *
     * Nothing is drawn over the target area in this state. During an ordinary
     * page change that means a brief empty moment, which is the point: better
     * a clear screen for a fraction of a second than an outline left behind on
     * a page that has gone.
     */
    data class Searching(val guideId: String, val stepIndex: Int) : GuideSessionState

    /**
     * The target is not on this part of the page, but the page scrolls and the
     * step says which way it lies.
     *
     * Dot shows a cue and waits. It never scrolls on the person's behalf.
     */
    data class ScrollNeeded(
        val guideId: String,
        val stepIndex: Int,
        val direction: ScrollDirection,
    ) : GuideSessionState

    /** The target has been located and the highlight is about to be placed. */
    data class TargetFound(
        val guideId: String,
        val stepIndex: Int,
        /** The whole logical setting being pointed at, not one node of it. */
        val region: SemanticTargetRegion,
        /** The wording on the control, when it has any. */
        val matchedLabel: String? = null,
        /**
         * Increments only when a genuinely different target is settled on.
         *
         * The overlay uses it as the key for Pebble's pop, so a target whose
         * bounds merely shifted keeps the Pebble it already has instead of
         * popping again at every event.
         */
        val targetGeneration: Int = 0,
    ) : GuideSessionState

    /** The highlight is up and it is the person's turn to act. */
    data class WaitingForUser(
        val guideId: String,
        val stepIndex: Int,
        /** The whole logical setting being pointed at, not one node of it. */
        val region: SemanticTargetRegion,
        /** The wording on the control, when it has any. */
        val matchedLabel: String? = null,
        /** See [TargetFound.targetGeneration]. */
        val targetGeneration: Int = 0,
        /** True once an adjustment has been seen on an [StepAction.Adjust] step. */
        val adjustmentSeen: Boolean = false,
    ) : GuideSessionState

    /** The person pressed Pause. Overlays shrink to a single Resume card. */
    /**
     * The person tapped the right thing; the next page has not arrived yet.
     *
     * Nothing positional is shown here. The control that was tapped belongs to
     * the page being left, and the next step's control does not exist yet, so
     * there is nothing honest to point at for the moment in between.
     *
     * @param since when the tap happened, so a tap that goes nowhere can be
     *   noticed and the step resumed rather than left hanging.
     */
    data class AwaitingDestination(
        val guideId: String,
        val stepIndex: Int,
        val since: Long,
    ) : GuideSessionState

    data class Paused(val guideId: String, val stepIndex: Int) : GuideSessionState

    /** Dot cannot see the target, or the person said they cannot. */
    data class Recovery(
        val guideId: String,
        val stepIndex: Int,
        val reason: RecoveryReason,
    ) : GuideSessionState

    /** Every step is done. */
    data class Completed(val guideId: String) : GuideSessionState

    /** The person exited. */
    data class Cancelled(val guideId: String) : GuideSessionState

    /** Dot had to stop for a reason that was not the person's doing. */
    data class Failed(val guideId: String, val reason: FailureReason) : GuideSessionState
}

/** The step this state refers to, or null when there is no active step. */
val GuideSessionState.stepIndexOrNull: Int?
    get() = when (this) {
        is GuideSessionState.Searching -> stepIndex
        is GuideSessionState.ScrollNeeded -> stepIndex
        is GuideSessionState.TargetFound -> stepIndex
        is GuideSessionState.WaitingForUser -> stepIndex
        is GuideSessionState.AwaitingDestination -> stepIndex
        is GuideSessionState.Paused -> stepIndex
        is GuideSessionState.Recovery -> stepIndex
        else -> null
    }

/** True while Dot is entitled to read screen content. */
val GuideSessionState.isActive: Boolean
    get() = when (this) {
        GuideSessionState.Inactive,
        is GuideSessionState.Completed,
        is GuideSessionState.Cancelled,
        is GuideSessionState.Failed,
        -> false

        else -> true
    }
