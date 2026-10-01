package com.aaravsekhri.dot.guide.ai

/**
 * The one next thing the model proposes.
 *
 * A closed set. There is no "do something else" case, and nothing here carries
 * coordinates: a control is named by an id the phone offered, and if that
 * control cannot be found again on a live screen the decision is dropped
 * rather than approximated.
 *
 * Meaning travels in fields, not only in prose. Where the words on the card
 * have to agree with something else — which way a finger moves, how this
 * particular phone gets to its Home screen — the phone writes them from the
 * fields, and the model's sentence is used only where the model is the one who
 * knows.
 */
sealed interface AiDecision {

    /** Which reading this was decided from. Anything older is thrown away. */
    val observationId: String

    /** How the last instruction went, and what that established. */
    val meta: DecisionMeta

    /** Tap one control. */
    data class Highlight(
        override val observationId: String,
        val candidateId: String,
        val instruction: String,
        val expectedOutcome: String,
        override val meta: DecisionMeta = DecisionMeta(),
    ) : AiDecision

    /** Type into one editable field. [text] is empty when the words are the person's to choose. */
    data class TypeText(
        override val observationId: String,
        val candidateId: String,
        val instruction: String,
        val text: String,
        val expectedOutcome: String,
        override val meta: DecisionMeta = DecisionMeta(),
    ) : AiDecision

    /** Send what was typed: the keyboard's key, or a button on screen. */
    data class Submit(
        override val observationId: String,
        val viaKeyboard: Boolean,
        val candidateId: String?,
        val instruction: String,
        val expectedOutcome: String,
        override val meta: DecisionMeta = DecisionMeta(),
    ) : AiDecision

    /**
     * Bring more of some content into view.
     *
     * [reveal] is which part of the *content* is wanted — never which way a
     * finger moves. A suggestion, and described as one: it is not handed to
     * the scrolling controller as a proven position, because that controller
     * believes readings of the target and nothing else.
     */
    data class Scroll(
        override val observationId: String,
        val reveal: Reveal,
        val containerId: String?,
        val lookingFor: String,
        val expectedOutcome: String,
        override val meta: DecisionMeta = DecisionMeta(),
    ) : AiDecision

    /** Go to the Home screen, or back one screen. Worded by the phone. */
    data class Navigate(
        override val observationId: String,
        val destination: Destination,
        val reason: String,
        val expectedOutcome: String,
        override val meta: DecisionMeta = DecisionMeta(),
    ) : AiDecision

    /** One short question; [options] when the answer is a choice. */
    data class Ask(
        override val observationId: String,
        val question: String,
        val options: List<String> = emptyList(),
        override val meta: DecisionMeta = DecisionMeta(),
        /** Answered in their own words; [options] are quick answers beside the box. */
        val freeText: Boolean = false,
        /** Asks for something personal, such as an address. */
        val personal: Boolean = false,
    ) : AiDecision

    /** The screen is busy. Bounded: a wait that never ends is a hang. */
    data class Wait(
        override val observationId: String,
        val reason: String,
        val recheckAfterMs: Long,
        val maxRechecks: Int,
        override val meta: DecisionMeta = DecisionMeta(),
    ) : AiDecision

    /**
     * A step the person does alone. Words only — [candidateId], when given,
     * names the control involved so the card can be kept off it; it is never
     * outlined.
     */
    data class Manual(
        override val observationId: String,
        val instruction: String,
        val reason: String,
        override val meta: DecisionMeta = DecisionMeta(),
        val candidateId: String? = null,
    ) : AiDecision

    /**
     * A lesson's teaching point, now that the screen shows what it is about:
     * [checkpointId] names one of the lesson's authored points, whose own
     * words are shown — never the model's. [candidateId], when given, is the
     * information to look at (read, not tapped). [missing] when what the
     * point is about is not on this screen; [instruction] then says so, in a
     * sentence, and the lesson's fallback is followed.
     */
    data class Explain(
        override val observationId: String,
        val checkpointId: String,
        val candidateId: String? = null,
        val missing: Boolean = false,
        val instruction: String = "",
        override val meta: DecisionMeta = DecisionMeta(),
    ) : AiDecision

    /** Done, with something on screen to show for it. */
    data class Complete(
        override val observationId: String,
        val summary: String,
        val evidence: String,
        override val meta: DecisionMeta = DecisionMeta(),
    ) : AiDecision

    /** Not possible from here, said plainly, with somewhere to go next. */
    data class Blocked(
        override val observationId: String,
        val explanation: String,
        val suggestion: String,
        override val meta: DecisionMeta = DecisionMeta(),
        /** In a lesson: the feature it teaches is not available here. */
        val unavailable: Boolean = false,
        /**
         * The app itself shows an error about what was just tried — "We
         * couldn't create your account" — as opposed to Dot not seeing well.
         */
        val appError: Boolean = false,
    ) : AiDecision
}

/** How the previous instruction went, judged from the screen this decision was made on. */
enum class Assessment { Succeeded, Failed, Unclear, NotApplicable }

/**
 * What every decision says besides its action.
 *
 * [progressNote] is recorded as verified progress only when [assessment] is
 * [Assessment.Succeeded] — a model saying something happened is not the same
 * as a screen showing it, and the prompt asks for the first only with the
 * second.
 */
data class DecisionMeta(
    val assessment: Assessment = Assessment.NotApplicable,
    val progressNote: String = "",
    val subgoal: String = "",
    /**
     * In a lesson: what the learner chose to practise with instead of the
     * lesson's example — a place of their own. Blank when they did not.
     */
    val practiceChoice: String = "",
    /**
     * Facts about the task the person has settled — where they are going,
     * what to search for, who to contact — by name: "destination",
     * "search_text", "contact". Only what changed; blank values clear.
     */
    val taskDetails: Map<String, String> = emptyMap(),
)

/** Which part of the content should come into view. */
enum class Reveal { Below, Above, Right, Left }

enum class Destination { Home, Back }

/**
 * An instruction written at runtime rather than compiled in.
 *
 * The fixed tutorials carry string resource ids, which works because their
 * wording ships with the app. A general session's wording arrives from the
 * model, or is built on the phone from a decision's fields, so it travels as
 * text.
 */
data class RuntimeInstruction(
    val instruction: String,
    val expectedOutcome: String,
    /** What the control is called, for naming it in the card. */
    val targetName: String,
    /** Which kind of step this was, so the next request can say so. */
    val kind: String = "",
    /**
     * The movement it asks for, when it asks for one — built from the
     * decision's fields, never read out of [instruction].
     */
    val gesture: Gesture? = null,
    /**
     * For a typing step with known words: what should appear in the field.
     * Its arrival is the evidence the step is done — nobody has to say so.
     */
    val expectedText: String = "",
    /**
     * For a step about a form's box: what the box is for, from the app's own
     * words for it. Says what "I'm done" means on the card; never a value.
     */
    val field: FieldKind? = null,
)
