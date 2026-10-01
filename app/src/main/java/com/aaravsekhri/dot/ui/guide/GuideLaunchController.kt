package com.aaravsekhri.dot.ui.guide

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.aaravsekhri.dot.guide.ai.AiClient
import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.service.AccessibilityServiceStatus
import com.aaravsekhri.dot.guide.service.DotAccessibilityService
import com.aaravsekhri.dot.guide.service.GuideCoordinator
import com.aaravsekhri.dot.guide.service.GuidePreferences

/**
 * Drives the path from "Show me how" to a running guide.
 *
 * The order is fixed and cannot be skipped: a request is matched to a guide,
 * the disclosure is shown and accepted, and only then is Android's
 * Accessibility settings opened. Declining leaves the app working — it simply
 * cannot point at things outside itself.
 *
 * Holds the application context only, so it is safe to survive recomposition.
 */
class GuideLaunchController(context: Context) {

    private val appContext = context.applicationContext
    private val preferences = GuidePreferences(appContext)
    private val plus = com.aaravsekhri.dot.account.PlusRepository.get(appContext)
    private val scope = kotlinx.coroutines.MainScope()

    /** The id of the task being admitted, kept across a retry so it is admitted once. */
    private var admittingTaskId: String? = null

    /** The request to the service, so ending the wait ends the request and nothing it returns lands late. */
    private var admitting: kotlinx.coroutines.Job? = null

    /** A lesson waiting on Dot Plus, started once it is bought. */
    private var lessonAfterPlus: Pair<String, String>? = null

    /** Whether [courseId] needs Dot Plus that this person does not have, by the service's last word. */
    fun isLocked(courseId: String): Boolean {
        val allowance = plus.state.value.allowance ?: com.aaravsekhri.dot.account.Allowance.UNKNOWN
        return !allowance.plusActive && !allowance.isCourseFree(courseId)
    }

    /** "Dot Plus", asked for: from Settings, the invitation, a locked course. */
    fun showPlus(reason: String = "browse") {
        state = GuideLaunchState.Plus(reason)
    }

    /** The paywall closed. A lesson that was waiting on Plus starts if Plus is now there. */
    fun closePlus() {
        val waiting = lessonAfterPlus
        lessonAfterPlus = null
        state = GuideLaunchState.Idle
        if (waiting != null && !isLocked(waiting.first)) requestLesson(waiting.first, waiting.second)
    }

    /** "Try again" after the service could not be asked. */
    fun retryAdmission() {
        val goal = pendingGoal ?: return dismiss()
        admitAndStart(goal)
    }

    /**
     * Admits a new task on Dot's service, then starts it. The same task id is
     * sent again on a retry, so a slow answer or a double tap is one task.
     * With nothing left, Dot Plus is offered here, in Dot — before anything
     * starts, never partway through.
     */
    private fun admitAndStart(goal: String) {
        state = GuideLaunchState.Admitting
        val taskId = admittingTaskId ?: com.aaravsekhri.dot.account.AccountClient.newTaskId().also { admittingTaskId = it }
        // One request at a time: a second tap is the same request, not another.
        admitting?.cancel()
        admitting = scope.launch {
            when (val r = plus.accounts.admit(taskId, "ask")) {
                is com.aaravsekhri.dot.account.AccountResult.Ok -> {
                    plus.refreshAllowanceFrom(r.value.allowance)
                    if (r.value.admitted) {
                        admittingTaskId = null
                        pendingGoal = null
                        pendingResume = false
                        GuideCoordinator.requestGeneralHelp(goal, resume = false, taskId = taskId)
                        DotAccessibilityService.startRequestedGuide()
                        state = GuideLaunchState.Idle
                    } else {
                        state = GuideLaunchState.Plus(r.value.reason.ifBlank { "no_allowance" })
                    }
                }
                is com.aaravsekhri.dot.account.AccountResult.Failed -> state = GuideLaunchState.AdmissionFailed(r.kind)
            }
        }
    }

    var state: GuideLaunchState by mutableStateOf(GuideLaunchState.Idle)

    /** Which guide has already been asked about capture, so it is asked once. */
    private var offeredForGuide: String? = null
        private set

    /**
     * "Show me how" on a request the person typed.
     *
     * Every request goes to the general session. Matching against the
     * catalogue first would make the whole thing a lookup with a language
     * model bolted on: the point is that Dot can be asked for something nobody
     * wrote a tutorial for.
     *
     * The fixed tutorials remain reachable through [requestFixedGuide], which
     * is what the Learn tab and the regression runs use.
     */
    fun requestGuideFor(request: String) {
        val goal = request.trim()
        if (goal.isEmpty()) return
        if (!AiClient().configured) {
            state = GuideLaunchState.NoPlanningService
            return
        }
        // A lesson running in the background is not silently replaced.
        val running = GuideCoordinator.activeGuide.value
        if (running is GuideCoordinator.ActiveGuide.Lesson) {
            replacing = { startGoal(goal) }
            state = GuideLaunchState.ReplaceGuide(current = running.title, next = goal)
            return
        }
        startGoal(goal)
    }

