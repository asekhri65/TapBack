package com.aaravsekhri.dot.ui.guide

import androidx.compose.runtime.Immutable

/**
 * What the app is showing on top of the Ask tab while a guide is being set up.
 *
 * Getting to a running guide means passing through consent and, usually, a
 * trip to system settings. Each of those is a distinct screen rather than a
 * dialog buried somewhere, so this models them explicitly.
 */
@Immutable
sealed interface GuideLaunchState {

    /** Nothing in the way; the Ask tab is showing normally. */
    data object Idle : GuideLaunchState

    /** The prominent disclosure, shown before accessibility access is ever asked for. */
    data class Disclosure(val guideId: String) : GuideLaunchState

    /**
     * The second, separate disclosure: relevant screen content leaving the
     * phone so a service can work out the next step.
     *
     * Kept apart from [Disclosure] because it is a different thing to agree
     * to. Reading the screen on the phone and sending part of it away are not
     * the same, and consenting to the first is not consenting to the second.
     */
    data class CloudDisclosure(val goal: String) : GuideLaunchState

    /** Explaining what to do in Android's Accessibility settings. */
    data class NeedsService(val guideId: String) : GuideLaunchState

    /** Back from settings with access granted; offering to carry on. */
    data class ReadyToResume(val guideId: String, val goal: String? = null) : GuideLaunchState

    /** Consent was declined, or access is off; Dot explains what it can still do. */
    data class Limited(val guideId: String) : GuideLaunchState

    /** The request does not match any guide Dot ships. */
    data object Unsupported : GuideLaunchState

    /** This build was not given a planning service to ask. */
    data object NoPlanningService : GuideLaunchState

    /** Dot needs one thing cleared up before it can carry on. */
    data class Question(val question: String, val options: List<String> = emptyList()) : GuideLaunchState

    /** An unfinished request from last time, offered rather than resumed. */
    data class ResumeOffer(val goal: String) : GuideLaunchState

    /** A new task being admitted on Dot's service: a moment, before anything starts. */
    data object Admitting : GuideLaunchState

    /**
     * Dot Plus, in Dot's own screen: why it is shown ([reason]: "no_allowance",
     * "premium_course", or "browse" when asked for), and never over another app.
     */
    data class Plus(val reason: String) : GuideLaunchState

    /** The task could not be admitted because Dot's service could not be asked. */
    data class AdmissionFailed(val kind: com.aaravsekhri.dot.guide.ai.PlanningFailure) : GuideLaunchState

    /**
     * Something else is running. Starting [next] would stop [current] — both
     * are saved — so the person says which they want.
     */
    data class ReplaceGuide(val current: String, val next: String) : GuideLaunchState
}
