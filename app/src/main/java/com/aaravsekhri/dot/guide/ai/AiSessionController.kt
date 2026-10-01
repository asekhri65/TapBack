package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.matching.TextNormalizer
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One general, AI-guided session: observe, decide, point, watch, repeat.
 *
 * Kept out of the accessibility service deliberately. The service is already
 * the busiest class in the app and is full of things that must happen on the
 * right thread at the right moment; a planning loop with network calls and
 * retries does not belong in the middle of it. Everything here is plain
 * Kotlin over an interface the service implements, which also means the whole
 * loop is testable without a device.
 *
 * Rules it exists to keep:
 *
 * **One logical request in flight.** A screen that changes while a request is
 * out cancels it. A request asked for while one is running is queued, not
 * dropped — including one asked for by the running request itself, which is
 * how an answer found to be stale gets a fresh one rather than leaving the
 * session looking at nothing.
 *
 * **Nothing is drawn from the reading that prompted it.** Every answer is
 * re-grounded against a reading taken after it came back, and a reading from
 * before the screen moved can never restore an old target.
 *
 * **Everything is bounded, and nothing refills itself.** Failures, waits and
 * repeated suggestions all have ceilings, and reaching one hands the choice
 * to the person rather than looping or declaring the task over.
 *
 * **A tap is evidence, not success.** Success is judged from the next screen,
 * and progress is recorded only when a screen showed it.
 */