    private fun startGoal(goal: String) {
        pendingGoal = goal
        pendingResume = false
        proceedGeneral()
    }

    /**
     * "Start lesson" / "Resume" / a lesson chosen from a course.
     *
     * Through the same consent and access as a request typed in Ask. If
     * another guide is running, the person is asked first; both keep their
     * saved places whichever they choose.
     */
    fun requestLesson(
        courseId: String,
        lessonId: String,
        restart: GuideCoordinator.Restart = GuideCoordinator.Restart.None,
    ) {
        val lesson = com.aaravsekhri.dot.courses.CourseCatalog.lesson(courseId, lessonId) ?: return
        if (lesson.guideId == null && !AiClient().configured) {
            state = GuideLaunchState.NoPlanningService
            return
        }
        val running = GuideCoordinator.activeGuide.value
        val same = running is GuideCoordinator.ActiveGuide.Lesson && running.lessonId == lessonId
        if (running != null && !same) {
            replacing = { startLesson(courseId, lessonId, restart) }
            state = GuideLaunchState.ReplaceGuide(
                current = when (running) {
                    is GuideCoordinator.ActiveGuide.Ask -> running.goal
                    is GuideCoordinator.ActiveGuide.Lesson -> running.title
                },
                next = lesson.title,
            )
            return
        }
        startLesson(courseId, lessonId, restart)
    }

    private fun startLesson(courseId: String, lessonId: String, restart: GuideCoordinator.Restart = GuideCoordinator.Restart.None) {
        // A course that needs Dot Plus: offered here, before anything starts.
        // Progress already made stays; only a new start waits. Dot's service
        // checks again for itself before any lesson is guided.
        if (isLocked(courseId)) {
            lessonAfterPlus = courseId to lessonId
            state = GuideLaunchState.Plus("premium_course")
            return
        }
        pendingLesson = "$LESSON_PENDING$courseId/$lessonId" +
            if (restart == GuideCoordinator.Restart.None) "" else "#${restart.name}"
        proceedLesson()
    }

    /** The choice to replace what is running: what to do if they say yes. */
    private var replacing: (() -> Unit)? = null

    /** "Start this instead": the running guide stops, saved; the new one starts. */
    fun confirmReplace() {
        val next = replacing ?: return
        replacing = null
        DotAccessibilityService.suspendGeneralSession()
        state = GuideLaunchState.Idle
        next()
    }

    /** "Keep going": nothing changes. */
    fun keepCurrent() {
        replacing = null
        state = GuideLaunchState.Idle
    }

    /**
     * The lesson waiting on consent or on accessibility access, as
     * "lesson:<course>/<lesson>". Held in the same place as a pending guide,
     * so it survives the trip to Accessibility settings.
     */
    private var pendingLesson: String?
        get() = preferences.pendingGuideId?.takeIf { it.startsWith(LESSON_PENDING) } ?: lessonInMemory
        set(value) {
            lessonInMemory = value
        }
    private var lessonInMemory: String? = null

    private fun proceedLesson() {
        val pending = pendingLesson ?: return
        val (courseId, lessonAndRestart) = pending.removePrefix(LESSON_PENDING).split('/', limit = 2).takeIf { it.size == 2 }
            ?: return
        val lessonId = lessonAndRestart.substringBefore('#')
        val restart = lessonAndRestart.substringAfter('#', "").let { name ->
            GuideCoordinator.Restart.entries.firstOrNull { it.name == name }
        } ?: GuideCoordinator.Restart.None
        // A lesson taught by an authored guide runs on the phone alone:
        // agreeing to cloud guidance is not needed for it, only access.
        val onPhoneOnly = com.aaravsekhri.dot.courses.CourseCatalog.lesson(courseId, lessonId)?.guideId != null
        state = when {
            onPhoneOnly && !preferences.disclosureAccepted -> {
                preferences.pendingGuideId = pending
                GuideLaunchState.Disclosure(pending)
            }
            !onPhoneOnly && !preferences.cloudGuidanceAccepted -> GuideLaunchState.CloudDisclosure(
                com.aaravsekhri.dot.courses.CourseCatalog.lesson(courseId, lessonId)?.title.orEmpty(),
            )
            !AccessibilityServiceStatus.isEnabled(appContext) -> {
                preferences.pendingGuideId = pending
                GuideLaunchState.NeedsService(pending)
            }
            else -> {
                pendingLesson = null
                if (preferences.pendingGuideId == pending) preferences.pendingGuideId = null
                GuideCoordinator.requestLesson(courseId, lessonId, restart)
                DotAccessibilityService.startRequestedGuide()
                GuideLaunchState.Idle
            }
        }
    }

