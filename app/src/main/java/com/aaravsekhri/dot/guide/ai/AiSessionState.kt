package com.aaravsekhri.dot.guide.ai

/**
 * Where a general, AI-guided session stands.
 *
 * The fixed tutorials have their own state; this is separate on purpose. A
 * tutorial knows its steps in advance and can talk about "step 2 of 3". A
 * general session knows only the goal, what it has seen, and what it last
 * said, and everything else is decided one screen at a time.
 */
sealed interface AiSessionState {

    /** Taking a reading of the screen. */
    data object Observing : AiSessionState

    /**
     * A lesson's teaching card, over the app, waiting for the learner to
     * continue. Nothing on screen dismisses it: not a screen change, not a
     * late answer, not a scroll. Continuing acknowledges the words; it is
     * never taken as proof that anything in the app was done.
     *
     * @property target the information to look at, when there is one: read,
     *   not tapped, and outlined only while it is found on the screen.
     * @property id this card, so a tap on an earlier card is refused.
     */
    data class Teaching(
        val stage: TeachStage,
        val words: String,
        val checkpointId: String = "",
        val target: ValidatedTarget? = null,
        val id: Int = 0,
    ) : AiSessionState

    /**
     * A planning request is in flight. Exactly one may be, per session.
     *
     * [slow] once it has taken noticeably longer than usual: the card says so
     * and offers to try again, rather than animating for ever.
     */
    data class Thinking(val attempt: Int, val slow: Boolean = false) : AiSessionState

    /**
     * The page has kept moving long enough that no useful reading can be
     * taken. One steady message, held until it stops — not a flicker between
     * "thinking" and scroll prompts — and then one fresh look.
     */
    data object HoldStill : AiSessionState

    /**
     * A request failed in a way that usually passes, and another is due
     * shortly — on a bounded schedule the controller keeps. Nothing on screen
     * brings it forward. Pause and Exit still work.
     */
    data class Reconnecting(
        /** Why: the words say what is actually happening, not always "trouble reaching". */
        val why: PlanningFailure = PlanningFailure.Unknown,
    ) : AiSessionState

    /**
     * The screen could not be read just now — a page still loading, a window
     * changing. Not a failure: Dot looks again, on the phone, a few times and
     * whenever the screen settles, asks nothing of the planner meanwhile, and
     * carries on the moment it can see.
     */
    data object Reacquiring : AiSessionState

    /**
     * Still unreadable after looking again. Said plainly, with the useful
     * thing to do next — look again, answer the system's question — and
     * without making "later" the answer to one screen that could not be read.
     *
     * @property checks how many times the person has asked to check again;
     *   only after a few does stopping become the recommendation.
     */
    /**
     * @property title for a window Android withholds: its own title — the
     *   permission it asks for — the one thing about it that can be read.
     */
    data class CannotRead(val why: Unreadable, val checks: Int = 0, val title: String = "") : AiSessionState

    /** An instruction is on screen and the person is being pointed at something. */
    data class Guiding(
        val instruction: RuntimeInstruction,
        val target: ValidatedTarget,
    ) : AiSessionState

    /** Told what to do; watching for it to happen. */
    data class WaitingForUserAction(
        val instruction: RuntimeInstruction,
        val since: Long,
    ) : AiSessionState

    /**
     * One question, and nothing else until it is answered — asked over
     * whatever app is in front, never by sending the person back to Dot.
     *
     * [id] tells this question from any other, so a tap on a card for an
     * earlier question can never answer this one.
     */
    data class AskingClarification(
        val question: String,
        val options: List<String> = emptyList(),
        val id: Int = 0,
        /** Answered in their own words, with [options] as quick answers ("I'm not sure"). */
        val freeText: Boolean = false,
        /** The answer is personal — an address — and the card says where it goes. */
        val personal: Boolean = false,
    ) : AiSessionState

    /** Something outside Dot is happening: a page loading, an app installing. */
    data class WaitingForExternal(
        val reason: String,
        val rechecksLeft: Int,
        val recheckAfterMs: Long,
    ) : AiSessionState

    /**
     * A screen Dot will not look at.
     *
     * Nothing is uploaded and nothing is read while this lasts. The person
     * does the private part themselves and says when they are through.
     */
    data class ManualHandoff(
        val instruction: String,
        val reason: String,
        /** True once the person has done something since: "I'm done" is then the next thing. */
        val emphasizeDone: Boolean = false,
        /**
         * The button's own words when what it confirms is known — "I've set
         * my birthday" — from what the step is about, never from anything
         * typed. Null for the general "I'm done".
         */
        val confirmLabel: String? = null,
    ) : AiSessionState {
        /** A step on private details — a password, a code, a secure screen — that Dot leaves the person alone for. */
        val private: Boolean get() = reason.startsWith("this ") || reason == PRIVATE_REASON

        companion object {
            const val PRIVATE_REASON = "private details are typed in the app"
        }
    }

