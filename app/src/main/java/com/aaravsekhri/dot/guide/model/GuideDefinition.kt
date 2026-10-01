package com.aaravsekhri.dot.guide.model

import androidx.annotation.StringRes

/**
 * What Dot is looking for on screen.
 *
 * Several candidates are given for every field because the same control is
 * worded differently across Android versions and manufacturers. Nothing here
 * is required to match exactly — [com.aaravsekhri.dot.guide.matching.TargetMatcher]
 * scores candidates and picks the best one, or none at all.
 */
data class TargetSpec(
    /** Visible wording the control may carry, best first. */
    val labels: List<String> = emptyList(),
    /** Spoken descriptions the control may carry. */
    val contentDescriptions: List<String> = emptyList(),
    /** Fragments of a resource id, e.g. "font_size" in "…:id/font_size_slider". */
    val viewIdFragments: List<String> = emptyList(),
    /** Class names that would make a candidate more likely, e.g. "SeekBar". */
    val expectedClassNames: List<String> = emptyList(),
    /**
     * Whether the thing being pointed at should be tappable. False for
     * controls a person drags rather than taps.
     */
    val requireInteractive: Boolean = true,
    /**
     * Whether wording alone is enough.
     *
     * Set this when the target is a specific widget rather than a row of text —
     * a slider, say. Settings screens are full of banners and summaries that
     * mention the same words as the control they describe, and pointing at the
     * explanation instead of the control is exactly the wrong outcome. With
     * this on, a candidate must also match [viewIdFragments] or
     * [expectedClassNames].
     */
    val requireStructuralMatch: Boolean = false,
    /**
     * How much around the matched node belongs to the thing being pointed at.
     * See [TargetRegionStrategy].
     */
    val regionStrategy: TargetRegionStrategy = TargetRegionStrategy.CLICKABLE_PARENT,
    /**
     * Wording carried by the other parts of the same setting — its title, its
     * line of explanation — so they can be gathered into one region.
     */
    val groupLabels: List<String> = emptyList(),
    /**
     * Classes of the controls belonging to the same setting, such as the
     * slider and the buttons either side of it.
     */
    val groupClassNames: List<String> = emptyList(),
    /**
     * Wording that marks where this setting stops.
     *
     * The title of whatever comes next, usually. A region is never grown
     * across one of these, which is what stops "Font size" swallowing the
     * "Display size" underneath it.
     */
    val boundaryLabels: List<String> = emptyList(),
    /**
     * The exact wording that proves a candidate really is this target.
     *
     * Checked for equality after normalisation, never as a substring, and
     * required before anything is highlighted. Scoring decides which candidate
     * is best; this decides whether any candidate is acceptable at all.
     *
     * Left empty, [labels] are used, so every guide is gated by default.
     */
    val identityLabels: List<String> = emptyList(),
    /**
     * Resource-id fragments specific enough to prove identity on their own.
     *
     * Distinct from [viewIdFragments], which may be loose enough to help with
     * scoring — "slider" and "seekbar" narrow a search usefully but name
     * nothing in particular, and on the display page they match the Display
     * size control just as well as the Font size one. Only fragments that
     * identify this control and no other belong here.
     *
     * Left empty, [viewIdFragments] are used.
     */
    val identityViewIdFragments: List<String> = emptyList(),
)

/**
 * Which way a target is expected to lie when it is not on screen yet.
 *
 * Declared per step rather than inferred. Dot could guess from a list's scroll
 * position, but guessing wrong means telling someone to scroll away from the
 * thing they are looking for — so a step that does not say gets no scroll cue
 * at all, and falls back to recovery instead.
 */
enum class ScrollDirection {
    /** Further down the page. */
    Down,

    /** Back up the page. */
    Up,
}

/** What the person is being asked to do on a step. */
enum class StepAction {
    /** Tap a control; Dot watches for the tap the person makes. */
    Tap,

    /** Adjust a control such as a slider, then say when they are happy. */
    Adjust,
}

/**
 * One step of a guide.
 *
 * @param expectedPackages packages this step can legitimately run over. Dot
 *   reads screen content and shows overlays only while one of these is in
 *   front, which is what keeps it off unrelated apps.
 * @param instructionRes a format string taking the target's name, so the name
 *   can be emphasised in the card.
 * @param fallbackRes the extra wording shown by "I don't see that".
 */
data class GuideStep(
    val expectedPackages: Set<String>,
    val target: TargetSpec,
    @param:StringRes val instructionRes: Int,
    @param:StringRes val targetNameRes: Int,
    @param:StringRes val fallbackRes: Int,
    val action: StepAction = StepAction.Tap,
    /**
     * Where the target is expected to be when it is not visible.
     *
     * Null means Dot has not been told, and will not offer a direction it
     * cannot stand behind.
     */
    val expectedDirection: ScrollDirection? = null,
    /**
     * What finishing this step looks like.
     *
     * Stated per step rather than assumed. A tap step finishes when the person
     * taps its control *or* when the page it leads to appears — the second
     * clause matters because some screens do not emit a usable click event,
     * and because the click and the navigation can arrive in either order.
     */
    val completion: StepCompletionRule = StepCompletionRule.ExactTargetClicked,
    /**
     * The page this step leads to, if it leads anywhere.
     *
     * Used to confirm that a tap worked, to notice a tap that did nothing, and
     * to recognise the step again if the person comes back to it.
     */
    val expectedDestination: ScreenSignature? = null,
    /** The page this step is performed on, for backward recovery. */
    val screen: ScreenSignature? = null,
)

/**
 * An ordered set of steps that walks someone to one outcome.
 *
 * The engine is driven entirely by these definitions, so adding a guide later
 * means adding data rather than more branching.
 */
data class GuideDefinition(
    val id: String,
    @param:StringRes val titleRes: Int,
    val steps: List<GuideStep>,
) {
    val stepCount: Int get() = steps.size

    init {
        require(steps.isNotEmpty()) { "A guide needs at least one step" }
    }
}