    /** The old path, by guide id. Kept for the Learn tab and for regression. */
    fun requestFixedGuide(guideId: String) {
        val guide = GuideCatalog.byId(guideId)
        if (guide == null) {
            state = GuideLaunchState.Unsupported
            return
        }
        proceed(guide.id)
    }

    /**
     * The goal waiting on consent or on accessibility access.
     *
     * Persisted, not just held: the trip to Accessibility settings can outlast
     * this controller — a rotation recreates it, and on a low-memory phone the
     * process can go — and the person should not come back to find what they
     * typed has gone.
     */
    private var pendingGoal: String?
        get() = sessions.pendingGoal
        set(value) {
            sessions.pendingGoal = value
        }

    /** True when the pending goal should carry on a saved session. */
    private var pendingResume = false

    /** "Allow and continue" on the cloud disclosure. */
    fun acceptCloudDisclosure() {
        preferences.disclosureAccepted = true
        preferences.cloudGuidanceAccepted = true
        if (pendingLesson != null) proceedLesson() else proceedGeneral()
    }

    /** "Not now" on the cloud disclosure. The fixed tutorials still work. */
    fun declineCloudDisclosure() {
        pendingGoal = null
        pendingLesson = null
        state = GuideLaunchState.Limited("")
    }

    /** A question from the running session, surfaced where it can be answered. */
    fun showQuestion(question: String, options: List<String> = emptyList()) {
        if ((state as? GuideLaunchState.Question)?.question == question) return
        state = GuideLaunchState.Question(question, options)
    }

    /**
     * An unfinished request from a previous run, if there is one.
     *
     * Not while a session is running: that one has not been left, and
     * offering to "carry on" with it would start a second copy.
     */
    fun offerResumeIfAny() {
        if (state != GuideLaunchState.Idle) return
        if (GuideCoordinator.generalSessionActive.value) return
        val checkpoint = sessions.load() ?: return
        state = GuideLaunchState.ResumeOffer(checkpoint.goal)
    }

    /**
     * "Carry on" — with everything the person told Dot and everything that
     * was verified, from whatever is on screen now.
     */
    fun resumeSession() {
        val checkpoint = sessions.load() ?: return
        state = GuideLaunchState.Idle
        pendingGoal = checkpoint.goal
        pendingResume = true
        proceedGeneral()
    }

    /** "End guide" from inside the app: the running session ends, properly. */
    fun endGuide() {
        DotAccessibilityService.endGeneralSession()
        sessions.discard()
        GuideCoordinator.clear()
        state = GuideLaunchState.Idle
    }

    /** "Start again" — the checkpoint goes. */
    fun discardSession() {
        sessions.discard()
        state = GuideLaunchState.Idle
    }

    /**
     * The person answered the question Dot asked mid-session.
     *
     * @return true when the answer reached the running session, which is the
     *   caller's cue to step Dot aside so the person is back in the app they
     *   were using.
     */
    fun answerQuestion(answer: String): Boolean {
        val trimmed = answer.trim()
        if (trimmed.isEmpty()) return false
        GuideCoordinator.answerQuestion(trimmed)
        val delivered = DotAccessibilityService.deliverAnswer()
        state = GuideLaunchState.Idle
        return delivered
    }

    private val sessions = com.aaravsekhri.dot.guide.ai.AiSessionStore(appContext)

    private fun proceedGeneral() {
        val goal = pendingGoal ?: return
        state = when {
            !preferences.cloudGuidanceAccepted -> GuideLaunchState.CloudDisclosure(goal)
            !AccessibilityServiceStatus.isEnabled(appContext) -> {
                preferences.pendingGuideId = GENERAL_PENDING
                GuideLaunchState.NeedsService(GENERAL_PENDING)
            }

            // A saved task carries on with the task it was admitted as.
            pendingResume -> {
                pendingGoal = null
                pendingResume = false
                GuideCoordinator.requestGeneralHelp(goal, resume = true)
                DotAccessibilityService.startRequestedGuide()
                GuideLaunchState.Idle
            }

            else -> {
                admitAndStart(goal)
                GuideLaunchState.Admitting
            }
        }
    }