class AiSessionController(
    private val client: PlanningService,
    private val scope: CoroutineScope,
    private val store: SessionCheckpoints,
    private val host: Host,
    private val now: () -> Long = System::currentTimeMillis,
    /** Between 0 and 1, for spreading retries out. A test fixes it. */
    private val jitter: () -> Double = Math::random,
    /** Where a task is admitted, released and finished; null with no ledger to ask. */
    private val admission: TaskAdmission? = null,
) {

    /**
     * Admission of a task on Dot's service: a task is admitted once, before
     * its first planning request, and never again while it runs or resumes.
     */
    interface TaskAdmission {
        suspend fun admit(taskId: String, kind: String, courseId: String): AdmitOutcome

        /** The session ended without finishing: give the credit back, if Dot never helped. The service decides. */
        fun release(taskId: String)

        /** Finished: charged for good. [summary] is the closing line the person was shown. */
        fun finish(taskId: String, kind: String, summary: String = "")
    }

    sealed interface AdmitOutcome {
        data object Admitted : AdmitOutcome
        data class Refused(val reason: String) : AdmitOutcome
        data class Failed(val kind: PlanningFailure) : AdmitOutcome
    }

    /** The id being admitted: the same one for every retry, so a retry is never a second task. */
    private var admittingTaskId: String? = null

    /**
     * True once this session's task is admitted. A static card needs no
     * admission — the lesson's opening words are Dot's own — so this is
     * asked only when the first planning request is due.
     */
    private suspend fun ensureAdmitted(startedAt: Long): Boolean {
        val admission = admission ?: return true
        if (context.taskId.isNotBlank()) return true
        val taskId = admittingTaskId ?: UUID.randomUUID().toString().replace("-", "").also { admittingTaskId = it }
        val kind = if (context.lesson != null) "lesson" else "ask"
        return when (val r = admission.admit(taskId, kind, context.lesson?.courseId.orEmpty())) {
            AdmitOutcome.Admitted -> {
                admittingTaskId = null
                context = context.copy(taskId = taskId)
                GuideLog.d("ai") { "task admitted kind=$kind" }
                checkpoint("observing")
                current(startedAt)
            }
            is AdmitOutcome.Refused -> {
                GuideLog.d("ai") { "task not admitted: ${r.reason}" }
                if (current(startedAt)) {
                    val (explanation, suggestion) = notAdmittedWords(r.reason)
                    fail(explanation, suggestion, canRetry = false, kind = PlanningFailure.NotAdmitted)
                }
                false
            }
            is AdmitOutcome.Failed -> {
                if (current(startedAt)) failed(r.kind)
                false
            }
        }
    }

    /** What the controller needs the rest of the app to do for it. */
    interface Host {
        /** A settled reading of what is in front. */
        suspend fun observe(purpose: ObservePurpose = ObservePurpose.Planning): ObserveResult

        /** Point at this, with these words. */
        fun showTarget(target: ValidatedTarget, instruction: RuntimeInstruction)

        /** Words only: a question, a wait, a handoff, an ending, a text-only step. */
        fun showMessage(state: AiSessionState)

        /**
         * Take everything positional down.
         *
         * @param samePage true when the person is still on the same page — a
         *   switch flipped, a slider nudged — so a target drawn again on it is
         *   not a new arrival and Pebble does not replay its entrance.
         */
        fun clearTarget(samePage: Boolean = false)

        fun onStateChanged(state: AiSessionState)

        /** Take the card down too: Dot's own screen is in front and needs to be seen. */
        fun hideGuidance() = Unit

        /** How this phone navigates, its Android version and text size. */
        fun device(): DeviceContext = DeviceContext()

        /**
         * Whether cards should offer to change the answer to [question] —
         * shown for the few steps after a question was answered, so a wrong
         * tap can be put right without starting over. Null takes it away.
         */
        fun offerChangeAnswer(question: String?) = Unit

        /**
         * Keep the card off this part of the screen: a control the words
         * name but that is deliberately not outlined, which the card must
         * still not hide.
         */
        fun keepClear(regions: List<com.aaravsekhri.dot.guide.model.Bounds>) = Unit

        /**
         * Where [purpose] starts and goes on the screen in front, or null when
         * that cannot be said with confidence — then it is said in words only.
         */
        fun planGesture(
            purpose: GesturePurpose,
            from: Observation?,
            reveal: Reveal? = null,
            area: com.aaravsekhri.dot.guide.model.Bounds? = null,
        ): GesturePlan? = null

        /** Show a gesture on the screen itself, where a finger should go. */
        fun showGesture(plan: GesturePlan) = Unit

        /** Stop showing it: the person has begun, or the screen changed. */
        fun clearGesture() = Unit

        /**
         * Keeps what would help to see — a box's label, the form's Next —
         * in view where that costs the card nothing. Replaces the last list.
         */
        fun keepInView(regions: List<com.aaravsekhri.dot.guide.model.Bounds>) = Unit

        /** In a lesson, the learner chose [value] to practise with; see [DecisionMeta.practiceChoice]. */
        fun rememberPracticeChoice(value: String) = Unit

        /**
         * The keyboard's action key, when the keyboard labels it: read from
         * its fixed key names only — never a suggestion, never typed words.
         */
        fun keyboardAction(): KeyboardAction? = null

        /** An app's name as the phone shows it under its icon, or blank. */
        fun appLabel(packageName: String): String = ""

        /** The person said what a form's box holds is right. See [FormMemory]. */
        fun confirmField(fieldKey: String) = Unit

        /**
         * What page is in front, structurally, read on the phone: for noticing
         * a new page that arrived in the same window. Works whether or not
         * anything may be sent. Null when nothing can be read.
         */
        fun screenShape(): ScreenShape? = null
    }

    enum class ObservePurpose {
        /** For a request: may include a picture. */
        Planning,

        /**
         * For a request, where the words alone may do: the picture is left
         * out only if the screen's description is full and unambiguous.
         */
        PlanningTextFirst,

        /** For re-finding a control on the phone. Never sent anywhere; no picture. */
        Grounding,

        /**
         * For a request about a screen whose description stayed almost empty
         * after every second look: the picture alone, where one may be taken.
         * Never on a private screen, never with a keyboard up, never with a
         * box to type in anywhere on it.
         */
        PlanningPictureOnly,
    }

    /** What a reading came back as. */
    sealed interface ObserveResult {
        data class Seen(val observation: Observation) : ObserveResult

        /**
         * A screen Dot will not describe to anybody, and why — with its
         * structure, for local sign-in help, when there is any to use.
         */
        data class Sensitive(
            val reason: SensitiveScreenPolicy.Reason,
            val auth: AuthScreen? = null,
        ) : ObserveResult

        /** Dot itself is in front: excluded from reading, and not an error. */
        data object InDot : ObserveResult

        /** Nothing readable in front just now: a window changing, nothing active. */
        data object Nothing : ObserveResult

        /**
         * A window with too little in its description to guide from — a page
         * still loading, or an app that draws without describing itself.
         *
         * @property pictureAllowed true when a picture of it could be sent
         *   instead: nothing private is showing, nothing can be typed on it,
         *   and the app allows pictures of it.
         */
        data class Sparse(val pictureAllowed: Boolean) : ObserveResult

        /** A system dialog — a permission, an installer — is in front. It is the person's to answer. */
        data object SystemDialog : ObserveResult

        /** A window in front whose contents are withheld: its title and where it is, nothing else. */
        data class Withheld(val title: String, val bounds: com.aaravsekhri.dot.guide.model.Bounds, val permission: Boolean) : ObserveResult

        /** Looking at the screen has not been agreed to. */
        data object NotAllowed : ObserveResult
    }

    /**
     * What kind of change the screen has just settled after.
     *
     * In order of significance, most significant first: changes that arrive
     * together are merged and the first of these wins. A scroll outranks a
     * content update because a moving list always updates its content too,
     * and with the order the other way round every scroll was recorded as
     * mere content.
     */
    enum class Change {
        /** A different window or app arrived. */
        Navigation,

        /** The person touched something; the page may or may not have changed. */
        Action,

        /** The same page, moved. */
        Scroll,

        /** The same page, content updated. */
        Content;

        /** The word a request made because of this change is sent with. */
        val why: String get() = name.lowercase()
    }

    var state: AiSessionState = AiSessionState.Observing
        private set

    val sessionId: String = UUID.randomUUID().toString().take(16)

    private var goal: String = ""

    /** Which app, and which lesson, the goal is about. Never replaces the goal. */
    var context: TaskContext = TaskContext()
        private set

    /** Steps that stalled: never offered again in this session. */
    private val stalledKeys = mutableSetOf<String>()

    /** How many times "Keep trying" has been chosen. Bounded. */
    private var keepTryingRounds = 0

    /** Looking again at a screen that could not be read. */
    private var reacquireJob: Job? = null
    private var reacquireDeadline = 0L
    private var lastUnreadable: ObserveResult = ObserveResult.Nothing

    /** The next request is for a picture alone: the description stayed nearly empty. */
    private var pictureOnlyNext = false

    /** "Check again" pressed on the "cannot read" card; stopping is suggested only after a few. */
    private var cannotReadChecks = 0

    /** The question that was waiting when the person paused. */
    private var pausedQuestion: AiSessionState.AskingClarification? = null

    /** Numbers every question asked, so an answer can only ever answer its own. */
    private var questionSeq = 0

    /** Questions the planner asked again after they were answered, sent back once each. */
    private val reAsked = mutableSetOf<String>()

    /** Times the app list swipe opened recent apps instead. Put right locally, a couple of times. */
    private var wrongTurns = 0

    /**
     * The gesture being demonstrated for the step on screen, so it can be put
     * back up with its card — after Dot's own decision screen closes, say —
     * and never left over a step it does not belong to.
     */
    private var demonstration: GesturePlan? = null

    /** The card was taken down because Dot itself came forward; it comes back with the next screen. */
    private var hiddenForDot = false

    /** The question about a default birthday on the card now, and the box it is about. */
    private var formQuestion: Pair<Int, String>? = null

    /** In a lesson: teaching points shown and acknowledged, by id ("c1"…). */
    private val explained = mutableListOf<String>()

    /** The lesson's result card has been shown and continued from. */
    private var resultShown = false

    /** The completion the result card stands before: said once the card is continued from. */
    private var pendingCompletion: Pair<String, String>? = null

    /** Teaching points whose information could not be found on the screen, and how often. */
    private val infoMisses = mutableMapOf<String, Int>()

    /** The teaching card up when the person paused, to put back afterwards. */
    private var pausedTeaching: AiSessionState.Teaching? = null

    /** What the person has settled about the task: see [TaskDetails]. */
    private val taskDetails = linkedMapOf<String, String>()

    /** Results refused for not matching what is being searched for, per screen. */
    private var mismatchRefusedOn: String? = null

    /** The page the last manual step was given on, to notice the same step coming back. */
    private var lastManualPage: String? = null

    /** The question "Is that done?" on the card now, when there is one. */
    private var doneQuestionId: Int? = null

    /** Manual steps refused for handing over the whole task. Bounded. */
    private var broadHandoffs = 0

    /** Boxes already asked about, so nobody is asked the same thing twice. */
    private val askedAboutField = mutableMapOf<String, Boolean>()

    /** A wheel date picker was in front at the last look, helped with on the phone. */
    private var pickerOpen = false

    /** Which of the picker's wheels is being helped with; past the last one is Set. */
    private var pickerStep = 0

    /** The birthday box the person asked to change after its picker closed. */
    private var changeBirthday = false

    /**
     * The form, as it was when its Next was last shown — which boxes, what
     * they held — as a fingerprint. The same form again, unchanged, after
     * Next was shown is Next not having worked.
     */
    private var advanceShownOn: Int? = null
    private var advanceNaming: String? = null

    /** The person tapped that Next: only then does the same form afterwards count as it not working. */
    private var advanceTapped = false
    private var rejectedRetries = 0

    /**
     * Which step is on screen. Rises with every new step — a new decision, a
     * new page of local help, the way out of Dot — and not when the same
     * step is drawn again, moved or re-found. A card's action says which step
     * it was for, and one for a step that has gone is refused.
     */
    var stepId: Long = 0L
        private set

    /** The step whose typing was confirmed, so a second tap asks nothing more. */
    private var typingConfirmedFor: Long = -1L

    /** The page the last local sign-in step was shown on. */
    private var authStepShape: ScreenShape? = null

    private fun beginStep() {
        stepId++
    }

    /** The page as the service last saw it settle, for noticing a new one in the same window. */
    private var lastShape: ScreenShape? = null

    /** What kind of form box the last step pointed at, for what a following manual step's button says. */
    private var fieldInHand: FieldKind? = null

    /** The page a manual step was given on, and whether it is one the person should be left alone on. */
    private var handoffShape: ScreenShape? = null

    /** The page the request in flight was made from. */
    private var decidedShape: ScreenShape? = null

    /** The last app, not a launcher, that was read: where "go back" means. */
    private var lastAppPackage: String? = null

    /** Sideways swipes suggested on the Home screen. Bounded: search comes first. */
    private var homePageSwipes = 0
    private val answers = mutableListOf<QuestionAnswer>()
    private var pendingQuestion: String? = null
    private val progress = mutableListOf<String>()
    private var subgoal: String = ""
    private val failedApproaches = mutableListOf<String>()
    private val recentApps = mutableListOf<String>()
    private var lastInstruction: RuntimeInstruction? = null
    private var reportedProblem = false

    /** The reading the outstanding request was made from. */
    private var decidedFrom: Observation? = null

    /** The one planning job. */
    private var planning: Job? = null

    /** Another plan was asked for while one was running. */
    private var replanQueued = false

    /** Requests made and avoided, for measuring cost. Counts only. */
    val costs = PlanningCosts()

    /** Why the next request is being made, until it is made. */
    private var pendingWhy: String? = null

    /** The next plan must ask even if the screen looks unchanged. */
    private var forceNext = false

    /** A local re-find of the current target, after the page moved. */
    private var relocating: Job? = null

    /** The pending recheck of a bounded wait. */
    private var waitTimer: Job? = null

    /** Rechecks left in the current wait episode, or null when not waiting. */
    private var waitAllowance: Int? = null
    private var waitReason: String = ""

    /** Rises whenever the world moves on, so late work can be discarded. */
    private var generation: Long = 0L

    /** The sign-in subtask. Local: nothing it sees is ever sent. */
    private val auth = AuthAssistant { host.device().navigation }

    /** When the page started moving, while it still is; null when still. */
    private var movingSince: Long? = null

    /**
     * An answer that arrived while the page was still moving. Nothing from it
     * is drawn until the page settles and its target has been found again on
     * the settled screen.
     */
    private var parked: Pair<AiDecision, Observation>? = null

    /** The page moved while the current request was out. */
    private var movedDuringRequest = false

    /** Marks a request as slow once it has run past [SLOW_MS]. */
    private var slowTimer: Job? = null

    /**
     * A typing step whose words are known, and the field they go in. The
     * words appearing in the field is the evidence the step is done; the
     * words disappearing again, after moving on, means they are being edited.
     */
    private data class TypingWatch(
        val instruction: RuntimeInstruction,
        val target: ValidatedTarget,
    )

    private var typingWatch: TypingWatch? = null

    /** The pending automatic retry after a transient failure. */
    private var recoveryTimer: Job? = null

    /** Automatic retries used in the current recovery episode, and when it began. */
    private var recoveryAttempts = 0
    private var recoveryStartedAt = 0L

    /**
     * A failure waiting cannot fix — a spending cap, a bad key — once it has
     * been seen. While set, nothing on screen sets off a request: only the
     * person, by trying again or carrying on.
     */
    private var outage: PlanningFailure? = null

    private var lastManualRetryAt = Long.MIN_VALUE / 2

    /** The last question answered, and how many steps ago. */
    private var answeredQuestion: String? = null
    private var stepsSinceAnswer = 0

    /** A "complete" refused for coming from an unchanged screen, once per step. */
    private var completionChecked: String? = null

    /** The app a private sign-in was last checked against the goal for. */
    private var goalCheckedFor: String? = null

    /** The reading the step on screen was decided from. */
    private var lastShownKey: String? = null

    private var consecutiveFailures = 0
    private var lastStepKey: String? = null
    private var strikes = 0
    private var problemReports = 0
    private var lastContentKey: String? = null
    private var stopped = false
    private var lastWasBlocked = false

    // ------------------------------------------------------------- driving

    /** Begin. [goal] is the person's own words, or the lesson's objective. */
    fun start(goal: String, context: TaskContext = TaskContext()) {
        this.goal = goal.trim()
        this.context = context
        auth.reset()
        checkpoint("observing")
        // A lesson begins as a lesson: what it is for, then what is practised,
        // each on a card the learner continues from. Nothing is asked of the
        // planner until they do.
        val lesson = context.lesson
        when {
            lesson != null && lesson.purpose.isNotBlank() -> teach(TeachStage.Intro, lesson.purpose)
            lesson?.practice?.intro?.isNotBlank() == true -> teach(TeachStage.Practice, lesson.practice.intro)
            else -> {
                transitionTo(AiSessionState.Observing)
                requestPlan(force = true, why = "start")
            }
        }
    }

    // ------------------------------------------------------------ teaching

    /**
     * Shows a lesson's teaching card over the app. Whatever was in flight is
     * dropped — a late answer must not replace the card — and the last step's
     * outline goes; the card stays until the learner continues from it.
     */
    private fun teach(stage: TeachStage, words: String, checkpointId: String = "", target: ValidatedTarget? = null) {
        abandonInFlight()
        endWaitEpisode()
        reacquireJob?.cancel()
        clearTarget()
        beginStep()
        transitionTo(AiSessionState.Teaching(stage, words, checkpointId, target, ++questionSeq))
        checkpoint("teaching")
        host.showMessage(state)
    }

    /**
     * "Let's begin", "Show me how", "Next": the learner has read the card.
     * An acknowledgement of the words, and nothing more — it is never taken
     * as proof that anything in the app was done.
     *
     * @param cardId the card the tap was on; a tap on an earlier card is refused.
     */
    fun continueTeaching(step: Long? = null) {
        val card = state as? AiSessionState.Teaching ?: return
        if (step != null && step != stepId) {
            GuideLog.d("ai") { "a tap on a teaching card that has gone; ignored" }
            return
        }
        val lesson = context.lesson
        when (card.stage) {
            TeachStage.Intro ->
                if (lesson?.practice?.intro?.isNotBlank() == true) {
                    teach(TeachStage.Practice, lesson.practice.intro)
                } else {
                    guideFromHere("start")
                }
            TeachStage.Practice -> guideFromHere("start")
            TeachStage.Checkpoint -> {
                if (card.checkpointId.isNotBlank() && card.checkpointId !in explained) explained += card.checkpointId
                guideFromHere("explained")
            }
            TeachStage.Result -> {
                resultShown = true
                val (summary, evidence) = pendingCompletion ?: ("Done." to "")
                pendingCompletion = null
                clearTarget()
                finish(summary, evidence)
            }
        }
    }

    /**
     * A lesson whose practice types something is not done until it has been
     * typed in this attempt: a result left on screen from before — a
     * restarted lesson, an app still showing last time's search — is not
     * this attempt's practice. Refused a couple of times at most, with the
     * reason, then taken at its word.
     */
    private fun practiceNotDoneYet(): Boolean {
        val practice = context.lesson?.practice ?: return false
        if (practice.typeText.isBlank() || typedThisAttempt || practiceRefusals >= MAX_PRACTICE_REFUSALS) return false
        practiceRefusals++
        GuideLog.d("ai") { "a lesson finished before its practice was done in this attempt; guiding the practice" }
        remember(
            failedApproaches,
            "The screen already showed the result before the learner practised in this attempt; guide the practice itself from the start",
            MAX_FAILED,
        )
        forceNext = true
        transitionTo(AiSessionState.Observing)
        host.showMessage(state)
        requestPlan(force = true, why = "practice_first")
        return true
    }

    /** A typing step with the practice text was shown in this attempt. */
    private var typedThisAttempt = false
    private var practiceRefusals = 0

    /** Back to guiding, from a fresh look at the screen as it is now. */
    private fun guideFromHere(why: String) {
        clearTarget()
        beginStep()
        transitionTo(AiSessionState.Observing)
        host.showMessage(state)
        checkpoint("observing")
        requestPlan(force = true, why = why)
    }

    /**
     * A teaching point the planner says the screen has reached. Its words are
     * the lesson's own; the information it is about is outlined only when it
     * is found on the screen now. Never "you found the hours" for hours that
     * are not there.
     */
    private suspend fun explain(decision: AiDecision.Explain, from: Observation, startedAt: Long) {
        val lesson = context.lesson
        val words = lesson?.checkpoints?.firstOrNull { it.first == decision.checkpointId }?.second
        if (lesson == null || words == null || decision.checkpointId in explained) {
            GuideLog.d("ai") { "a teaching point that is unknown or already shown; asking for the next step" }
            remember(failedApproaches, "Explained ${decision.checkpointId}, which is unknown or already explained; guide the next action", MAX_FAILED)
            forceNext = true
            transitionTo(AiSessionState.Observing)
            requestPlan(force = true, why = "explained")
            return
        }
        if (decision.missing) {
            // Honest: what the point is about is not here. Said in a sentence,
            // with no outline, and the lesson's fallback follows.
            teach(TeachStage.Checkpoint, decision.instruction.ifBlank { "That isn't shown on this screen." }, decision.checkpointId)
            return
        }
        val id = decision.candidateId ?: run {
            // A point about something on the screen is outlined, so "this
            // section" always has a section. Asked once to name it; after
            // that, the lesson's words alone (the point may be about the
            // whole screen).
            val misses = (infoMisses[decision.checkpointId] ?: 0) + 1
            infoMisses[decision.checkpointId] = misses
            if (misses < MAX_INFO_MISSES && (from.readable.isNotEmpty() || from.candidates.isNotEmpty())) {
                GuideLog.d("ai") { "teaching point ${decision.checkpointId} names nothing on the screen; asking which part shows it" }
                remember(failedApproaches, "Explained ${decision.checkpointId} without candidate_id; give the id of the information or control that shows it, or missing true", MAX_FAILED)
                forceNext = true
                transitionTo(AiSessionState.Observing)
                requestPlan(force = true, why = "regrounding")
                return
            }
            GuideLog.d("ai") { "teaching point ${decision.checkpointId} names nothing on the screen; words only" }
            return teach(TeachStage.Checkpoint, words, decision.checkpointId)
        }
        GuideLog.d("ai") { "teaching point ${decision.checkpointId} points at ${if (id.startsWith("t")) "information" else "a control"}" }
        val live = host.observe(ObservePurpose.Grounding) as? ObserveResult.Seen
        if (!current(startedAt)) return
        val found = live?.let {
            DecisionValidator.ground(decision.observationId, id, from, it.observation, reading = true) as? DecisionValidator.Result.Ready
        }
        if (found != null) {
            teach(TeachStage.Checkpoint, words, decision.checkpointId, found.target.copy(reading = true))
            return
        }
        val misses = (infoMisses[decision.checkpointId] ?: 0) + 1
        infoMisses[decision.checkpointId] = misses
        GuideLog.d("ai") { "the information for a teaching point is not on the screen now ($misses)" }
        if (misses >= MAX_INFO_MISSES) {
            // Not found twice: not claimed. The point is left, and guiding goes on.
            explained += decision.checkpointId
        } else {
            remember(failedApproaches, "The information for ${decision.checkpointId} was not found where you pointed; point at what shows it, or say it is missing", MAX_FAILED)
        }
        forceNext = true
        transitionTo(AiSessionState.Observing)
        requestPlan(force = true, why = "regrounding")
    }

    /**
     * The information a teaching card points at, found again after the page
     * moved. Found: outlined where it now is. Not found: no outline — the card
     * stays, and never points at something that is not there.
     */
    private fun relocateInformation() {
        val card = state as? AiSessionState.Teaching ?: return
        val target = card.target ?: return
        relocating?.cancel()
        val startedAt = generation
        relocating = scope.launch {
            val live = host.observe(ObservePurpose.Grounding) as? ObserveResult.Seen
            if (stopped || generation != startedAt || state != card) return@launch
            val found = live?.let { findInformation(target, it.observation) }
            if (found != null) {
                transitionTo(card.copy(target = found))
                host.showMessage(state)
            } else {
                GuideLog.d("ai") { "the information is not on the screen now; no outline" }
                host.clearTarget(samePage = true)
            }
        }
    }

    /** Where the information [target] names is on [live], by its words, if it is there once. */
    private fun findInformation(target: ValidatedTarget, live: Observation): ValidatedTarget? {
        val same = target.copy(packageName = live.packageName, windowId = live.windowId)
        return (DecisionValidator.relocate(same, live) as? DecisionValidator.Result.Ready)?.target
    }

    /**
     * Carry on an unfinished session.
     *
     * Everything the person told Dot and everything that was verified comes
     * back. Nothing positional does: the last instruction was about a screen
     * that is no longer in front, so it is remembered as what was last said —
     * for the model to judge — and never shown again as a current step.
     */
    fun resume(checkpoint: AiSessionStore.Checkpoint, context: TaskContext = TaskContext()) {
        goal = checkpoint.goal
        // The task admitted before: resuming it never costs another.
        this.context = if (context.taskId.isBlank()) context.copy(taskId = checkpoint.taskId) else context
        auth.reset()
        answers.clear()
        answers += checkpoint.answers
        // An account answered before the break still stands.
        auth.rememberAccount(
            checkpoint.answers.asReversed().firstNotNullOfOrNull {
                AuthAssistant.accountAnswerFrom(it.question, it.answer)
            },
        )
        progress.clear()
        progress += checkpoint.progress
        subgoal = checkpoint.subgoal
        failedApproaches.clear()
        failedApproaches += checkpoint.failedApproaches
        taskDetails.clear()
        taskDetails += checkpoint.taskDetails
        // Carried as what was being done before the break — not as a step to
        // be judged. Judged, it looked like a failure (the screen does not
        // show it done, because nobody did it), was recorded as a failed
        // approach, and sent the guide round a detour.
        lastInstruction = checkpoint.lastInstruction.takeIf(String::isNotBlank)?.let {
            RuntimeInstruction(it, checkpoint.lastExpectedOutcome, targetName = "", kind = KIND_RESUMED)
        }
        explained.clear()
        explained += checkpoint.explained
        resultShown = checkpoint.resultShown
        TeachStage.of(checkpoint.teachStage)?.let { stage ->
            // A teaching card was up when the break came: put back, as it
            // was, with nothing asked of anybody. Its information is looked
            // for on the screen now, and outlined only if it is there.
            val target = checkpoint.teachTarget.takeIf(String::isNotBlank)?.let {
                ValidatedTarget(
                    com.aaravsekhri.dot.guide.model.Bounds.EMPTY, it, "",
                    // Found again as the kind of thing it was: a control stays a control.
                    checkpoint.teachTargetRole.ifBlank { ObservationBuilder.READABLE_ROLE },
                    reading = true,
                )
            }
            if (stage == TeachStage.Intro || stage == TeachStage.Practice) {
                teach(stage, checkpoint.teachWords, checkpoint.teachCheckpoint, target)
                return
            }
            // A point about the app ("this section…") or its result means
            // something only over the app. Put back only when the app is
            // what is in front; over Dot, guiding carries on, and the point —
            // never marked explained — is taught again when the app shows it.
            transitionTo(AiSessionState.Observing)
            val startedAt = generation
            scope.launch {
                val live = host.observe(ObservePurpose.Grounding)
                if (stopped || generation != startedAt || state != AiSessionState.Observing) return@launch
                if (live is ObserveResult.Seen) {
                    if (stage == TeachStage.Result) pendingCompletion = "Done." to ""
                    teach(stage, checkpoint.teachWords, checkpoint.teachCheckpoint, target)
                    if (target != null) relocateInformation()
                } else {
                    GuideLog.d("ai") { "the app is not in front; its teaching point waits until it is" }
                    if (stage == TeachStage.Result) resultShown = false
                    checkpoint("observing")
                    requestPlan(force = true, why = "resume")
                }
            }
            return
        }
        if (checkpoint.pendingQuestion.isNotBlank()) {
            // A question was waiting when the break came: it is put back up,
            // as it was, with nothing asked of the planner. Answering it
            // carries on from a fresh look at the screen.
            pendingQuestion = checkpoint.pendingQuestion
            transitionTo(
                AiSessionState.AskingClarification(
                    checkpoint.pendingQuestion, checkpoint.pendingOptions, ++questionSeq,
                    freeText = checkpoint.pendingFreeText, personal = checkpoint.pendingPersonal,
                ),
            )
            checkpoint("asking")
            host.showMessage(state)
            return
        }
        checkpoint("observing")
        transitionTo(AiSessionState.Observing)
        requestPlan(force = true, why = "resume")
    }

    /**
     * The person answered the question Dot asked.
     *
     * @param questionId the question the answer was given to, when known. An
     *   answer to an earlier question — a second tap, a card that was still
     *   on its way down — is refused rather than applied to this one.
     */
    fun answerClarification(answer: String, questionId: Int? = null) {
        val asking = state as? AiSessionState.AskingClarification ?: return
        if (questionId != null && questionId != asking.id) {
            GuideLog.d("ai") { "an answer to an earlier question; ignored" }
            return
        }
        if (answer.isBlank()) return
        val question = pendingQuestion.orEmpty()
        answers += QuestionAnswer(question, answer.trim().take(200))
        if (doneQuestionId == asking.id) {
            doneQuestionId = null
            pendingQuestion = null
            if (answer.trim() == DONE_YES) {
                remember(progress, "The learner confirmed this was done.", MAX_PROGRESS)
                return finish("Done.")
            }
            lastManualPage = null
        }
        formQuestion?.takeIf { it.first == asking.id }?.let { (_, key) ->
            formQuestion = null
            val yes = answer.trim() == BIRTHDAY_YES
            askedAboutField[key] = yes
            if (yes) {
                host.confirmField(key)
                birthdayAdvanceShownFor = null
            } else {
                changeBirthday = true
            }
        }
        // An account answer, however it was asked, is what the sign-in help
        // goes by from here on.
        AuthAssistant.accountAnswerFrom(question, answer.trim())?.let { auth.rememberAccount(it) }
        pendingQuestion = null
        noteAnswered(question)
        checkpoint("observing")
        transitionTo(AiSessionState.Observing)
        // The question comes down at once: a second tap has nothing to land on.
        host.showMessage(state)
        requestPlan(force = true, why = "answer")
    }

    /**
     * The screen has stopped changing.
     *
     * Which states replan on which changes is the whole of the loop's
     * appetite for requests, so it is written out rather than implied.
     */
    fun onScreenSettled(change: Change = Change.Navigation) {
        // Anything settling means the page has stopped moving.
        movingSince = null
        // A new page in the same window — a password page swapped in for a
        // birthday page — is a new page, whatever event announced it, and
        // whatever this state would otherwise make of a content change.
        if (newPageInPlace(change)) return
        if (hiddenForDot && change == Change.Navigation) {
            // Out of Dot again: whatever was up before comes back — a pause,
            // a question, a card waiting for a choice — before anything else.
            hiddenForDot = false
            redraw()
        }
        parked?.let {
            // An answer was held back while the page moved. Now it can be
            // checked against the page as it has settled.
            parked = null
            resumeParked(it.first, it.second)
            return
        }
        if (state == AiSessionState.HoldStill) {
            // One fresh look at the settled page.
            transitionTo(AiSessionState.Observing)
            host.showMessage(state)
            requestPlan(force = true, why = change.why)
            return
        }
        if (change == Change.Navigation) typingWatch = null
        when (state) {
            is AiSessionState.Guiding -> when {
                change == Change.Scroll -> relocate()
                // Sign-in help reads the screen on the phone, and sends
                // nothing, so it can look at every change: the cursor moving
                // to the next box is how it knows the last one is done.
                isAuthTyping() && change == Change.Content -> requestPlan(why = change.why)
                // Typing produces a content change per keystroke. A pause
                // between letters is not the end of it; the words the step
                // asked for appearing in the field is.
                isTyping() && change == Change.Content -> checkTypedText()
                isSubmitting() && change == Change.Content -> checkTypedText()
                else -> requestPlan(why = change.why)
            }

            // Asked to scroll, and they scrolled: that is the step done,
            // whether or not anything tappable changed. A pager's next page
            // is usually pictures and text with no controls on it, so the
            // content key did not move and the session sat saying the same
            // thing over a page that had changed.
            is AiSessionState.WaitingForUserAction ->
                if (isSubmitting() && change == Change.Content) {
                    checkTypedText()
                } else {
                    requestPlan(
                        why = change.why,
                        force = change == Change.Scroll &&
                            (state as AiSessionState.WaitingForUserAction).instruction.kind == KIND_SCROLL,
                    )
                }

            AiSessionState.Observing,
            is AiSessionState.Thinking,
            -> requestPlan(why = change.why)

            // A progress bar changes the page constantly. The wait's own timer
            // decides when to look again — which is how "Open" appearing
            // after an install is noticed — and only a genuinely new screen
            // jumps it.
            is AiSessionState.WaitingForExternal ->
                if (change == Change.Navigation) requestPlan(why = change.why)

            // A failure a change of screen might have fixed, but only a new
            // screen — not every animation on the one that failed — and not
            // once the failures have run out.
            // Never against a known outage: a spending cap does not lift because
            // somebody scrolled.
            is AiSessionState.RecoverableFailure ->
                if (change == Change.Navigation && consecutiveFailures < MAX_FAILURES && outage == null) requestPlan(why = change.why)

            // The person has done something while a card waits for "I'm
            // done": the button is now the thing to press. Not before —
            // highlighting it straight away would invite pressing it first.
            is AiSessionState.ManualHandoff -> {
                val current = state as AiSessionState.ManualHandoff
                // The person did it and the app moved on — the picker closed,
                // a new page came up — so the card waiting for "I'm done" is
                // out of date: carried on from the new page by itself. Not on
                // a private step, which may take several pages of its own.
                // A page that changed without a new window — "Sign in" becoming
                // "Checking info…" — counts as moved on too: the step it asked
                // for is visibly over, and its words must not stay up.
                val movedOn = change != Change.Scroll && handoffShape != null && lastShape != null && !current.private &&
                    (lastShape!!.isNewPageAfter(handoffShape!!) || lastShape!!.controlsReplacedSince(handoffShape!!))
                // A picker's wheel step, and the picker closed — Set, Cancel or
                // outside it: looked at again on the phone, never left saying
                // "turn the wheel" over the form.
                if (pickerOpen && change == Change.Navigation) {
                    transitionTo(AiSessionState.Observing)
                    requestPlan(force = true, why = "picker_closed")
                    return
                }
                if (movedOn) {
                    GuideLog.d("ai") { "the app moved on from the manual step; carrying on without \"I'm done\"" }
                    handoffShape = null
                    continueAfterHandoff()
                    return
                }
                if (!current.emphasizeDone && change != Change.Scroll) {
                    transitionTo(current.copy(emphasizeDone = true))
                    host.showMessage(state)
                }
            }

            // Sign-in help watches the screen locally — nothing is sent — so
            // it can move on to the next box, notice the sign-in has
            // finished, or see the cursor arrive in the code box after the
            // code was read from the notifications.
            // Sign-in help moves on by itself when the page does, so its
            // "I'm done" stays a quiet fallback: emphasising it would invite
            // pressing it before the page's own button.
            is AiSessionState.AuthGuidance, is AiSessionState.LocalChoice -> requestPlan(why = change.why)

            // Still unanswered, and somewhere new: put the question back up.
            // It comes down while it is answered in Dot, and somebody who
            // leaves Dot without answering should still see what was asked.
            // But a question about a page the person has since left — they
            // tapped the app's own button instead of answering, and a new
            // page or dialog is up — is out of date: it comes down, and the
            // new page is looked at afresh. Coming back from Dot, or to the
            // same page, it stays.
            // Something was tapped in the app while the question is up: a
            // permission prompt it raises sends no event of its own, so the
            // page is looked at once more a moment later.
            is AiSessionState.AskingClarification -> if (change == Change.Action) {
                val id = (state as AiSessionState.AskingClarification).id
                scope.launch {
                    delay(ASK_RECHECK_MS)
                    if (!stopped && (state as? AiSessionState.AskingClarification)?.id == id) onScreenSettled(Change.Navigation)
                }
            } else if (change == Change.Navigation) {
                val now = lastShape
                val asked = askedShape
                if (now != null && asked != null && !now.inDot && !asked.inDot && now.isNewPageAfter(asked)) {
                    GuideLog.d("ai") { "the question was about a page that has gone; looking afresh" }
                    pendingQuestion = null
                    formQuestion = null
                    doneQuestionId = null
                    startAfresh("page_changed")
                } else {
                    host.showMessage(state)
                }
            }

            // A teaching card stays whatever the screen does; the information
            // it points at is found again where it now is, or not outlined.
            is AiSessionState.Teaching -> relocateInformation()

            // Looking again already: a settled screen is the best moment for
            // the next look, sooner than the timer.
            AiSessionState.Reacquiring -> scheduleReacquire(0)

            // Could not read it: a screen that settles into something
            // readable — the page finished loading, the dialog was answered —
            // carries the task straight on, without anyone pressing anything.
            is AiSessionState.CannotRead -> scheduleReacquire(0, oneLook = true)

            else -> Unit
        }
    }

    /**
     * The control the step points at has just gone from the screen. Its
     * words go with it — nothing that says "Tap Sign in" stays over a page
     * with no Sign in — and the page is looked at again once it settles.
     */
    fun onTargetVanished() {
        if (state !is AiSessionState.Guiding) return
        clearTarget()
        // "Looking again", not "pause scrolling": nothing was scrolled.
        transitionTo(AiSessionState.Observing)
        host.showMessage(state)
    }

    /** The keyboard opened or closed. Not navigation. */
    fun onKeyboardChanged(visible: Boolean) {
        if (state !is AiSessionState.Guiding) return
        if (isTyping() && !visible) {
            // Putting the keyboard away is not the same as having finished:
            // people close it to see the page, and reopen it. Nothing is
            // concluded from it.
            //
            // Local sign-in help looks again — on the phone, nothing sent —
            // so the words match a box that no longer has the keyboard up.
            // Anywhere else the step stays as it is, and moves on when the
            // words appear in the field, or the page changes.
            if (isAuthTyping()) requestPlan(force = true, why = "keyboard") else relocate()
        } else {
            // The page was resized around the keyboard: the target is where it
            // was on the page, somewhere else on the screen.
            relocate()
        }
    }

    /**
     * Something moved while a request was out.
     *
     * The answer will be about a screen that has gone, so it is cancelled
     * rather than allowed to arrive and be rejected later — the rejection
     * costs the same and the request costs money.
     */
    fun onScreenChanged() {
        stopDemonstration()
        abandonInFlight()
    }

    /**
     * Looks, on the phone, at what page is in front. When it is a different
     * page from the last one, though the window is the same, everything
     * belonging to the old one is dropped — the request in flight, its
     * answer when it comes, the wait's timer, the step and its target — and
     * the new page is looked at afresh: locally, at once, when it is private.
     *
     * @return true when the page was new and has been dealt with.
     */
    private fun newPageInPlace(change: Change): Boolean {
        val shape = host.screenShape() ?: return false
        val before = lastShape
        lastShape = shape
        // Structure only: which window, how many boxes, whether private. Never a value.
        GuideLog.d("ai") { "settled ${change.name.lowercase()}: window ${shape.windowId}, ${shape.fields.size} box(es), private=${shape.private}" }
        // A navigation or a scroll has its own path already; this is for the
        // page that changed without saying so.
        if (change == Change.Navigation || change == Change.Scroll) return false
        if (before == null || !shape.isNewPageAfter(before)) return false
        if (stopped || shape.inDot) return false
        when (state) {
            // Left as they are: the person's own pause, an ending, and the
            // cards that wait for a decision only the person can make.
            AiSessionState.Paused,
            is AiSessionState.Completed,
            is AiSessionState.Stuck,
            is AiSessionState.LessonUnavailable,
            is AiSessionState.WaitExpired,
            is AiSessionState.AskingClarification,
            is AiSessionState.Teaching,
            -> return false
            is AiSessionState.RecoverableFailure -> if (outage != null) return false
            else -> Unit
        }
        GuideLog.d("ai") { "a new page in the same window (private=${shape.private}); starting afresh from it" }
        startAfresh(if (shape.private) "private_screen" else "page_changed")
        return true
    }

    /** Everything about the old page goes; the page in front is looked at now. */
    private fun startAfresh(why: String) {
        beginStep()
        onScreenChanged()
        endWaitEpisode()
        typingWatch = null
        reacquireJob?.cancel()
        clearTarget()
        transitionTo(AiSessionState.Observing)
        host.showMessage(state)
        requestPlan(force = true, why = why)
    }

    /** Drops whatever was being worked out for the screen that has gone. */
    private fun abandonInFlight() {
        parked = null
        movingSince = null
        slowTimer?.cancel()
        generation++
        relocating?.cancel()
        if (planning?.isActive == true) {
            GuideLog.d("ai") { "the screen moved; abandoning the request in flight" }
            planning?.cancel()
            planning = null
            // Not queued: the settle that follows this change asks again, with
            // the screen as it has become.
            replanQueued = false
        }
    }

    /**
     * The page is being scrolled.
     *
     * Not a reason to abandon a request in flight: cancelling at every small
     * movement is what kept a slow answer from ever arriving while somebody
     * scrolled, and the session sat thinking for ever. The request carries
     * on; its answer is checked against the page once it has settled. Only
     * movement that goes on long enough to stand in the way of a useful
     * reading earns a message, and it is a steady one.
     */
    fun onMovement() {
        // The person has begun: the demonstration has done its work.
        stopDemonstration()
        if (planning?.isActive == true) movedDuringRequest = true
        val t = now()
        val since = movingSince ?: t.also { movingSince = it }
        relocating?.cancel()
        val waitingOnIt = state == AiSessionState.Observing || state is AiSessionState.Thinking || parked != null
        if (waitingOnIt && t - since >= HOLD_AFTER_MS && state != AiSessionState.HoldStill) {
            slowTimer?.cancel()
            clearTarget(samePage = true)
            transitionTo(AiSessionState.HoldStill)
            host.showMessage(state)
        }
    }

    /**
     * The person did something. Was it the thing?
     *
     * A click event is not an answer: it says a control was touched, not that
     * the screen it was meant to produce arrived. So the next reading decides.
     */
    fun onUserActed() {
        stopDemonstration()
        val current = state
        // The send button of a private screen: an attempt, remembered so the
        // same screen afterwards is recognised as "it didn't go through".
        if (current is AiSessionState.Guiding && current.instruction.kind == KIND_AUTH && current.target.role == "Button") {
            auth.onSubmitTapped()
        }
        if (current is AiSessionState.Guiding && advanceNaming != null && current.target.naming == advanceNaming) advanceTapped = true
        if (current is AiSessionState.Guiding && !isTyping()) {
            transitionTo(AiSessionState.WaitingForUserAction(current.instruction, now()))
            clearTarget(samePage = true)
        }
    }

    /**
     * Points at the control named [naming] as though the model had chosen it:
     * a fresh reading, the same validation, the same drawing. For looking at
     * how a given control is outlined on a device; the service only calls it
     * in debug builds.
     */
    fun debugPointAt(naming: String) {
        if (stopped || state == AiSessionState.Paused) return
        onScreenChanged()
        val startedAt = ++generation
        scope.launch {
            val observed = host.observe(ObservePurpose.Grounding)
            val seen = observed as? ObserveResult.Seen
                ?: return@launch GuideLog.d("ai") { "debug point: nothing readable ($observed)" }
            val control = seen.observation.candidates.firstOrNull { it.naming == naming }
                ?: return@launch GuideLog.d("ai") { "debug point: no control named that" }
            val words = if (control.editable) "Type in “$naming”." else "Tap “$naming”."
            pointAt(
                seen.observation.observationId, control.id, seen.observation, startedAt,
                RuntimeInstruction(words, "", "", if (control.editable) KIND_TYPE else KIND_TAP),
                mustBeEditable = control.editable,
            )
        }
    }

    /**
     * Puts up a manual step naming the control [naming], as the planner
     * would. For looking at card placement on a device; debug builds only.
     */
    /** A question put up directly, for a device test of the question cards. Debug builds only. */
    fun debugAsk(question: String, options: List<String>) {
        if (!GuideLog.isEnabled) return
        onScreenChanged()
        clearTarget()
        pendingQuestion = question
        transitionTo(AiSessionState.AskingClarification(question, options, ++questionSeq))
        checkpoint("asking")
        host.showMessage(state)
    }

    fun debugManual(words: String, naming: String) {
        if (stopped || state == AiSessionState.Paused) return
        onScreenChanged()
        val startedAt = ++generation
        scope.launch {
            val seen = host.observe(ObservePurpose.Grounding) as? ObserveResult.Seen ?: return@launch
            val control = seen.observation.candidates.firstOrNull { it.naming == naming }
            apply(
                AiDecision.Manual(seen.observation.observationId, words, "test", candidateId = control?.id),
                seen.observation,
                startedAt,
            )
        }
    }

    /**
     * The small "I've finished typing" link, for words only the person
     * knows — Dot cannot see when an email address is complete.
     */
    fun doneTyping(step: Long? = null) {
        if (!isTyping()) return
        if (step != null && step != stepId) {
            GuideLog.d("ai") { "\"done typing\" from a card that has gone; ignored" }
            return
        }
        // Once per step: a second tap on the same button asks nothing more.
        if (typingConfirmedFor == stepId) return
        typingConfirmedFor = stepId
        // Ready for what comes next — not proof the app accepted it. The next
        // look at the screen says whether it did.
        if (isAuthTyping()) auth.onDoneTyping()
        requestPlan(force = true, why = "typed")
    }

    /** A choice made on the card, for the question [questionId] when known. */
    fun chooseAt(index: Int, questionId: Int? = null) {
        (state as? AiSessionState.AskingClarification)?.let { asking ->
            // The model's question, answered where it was asked.
            asking.options.getOrNull(index)?.let { answerClarification(it, questionId) }
            return
        }
        val current = state as? AiSessionState.LocalChoice ?: return
        if (questionId != null && questionId != current.id) {
            GuideLog.d("ai") { "a choice from an earlier question; ignored" }
            return
        }
        val choice = current.choices.getOrNull(index) ?: return
        if (choice in ACCOUNT_CHOICES) {
            // A choice that changes the path, kept with the task: it goes to
            // the planner with every later request, survives a break, and
            // can be changed.
            answers.removeAll { it.question == current.question }
            answers += QuestionAnswer(current.question, choice.label)
            noteAnswered(current.question)
            checkpoint("guiding")
        }
        val step = auth.onChoice(choice)
        if (!auth.active) {
            // "It looks finished": back to ordinary guidance, from a fresh look.
            noteSignInStep()
            transitionTo(AiSessionState.Observing)
            requestPlan(force = true, why = "choice")
            return
        }
        applyAuth(step)
    }

    /**
     * "Change answer": the last answer was a wrong tap, or they have thought
     * again. The answer is taken back and the question comes up again from
     * the screen in front, on the phone or from the planner.
     */
    fun changeAnswer() {
        val question = answeredQuestion ?: return
        answers.removeAll { it.question == question }
        if (AuthAssistant.accountAnswerFrom(question, AuthChoice.HaveAccount.label) != null) auth.rememberAccount(null)
        answeredQuestion = null
        host.offerChangeAnswer(null)
        checkpoint("guiding")
        onScreenChanged()
        clearTarget()
        auth.end()
        transitionTo(AiSessionState.Observing)
        host.showMessage(state)
        requestPlan(force = true, why = "answer_changed")
    }

    /**
     * "That didn't work."
     *
     * Recorded against the instruction, so the next answer is asked not to
     * repeat it. Twice on the same step is a pattern, and is offered another
     * way rather than a third attempt at the same thing.
     */
    fun reportProblem() {
        val last = lastInstruction ?: return requestPlan(force = true, why = "did_not_work")
        remember(failedApproaches, "Said it did not work: ${last.instruction}", MAX_FAILED)
        reportedProblem = true
        problemReports++
        if (problemReports >= MAX_PROBLEM_REPORTS) {
            problemReports = 0
            lastStepKey?.let { stalledKeys += it }
            stuck(stallCause())
            return
        }
        clearTarget()
        transitionTo(AiSessionState.Observing)
        requestPlan(force = true, why = "did_not_work")
    }

    /** "Try another way": the same as keeping on trying. */
    fun tryAnotherWay() = keepTrying()

    /**
     * "Keep trying", from a task that has stopped moving.
     *
     * A fresh look, with everything that did not work still on record and
     * asked to be avoided. Not the same thing again: a step that stalled is
     * never shown a second time, and after [MAX_KEEP_TRYING] rounds only
     * stopping is offered. A connection that failed is simply asked again.
     */
    fun keepTrying() {
        val current = state
        if (current is AiSessionState.RecoverableFailure) {
            if (!current.canRetry) return
            if (!lastWasBlocked) return retry()
        } else if (current !is AiSessionState.Stuck || !current.canKeepTrying) {
            return
        }
        keepTryingRounds++
        lastWasBlocked = false
        reportedProblem = true
        strikes = 0
        lastStepKey = null
        consecutiveFailures = 0
        clearTarget()
        transitionTo(AiSessionState.Observing)
        host.showMessage(state)
        requestPlan(force = true, why = "another_way")
    }

    /**
     * "Try again later": stop here, with everything kept.
     *
     * The goal, the lesson, the answers and what was verified are saved; any
     * request, timer and overlay stops. Nothing is marked done, nothing is
     * scheduled, and nothing will try again by itself — the task is simply
     * there to carry on when the person comes back to it.
     */
    fun tryLater() {
        GuideLog.d("ai") { "try again later: saved, stopping" }
        checkpoint(AiSessionStore.STATUS_LATER)
        stop(keepCheckpoint = true, status = AiSessionStore.STATUS_LATER)
    }

    /**
     * "End guide": the person's own choice to stop, now, from any state.
     *
     * A lesson keeps its place and stays unfinished, so Learn can offer to carry
     * on; an Ask is over, and is never marked done. Either way nothing in flight
     * can land afterwards ([stop] sets [stopped], and the request is cancelled),
     * and whether the task's credit goes back is decided as it is for any guide
     * that ends unfinished — by the service, which never charges a second time.
     */
    fun endGuide() {
        if (context.lesson != null && state !is AiSessionState.Completed) {
            checkpoint(AiSessionStore.STATUS_LATER)
            stop(keepCheckpoint = true, status = AiSessionStore.STATUS_LATER)
        } else {
            stop()
        }
    }

    /** "Keep waiting": another bounded allowance, granted by the person, never by itself. */
    fun keepWaiting() {
        if (state !is AiSessionState.WaitExpired) return
        waitAllowance = KEEP_WAITING_EXTRA
        transitionTo(AiSessionState.WaitingForExternal(waitReason, KEEP_WAITING_EXTRA, DEFAULT_RECHECK_MS))
        host.showMessage(state)
        scheduleRecheck(DEFAULT_RECHECK_MS)
    }

    fun pause() {
        reacquireJob?.cancel()
        // A question waiting for an answer is kept through the pause and
        // put back up afterwards, not asked again from scratch.
        pausedQuestion = state as? AiSessionState.AskingClarification
        pausedTeaching = state as? AiSessionState.Teaching
        onScreenChanged()
        waitTimer?.cancel()
        recoveryTimer?.cancel()
        replanQueued = false
        clearTarget()
        checkpoint("paused")
        transitionTo(AiSessionState.Paused)
        // Every other state draws itself here rather than from the transition,
        // and pausing is the one that most needs to: it is the person asking
        // Dot to stop looking, and they have to be able to see that it has.
        host.showMessage(state)
    }

    fun resumeFromPause() {
        if (state !is AiSessionState.Paused) return
        pausedTeaching?.let { card ->
            pausedTeaching = null
            teach(card.stage, card.words, card.checkpointId, card.target)
            if (card.target != null) relocateInformation()
            return
        }
        pausedQuestion?.let { question ->
            pausedQuestion = null
            transitionTo(question.copy(id = ++questionSeq))
            host.showMessage(state)
            return
        }
        recoveryAttempts = 0
        transitionTo(AiSessionState.Observing)
        // Said at once, so the paused card is replaced by something true
        // rather than left up, or taken down, while the screen is read.
        host.showMessage(state)
        requestPlan(force = true, why = "resume")
    }

    /** The person says they have finished the private part. */
    fun continueAfterHandoff() {
        if (state is AiSessionState.AuthGuidance) {
            auth.onContinue()
            transitionTo(AiSessionState.Observing)
            requestPlan(force = true, why = "handoff_done")
            return
        }
        if (state !is AiSessionState.ManualHandoff) return
        // "Done" on a wheel: the next wheel, or Set — looked at again first,
        // on the phone, in case the picker has closed meanwhile.
        if (pickerOpen) pickerStep++
        transitionTo(AiSessionState.Observing)
        requestPlan(force = true, why = if (pickerOpen) "picker_step" else "handoff_done")
    }

    /** The person says the thing they wanted has happened. */
    fun confirmDone() {
        remember(progress, "The person confirmed this was done.", MAX_PROGRESS)
        finish("Done.")
    }

    fun retry() {
        if ((state as? AiSessionState.Thinking)?.slow == true) {
            // Taking too long: this attempt is dropped and a fresh one made.
            onScreenChanged()
            transitionTo(AiSessionState.Observing)
            requestPlan(force = true, why = "retry")
            return
        }
        val failure = state as? AiSessionState.RecoverableFailure ?: return
        // A spent allowance or a missing set-up is not tried again from
        // here: the card offers only stopping, and nothing else may ask.
        if (!failure.canRetry) return
        // Several quick taps are one request, not several.
        if (planning?.isActive == true || now() - lastManualRetryAt < RETRY_DEBOUNCE_MS) {
            GuideLog.d("ai") { "try again ignored: busy=${planning?.isActive == true}" }
            return
        }
        lastManualRetryAt = now()
        recoveryAttempts = 0
        // After a blocked answer, asking the same question again would get the
        // same answer. Ask for another way instead.
        if (lastWasBlocked) reportedProblem = true
        lastWasBlocked = false
        transitionTo(AiSessionState.Observing)
        requestPlan(force = true, why = "retry")
    }

    /**
     * The end of the session, from any state.
     *
     * Nothing already in flight may land afterwards: the generation moves on,
     * every job is cancelled, and [stopped] turns away anything that was past
     * its last check.
     *
     * @param keepCheckpoint true when the session is being cut off rather
     *   than ended — the service going away, an app update — so the person
     *   can carry on later. False when they chose to end it.
     */
    fun stop(keepCheckpoint: Boolean = false, status: String = "interrupted") {
        // Once. A second stop — "Try again later" followed by the service
        // letting go — must not discard what the first one kept.
        if (stopped) return
        GuideLog.d("cost") { "session stopped: ${costs.summary()}" }
        if (keepCheckpoint) checkpoint(status)
        // Ended without finishing: the credit goes back if Dot never actually
        // helped with this task. The service decides, not this.
        if (state !is AiSessionState.Completed) context.taskId.takeIf(String::isNotBlank)?.let { admission?.release(it) }
        stopped = true
        onScreenChanged()
        waitTimer?.cancel()
        recoveryTimer?.cancel()
        relocating?.cancel()
        slowTimer?.cancel()
        reacquireJob?.cancel()
        planning?.cancel()
        replanQueued = false
        clearTarget()
        if (!keepCheckpoint) store.discard()
    }

    // ----------------------------------------------------------- the loop

    /**
     * Asks for the next step, now or as soon as the current request ends.
     *
     * Calling this from inside the running request — which is what a stale
     * answer does — queues the next one instead of silently doing nothing.
     */
    private fun requestPlan(force: Boolean = false, why: String = "other") {
        if (stopped || !mayPlan(state)) return
        if (force) forceNext = true
        // The first reason given since the last request is the one it is sent
        // with — unless a later one insists: a wait's own recheck, a retry.
        if (pendingWhy == null || force) pendingWhy = why
        if (planning?.isActive == true) {
            replanQueued = true
            return
        }
        launchPlan()
    }

    private fun launchPlan() {
        val startedAt = ++generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // A watchdog over the whole step. Every part of it is bounded on
            // its own — the network, the screenshot — but a single hang
            // anywhere would otherwise hold the one planning slot for ever,
            // and every later request would queue behind it.
            val finished = withTimeoutOrNull(PLAN_WATCHDOG_MS) { plan(startedAt); true }
            if (finished == null && current(startedAt)) {
                GuideLog.d("ai") { "planning took too long; giving up on this attempt" }
                failed(PlanningFailure.SlowAnswer)
            }
        }
        occupy(job)
    }

    /** The one planning slot: [job] holds it until it ends, and a queued request follows. */
    private fun occupy(job: Job) {
        planning = job
        job.invokeOnCompletion {
            if (planning === job) planning = null
            if (replanQueued && !stopped && mayPlan(state)) {
                replanQueued = false
                scope.launch { requestPlan(why = "queued") }
            }
        }
        job.start()
    }

    private suspend fun plan(startedAt: Long) {
        // While a code is being fetched from another app, nothing is looked
        // at for sending — not even a picture taken to be thrown away.
        // A picture helps most exactly when things have gone wrong, so it is
        // always sent after a failure, a complaint, or a retry.
        val textFirst = PlanningTuning.selectiveScreenshots && consecutiveFailures == 0 && !reportedProblem &&
            failedApproaches.isEmpty() && pendingWhy !in PICTURE_TRIGGERS
        val pictureOnly = pictureOnlyNext && !auth.keepsUploadsOff
        pictureOnlyNext = false
        val purpose = when {
            auth.keepsUploadsOff -> ObservePurpose.Grounding
            pictureOnly -> ObservePurpose.PlanningPictureOnly
            textFirst -> ObservePurpose.PlanningTextFirst
            else -> ObservePurpose.Planning
        }
        var observed = host.observe(purpose)
        if (!current(startedAt)) return
        // A window being rebuilt — after a font-size change, say — has
        // nothing to read for a moment. Seen live as a spurious "I can't read
        // this screen". One short second look before calling it a failure.
        if (observed == ObserveResult.Nothing || (observed is ObserveResult.Sparse && !pictureOnly)) {
            delay(REREAD_MS)
            if (!current(startedAt)) return
            observed = host.observe(purpose)
            if (!current(startedAt)) return
        }

        val observation = when (observed) {
            is ObserveResult.Sensitive -> {
                lastShape = host.screenShape() ?: lastShape
                val screen = observed.auth
                // Arriving at a sign-in from ordinary guidance: is signing in
                // part of the goal at all? "Install X" is done once X opens,
                // sign-in screen or not. Asked with nothing from the screen.
                if (screen != null && goalCheckedFor != screen.packageName) {
                    // Once per app per task, and only when coming from
                    // ordinary guidance: a task that starts on a sign-in, or
                    // comes back to one, is not asked again.
                    goalCheckedFor = screen.packageName
                    if (!auth.active && lastInstruction != null && outage == null) {
                        if (goalDoneBeforeSignIn(screen, startedAt)) return
                        if (!current(startedAt)) return
                    }
                }
                return if (screen != null) applyAuth(auth.onScreen(screen)) else handOff(observed.reason)
            }
            ObserveResult.InDot -> return leaveDot()
            // A screen that could not be read is not a failed task. Dot looks
            // again on the phone — nothing is asked of the planner meanwhile —
            // and carries on as soon as it can see. Seen live on a Facebook
            // sign-up page between steps: one empty reading, a second one
            // 700 ms later, and the person was told to try again later.
            ObserveResult.Nothing, is ObserveResult.Sparse ->
                return if (pictureOnly) unreadable(Unreadable.Unclear) else reacquire(observed)
            ObserveResult.SystemDialog -> return unreadable(Unreadable.SystemDialog)
            is ObserveResult.Withheld -> return withheld(observed)
            ObserveResult.NotAllowed -> return unreadable(Unreadable.NotAllowed)
            is ObserveResult.Seen -> observed.observation
        }
        decidedShape = host.screenShape()?.also { lastShape = it }
        remember(recentApps, observation.packageName, MAX_RECENT_APPS)
        if (!observation.homeScreen) lastAppPackage = observation.packageName

        if (auth.keepsUploadsOff) {
            if (observation.packageName != auth.originPackage) {
                // In Messages or email, reading the code. Say how to get
                // back; describe nothing.
                return applyAuth(auth.onElsewhere())
            }
            // Back in the app, on an ordinary screen: the code step is over.
        }
        if (auth.active) {
            // Out of the private screens. Whatever happened there is judged
            // from this screen — a tap on "Next" is not a sign-in.
            auth.end()
            noteSignInStep()
        }

        // A date picker, and the moment it closes, are helped with on the
        // phone: one wheel at a time, then Set, then "is that right?".
        if (localFormStep(observation, startedAt)) return

        // The swipe for the list of all apps opened recent apps instead —
        // easily done from too near the bottom edge. Recognised on the phone,
        // and put right on the phone: back to Home, then the swipe again.
        if (lastInstruction?.kind == KIND_APP_LIST &&
            GesturePlanner.viewOf(observation) == LauncherView.Recents &&
            wrongTurns < MAX_WRONG_TURNS
        ) {
            wrongTurns++
            beginStep()
            GuideLog.d("ai") { "the app list swipe opened recent apps; going back Home first" }
            val navigation = host.device().navigation
            forceNext = true
            textOnly(
                RuntimeInstruction(
                    LocalWording.leaveRecents(navigation),
                    "The Home screen shows.",
                    "",
                    KIND_HOME,
                    gesture = Gesture.Home(navigation),
                ),
            )
            demonstrate(host.planGesture(GesturePurpose.GoHome, observation))
            return
        }

        // The same screen, unchanged, is the same question. Asking again
        // would cost money to be told the same thing.
        val force = forceNext
        if (!force && observation.contentKey == lastContentKey && isShowingStep()) {
            GuideLog.d("ai") { "the screen has not changed; not asking again" }
            pendingWhy = null
            costs.avoided("unchanged")
            return
        }
        forceNext = false

        // The outline comes down while the next step is worked out — as a
        // same-page withdrawal, so if the answer is the same control again
        // Pebble does not replay its entrance.
        clearTarget(samePage = true)
        // Scrolling that has gone on long enough keeps its steady message;
        // the request still goes, and its answer waits for the page.
        if (state != AiSessionState.HoldStill) {
            transitionTo(AiSessionState.Thinking(consecutiveFailures))
            host.showMessage(state)
        }
        decidedFrom = observation
        movedDuringRequest = false
        slowTimer?.cancel()
        slowTimer = scope.launch {
            delay(SLOW_MS)
            val thinking = state as? AiSessionState.Thinking
            if (current(startedAt) && thinking != null && !thinking.slow) {
                transitionTo(thinking.copy(slow = true))
                host.showMessage(state)
            }
        }

        if (!ensureAdmitted(startedAt)) return
        val trigger = pendingWhy ?: "other"
        pendingWhy = null
        costs.asked(trigger, observation.screenshotJpeg != null)
        val outcome = client.nextStep(
            PlanningRequest(
                goal = goal,
                observation = observation,
                answers = answers.toList(),
                progress = progress.toList(),
                subgoal = subgoal,
                failedApproaches = failedApproaches.toList(),
                recentApps = recentApps.toList(),
                lastInstruction = lastInstruction?.instruction.orEmpty(),
                lastExpectedOutcome = lastInstruction?.expectedOutcome.orEmpty(),
                lastKind = lastInstruction?.kind.orEmpty(),
                reportedProblem = reportedProblem,
                device = host.device(),
                attempt = consecutiveFailures,
                trigger = trigger,
                context = context,
                taskDetails = taskDetails.toMap(),
                explained = explained.toList(),
            ),
        )
        slowTimer?.cancel()
        if (!current(startedAt)) {
            GuideLog.d("ai") { "an answer arrived for a screen that has gone" }
            return
        }
        // A new page may have arrived in the same window while the answer was
        // on its way, with nothing to announce it yet. The answer was about
        // the old page, and nothing of it is shown.
        val arrivedOn = host.screenShape()
        val askedOn = decidedShape
        if (arrivedOn != null && askedOn != null && arrivedOn.isNewPageAfter(askedOn)) {
            GuideLog.d("ai") { "an answer arrived after a new page came in place; not showing it" }
            lastShape = arrivedOn
            startAfresh(if (arrivedOn.private) "private_screen" else "page_changed")
            return
        }

        when (outcome) {
            is PlanningOutcome.Unreachable -> failed(outcome.kind, outcome.retryAfterMs, outcome.correlationId)
            is PlanningOutcome.Refused -> failed(outcome.kind, outcome.retryAfterMs, outcome.correlationId)
            is PlanningOutcome.Decided -> {
                cannotReadChecks = 0
                consecutiveFailures = 0
                recoveryAttempts = 0
                outage = null
                reportedProblem = false
                lastContentKey = observation.contentKey
                GuideLog.d("ai") {
                    "decided ${outcome.decision::class.simpleName} " +
                        "(${outcome.decision.meta.assessment}) in ${outcome.latencyMs}ms"
                }
                record(outcome.decision.meta)
                if (outcome.decision !is AiDecision.Wait) waitTimer?.cancel()
                if (repeatsWithoutProgress(outcome.decision)) return
                if (movingSince != null) {
                    // Still moving: nothing is drawn from a page in motion.
                    // Held until it settles, then checked against it.
                    GuideLog.d("ai") { "an answer arrived while the page was moving; holding it" }
                    parked = outcome.decision to observation
                    return
                }
                if (movedDuringRequest && outcome.decision is AiDecision.Scroll) {
                    // Advice to scroll, from before they scrolled: the page
                    // it was about has gone. A fresh look instead.
                    GuideLog.d("ai") { "a scroll suggestion from before the page moved; looking again" }
                    transitionTo(AiSessionState.Observing)
                    host.showMessage(state)
                    requestPlan(force = true, why = "regrounding")
                    return
                }
                apply(outcome.decision, observation, startedAt)
            }
        }
    }

    private suspend fun apply(decision: AiDecision, from: Observation, startedAt: Long) {
        beginStep()
        // Typing is watched through to its send button, and no further.
        if (decision !is AiDecision.TypeText && decision !is AiDecision.Submit) typingWatch = null
        if (decision is AiDecision.Complete && unsupportedCompletion(from)) return
        // A form's Next, Continue or Submit is only shown once the form is
        // ready as far as the phone can tell; otherwise what needs putting
        // right is, instead.
        val advancing = when (decision) {
            is AiDecision.Highlight -> decision.candidateId
            is AiDecision.Submit -> decision.candidateId.takeUnless { decision.viaKeyboard }
            else -> null
        }
        if (advancing != null && formHolds(advancing, from, startedAt)) return
        if (decision is AiDecision.Highlight && resultContradictsSearch(decision.candidateId, from)) return
        when (decision) {
            is AiDecision.Highlight -> {
                // In a launcher's search results, the installed app itself —
                // not a web search, a store suggestion or a look-alike — when
                // it is there, unambiguously, to be pointed at.
                val installed = com.aaravsekhri.dot.guide.apps.LauncherResults.correct(decision.candidateId, from, context.app)
                if (installed != null) {
                    GuideLog.d("ai") { "launcher results: pointing at the installed app rather than another result" }
                    pointAt(
                        decision.observationId, installed, from, startedAt,
                        RuntimeInstruction(
                            LocalWording.tap(from.candidate(installed)?.naming.orEmpty()),
                            "${context.app?.name ?: "The app"} opens.",
                            "",
                            KIND_TAP,
                        ),
                    )
                } else {
                    pointAt(
                        decision.observationId, decision.candidateId, from, startedAt,
                        RuntimeInstruction(decision.instruction, decision.expectedOutcome, "", KIND_TAP),
                    )
                }
            }

            is AiDecision.TypeText -> {
                pointAt(
                    decision.observationId, decision.candidateId, from, startedAt,
                    RuntimeInstruction(
                        LocalWording.typing(
                            decision.instruction.ifBlank {
                                LocalWording.typeInto(from.candidate(decision.candidateId)?.naming.orEmpty(), decision.text)
                            },
                            decision.text,
                        ),
                        decision.expectedOutcome,
                        "",
                        KIND_TYPE,
                        expectedText = decision.text.trim(),
                        field = from.candidate(decision.candidateId)?.let(FormFields::kindOf),
                    ),
                    mustBeEditable = true,
                )
                if (decision.text.isNotBlank()) typedThisAttempt = true
                // Words Dot knows can be watched for; the step then moves on
                // by itself when they are in the field.
                val guiding = state as? AiSessionState.Guiding
                typingWatch = guiding
                    ?.takeIf { it.instruction.kind == KIND_TYPE && it.instruction.expectedText.isNotBlank() }
                    ?.let { TypingWatch(it.instruction, it.target) }
            }

            is AiDecision.Submit -> {
                val control = decision.candidateId
                val words = decision.instruction.ifBlank {
                    if (decision.viaKeyboard || control == null) {
                        LocalWording.submitWithKeyboard(host.keyboardAction())
                    } else {
                        LocalWording.tap(from.candidate(control)?.naming.orEmpty())
                    }
                }
                val instruction = RuntimeInstruction(words, decision.expectedOutcome, "", KIND_SUBMIT)
                if (decision.viaKeyboard || control == null) {
                    textOnly(instruction)
                } else {
                    pointAt(decision.observationId, control, from, startedAt, instruction)
                }
            }

            is AiDecision.Scroll -> if (from.homeScreen && (decision.reveal == Reveal.Left || decision.reveal == Reveal.Right) &&
                ++homePageSwipes > MAX_HOME_PAGE_SWIPES
            ) {
                // Paging sideways through Home screens hunting for an icon is
                // what search is for. After a page or two, not suggested again.
                GuideLog.d("ai") { "a sideways Home screen swipe again; asking for app search instead" }
                remember(failedApproaches, "Swiping between Home screen pages to find an app; use the app search instead", MAX_FAILED)
                transitionTo(AiSessionState.Observing)
                host.showMessage(state)
                if (homePageSwipes > MAX_HOME_PAGE_SWIPES + 1) stuck(stallCause()) else requestPlan(force = true, why = "search_first")
            } else {
                scrollStep(decision, from)
            }

            is AiDecision.Navigate -> {
                val navigation = host.device().navigation
                val words = when (decision.destination) {
                    Destination.Home -> LocalWording.home(navigation)
                    Destination.Back -> LocalWording.back(navigation)
                }
                textOnly(
                    RuntimeInstruction(
                        words,
                        decision.expectedOutcome,
                        "",
                        if (decision.destination == Destination.Home) KIND_HOME else KIND_BACK,
                        gesture = if (decision.destination == Destination.Home) {
                            Gesture.Home(navigation)
                        } else {
                            Gesture.Back(navigation)
                        },
                    ),
                )
                // With gesture navigation, the system gesture is shown where
                // it really starts: the very bottom edge for Home, the side
                // edge for Back — never where the app list's swipe starts.
                val purpose = if (decision.destination == Destination.Home) GesturePurpose.GoHome else GesturePurpose.GoBack
                demonstrate(host.planGesture(purpose, from))
            }

            is AiDecision.Ask -> {
                // Already answered, and nothing since has said otherwise: the
                // person is not asked twice. Sent back once, with a reminder
                // of what they said; asked again only if the planner insists.
                val key = TextNormalizer.normalize(decision.question)
                val earlier = answers.lastOrNull { TextNormalizer.normalize(it.question) == key }
                if (earlier != null && reAsked.add(key)) {
                    GuideLog.d("ai") { "a question already answered was asked again; reminding the planner" }
                    remember(failedApproaches, "Asked again: \"${decision.question}\" was already answered \"${earlier.answer}\"", MAX_FAILED)
                    transitionTo(AiSessionState.Observing)
                    host.showMessage(state)
                    requestPlan(force = true, why = "already_answered")
                    return
                }
                if ((decision.options.size < 2 || decision.freeText) && asksForSecret(decision.question)) {
                    // Never a box for a password, a code or a card number:
                    // those go in the app itself, and never through Dot.
                    GuideLog.d("ai") { "a question that seems to want something private; not asking it" }
                    clearTarget()
                    lastInstruction = RuntimeInstruction(PRIVATE_ANSWER_NOTE, "", "", KIND_MANUAL)
                    transitionTo(AiSessionState.ManualHandoff(PRIVATE_ANSWER_NOTE, AiSessionState.ManualHandoff.PRIVATE_REASON))
                    checkpoint("manual")
                    host.showMessage(state)
                    return
                }
                clearTarget()
                pendingQuestion = decision.question
                // Something personal is never required to go on: "I'm not
                // sure" is always one of the answers, whatever was offered.
                val options = if (decision.personal && decision.options.none { NOT_SURE.containsMatchIn(it) }) {
                    (decision.options + NOT_SURE_ANSWER).takeLast(3)
                } else {
                    decision.options
                }
                transitionTo(
                    AiSessionState.AskingClarification(
                        decision.question, options, ++questionSeq,
                        freeText = decision.freeText || decision.personal, personal = decision.personal,
                    ),
                )
                checkpoint("asking")
                host.showMessage(state)
            }

            is AiDecision.Wait -> waitFor(decision, from)

            is AiDecision.Manual -> if (handsOverTheWholeTask(decision.instruction)) {
                // "Finish signing up yourself" is not a step: it is the whole
                // tutorial missing. Asked for the next single step instead,
                // once; after that, an honest pause rather than abandonment.
                GuideLog.d("ai") { "a manual step that hands over the whole task; asking for one step instead" }
                remember(failedApproaches, "Handing the whole task to the person (\"${decision.instruction.take(60)}\"); give the next single step, or ask what they see", MAX_FAILED)
                if (broadHandoffs++ < 1) {
                    transitionTo(AiSessionState.Observing)
                    host.showMessage(state)
                    forceNext = true
                    requestPlan(force = true, why = "one_step_please")
                } else {
                    stuck(StallCause.Observed("I can't guide this part reliably yet."))
                }
            } else if (decision.candidateId != null && TAP_WORDS.containsMatchIn(decision.instruction) &&
                from.candidate(decision.candidateId)?.let {
                    it.clickable && it.enabled && !it.editable && !CONSEQUENTIAL.containsMatchIn(it.naming)
                } == true
            ) {
                // "Tap the button at the bottom right" about a control that is
                // right there in the list: pointed at, not described.
                GuideLog.d("ai") { "a manual tap on a listed control; pointing at it instead" }
                pointAt(
                    decision.observationId, decision.candidateId, from, startedAt,
                    RuntimeInstruction(LocalWording.tap(from.candidate(decision.candidateId)?.naming.orEmpty()), "", "", KIND_TAP),
                )
            } else if (decision.candidateId == null && TAP_WORDS.containsMatchIn(decision.instruction) &&
                uniquelyMentioned(decision.instruction, from) != null
            ) {
                // "Tap the blue Get started button" with no id, about a control
                // that is in the list under exactly those words: the actual
                // control is outlined, and the step moves on when it is tapped,
                // rather than a card asking to be told "I'm done".
                val control = uniquelyMentioned(decision.instruction, from)!!
                GuideLog.d("ai") { "a manual tap naming one listed control; pointing at it instead" }
                pointAt(
                    decision.observationId, control.id, from, startedAt,
                    RuntimeInstruction(LocalWording.tap(control.naming), "", "", KIND_TAP),
                )
            } else if (repeatsManualOnSamePage(from)) {
                // The same kind of step again, on the same page, after the
                // person said they had done it: not shown a third time. A
                // lesson the learner confirms is theirs to call done.
                GuideLog.d("ai") { "a manual step again on the same page; asking whether it is done" }
                askIfDone()
            } else {
                // Where the control it names is now, read before the card goes
                // up: the card waits for "I'm done", so it must not sit on the
                // very thing being done. Never outlined.
                // Or, with no control given, the controls its words name —
                // "Tap Use password or Use biometrics" — wherever they are now.
                val live = host.observe(ObservePurpose.Grounding) as? ObserveResult.Seen
                if (!current(startedAt)) return
                val keepOff = live?.let { seen ->
                    decision.candidateId?.let { id -> sameNamed(from.candidate(id), seen.observation) }
                        ?: mentioned(decision.instruction, seen.observation)
                }.orEmpty()
                clearTarget()
                lastInstruction = RuntimeInstruction(decision.instruction, "", "", KIND_MANUAL)
                checkpoint("manual")
                // What it confirms, when the step is about a box: the one
                // named, or the one the last step pointed at.
                val about = decision.candidateId?.let { from.candidate(it) }?.let(FormFields::kindOf)?.takeIf { it != FieldKind.Unknown }
                    ?: fieldInHand
                handoffShape = lastShape
                transitionTo(
                    AiSessionState.ManualHandoff(
                        decision.instruction, decision.reason,
                        confirmLabel = about?.takeIf { it != FieldKind.Search }?.let(FormFields::doneTypingLabel),
                    ),
                )
                host.showMessage(state)
                // Counts only: why no control could be pointed at.
                GuideLog.d("ai") {
                    "manual step; keeping the card off ${keepOff.size} control(s) " +
                        "(listed=${from.candidates.size} truncated=${from.truncated} named=${decision.candidateId != null})"
                }
                host.keepClear(keepOff)
                // Naming nothing that could be matched: the app's own buttons
                // along the bottom — Get started, Skip, Next — are where such
                // a step is most often done, and are kept in view where that
                // costs nothing.
                if (keepOff.isEmpty()) live?.let { seen -> host.keepInView(bottomActions(seen.observation)) }
            }

            is AiDecision.Complete -> {
                if (practiceNotDoneYet()) return
                val result = context.lesson?.result.orEmpty()
                if (result.isNotBlank() && !resultShown) {
                    // What the result means, before the celebration; the
                    // completion itself was seen on this screen.
                    pendingCompletion = decision.summary to decision.evidence
                    teach(TeachStage.Result, result)
                } else {
                    finish(decision.summary, decision.evidence)
                }
            }

            is AiDecision.Explain -> explain(decision, from, startedAt)

            is AiDecision.Blocked -> {
                lastWasBlocked = true
                clearTarget()
                if (decision.unavailable && context.lesson != null) {
                    // What the lesson teaches is not here. Said plainly, and
                    // never replaced with steps for a feature that is not there.
                    checkpoint(AiSessionStore.STATUS_UNAVAILABLE)
                    transitionTo(AiSessionState.LessonUnavailable(decision.explanation))
                    host.showMessage(state)
                    return
                }
                checkpoint("blocked")
                transitionTo(
                    AiSessionState.Stuck(
                        // The app saying no is told apart from Dot not seeing:
                        // only then is the app named as what stopped it.
                        if (decision.appError) StallCause.AppError(decision.explanation) else StallCause.Observed(decision.explanation),
                        canKeepTrying = keepTryingRounds < MAX_KEEP_TRYING,
                    ),
                )
                host.showMessage(state)
            }
        }
    }

    /**
     * A scroll, shown where it happens.
     *
     * On a Home screen, "see more below" means opening the list of all apps:
     * shown on the Home screen itself, above the dock and well clear of the
     * bottom edge — whose upward swipe goes Home or to recent apps instead.
     * On a launcher whose way in is not known, it is said in words. Anywhere
     * else, the swipe is shown inside the list that moves.
     */
    private fun scrollStep(decision: AiDecision.Scroll, from: Observation) {
        val view = GesturePlanner.viewOf(from)
        if (from.homeScreen) GuideLog.d("ai") { "scroll on a launcher: view=$view reveal=${decision.reveal}" }
        if (from.homeScreen && decision.reveal == Reveal.Below && view == LauncherView.Home) {
            val plan = host.planGesture(GesturePurpose.OpenAppList, from)
            GuideLog.d("ai") { "app list swipe: ${if (plan == null) "said in words" else "shown from ${plan.start.x},${plan.start.y}"}" }
            textOnly(
                RuntimeInstruction(
                    if (plan != null) LocalWording.openAppListHere() else LocalWording.openAppListInWords(),
                    "The list of all apps opens.",
                    decision.lookingFor,
                    KIND_APP_LIST,
                    gesture = Gesture.Slide(FingerDirection.Up),
                ),
            )
            demonstrate(plan)
            return
        }
        // A suggestion, and described as one. It is not handed to the
        // scrolling controller as a proven position: that controller
        // believes readings of the target and nothing else, and a model's
        // guess about where something is would be exactly the invented
        // direction the scrolling design refuses to show.
        textOnly(
            RuntimeInstruction(
                LocalWording.scroll(decision.reveal, decision.lookingFor),
                decision.expectedOutcome,
                decision.lookingFor,
                KIND_SCROLL,
                gesture = Gesture.Slide(decision.reveal.finger()),
            ),
        )
        val area = decision.containerId?.let { from.candidate(it)?.bounds }
            ?: from.candidates.filter { it.scrollable && !it.horizontal == (decision.reveal == Reveal.Below || decision.reveal == Reveal.Above) }
                .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }?.bounds
        demonstrate(host.planGesture(GesturePurpose.ScrollContent, from, decision.reveal, area))
    }

    /**
     * Grounds a named control against the screen as it is now, then points.
     *
     * A control that cannot be found unambiguously is not approximated: the
     * words stand on their own as a text-only step, which keeps the session
     * watching — unlike a handoff, which would stop it.
     */
    private suspend fun pointAt(
        observationId: String,
        candidateId: String,
        from: Observation,
        startedAt: Long,
        instruction: RuntimeInstruction,
        mustBeEditable: Boolean = false,
    ) {
        val live = host.observe(ObservePurpose.Grounding)
        if (!current(startedAt)) return
        if (live !is ObserveResult.Seen) {
            GuideLog.d("ai") { "the screen changed before the answer could be grounded" }
            requestPlan(force = true, why = "regrounding")
            return
        }

        when (
            val grounded = DecisionValidator.ground(
                observationId, candidateId, from, live.observation, mustBeEditable,
            )
        ) {
            is DecisionValidator.Result.Ready -> {
                val named = instruction.copy(targetName = grounded.target.naming)
                stepShown()
                lastInstruction = named
                checkpoint("guiding")
                transitionTo(AiSessionState.Guiding(named, grounded.target))
                host.showTarget(grounded.target, named)
                host.keepInView(helpfulFor(named, candidateId, live.observation))
                fieldInHand = named.field ?: live.observation.candidate(candidateId)
                    ?.takeIf { it.readiness != Readiness.Unknown }?.let(FormFields::kindOf)?.takeIf { it != FieldKind.Unknown }
            }

            DecisionValidator.Result.Stale -> {
                GuideLog.d("ai") { "the answer was about a screen that has gone; looking again" }
                clearTarget()
                transitionTo(AiSessionState.Observing)
                requestPlan(force = true, why = "regrounding")
            }

            DecisionValidator.Result.NotFound,
            DecisionValidator.Result.Ambiguous,
            -> {
                GuideLog.d("ai") { "could not ground the answer: $grounded; words only" }
                textOnly(instruction)
                // Not outlined — it could not be pinned down — but whatever
                // on this page answers to its name is still kept clear.
                host.keepClear(sameNamed(from.candidate(candidateId), live.observation))
            }

            is DecisionValidator.Result.NotActionable -> {
                val wanted = from.candidate(candidateId)
                val offScreen = grounded.why.contains("off the screen")
                if (offScreen && wanted != null) {
                    // Not somewhere to tap yet: somewhere to scroll to.
                    val reveal = if (wanted.bounds.top >= live.observation.screenHeight) Reveal.Below else Reveal.Above
                    textOnly(
                        instruction.copy(
                            instruction = LocalWording.scroll(reveal, wanted.naming),
                            kind = KIND_SCROLL,
                            gesture = Gesture.Slide(reveal.finger()),
                        ),
                    )
                } else {
                    textOnly(instruction.copy(instruction = "${instruction.instruction} (It looks unavailable just now.)"))
                    host.keepClear(sameNamed(wanted, live.observation))
                }
            }

            // Found, but with no boundary worth drawing: the words carry it.
            is DecisionValidator.Result.WordsOnly -> {
                GuideLog.d("ai") { "no outline for this control (${grounded.why}); words only" }
                val named = from.candidate(candidateId)
                textOnly(instruction.copy(targetName = named?.naming.orEmpty()))
                host.keepClear(sameNamed(named, live.observation))
            }
        }
    }

    /**
     * Where on the live page something with this control's name and kind is:
     * for keeping the card off it, never for drawing. Positions from the page
     * as it is now, not from the reading the decision was made on.
     */
    /**
     * The controls on [live] that [words] name, for keeping the card off
     * them. Whole names only, three letters or more, and a handful at most:
     * this is for "Tap Use password or Use biometrics", not for guessing.
     */
    /** Words that mean a question is after something only the person should type, in the app. */
    private fun asksForSecret(question: String): Boolean = SECRET_WORDS.containsMatchIn(question)

    private fun mentioned(words: String, live: Observation): List<com.aaravsekhri.dot.guide.model.Bounds> {
        val said = " " + TextNormalizer.normalize(words) + " "
        return live.candidates
            .filter { it.clickable && it.enabled && !it.bounds.isEmpty }
            .filter { c -> TextNormalizer.normalize(c.naming).let { n -> n.length >= 3 && " $n " in said } }
            .map { it.drawBounds }
            .take(MAX_MENTIONED)
    }

    /** Tappable controls in the bottom fifth of the screen: an app's action bar. */
    private fun bottomActions(o: Observation): List<com.aaravsekhri.dot.guide.model.Bounds> =
        o.candidates.filter { it.clickable && it.enabled && !it.editable && !it.bounds.isEmpty && it.bounds.top > o.screenHeight * 4 / 5 }
            .map { it.drawBounds }
            .take(MAX_MENTIONED)

    /**
     * The one control an instruction names, when exactly one tappable,
     * enabled control in [live] carries those words — never a box, never one
     * that sends, buys or deletes, and never a guess between two.
     */
    internal fun uniquelyMentioned(words: String, live: Observation): CandidateControl? {
        val said = " " + TextNormalizer.normalize(words) + " "
        val matches = live.candidates
            .filter { it.clickable && it.enabled && !it.editable && !it.bounds.isEmpty && !CONSEQUENTIAL.containsMatchIn(it.naming) }
            .filter { c -> TextNormalizer.normalize(c.naming).let { n -> n.length >= 3 && " $n " in said } }
        // A name inside another that also matched is the same mention
        // ("Sign in" inside "Sign in with Google"); two separate names are
        // two controls, and then nothing is guessed.
        val names = matches.map { TextNormalizer.normalize(it.naming) }
        val best = matches.filterIndexed { i, _ -> names.withIndex().none { (j, other) -> j != i && other != names[i] && " ${names[i]} " in " $other " } }
        return best.singleOrNull()
    }

    private fun sameNamed(control: CandidateControl?, live: Observation): List<com.aaravsekhri.dot.guide.model.Bounds> {
        control ?: return emptyList()
        val name = TextNormalizer.normalize(control.naming)
        if (name.isBlank()) return emptyList()
        return live.candidates
            .filter { TextNormalizer.normalize(it.naming) == name && it.role == control.role }
            .map { it.bounds }
    }

    /**
     * Shows one local sign-in step.
     *
     * The target, when there is one, was found on the live screen a moment
     * ago and needs no grounding: nobody but the phone chose it.
     */
    private fun applyAuth(step: AuthStep) {
        // The same words on a new page are a new step; the same words on the
        // same page, re-read, are not.
        val shownWords = when (val current = state) {
            is AiSessionState.Guiding -> current.instruction.instruction
            is AiSessionState.AuthGuidance -> current.instruction.instruction
            is AiSessionState.LocalChoice -> current.question
            else -> null
        }
        if (shownWords != step.words || authStepShape != lastShape) beginStep()
        authStepShape = lastShape
        when (step) {
            is AuthStep.Point -> {
                val t = step.target
                val target = ValidatedTarget(t.bounds, t.naming, LOCAL_OBSERVATION, t.role, t.packageName, t.windowId)
                val instruction = RuntimeInstruction(
                    step.words, "", t.naming, if (step.typing) KIND_AUTH_TYPE else KIND_AUTH,
                    field = step.field,
                )
                val next = AiSessionState.Guiding(instruction, target)
                if (next == state) return
                lastInstruction = instruction
                transitionTo(next)
                host.showTarget(target, instruction)
                // Typing a private box: the send button stays in view too.
                host.keepInView(if (step.typing) auth.reachableRegions() else emptyList())
            }

            is AuthStep.Say -> {
                val instruction = RuntimeInstruction(step.words, "", "", KIND_AUTH, gesture = step.gesture)
                val current = state as? AiSessionState.AuthGuidance
                // The same advice again keeps whatever emphasis it had earned.
                val next = AiSessionState.AuthGuidance(
                    instruction, step.confirmLabel,
                    emphasizeDone = current?.takeIf { it.instruction == instruction }?.emphasizeDone ?: false,
                )
                if (next == state) return
                clearTarget()
                lastInstruction = instruction
                transitionTo(next)
                host.showMessage(state)
                // Words only on a sign-in screen — and the boxes and buttons
                // they talk about must stay reachable.
                host.keepClear(auth.reachableRegions())
            }

            is AuthStep.Stop -> {
                GuideLog.d("ai") { "sign-in help cannot go on here (app error=${step.appError})" }
                auth.end()
                stuck(if (step.appError) StallCause.AppError(step.words) else StallCause.Observed(step.words))
            }

            is AuthStep.Choose -> {
                // The same question on the same screen is the same card: a
                // window event re-reading the screen neither replaces it nor
                // asks it twice.
                val current = state as? AiSessionState.LocalChoice
                if (current != null && current.question == step.words && current.choices == step.choices) return
                val next = AiSessionState.LocalChoice(step.words, step.choices, ++questionSeq)
                clearTarget()
                transitionTo(next)
                host.showMessage(state)
                host.keepClear(auth.reachableRegions())
            }
        }
    }

    /**
     * Tells the next request that a sign-in step was done on screens it never
     * saw, so it checks rather than assumes.
     */
    private fun noteSignInStep() {
        lastInstruction = RuntimeInstruction(
            "The person finished a sign-in step on private screens TapBack does not describe.",
            "Check from this screen whether they are signed in or an error is shown.",
            "",
            KIND_AUTH,
        )
        forceNext = true
    }

    /** An instruction with nothing outlined. The session keeps watching. */
    private fun textOnly(instruction: RuntimeInstruction) {
        stepShown()
        clearTarget()
        lastInstruction = instruction
        checkpoint("guiding")
        transitionTo(AiSessionState.WaitingForUserAction(instruction, now()))
        host.showMessage(state)
    }

    /**
     * The screen could not be read: look again, on the phone, for a few
     * seconds, and whenever it settles. Nothing is sent meanwhile — a local
     * problem is not the planner's to solve — and no old target stays up.
     */
    private fun reacquire(result: ObserveResult) {
        lastUnreadable = result
        clearTarget()
        if (state != AiSessionState.Reacquiring) {
            GuideLog.d("ai") { "the screen could not be read; looking again (${result::class.simpleName})" }
            reacquireDeadline = now() + REACQUIRE_BUDGET_MS
            transitionTo(AiSessionState.Reacquiring)
            host.showMessage(state)
        }
        scheduleReacquire(REACQUIRE_FIRST_MS)
    }

    /**
     * The next local look, after [afterMs].
     *
     * @param oneLook a single look, from the "cannot read" card, that carries
     *   on if the screen is now readable and otherwise leaves the card be.
     */
    private fun scheduleReacquire(afterMs: Long, oneLook: Boolean = false) {
        reacquireJob?.cancel()
        reacquireJob = scope.launch {
            var wait = afterMs
            while (true) {
                if (wait > 0) delay(wait)
                val still = state == AiSessionState.Reacquiring || (oneLook && state is AiSessionState.CannotRead)
                if (stopped || !still) return@launch
                val look = host.observe(ObservePurpose.Grounding)
                if (stopped) return@launch
                when (look) {
                    is ObserveResult.Seen, is ObserveResult.Sensitive, ObserveResult.InDot -> {
                        GuideLog.d("ai") { "the screen can be read again; carrying on" }
                        transitionTo(AiSessionState.Observing)
                        host.showMessage(state)
                        requestPlan(force = true, why = "reacquired")
                        return@launch
                    }
                    ObserveResult.SystemDialog -> return@launch unreadable(Unreadable.SystemDialog)
                    is ObserveResult.Withheld -> return@launch withheld(look)
                    ObserveResult.NotAllowed -> return@launch unreadable(Unreadable.NotAllowed)
                    else -> lastUnreadable = look
                }
                if (oneLook) return@launch
                if (now() >= reacquireDeadline) break
                wait = (if (wait <= 0) REACQUIRE_FIRST_MS else wait * 2).coerceAtMost(REACQUIRE_MAX_STEP_MS)
            }
            // Still too little to go by. Where a picture of it may be sent —
            // nothing private, nothing to type into — one request with the
            // picture alone; otherwise say so, with something to do.
            val sparse = lastUnreadable as? ObserveResult.Sparse
            if (sparse?.pictureAllowed == true && PlanningTuning.pictureFallback && !auth.keepsUploadsOff) {
                GuideLog.d("ai") { "the description stayed nearly empty; one look with the picture alone" }
                pictureOnlyNext = true
                transitionTo(AiSessionState.Observing)
                requestPlan(force = true, why = "picture_only")
                return@launch
            }
            unreadable(Unreadable.Unclear)
        }
    }

    /** Said plainly, with the useful next thing to do. */
    private fun unreadable(why: Unreadable) {
        reacquireJob?.cancel()
        clearTarget()
        transitionTo(AiSessionState.CannotRead(why, cannotReadChecks))
        checkpoint("guiding")
        host.showMessage(state)
    }

    /**
     * A window in front that Android keeps from TapBack. Nothing old stays up
     * over it — no outline, no question about the page underneath — and the
     * card keeps off the window entirely. It is a wait for the person, not a
     * failure to read: no "Check again", no counting down. When the window
     * closes the next settled screen carries the task on.
     */
    private fun withheld(w: ObserveResult.Withheld) {
        reacquireJob?.cancel()
        clearTarget()
        pendingQuestion = null
        formQuestion = null
        doneQuestionId = null
        val why = if (w.permission) Unreadable.Permission else Unreadable.Protected
        GuideLog.d("ai") { "a window with its contents withheld is in front (${why.name}); waiting for it to close" }
        val next = AiSessionState.CannotRead(why, 0, w.title)
        if (state != next) {
            transitionTo(next)
            checkpoint("guiding")
            host.showMessage(state)
        }
        host.keepClear(listOf(w.bounds))
    }

    /** "Check again", from the "cannot read" card: a fresh round of looking. */
    fun checkAgain() {
        if (state !is AiSessionState.CannotRead) return
        cannotReadChecks++
        reacquire(ObserveResult.Nothing)
    }

    // -------------------------------------------------------------- forms

    /**
     * Whether the form [controlId] would move on is ready for it, and when it
     * is not, what is shown instead. True when something else was shown.
     *
     * Only what the phone can stand behind holds a form back: a box the app
     * itself flags, a birthday still showing the app's default, or the same
     * form, unchanged, after its Next was already shown. Anything else goes
     * ahead — an enabled Next is not proof, but neither is Dot's guess.
     */
    private suspend fun formHolds(controlId: String, from: Observation, startedAt: Long): Boolean {
        val control = from.candidate(controlId) ?: return false
        if (!FormFields.isAdvance(control)) return false
        val fields = from.candidates.filter { it.readiness != Readiness.Unknown }
        if (fields.isEmpty()) return false
        val evidence = (from.packageName + "|" + fields.joinToString("|") { "${it.fieldKey}=${it.label}/${it.readiness}" }).hashCode()
        val again = advanceShownOn == evidence && advanceTapped
        val errored = fields.firstOrNull { it.readiness == Readiness.Error }
        val birthday = fields.firstOrNull { FormFields.kindOf(it) == FieldKind.Birthday && it.readiness == Readiness.Prefilled }
        when {
            errored != null -> {
                GuideLog.d("ai") { "form: a box the app flags; putting it right before ${control.naming}" }
                remember(failedApproaches, "The app flags “${errored.naming}”; it needs putting right before “${control.naming}”", MAX_FAILED)
                val words = if (errored.error.isNotBlank()) {
                    "The app says: “${errored.error}”. Tap the ${errored.naming.ifBlank { "box" }} and put it right."
                } else {
                    "Check the ${errored.naming.ifBlank { "box" }} and put it right."
                }
                pointAt(from.observationId, errored.id, from, startedAt, RuntimeInstruction(words, "", "", KIND_SET_FIELD, field = FormFields.kindOf(errored)), mustBeEditable = errored.editable)
                return true
            }
            birthday != null -> {
                val answered = askedAboutField[birthday.fieldKey]
                if (answered == null) {
                    GuideLog.d("ai") { "form: the birthday shown may be the app's default; asking" }
                    askAboutBirthday(birthday)
                } else {
                    // They said it is not theirs, or it changed and still reads
                    // as a default: set it with the app's own picker.
                    GuideLog.d("ai") { "form: the birthday still shows a default; guiding to the picker" }
                    pointAt(
                        from.observationId, birthday.id, from, startedAt,
                        RuntimeInstruction(BIRTHDAY_SET, "", "", KIND_SET_FIELD, field = FieldKind.Birthday),
                        mustBeEditable = false,
                    )
                }
                return true
            }
            again -> {
                // Next was shown on this very form, and here it is again,
                // unchanged. Not shown a second time.
                remember(failedApproaches, "“${control.naming}” was tapped on this form and it did not move on; find what needs changing first", MAX_FAILED)
                if (from.notices.isNotEmpty()) {
                    GuideLog.d("ai") { "form: the same form after ${control.naming}, with a message; saying what it says" }
                    textOnly(RuntimeInstruction("The app says: “${from.notices.first()}”. Put that right, then tap “${control.naming}” again.", "", "", KIND_MANUAL))
                    host.keepClear(fields.map { it.drawBounds })
                } else if (rejectedRetries++ < 1) {
                    GuideLog.d("ai") { "form: the same form after ${control.naming}; asking for another way" }
                    reportedProblem = true
                    transitionTo(AiSessionState.Observing)
                    host.showMessage(state)
                    forceNext = true
                    requestPlan(force = true, why = "rejected")
                } else {
                    stuck(stallCause())
                }
                return true
            }
        }
        if (advanceShownOn != evidence) rejectedRetries = 0
        advanceShownOn = evidence
        advanceNaming = control.naming
        advanceTapped = false
        return false
    }

    /**
     * What helps while a box is being filled: the box itself with its label,
     * and the form's Next, so the next step is in view too. Nothing for any
     * other kind of step.
     */
    private fun helpfulFor(instruction: RuntimeInstruction, candidateId: String, live: Observation): List<com.aaravsekhri.dot.guide.model.Bounds> {
        val target = live.candidates.firstOrNull { it.id == candidateId }
        val aboutABox = instruction.kind == KIND_TYPE || instruction.kind == KIND_SET_FIELD || target?.readiness != null && target.readiness != Readiness.Unknown
        if (!aboutABox) return emptyList()
        val box = target?.let { listOfNotNull(it.bounds, it.outline) }.orEmpty()
        return box + live.candidates.filter(FormFields::isAdvance).map { it.bounds }.take(2)
    }

    /**
     * Whether a manual step's words hand over a whole process rather than one
     * action Dot cannot see: "finish signing up yourself", "complete the rest
     * on your own". From the planner's own words, never from the screen.
     */
    private fun handsOverTheWholeTask(words: String): Boolean = BROAD_HANDOFF.containsMatchIn(words)

    /**
     * A result that cannot be the address being looked for: its house number
     * is not the one searched for. Not pointed at; the planner is told why and
     * asked again, once per screen — never the first suggestion only because
     * it is first.
     */
    private fun resultContradictsSearch(controlId: String, from: Observation): Boolean {
        val wanted = taskDetails["search_text"] ?: taskDetails["destination"] ?: return false
        val control = from.candidate(controlId) ?: return false
        if (control.editable || FormFields.isAdvance(control)) return false
        if (!AddressMatch.contradicts("${control.naming} ${control.description}", wanted)) return false
        if (mismatchRefusedOn == from.contentKey) return false
        mismatchRefusedOn = from.contentKey
        GuideLog.d("ai") { "a result whose number does not match what is being searched for; not pointing at it" }
        remember(failedApproaches, "Pointed at a result that does not match the searched address; check the number, street and town, and ask if several could be it", MAX_FAILED)
        transitionTo(AiSessionState.Observing)
        host.showMessage(state)
        forceNext = true
        requestPlan(force = true, why = "result_mismatch")
        return true
    }

    /**
     * The planner gave a manual step on this same page right after the last
     * one was confirmed done. Only in a lesson the learner confirms: there,
     * whether it is done is the learner's to say, and asking the planner again
     * only asked for the same step.
     */
    private fun repeatsManualOnSamePage(from: Observation): Boolean {
        if (context.lesson?.learnerConfirms != true) return false
        val page = "${from.packageName}|${from.windowId}"
        val again = lastInstruction?.kind == KIND_MANUAL && lastManualPage == page
        lastManualPage = page
        return again
    }

    private fun askIfDone() {
        clearTarget()
        val id = ++questionSeq
        doneQuestionId = id
        pendingQuestion = DONE_QUESTION
        transitionTo(AiSessionState.AskingClarification(DONE_QUESTION, listOf(DONE_YES, DONE_NOT_YET), id))
        checkpoint("asking")
        host.showMessage(state)
    }

    /**
     * The steps of a date picker that are the phone's to help with, without
     * asking the planner: the picker's wheels one at a time and then its Set;
     * when it closes, whether the date now shown is right; after "Yes", the
     * form's Next; after "Change it", the box again.
     *
     * @return true when a step was shown here.
     */
    private suspend fun localFormStep(o: Observation, startedAt: Long): Boolean {
        val picker = DatePickerHelp.find(o)
        if (picker != null) {
            if (!pickerOpen) {
                pickerOpen = true
                pickerStep = 0
                GuideLog.d("ai") { "date picker: ${picker.wheels.size} wheel(s), set=${picker.set != null}" }
            }
            beginStep()
            if (pickerStep < picker.wheels.size) {
                val words = DatePickerHelp.wheelWords(picker.wheels[pickerStep], pickerStep, picker.wheels.size)
                clearTarget()
                lastInstruction = RuntimeInstruction(words, "", "", KIND_MANUAL)
                handoffShape = null
                transitionTo(AiSessionState.ManualHandoff(words, PICKER_REASON, confirmLabel = DatePickerHelp.WHEEL_DONE))
                host.showMessage(state)
                host.keepClear(listOf(picker.area))
            } else if (picker.set != null) {
                pointAt(
                    o.observationId, picker.set.id, o, startedAt,
                    RuntimeInstruction(LocalWording.tap(picker.set.naming), "The picker closes.", "", KIND_TAP),
                )
                host.keepClear(listOf(picker.area))
            } else {
                clearTarget()
                val words = "When the date is right, close the picker the way it offers."
                lastInstruction = RuntimeInstruction(words, "", "", KIND_MANUAL)
                transitionTo(AiSessionState.ManualHandoff(words, PICKER_REASON))
                host.showMessage(state)
                host.keepClear(listOf(picker.area))
            }
            return true
        }
        val birthday = o.candidates.firstOrNull { FormFields.kindOf(it) == FieldKind.Birthday && it.readiness != Readiness.Unknown }
        if (pickerOpen) {
            // Set or Cancel: the picker has closed. Whatever it shows now —
            // changed, unchanged, or cancelled — only the person can say
            // whether it is their birthday. An error from the app goes to the
            // ordinary path, which says what the app says.
            pickerOpen = false
            pickerStep = 0
            GuideLog.d("ai") { "date picker closed; birthday box ${if (birthday == null) "not found" else birthday.readiness.name}" }
            if (birthday != null && birthday.readiness != Readiness.Error) {
                askAboutBirthday(birthday)
                return true
            }
            return false
        }
        if (birthday == null) return false
        if (changeBirthday) {
            changeBirthday = false
            pointAt(
                o.observationId, birthday.id, o, startedAt,
                RuntimeInstruction(BIRTHDAY_OPEN, "The date picker opens.", "", KIND_SET_FIELD, field = FieldKind.Birthday),
                mustBeEditable = false,
            )
            return true
        }
        // Confirmed, and not changed since: the form's own Next, not the box
        // again. (A second "tap the birthday box" was the loop seen live.)
        if (birthday.readiness == Readiness.Confirmed && askedAboutField[birthday.fieldKey] == true) {
            val next = o.candidates.firstOrNull { FormFields.isAdvance(it) && it.enabled }
            if (next != null && birthdayAdvanceShownFor != birthday.fieldKey) {
                birthdayAdvanceShownFor = birthday.fieldKey
                GuideLog.d("ai") { "birthday confirmed; pointing at the form's ${next.role}" }
                pointAt(
                    o.observationId, next.id, o, startedAt,
                    RuntimeInstruction(LocalWording.tap(next.naming), "The next page opens.", "", KIND_TAP),
                )
                return true
            }
        }
        return false
    }

    /** Where the question now up was asked. */
    private var askedShape: ScreenShape? = null

    /** The birthday whose Next was pointed at after confirming it: pointed at once, then the planner judges. */
    private var birthdayAdvanceShownFor: String? = null

    /** A birthday that may be the app's own default: asked about on the card, once. */
    private fun askAboutBirthday(field: CandidateControl) {
        clearTarget()
        val id = ++questionSeq
        formQuestion = id to field.fieldKey
        pendingQuestion = BIRTHDAY_QUESTION
        transitionTo(AiSessionState.AskingClarification(BIRTHDAY_QUESTION, listOf(BIRTHDAY_YES, BIRTHDAY_NO), id))
        checkpoint("asking")
        host.showMessage(state)
        host.keepClear(listOf(field.drawBounds))
    }

    /**
     * Dot is in front: the guide has just started, or somebody came back to
     * Dot in the middle of it.
     *
     * Said locally, with no request: Dot's own screens are never described to
     * anybody, and there is nothing a model could add to "go to your Home
     * screen". The next screen that is not Dot's is observed as it is, not as
     * it was hoped to be.
     */
    private fun leaveDot() {
        // Mid-task, with an app to go back to: the way back to it.
        if (lastAppPackage != null) return returnFromDot()
        // Already said: kept up, with its demonstration, rather than taken
        // down and left down — the step has not changed.
        if (showingLeaveStep()) return redraw()
        beginStep()
        clearTarget()
        val navigation = host.device().navigation
        transitionTo(
            AiSessionState.WaitingForUserAction(
                RuntimeInstruction(
                    LocalWording.leaveDot(navigation), "The Home screen shows.", "", KIND_LEAVE_DOT,
                    gesture = Gesture.Home(navigation),
                ),
                now(),
            ),
        )
        host.showMessage(state)
        // Where the Home swipe starts, shown on the screen itself — the same
        // demonstration as leaving recent apps. Button navigation has none:
        // the card's drawing shows the button.
        demonstrate(host.planGesture(GesturePurpose.GoHome, null))
    }

    /**
     * Dot is in front in the middle of a task, and the task is in another
     * app: the way back to it, said and shown. Not hidden: a card that stepped
     * aside left somebody in Dot with no idea how to carry on. It sits at the
     * top, where Dot makes room for it, so nothing of Dot's is covered.
     */
    private fun returnFromDot() {
        if ((state as? AiSessionState.WaitingForUserAction)?.instruction?.kind == KIND_RETURN_TO_APP) return redraw()
        beginStep()
        clearTarget()
        val navigation = host.device().navigation
        val app = context.app?.takeIf { it.packageName == lastAppPackage }?.let { it.label.ifBlank { it.name } }
            ?: lastAppPackage?.let(host::appLabel).orEmpty()
        GuideLog.d("ai") { "Dot is in front mid-task; showing the way back to the app" }
        val back = RuntimeInstruction(
            LocalWording.returnFromDot(app, navigation),
            "${app.ifBlank { "The app" }} is in front again.",
            "",
            KIND_RETURN_TO_APP,
            gesture = Gesture.Recents(navigation),
        )
        // What was last said, so the next look judges this step — the way
        // back — and not the app's step from before, which nobody could do
        // from Dot and which would otherwise be recorded as not working.
        lastInstruction = back
        transitionTo(AiSessionState.WaitingForUserAction(back, now()))
        host.showMessage(state)
        demonstrate(host.planGesture(GesturePurpose.OpenRecents, null))
    }

    private fun showingLeaveStep() =
        (state as? AiSessionState.WaitingForUserAction)?.instruction?.kind.let { it == KIND_LEAVE_DOT || it == KIND_RETURN_TO_APP }

    /**
     * Dot's own screen came forward while this session runs.
     *
     * Told apart by what the session is doing, not by which package is in
     * front. Paused, finished, or waiting on the person — a question, a
     * choice, a private step — they came to Dot on purpose, and the card
     * steps aside until they go back. Asked to leave Dot, the card and its
     * demonstration stay up. In the middle of guidance, the way back to the
     * app is shown.
     */
    fun onDotInFront() {
        if (stopped) return
        // Anything in flight was about the app left behind. The step's own
        // demonstration is not: it is kept for the step that stays up.
        abandonInFlight()
        when (state) {
            is AiSessionState.WaitingForUserAction ->
                if (showingLeaveStep()) redraw() else leaveDot()
            is AiSessionState.Guiding,
            AiSessionState.Observing,
            is AiSessionState.Thinking,
            AiSessionState.HoldStill,
            AiSessionState.Reacquiring,
            is AiSessionState.WaitingForExternal,
            -> leaveDot()
            else -> {
                GuideLog.d("ai") { "Dot is in front while ${state::class.simpleName}; the card steps aside" }
                clearTarget()
                hiddenForDot = true
                host.hideGuidance()
            }
        }
    }

    /**
     * Puts the current step back on screen as it is: its card, its outline,
     * and its demonstration. For when something of Dot's own had taken them
     * down; changes nothing about the step.
     */
    fun redraw() {
        if (stopped) return
        when (val current = state) {
            is AiSessionState.Guiding -> host.showTarget(current.target, current.instruction)
            else -> host.showMessage(current)
        }
        demonstration?.let(host::showGesture)
    }

    /** Shows [plan] on the screen, and remembers it belongs to this step. */
    private fun demonstrate(plan: GesturePlan?) {
        plan ?: return
        demonstration = plan
        host.showGesture(plan)
    }

    /** The demonstration has done its work, or its step is over. */
    private fun stopDemonstration() {
        demonstration = null
        host.clearGesture()
    }

    private fun clearTarget(samePage: Boolean = false) {
        demonstration = null
        host.clearTarget(samePage)
    }

    // ------------------------------------------------------------ waiting

    /**
     * A bounded wait.
     *
     * The allowance is set once, when a wait episode begins, and only ever
     * counts down. The episode ends when any other decision arrives. Running
     * out is the person's decision point, not the end of the task: a long
     * install is a valid thing to wait for.
     */
    private fun waitFor(decision: AiDecision.Wait, from: Observation? = null) {
        clearTarget()
        from?.let { waitProgressKey = it.progressKey() }
        // Progress ticks that arrived while this answer was being fetched
        // queued another request behind it, which would fire the moment the
        // wait began and again after every answer — a request per tick. The
        // wait's own check compares against the page this answer was made
        // from, so anything that really changed meanwhile is still noticed.
        replanQueued = false
        pendingWhy = null
        localWaitSkips = 0
        waitReason = decision.reason
        val left = waitAllowance ?: decision.maxRechecks.coerceIn(1, MAX_WAIT_RECHECKS)
        if (left <= 0) {
            waitAllowance = 0
            transitionTo(AiSessionState.WaitExpired(decision.reason))
            host.showMessage(state)
            return
        }
        waitAllowance = left - 1
        transitionTo(AiSessionState.WaitingForExternal(decision.reason, left - 1, decision.recheckAfterMs))
        host.showMessage(state)
        scheduleRecheck(decision.recheckAfterMs)
    }

    private fun scheduleRecheck(afterMs: Long) {
        waitTimer?.cancel()
        waitTimer = scope.launch {
            delay(afterMs)
            if (stopped || state !is AiSessionState.WaitingForExternal) return@launch
            val key = waitProgressKey
            if (PlanningTuning.localWaitChecks && key != null && localWaitSkips < MAX_LOCAL_WAIT_SKIPS) {
                // Looked at on the phone first: still the same controls, just
                // a number moving, is still waiting — not worth a request.
                costs.checkedLocally("wait")
                val seen = host.observe(ObservePurpose.Grounding) as? ObserveResult.Seen
                if (stopped || state !is AiSessionState.WaitingForExternal) return@launch
                if (seen != null && seen.observation.progressKey() == key) {
                    localWaitSkips++
                    costs.avoided("wait_unchanged")
                    scheduleRecheck(afterMs)
                    return@launch
                }
                GuideLog.d("ai") { "the page changed while waiting; asking what now" }
                requestPlan(force = true, why = "wait_changed")
                return@launch
            }
            requestPlan(force = true, why = "wait_recheck")
        }
    }

    /** The controls on screen when the wait began, with numbers blurred. */
    private var waitProgressKey: String? = null
    private var localWaitSkips = 0

    /**
     * The allowance is only ever restored by progress a screen has shown.
     *
     * Not by a different decision in between: a model alternating "wait" with
     * something else would otherwise refill it forever.
     */
    private fun endWaitEpisode() {
        waitAllowance = null
        waitTimer?.cancel()
        waitTimer = null
    }

    // -------------------------------------------------------- local moves

    /**
     * The page moved under a drawn target: find it again, on the phone.
     *
     * No request. If the control is still on the page, unambiguously, it is
     * pointed at where it now is. If not, the model is asked what now.
     */
    private fun relocate() {
        val current = state as? AiSessionState.Guiding ?: return
        if (planning?.isActive == true) return
        relocating?.cancel()
        val startedAt = generation
        relocating = scope.launch {
            val live = host.observe(ObservePurpose.Grounding)
            if (stopped || generation != startedAt || state != current) return@launch
            val seen = live as? ObserveResult.Seen ?: return@launch requestPlan(why = "target_moved")
            when (val found = DecisionValidator.relocate(current.target, seen.observation)) {
                is DecisionValidator.Result.Ready -> {
                    transitionTo(AiSessionState.Guiding(current.instruction, found.target))
                    host.showTarget(found.target, current.instruction)
                }
                else -> {
                    GuideLog.d("ai") { "the target is not on the moved page ($found); asking again" }
                    requestPlan(why = "target_moved")
                }
            }
        }
    }

    // ----------------------------------------------------------- recording

    private fun record(meta: DecisionMeta) {
        if (meta.subgoal.isNotBlank()) subgoal = meta.subgoal
        // What the person settled — where to, what to search for — kept as
        // facts. A blank value takes one back. Never logged.
        meta.taskDetails.forEach { (key, value) -> if (value.isBlank()) taskDetails.remove(key) else taskDetails[key] = value }
        if (meta.practiceChoice.isNotBlank() && context.lesson?.practice?.exampleKey?.isNotBlank() == true) {
            host.rememberPracticeChoice(meta.practiceChoice)
        }
        when (meta.assessment) {
            Assessment.Succeeded -> {
                problemReports = 0
                endWaitEpisode()
                if (meta.progressNote.isNotBlank()) remember(progress, meta.progressNote, MAX_PROGRESS)
            }
            Assessment.Failed -> lastInstruction?.takeIf { it.kind != KIND_RESUMED }?.let {
                remember(failedApproaches, it.instruction, MAX_FAILED)
            }
            else -> Unit
        }
        checkpoint(checkpointStatus())
    }

    /**
     * The same step again, with nothing to show for the last time, is not
     * worth a third attempt. Legitimate repetition — "tap the plus" while the
     * text keeps growing — is judged a success each time and never counts.
     */
    private fun repeatsWithoutProgress(decision: AiDecision): Boolean {
        val key = stepKey(decision)
        if (key != null && key in stalledKeys && decision.meta.assessment != Assessment.Succeeded) {
            // The step that stalled, offered again: not shown. Back to the
            // choice, with one fewer round of trying left.
            GuideLog.d("ai") { "a step that already stalled was suggested again; not showing it" }
            lastStepKey = key
            stuck(stallCause())
            return true
        }
        if (key == null || decision.meta.assessment == Assessment.Succeeded || key != lastStepKey) {
            strikes = 0
            lastStepKey = key
            return false
        }
        strikes++
        if (strikes >= MAX_REPEATS) {
            strikes = 0
            lastStepKey = null
            lastInstruction?.let { remember(failedApproaches, "Repeated: ${it.instruction}", MAX_FAILED) }
            stalledKeys += key
            lastStepKey = key
            stuck(stallCause())
            return true
        }
        return false
    }

    private fun stepKey(decision: AiDecision): String? {
        val screen = decidedFrom
        fun named(id: String?) = id?.let { screen?.candidate(it)?.naming }.orEmpty()
        return when (decision) {
            is AiDecision.Highlight -> "tap:${named(decision.candidateId)}"
            is AiDecision.TypeText -> "type:${named(decision.candidateId)}"
            is AiDecision.Submit -> "submit:${named(decision.candidateId)}"
            is AiDecision.Scroll -> "scroll:${decision.reveal}:${decision.lookingFor}"
            is AiDecision.Navigate -> "nav:${decision.destination}"
            is AiDecision.Explain -> "explain:${decision.checkpointId}"
            else -> null
        }?.let { "${screen?.packageName}|$it" }
    }

    private fun stuck(cause: StallCause) {
        clearTarget()
        checkpoint("stuck")
        transitionTo(AiSessionState.Stuck(cause, canKeepTrying = keepTryingRounds < MAX_KEEP_TRYING))
        host.showMessage(state)
    }

    /**
     * What can honestly be said about why the task stopped moving.
     *
     * Only one cause is ever named without the screen saying it: the app the
     * task is about, whose icon was tapped on the Home screen, and which did
     * not open. Everything else is unknown, and said to be.
     */
    private fun stallCause(): StallCause {
        val app = context.app ?: return StallCause.Unknown
        val from = decidedFrom ?: return StallCause.Unknown
        val tapped = lastStepKey?.substringAfter("|tap:", "")?.takeIf { it.isNotBlank() } ?: return StallCause.Unknown
        val onHome = from.homeScreen
        val isTheApp = TextNormalizer.normalize(tapped).let {
            it == TextNormalizer.normalize(app.label) || it == TextNormalizer.normalize(app.name)
        }
        val opened = recentApps.contains(app.packageName)
        return if (onHome && isTheApp && !opened) StallCause.AppNotOpening(app.name) else StallCause.Unknown
    }

    // ---------------------------------------------------- watching progress

    /**
     * Looks at the field a typing step is about, on the phone, with nothing
     * sent. The requested words there move the step on by themselves; the
     * words gone again after moving on means they are being edited, and the
     * typing step comes back. A partial word, a pause, a closed keyboard:
     * none of those is the words being there, and none of them moves anything.
     */
    private fun checkTypedText() {
        val watch = typingWatch ?: return GuideLog.d("ai") { "typing check: nothing to watch for" }
        if (planning?.isActive == true) return GuideLog.d("ai") { "typing check: a request is out" }
        val startedAt = generation
        relocating?.cancel()
        relocating = scope.launch {
            val look = host.observe(ObservePurpose.Grounding)
            if (stopped || generation != startedAt) return@launch
            if (look is ObserveResult.Sensitive) {
                // A private page has arrived where the field was: its own
                // help, on the phone, straight away.
                GuideLog.d("ai") { "typing check: a private page is in front now" }
                return@launch startAfresh("private_screen")
            }
            val seen = look as? ObserveResult.Seen ?: return@launch
            val field = seen.observation.candidates.firstOrNull {
                it.editable && it.role == watch.target.role &&
                    TextNormalizer.normalize(it.naming) == TextNormalizer.normalize(watch.target.naming)
            } ?: run {
                GuideLog.d("ai") { "typing check: the field is not on this reading" }
                if (isSubmitting()) {
                    // The field has gone after the send step: the words were
                    // sent and a new page is up. That is the next thing to
                    // plan from.
                    typingWatch = null
                    requestPlan(force = true, why = "submitted")
                }
                return@launch
            }
            val typed = TypedText.matches(field.label, watch.instruction.expectedText)
            GuideLog.d("ai") { "typing check: requested words present=$typed" }
            when {
                typed && isTyping() -> {
                    GuideLog.d("ai") { "the requested words are in the field; moving on" }
                    lastInstruction = watch.instruction.copy(
                        expectedOutcome = "The field now shows the requested words; the next step is to send them.",
                    )
                    forceNext = true
                    requestPlan(force = true, why = "typed")
                }
                !typed && isSubmitting() -> {
                    // Edited after moving on: back to the typing step, on the
                    // field where it now is.
                    val found = DecisionValidator.relocate(watch.target, seen.observation) as? DecisionValidator.Result.Ready
                        ?: return@launch
                    lastInstruction = watch.instruction
                    transitionTo(AiSessionState.Guiding(watch.instruction, found.target))
                    host.showTarget(found.target, watch.instruction)
                }
            }
        }
    }

    /**
     * Asks the planner one thing about a private screen, without describing
     * it: whether the goal is already met by this app being open.
     *
     * The request names the app and says a private sign-in screen is in
     * front. No control, no word from the screen and no picture goes with it —
     * the same rule as everywhere else on a private screen. Only a "complete"
     * is acted on; anything else, including a failure, and the phone's own
     * sign-in help carries on as it would have.
     */
    private suspend fun goalDoneBeforeSignIn(screen: AuthScreen, startedAt: Long): Boolean {
        val stand = Observation(
            sessionId = sessionId,
            observationId = UUID.randomUUID().toString().take(24),
            takenAtMs = now(),
            screenGeneration = 0,
            packageName = screen.packageName,
            windowTitle = "",
            windowId = screen.windowId,
            screenWidth = screen.screenWidth,
            screenHeight = screen.screenHeight,
            candidates = emptyList(),
            scrollContainerIds = emptyList(),
            truncated = false,
            screenshotNote = PRIVATE_SIGN_IN_NOTE,
        )
        if (!ensureAdmitted(startedAt)) return false
        costs.asked("goal_check", image = false)
        val outcome = client.nextStep(
            PlanningRequest(
                goal = goal,
                observation = stand,
                answers = answers.toList(),
                progress = progress.toList(),
                subgoal = subgoal,
                failedApproaches = failedApproaches.toList(),
                recentApps = recentApps.toList(),
                lastInstruction = lastInstruction?.instruction.orEmpty(),
                lastExpectedOutcome = lastInstruction?.expectedOutcome.orEmpty(),
                lastKind = lastInstruction?.kind.orEmpty(),
                reportedProblem = false,
                device = host.device(),
                attempt = 0,
                trigger = "goal_check",
            ),
        )
        if (!current(startedAt)) return true
        val done = (outcome as? PlanningOutcome.Decided)?.decision as? AiDecision.Complete ?: return false
        GuideLog.d("ai") { "the goal is met with the app open at its sign-in; not signing in" }
        finish(done.summary, done.evidence)
        return true
    }

    /** A "done" that the screen does not bear out is not accepted — once. */
    private fun unsupportedCompletion(from: Observation): Boolean {
        val last = lastInstruction ?: return false
        val acted = last.kind in setOf(KIND_TAP, KIND_SUBMIT, KIND_TYPE)
        val key = "${last.instruction}|${from.contentKey}"
        if (!acted || from.contentKey != lastShownKey || completionChecked == key) return false
        completionChecked = key
        GuideLog.d("ai") { "a finish from the same screen the last step was given on; checking again" }
        remember(
            failedApproaches,
            "Said the goal was done while the screen had not changed since: ${last.instruction}",
            MAX_FAILED,
        )
        forceNext = true
        requestPlan(force = true, why = "recheck_done")
        return true
    }

    /** Held while the page moved; now checked against where it settled. */
    private fun resumeParked(decision: AiDecision, from: Observation) {
        if (stopped || !mayPlan(state)) return
        val now = host.screenShape()
        val askedOn = decidedShape
        if (now != null && askedOn != null && now.isNewPageAfter(askedOn)) {
            GuideLog.d("ai") { "a held answer was about a page that has gone; looking again" }
            return startAfresh("settled")
        }
        if (decision is AiDecision.Scroll) {
            // A suggestion to scroll, made before they scrolled: look again
            // rather than repeat advice the page may already have followed.
            transitionTo(AiSessionState.Observing)
            host.showMessage(state)
            requestPlan(force = true, why = "settled")
            return
        }
        val startedAt = ++generation
        occupy(scope.launch(start = CoroutineStart.LAZY) { apply(decision, from, startedAt) })
    }

    private fun noteAnswered(question: String) {
        answeredQuestion = question
        stepsSinceAnswer = 0
        host.offerChangeAnswer(question)
    }

    /** Each step shown after an answer; the offer to change it lasts a few. */
    private fun stepShown() {
        lastShownKey = decidedFrom?.contentKey
        if (answeredQuestion == null) return
        if (++stepsSinceAnswer > CHANGE_ANSWER_STEPS) {
            answeredQuestion = null
            host.offerChangeAnswer(null)
        }
    }

    // ------------------------------------------------------------ endings

    /**
     * A private screen Dot has no detail about. Never "carry on here
     * yourself": the phone's own help says what it honestly can — a question
     * about what is on the screen, bounded, then an honest pause with the
     * place saved.
     */
    private fun handOff(reason: SensitiveScreenPolicy.Reason) {
        applyAuth(auth.onPrivateWithoutDetail(reason))
    }

    /**
     * A request failed. What happens next depends on what kind of failure.
     *
     * Transient ones — a throttle, an overloaded provider, a dropped
     * connection — are tried again by themselves, on a bounded schedule: the
     * service's own delay when it gave one, otherwise a growing, jittered
     * wait, and never more than a few attempts or a minute altogether. Each
     * attempt looks at the screen afresh, so a task that has moved on is
     * planned from where it is now. When the allowance is spent, or the
     * failure is one waiting cannot fix, the person is told plainly, their
     * place is kept, and nothing asks again until they choose to.
     */
    private fun failed(kind: PlanningFailure, retryAfterMs: Long? = null, correlationId: String = "") {
        consecutiveFailures++
        GuideLog.d("ai") {
            "planning failed kind=$kind transient=${kind.transient} retryAfterMs=$retryAfterMs " +
                "cid=${correlationId.ifBlank { "-" }} attempt=$recoveryAttempts"
        }
        if (!kind.transient) {
            outage = kind
            recoveryAttempts = 0
            val setup = kind == PlanningFailure.NotAllowed || kind == PlanningFailure.NotConfigured
            val (explanation, suggestion) = failureWords(kind)
            // Trying again straight away cannot fix a spent allowance or a
            // missing set-up, so neither offers to keep trying: stopping,
            // with everything saved, is the way forward. A refused request is
            // about this screen: another screen may well work.
            fail(
                explanation, suggestion,
                canRetry = kind == PlanningFailure.ServiceProblem || kind == PlanningFailure.RequestRejected ||
                    kind == PlanningFailure.InternalError,
                setupProblem = setup, kind = kind,
            )
            return
        }

        val at = now()
        if (recoveryAttempts == 0) recoveryStartedAt = at
        // An unexplained limit, or an unknown failure, gets less patience
        // than one whose cause is known to pass.
        val allowance = if (kind == PlanningFailure.ProviderLimited || kind == PlanningFailure.Unknown) 1 else MAX_AUTO_RETRIES
        val wait = retryAfterMs?.coerceIn(MIN_RETRY_MS, AiClient.MAX_HONOURED_DELAY_MS) ?: backoff(recoveryAttempts)
        if (recoveryAttempts < allowance && at - recoveryStartedAt + wait <= RECOVERY_BUDGET_MS) {
            recoveryAttempts++
            replanQueued = false
            clearTarget()
            checkpoint("guiding")
            transitionTo(AiSessionState.Reconnecting(kind))
            host.showMessage(state)
            GuideLog.d("ai") { "trying again in ${wait}ms (attempt $recoveryAttempts of $allowance)" }
            recoveryTimer?.cancel()
            recoveryTimer = scope.launch {
                delay(wait)
                // Only if nothing has happened meanwhile: not paused, not
                // stopped, not answered some other way.
                if (!stopped && state is AiSessionState.Reconnecting) {
                    transitionTo(AiSessionState.Observing)
                    host.showMessage(state)
                    requestPlan(force = true, why = "retry_auto")
                }
            }
            return
        }

        recoveryAttempts = 0
        val (explanation, suggestion) = failureWords(kind)
        fail(explanation, suggestion, canRetry = true, kind = kind)
    }

    /** Two, four, eight seconds and so on, each spread by a quarter either way, never over twenty. */
    private fun backoff(attempt: Int): Long {
        val base = (BACKOFF_BASE_MS shl attempt.coerceIn(0, 4)).coerceAtMost(BACKOFF_MAX_MS)
        return (base * (0.75 + 0.5 * jitter())).toLong()
    }

    private fun fail(
        explanation: String,
        suggestion: String,
        canRetry: Boolean = true,
        setupProblem: Boolean = false,
        kind: PlanningFailure? = null,
    ) {
        // A request that queued up behind the failed one would fail the same
        // way a moment later — seen live under a rate limit, two refusals for
        // one step. From here only "Try again" or a new screen asks again.
        replanQueued = false
        clearTarget()
        checkpoint("failed")
        transitionTo(AiSessionState.RecoverableFailure(explanation, suggestion, canRetry, setupProblem, kind))
        host.showMessage(state)
    }

    private fun finish(summary: String, evidence: String = "") {
        GuideLog.d("cost") { "session finished: ${costs.summary()}" }
        context.taskId.takeIf(String::isNotBlank)?.let { admission?.finish(it, if (context.lesson != null) "lesson" else "ask", summary) }
        clearTarget()
        endWaitEpisode()
        if (evidence.isNotBlank()) remember(progress, evidence, MAX_PROGRESS)
        checkpoint(AiSessionStore.STATUS_COMPLETED)
        transitionTo(AiSessionState.Completed(summary))
        host.showMessage(state)
    }

    // ------------------------------------------------------------- helpers

    private fun current(startedAt: Long) = !stopped && startedAt == generation

    private fun isTyping() = (state as? AiSessionState.Guiding)?.instruction?.kind.let {
        it == KIND_TYPE || it == KIND_AUTH_TYPE || it == KIND_SET_FIELD
    }

    private fun isAuthTyping() = (state as? AiSessionState.Guiding)?.instruction?.kind == KIND_AUTH_TYPE

    /** Showing the send step that followed watched typing. */
    private fun isSubmitting(): Boolean {
        if (typingWatch == null) return false
        val instruction = when (val current = state) {
            is AiSessionState.Guiding -> current.instruction
            is AiSessionState.WaitingForUserAction -> current.instruction
            else -> return false
        }
        return instruction.kind == KIND_SUBMIT
    }

    private fun isShowingStep() =
        state is AiSessionState.Guiding || state is AiSessionState.WaitingForUserAction

    private fun transitionTo(next: AiSessionState) {
        if (next == state) return
        // The page a question was asked about: a different page arriving
        // unanswered makes the question stale.
        if (next is AiSessionState.AskingClarification && state !is AiSessionState.AskingClarification) askedShape = lastShape
        state = next
        host.onStateChanged(next)
    }

    /** The teaching card up now, or up when the person paused. */
    private fun teachingCard(): AiSessionState.Teaching? =
        state as? AiSessionState.Teaching ?: pausedTeaching?.takeIf { state == AiSessionState.Paused }

    private fun checkpointStatus() = when (state) {
        is AiSessionState.Paused -> "paused"
        is AiSessionState.AskingClarification -> "asking"
        else -> "guiding"
    }

    private fun checkpoint(status: String) {
        if (stopped) return
        // Once finished, always finished: nothing afterwards — an
        // interruption, a late write — may make it look unfinished.
        val effective = if (state is AiSessionState.Completed) AiSessionStore.STATUS_COMPLETED else status
        store.save(
            AiSessionStore.Checkpoint(
                sessionId = sessionId,
                goal = goal,
                answers = answers.toList(),
                progress = progress.toList(),
                subgoal = subgoal,
                failedApproaches = failedApproaches.toList(),
                lastInstruction = lastInstruction?.instruction.orEmpty(),
                lastExpectedOutcome = lastInstruction?.expectedOutcome.orEmpty(),
                lastKind = lastInstruction?.kind.orEmpty(),
                status = effective,
                updatedAtMs = now(),
                lessonId = context.lesson?.lessonId.orEmpty(),
                contentVersion = context.lesson?.contentVersion ?: 0,
                pendingQuestion = (state as? AiSessionState.AskingClarification)?.question.orEmpty(),
                pendingOptions = (state as? AiSessionState.AskingClarification)?.options.orEmpty(),
                pendingFreeText = (state as? AiSessionState.AskingClarification)?.freeText == true,
                pendingPersonal = (state as? AiSessionState.AskingClarification)?.personal == true,
                taskDetails = taskDetails.toMap(),
                teachStage = teachingCard()?.stage?.wire.orEmpty(),
                teachWords = teachingCard()?.words.orEmpty(),
                teachCheckpoint = teachingCard()?.checkpointId.orEmpty(),
                teachTarget = teachingCard()?.target?.naming.orEmpty(),
                teachTargetRole = teachingCard()?.target?.role.orEmpty(),
                explained = explained.toList(),
                resultShown = resultShown,
                taskId = context.taskId,
            ),
        )
    }

    private fun remember(list: MutableList<String>, item: String, max: Int) {
        val clean = item.trim()
        if (clean.isBlank()) return
        list.remove(clean)
        list += clean
        while (list.size > max) list.removeAt(0)
    }

    companion object {
        /** Planning failures in a row before retrying stops being offered. */
        const val MAX_FAILURES = 3

        /** The same step, without progress, this many times means it is not working. */
        const val MAX_REPEATS = 2

        /** "That didn't work" this many times on one step means the same. */
        const val MAX_PROBLEM_REPORTS = 2

        /** Controls named in a step's words that the card keeps clear of, at most. */
        const val MAX_MENTIONED = 4

        private val SECRET_WORDS = Regex(
            "(?i)\\b(password|passcode|pin|verification code|security code|one[- ]time code|otp|card number|cvv|cvc)\\b",
        )

        private val BROAD_HANDOFF = Regex(
            "(?i)\\b(finish|complete|carry on|continue|do)\\b[^.]{0,40}\\b(yourself|on your own|by yourself)\\b|\\bthe rest\\b[^.]{0,20}\\b(yourself|on your own)\\b",
        )

        private val NOT_SURE = Regex("(?i)not sure|don.?t know|no idea")
        /** Controls whose press is the person's own decision: left as words, never pointed at for them. */
        private val CONSEQUENTIAL = Regex("(?i)\\b(post|send|share|call|buy|pay|purchase|order|delete|remove|publish|agree|accept|install|confirm|subscribe)\\b")
        private val TAP_WORDS = Regex("(?i)^\\s*(now\\s+)?(tap|press|touch|select)\\b")
        const val NOT_SURE_ANSWER = "I'm not sure"

        const val PRIVATE_ANSWER_NOTE =
            "TapBack never asks for passwords or codes. Type that in the app yourself, then tap I'm done."

        /** "Keep trying" this many times, and then only stopping is offered. */
        const val MAX_KEEP_TRYING = 2

        /** Looking again at an unreadable screen: first after this, then doubling, for this long in all. */
        const val REACQUIRE_FIRST_MS = 600L
        const val REACQUIRE_MAX_STEP_MS = 2_400L
        const val REACQUIRE_BUDGET_MS = 8_000L

        /** "Check again" this many times before stopping becomes the recommendation. */
        const val MAX_CHECK_AGAIN = 2

        /** Wrong destinations put right on the phone before the planner is asked. */
        const val MAX_WRONG_TURNS = 2

        /** Sideways Home screen swipes suggested before app search is insisted on. */
        const val MAX_HOME_PAGE_SWIPES = 1

        /** Longer than the client's own connect and read timeouts together. */
        const val PLAN_WATCHDOG_MS = 45_000L

        const val REREAD_MS = 700L

        /**
         * What the planner is told in place of a private screen: that there
         * is one, and nothing about it.
         */
        const val PRIVATE_SIGN_IN_NOTE =
            "A private sign-in screen is in front. TapBack does not describe it; no controls are listed."

        const val CANNOT_CONNECT = "I can't connect to guidance right now. Your place is saved."

        /**
         * What to tell the person when guidance has stopped for [kind]: what
         * happened, in their terms, and what they can do. Truthful by kind —
         * a service that is down is not the person's Wi-Fi, and a spent
         * allowance is not something trying again will fix.
         */
        fun failureWords(kind: PlanningFailure): Pair<String, String> = when (kind) {
            PlanningFailure.Offline ->
                "Your phone seems to be offline. Your place is saved." to "Check your Wi-Fi or mobile data, then tap Try again."
            PlanningFailure.ServiceDown ->
                "TapBack's guidance service isn't answering right now. Your place is saved." to "Tap Try again in a minute."
            PlanningFailure.SlowAnswer ->
                "Guidance is taking much longer than usual. Your place is saved." to "Tap Try again in a minute."
            PlanningFailure.UnusableAnswer ->
                "I couldn't work out the next step." to "Tap Try again, or try saying what you want differently."
            PlanningFailure.LocalLimit ->
                "I've asked for a lot of help in a short time, so I'm pausing. Your place is saved." to "Tap Try again in a minute."
            PlanningFailure.Throttled, PlanningFailure.ProviderLimited, PlanningFailure.Capacity ->
                "The AI service is busy right now. Your place is saved." to "Tap Try again in a minute."
            PlanningFailure.QuotaExhausted ->
                "Guidance has reached its usage limit for now. Your place is saved." to ""
            PlanningFailure.RequestRejected ->
                "This screen couldn't be sent for guidance. Your place is saved." to "Try again, or go to another screen first."
            PlanningFailure.NotAllowed, PlanningFailure.NotConfigured ->
                "TapBack's guide isn't set up on this phone." to ""
            PlanningFailure.ServiceProblem ->
                "Guidance isn't working because of a problem on TapBack's side. Your place is saved." to "Tap Try again later."
            PlanningFailure.Unknown -> CANNOT_CONNECT to "Tap Try again in a minute."
            PlanningFailure.InternalError ->
                "Something went wrong inside TapBack. Your place is saved." to "Tap Try again. If it keeps happening, close and reopen TapBack."
            PlanningFailure.NotAdmitted -> notAdmittedWords("").first to notAdmittedWords("").second
            PlanningFailure.UsageLimit ->
                "This task has used a lot of help, so I've stopped it to be safe. Your place is saved." to
                    "Start it again from TapBack if you still need it."
        }

        /**
         * What to tell the person when a task could not even be started. Nothing
         * was guided yet, so "your place" is not the point: what they asked for is
         * still there (TapBack keeps it for Try again), and trying again is the
         * same task, never a second one. Truthful by kind, like [failureWords].
         */
        fun startFailureWords(kind: PlanningFailure): Pair<String, String> {
            val kept = "What you asked for is kept, and trying again won't use another guided task."
            return when (kind) {
                PlanningFailure.Offline ->
                    "Your phone seems to be offline. $kept" to "Check your Wi-Fi or mobile data, then tap Try again."
                PlanningFailure.ServiceDown ->
                    "TapBack couldn't reach its guidance service. $kept" to "Tap Try again in a minute."
                PlanningFailure.SlowAnswer ->
                    "TapBack's guidance service is taking much longer than usual to answer. $kept" to "Tap Try again in a minute."
                PlanningFailure.Throttled, PlanningFailure.ProviderLimited, PlanningFailure.Capacity, PlanningFailure.LocalLimit ->
                    "TapBack's guidance service is busy right now. $kept" to "Tap Try again in a minute."
                PlanningFailure.NotAllowed, PlanningFailure.NotConfigured ->
                    "TapBack's guide isn't set up on this phone." to ""
                PlanningFailure.ServiceProblem, PlanningFailure.InternalError, PlanningFailure.UnusableAnswer ->
                    "TapBack's guidance service had a problem starting this. $kept" to "Tap Try again in a minute."
                else -> "TapBack couldn't start that. $kept" to "Tap Try again in a minute."
            }
        }

        /** Why a task could not start, said plainly, with where to go. Never a paywall over someone else's app. */
        fun notAdmittedWords(reason: String): Pair<String, String> = when (reason) {
            "premium_course" -> "This course is part of TapBack Plus. Your progress is saved." to "Open TapBack to see your options."
            else -> "You've used your guided tasks for now. Your place is saved." to "Open TapBack to see when more arrive."
        }

        /** Automatic retries after a transient failure, and the time they may take altogether. */
        const val MAX_AUTO_RETRIES = 3
        const val RECOVERY_BUDGET_MS = 60_000L
        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_MAX_MS = 20_000L
        const val MIN_RETRY_MS = 1_000L

        /** Taps on Try again closer together than this are one tap. */
        const val RETRY_DEBOUNCE_MS = 2_000L

        /** A request taking longer than this says so, and offers another go. */
        const val SLOW_MS = 12_000L

        /** Scrolling for longer than this, while a reading is wanted, earns the steady message. */
        const val HOLD_AFTER_MS = 1_500L

        /** How many steps after an answer the card offers to change it. */
        const val CHANGE_ANSWER_STEPS = 3

        private val ACCOUNT_CHOICES = setOf(AuthChoice.HaveAccount, AuthChoice.NoAccount, AuthChoice.NotSureAccount)

        const val MAX_WAIT_RECHECKS = 10

        /**
         * Local looks during one wait before the planner is asked anyway —
         * about a minute at the usual interval — in case something that
         * matters changed where the controls do not show it.
         */
        const val MAX_LOCAL_WAIT_SKIPS = 30

        /** Reasons after which a picture is always sent. */
        private val PICTURE_TRIGGERS = setOf("did_not_work", "another_way", "retry", "retry_auto", "answer_changed")
        const val KEEP_WAITING_EXTRA = 3
        const val DEFAULT_RECHECK_MS = 3_000L

        const val MAX_PROGRESS = 10
        const val MAX_FAILED = 6
        const val MAX_RECENT_APPS = 6

        const val KIND_TAP = "tap"
        const val KIND_TYPE = "type_text"
        const val KIND_SUBMIT = "submit"
        const val KIND_SCROLL = "scroll"
        const val KIND_APP_LIST = "app_list"
        const val KIND_HOME = "home"
        const val KIND_BACK = "back"
        const val KIND_MANUAL = "manual"
        const val KIND_LEAVE_DOT = "leave_dot"

        /** Information on a teaching card: to read, never to tap. */
        const val KIND_LOOK = "look"

        /** Setting a form's box that is not typed into — a birthday's picker. */
        const val KIND_SET_FIELD = "set_field"

        /** A finish before the practice was done, refused at most this often. */
        const val MAX_PRACTICE_REFUSALS = 1

        /** Times a teaching point's information may not be found before it is left unsaid. */
        const val MAX_INFO_MISSES = 2

        const val DONE_QUESTION = "Is it how you'd like it now?"
        const val DONE_YES = "Yes, that's done"
        const val DONE_NOT_YET = "Not yet"

        /** How long after a tap, with a question up, the page is looked at again. */
        private const val ASK_RECHECK_MS = 1_200L

        const val BIRTHDAY_QUESTION = "Is the birthday shown correct?"
        const val BIRTHDAY_YES = "Yes, continue"
        const val BIRTHDAY_NO = "Change it"
        const val BIRTHDAY_OPEN = "Tap your birthday to change it."
        const val PICKER_REASON = "a wheel picker's position is the person's to set"
        const val BIRTHDAY_SET = "Tap your birthday and set your real one: the month, the day and the year."

        /** In Dot mid-task: the way back to the app, through recent apps. */
        const val KIND_RETURN_TO_APP = "return_to_app"

        /** An instruction from before a break, remembered as context only. */
        const val KIND_RESUMED = "resumed"

        /** Local sign-in help: pointing at a box or a button. */
        const val KIND_AUTH = "auth"

        /** Local sign-in help: typing into the box with the cursor. */
        const val KIND_AUTH_TYPE = "auth_type"

        /** Stands for the reading behind a local target, which is never sent anywhere. */
        const val LOCAL_OBSERVATION = "local"

        /** States in which a request may be started at all. */
        fun mayPlan(state: AiSessionState): Boolean = when (state) {
            AiSessionState.Paused,
            is AiSessionState.Completed,
            is AiSessionState.ManualHandoff,
            is AiSessionState.AskingClarification,
            is AiSessionState.WaitExpired,
            is AiSessionState.Stuck,
            is AiSessionState.LessonUnavailable,
            // The learner continues from it; nothing on screen may replace it.
            is AiSessionState.Teaching,
            // Its own looks decide when to carry on; nothing else may ask.
            is AiSessionState.CannotRead,
            // Its own timer decides when to look again; nothing else may.
            is AiSessionState.Reconnecting,
            -> false

            else -> true
        }
    }
}
