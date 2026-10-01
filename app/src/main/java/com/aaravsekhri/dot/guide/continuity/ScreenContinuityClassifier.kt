package com.aaravsekhri.dot.guide.continuity

/** What actually happened when the screen changed under the guide. */
enum class ScreenChangeKind {
    /** The same page, moved. */
    SAME_SCREEN_SCROLL,

    /** The same page, with a control's value changed — a slider nudged a notch. */
    SAME_SCREEN_CONTROL_MUTATION,

    /**
     * The same page, rebuilt from scratch. Changing the system font size does
     * this: new nodes, new measurements, often a new window, same screen.
     */
    SAME_SCREEN_LAYOUT_REBUILD,

    /** A genuinely different place. */
    TRUE_NAVIGATION,

    /** Not enough evidence either way. Treated cautiously by the caller. */
    UNKNOWN,
}

/**
 * Decides whether the person has actually gone anywhere.
 *
 * This exists because no single accessibility event answers that question.
 * `TYPE_WINDOW_STATE_CHANGED` fires when a page opens — and also when the font
 * size changes and Settings rebuilds the page the person is already looking at.
 * Acting on the event alone made a one-notch slider adjustment look identical
 * to walking to another screen, so the highlight vanished and popped back.
 *
 * The evidence used instead is what the screen says and what the guide is
 * doing, neither of which a rebuild disturbs.
 */
object ScreenContinuityClassifier {

    /**
     * Landmark overlap above which two screens are taken to be the same page,
     * even if every node beneath them has been replaced.
     */
    const val CONTINUITY_OVERLAP = 0.6f

    /**
     * @param stepAdvanced whether the guide itself moved on. The one signal
     *   that is navigation by definition, because Dot caused it.
     * @param scrollObserved whether a scroll event accompanied this change.
     * @param targetStillPresent whether the step's target can be seen right now.
     */
    fun classify(
        previous: ScreenFingerprint?,
        current: ScreenFingerprint,
        stepAdvanced: Boolean,
        scrollObserved: Boolean,
        targetStillPresent: Boolean,
    ): ScreenChangeKind {
        // The guide moved itself on. Nothing to infer.
        if (stepAdvanced) return ScreenChangeKind.TRUE_NAVIGATION

        if (previous == null) return ScreenChangeKind.UNKNOWN

        // A different app is a different place, always.
        if (previous.packageName != current.packageName) return ScreenChangeKind.TRUE_NAVIGATION

        // A different step is a different place.
        if (previous.stepIndex != current.stepIndex) return ScreenChangeKind.TRUE_NAVIGATION

        val sameTarget = previous.targetLabel == current.targetLabel &&
            previous.targetRole == current.targetRole
        val overlap = previous.landmarkOverlapWith(current)
        val familiar = overlap >= CONTINUITY_OVERLAP

        return when {
            // Content moved under a target that is still there.
            scrollObserved && sameTarget && targetStillPresent ->
                ScreenChangeKind.SAME_SCREEN_SCROLL

            // The page still reads the same and the control is still there:
            // its value changed, nothing more.
            familiar && sameTarget && targetStillPresent ->
                ScreenChangeKind.SAME_SCREEN_CONTROL_MUTATION

            // The page still reads the same but the control has momentarily
            // gone. That is a rebuild in progress, not a departure.
            familiar -> ScreenChangeKind.SAME_SCREEN_LAYOUT_REBUILD

            // Nothing recognisable left.
            overlap == 0f -> ScreenChangeKind.TRUE_NAVIGATION

            else -> ScreenChangeKind.UNKNOWN
        }
    }
}