    /** "Allow and continue" on the disclosure. */
    fun acceptDisclosure() {
        val guideId = (state as? GuideLaunchState.Disclosure)?.guideId ?: return
        // The only thing stored is that this was accepted. Never screen content.
        preferences.disclosureAccepted = true
        if (guideId.startsWith(LESSON_PENDING)) {
            pendingLesson = guideId
            proceedLesson()
        } else {
            proceed(guideId)
        }
    }

    /** "Not now" on the disclosure. Dot keeps working, without pointing. */
    fun declineDisclosure() {
        val guideId = (state as? GuideLaunchState.Disclosure)?.guideId ?: return
        state = GuideLaunchState.Limited(guideId)
    }

    /** "Open Accessibility settings". The guide is remembered for the return trip. */
    fun openAccessibilitySettings() {
        val guideId = (state as? GuideLaunchState.NeedsService)?.guideId ?: return
        preferences.pendingGuideId = guideId

        if (!AccessibilityServiceStatus.openSettings(appContext)) {
            state = GuideLaunchState.Limited(guideId)
        }
    }

    /**
     * Re-checks on every return to the app.
     *
     * Coming back from system settings is the moment access may have changed,
     * and the state is never assumed from what was true when Dot left.
     */
    fun onReturnToApp() {
        val pending = preferences.pendingGuideId ?: return
        if (AccessibilityServiceStatus.isEnabled(appContext)) {
            state = GuideLaunchState.ReadyToResume(
                pending,
                goal = when {
                    pending == GENERAL_PENDING -> pendingGoal
                    pending.startsWith(LESSON_PENDING) -> pending.removePrefix(LESSON_PENDING).split('/', limit = 2)
                        .takeIf { it.size == 2 }
                        ?.let { (course, lesson) -> com.aaravsekhri.dot.courses.CourseCatalog.lesson(course, lesson)?.title }
                    else -> null
                },
            )
        }
    }

    /** "Continue" once access is on. */
    fun resumePendingGuide() {
        val guideId = (state as? GuideLaunchState.ReadyToResume)?.guideId ?: return
        preferences.pendingGuideId = null
        state = GuideLaunchState.Idle
        when {
            guideId == GENERAL_PENDING -> proceedGeneral()
            guideId.startsWith(LESSON_PENDING) -> {
                pendingLesson = guideId
                preferences.pendingGuideId = guideId
                proceedLesson()
            }
            else -> launch(guideId)
        }
    }

    /** Anything that means "put this away". */
    fun dismiss() {
        admitting?.cancel()
        admitting = null
        // Ended before the task started: if the service had already counted it,
        // it decides whether the credit comes back — it does when Dot never helped.
        admittingTaskId?.let { id -> scope.launch { plus.accounts.release(id) } }
        admittingTaskId = null
        lessonAfterPlus = null
        if (preferences.pendingGuideId == GENERAL_PENDING) pendingGoal = null
        pendingLesson = null
        replacing = null
        preferences.pendingGuideId = null
        state = GuideLaunchState.Idle
    }

    // -------------------------------------------------------------- internals

    private fun proceed(guideId: String) {
        state = when {
            !preferences.disclosureAccepted -> GuideLaunchState.Disclosure(guideId)
            !AccessibilityServiceStatus.isEnabled(appContext) ->
                GuideLaunchState.NeedsService(guideId)

            else -> {
                launch(guideId)
                GuideLaunchState.Idle
            }
        }
    }

    // The screen-capture offer has been removed from the guide flow. Manual
    // testing of the tracker it fed found flickering, outlines that vanished
    // after scrolling and occasional highlights on unrelated rows, so asking
    // someone to let Dot watch their screen would be asking for something Dot
    // could not repay. The disclosure screen and the tracker are kept as
    // research; nothing reaches them. A guide requests no capture, starts no
    // foreground service, and runs on accessibility alone.

    /**
     * Hands the guide to the service. If the service is enabled but not yet
     * bound, the request waits in [GuideCoordinator] and is picked up the
     * moment it connects.
     */
    private fun launch(guideId: String) {
        GuideCoordinator.requestGuide(guideId)
        if (!DotAccessibilityService.startRequestedGuide()) {
            // Not connected yet. The request stays queued; if access is off
            // entirely, send the person to turn it on.
            if (!AccessibilityServiceStatus.isEnabled(appContext)) {
                state = GuideLaunchState.NeedsService(guideId)
            }
        }
    }
}

/** Stands in for a guide id while a general request waits on access. */
private const val GENERAL_PENDING = "__general__"

/** Prefixes a lesson waiting on access: "lesson:<course>/<lesson>". */
private const val LESSON_PENDING = "lesson:"

@Composable
fun rememberGuideLaunchController(): GuideLaunchController {
    val context = LocalContext.current
    return remember(context) { GuideLaunchController(context) }
}
