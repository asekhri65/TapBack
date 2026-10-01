package com.aaravsekhri.dot.guide.service

import com.aaravsekhri.dot.guide.model.GuideSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place the app and the accessibility service meet.
 *
 * The app cannot talk to the service directly — they are separate components
 * with separate lifecycles — so a request to start a guide is left here and
 * picked up when the service next connects. Holding only ids and state (never
 * a Context, an Activity, or any screen content) keeps this safe to have as a
 * process-wide singleton.
 */
object GuideCoordinator {

    private val _sessionState = MutableStateFlow<GuideSessionState>(GuideSessionState.Inactive)

    /** What the running guide is doing, observable from the app's UI. */
    val sessionState: StateFlow<GuideSessionState> = _sessionState.asStateFlow()

    /** A guide the app has asked for but the service has not yet begun. */
    @Volatile
    var requestedGuideId: String? = null
        private set

    /** Set by the service while it is bound, so the app can ask what is possible. */
    internal fun publish(state: GuideSessionState) {
        _sessionState.value = state
    }

    /** Called from the app when the person presses "Show me how". */
    fun requestGuide(guideId: String) {
        requestedGuideId = guideId
    }

    /**
     * A request in the person's own words, with no tutorial behind it.
     *
     * Kept separate from [requestedGuideId] because the two are genuinely
     * different things: one names a guide that ships with the app, the other
     * is a sentence somebody typed that nothing in the app has seen before.
     */
    @Volatile
    var requestedGoal: String? = null
        private set

    /**
     * True when the goal should carry on a saved session — its answers,
     * progress and failed approaches — rather than start afresh.
     */
    @Volatile
    var resumeRequested: Boolean = false
        private set

    /** The task Dot's service admitted for the requested goal; empty when resuming one that already has it. */
    @Volatile
    var requestedTaskId: String = ""
        private set

    fun requestGeneralHelp(goal: String, resume: Boolean = false, taskId: String = "") {
        requestedGoal = goal
        resumeRequested = resume
        requestedTaskId = taskId
    }

    /** A lesson to run, from Learn. Kept apart from a goal typed in Ask. */
    data class LessonRequest(val courseId: String, val lessonId: String, val restart: Restart = Restart.None)

    /** A lesson asked for again from the start: just the lesson, or the whole course from it. */
    enum class Restart { None, Lesson, Course }

    @Volatile
    var requestedLesson: LessonRequest? = null
        private set

    fun requestLesson(courseId: String, lessonId: String, restart: Restart = Restart.None) {
        requestedLesson = LessonRequest(courseId, lessonId, restart)
    }

    internal fun consumeLesson(): LessonRequest? {
        val lesson = requestedLesson
        requestedLesson = null
        return lesson
    }

    /** Which guide is running in the service right now, if any. */
    sealed interface ActiveGuide {
        data class Ask(val goal: String) : ActiveGuide
        data class Lesson(val courseId: String, val lessonId: String, val title: String) : ActiveGuide
    }

    private val _activeGuide = MutableStateFlow<ActiveGuide?>(null)

    /**
     * What is running, so starting something else can say what it would
     * replace — and keep both — rather than silently ending it.
     */
    val activeGuide: StateFlow<ActiveGuide?> = _activeGuide.asStateFlow()

    internal fun setActiveGuide(guide: ActiveGuide?) {
        _activeGuide.value = guide
    }

    /** Set when a lesson's card asks Dot to show that course. */
    private val _openCourse = MutableStateFlow<String?>(null)
    val openCourse: StateFlow<String?> = _openCourse.asStateFlow()

    internal fun showCourse(courseId: String?) {
        _openCourse.value = courseId
    }

    fun courseShown() {
        _openCourse.value = null
    }

    internal data class GoalRequest(val goal: String, val resume: Boolean, val taskId: String)

    internal fun consumeGoal(): GoalRequest? {
        val goal = requestedGoal ?: return null
        val request = GoalRequest(goal, resumeRequested, requestedTaskId)
        requestedGoal = null
        resumeRequested = false
        requestedTaskId = ""
        return request
    }

    private val _cardOverDot = MutableStateFlow(0)

    /**
     * How far down the screen, in pixels, a guide card kept over Dot's own
     * screen reaches; 0 when there is none. Dot moves its screen down by
     * that much, so the card covers none of Dot's own controls.
     */
    val cardOverDot: StateFlow<Int> = _cardOverDot.asStateFlow()

    internal fun setCardOverDot(bottomPx: Int) {
        _cardOverDot.value = bottomPx.coerceAtLeast(0)
    }

    private val _generalSessionActive = MutableStateFlow(false)

    /**
     * True while a general session is running in the service.
     *
     * So the app does not offer to "carry on where you left off" with a
     * session that has not been left.
     */
    val generalSessionActive: StateFlow<Boolean> = _generalSessionActive.asStateFlow()

    internal fun setGeneralSessionActive(active: Boolean) {
        _generalSessionActive.value = active
    }

    /**
     * A question the session needs answered before it can carry on.
     *
     * Answered in the app rather than on the overlay: an overlay text field
     * over somebody else's app is a bad place to type, and the Ask screen
     * already does this well.
     */
    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion.asStateFlow()

    /** A question, and the choices offered with it, if any. */
    data class PendingQuestion(val question: String, val options: List<String> = emptyList())

    internal fun askQuestion(question: String?, options: List<String> = emptyList()) {
        _pendingQuestion.value = question?.let { PendingQuestion(it, options) }
    }

    /** The person's answer, on its way back to the session. */
    @Volatile
    var pendingAnswer: String? = null
        private set

    fun answerQuestion(answer: String) {
        pendingAnswer = answer
        _pendingQuestion.value = null
    }

    internal fun consumeAnswer(): String? {
        val answer = pendingAnswer
        pendingAnswer = null
        return answer
    }

    /** Called by the service once it has taken the request on. */
    internal fun consumeRequest(): String? {
        val requested = requestedGuideId
        requestedGuideId = null
        return requested
    }

    /** Clears everything. Used when the person declines or exits. */
    fun clear() {
        requestedGuideId = null
        requestedGoal = null
        requestedLesson = null
        _activeGuide.value = null
        resumeRequested = false
        requestedTaskId = ""
        pendingAnswer = null
        _pendingQuestion.value = null
        _generalSessionActive.value = false
        _sessionState.value = GuideSessionState.Inactive
    }
}