    /**
     * A bounded wait ran out.
     *
     * Not a failure and not the end: a long install is a valid thing to be
     * waiting for. The person decides whether to keep waiting, which grants
     * another bounded allowance, or to stop. The allowance never refills
     * itself.
     */
    data class WaitExpired(val reason: String) : AiSessionState

    /**
     * The same suggestion is not working.
     *
     * Reached when an instruction has been given repeatedly without the screen
     * showing progress, or the person has said twice that it did not work.
     * Offering another way, rather than repeating, is the whole point.
     */
    data class Stuck(
        val cause: StallCause = StallCause.Unknown,
        /** False once "Keep trying" has been tried enough: then only stopping is offered. */
        val canKeepTrying: Boolean = true,
    ) : AiSessionState

    /**
     * In a lesson: what the lesson teaches is not available here — not on
     * this account, plan, device or version. Said plainly, recorded as
     * unavailable rather than done, and another lesson offered.
     */
    data class LessonUnavailable(val explanation: String) : AiSessionState

    /**
     * Sign-in help on a private screen: words only, from structure, with a
     * button for "I'm done" or "I have the code". Dot keeps watching, locally,
     * so it notices when the screen moves on.
     */
    data class AuthGuidance(
        val instruction: RuntimeInstruction,
        val confirmLabel: String?,
        /** True once the person has done something since: the button is then the next thing. */
        val emphasizeDone: Boolean = false,
    ) : AiSessionState

    /** A choice answered on the card itself — where the code is, what can be seen. */
    data class LocalChoice(
        val question: String,
        val choices: List<AuthChoice>,
        val id: Int = 0,
    ) : AiSessionState

    data object Paused : AiSessionState

    data class Completed(val summary: String) : AiSessionState

    /**
     * Something went wrong that trying again could fix.
     *
     * Distinct from blocked: the goal is still live and the person is offered
     * another go rather than being told to start over.
     */
    data class RecoverableFailure(
        val explanation: String,
        val suggestion: String,
        val canRetry: Boolean,
        /** Dot is not set up to guide on this phone; waiting will not change it. */
        val setupProblem: Boolean = false,
        /** What failed, when a request did; for wording only, never shown as a code. */
        val kind: PlanningFailure? = null,
    ) : AiSessionState
}

/** True while Dot may read the screen for this session. */
fun mayObserve(state: AiSessionState): Boolean = state.mayObserve

/** True while Dot may read the screen for this session. */
val AiSessionState.mayObserve: Boolean
    get() = when (this) {
        AiSessionState.Paused,
        is AiSessionState.Completed,
        is AiSessionState.ManualHandoff,
        -> false

        // Sign-in help keeps looking — locally, at structure only — so it can
        // move on from one box to the next.

        else -> true
    }

/**
 * True while Dot may read which page is in front — its app, window and kinds
 * of box, never anything in them — to notice the person has moved on. Wider
 * than [mayObserve]: a manual step is done by the person without Dot
 * describing the screen, but the page changing is how it knows the step is
 * over. Never while paused or finished, or on a private step.
 */
val AiSessionState.mayReadStructure: Boolean
    get() = when (this) {
        AiSessionState.Paused, is AiSessionState.Completed -> false
        is AiSessionState.ManualHandoff -> !private
        else -> true
    }

/** Where a lesson is, as a lesson: its purpose, its practice, a teaching point, its result. */
enum class TeachStage(val wire: String) {
    Intro("intro"),
    Practice("practice"),
    Checkpoint("checkpoint"),
    Result("result");

    companion object {
        fun of(wire: String): TeachStage? = entries.firstOrNull { it.wire == wire }
    }
}

/** Why a screen could not be read. Only what is known; never a guess at whose fault. */
enum class Unreadable {
    /** Too little on it to go by, or nothing at all: loading, or drawn without a description. */
    Unclear,

    /** A system dialog is in front, waiting for the person. */
    SystemDialog,

    /** Looking at the screen has not been agreed to. */
    NotAllowed,

    /**
     * Android asking for a permission, in a window whose buttons it keeps
     * from TapBack. The choice is the person's; TapBack waits for the
     * window to close and carries on.
     */
    Permission,

    /** A window in front whose contents Android or the app keeps private. */
    Protected,
}
