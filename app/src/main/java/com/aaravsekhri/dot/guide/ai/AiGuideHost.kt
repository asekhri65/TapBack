package com.aaravsekhri.dot.guide.ai

import android.content.Context
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.overlay.AiCardAction
import com.aaravsekhri.dot.guide.overlay.CardEmphasis
import com.aaravsekhri.dot.guide.overlay.ClearSource
import com.aaravsekhri.dot.guide.overlay.GuideOverlayController
import com.aaravsekhri.dot.guide.overlay.WaitingKind
import com.aaravsekhri.dot.guide.service.GuideCoordinator
import kotlinx.coroutines.delay

/**
 * What is in front, as the service sees it.
 *
 * Distinguished because each needs a different answer: Dot's own screen is
 * excluded from reading and gets a local instruction, a system dialog is left
 * alone, and a locked phone is not looked at.
 */
sealed interface Foreground {
    data class App(val screen: ScreenSnapshot) : Foreground
    data object Dot : Foreground
    data object SystemDialog : Foreground

    /**
     * A window in front whose contents Android keeps from TapBack — a
     * permission request on current Android, or a window an app protects —
     * known only by its title and where it is.
     */
    data class Withheld(val title: String, val bounds: com.aaravsekhri.dot.guide.model.Bounds, val permission: Boolean) : Foreground
    data object Unavailable : Foreground
}

/**
 * Everything a general session needs from the phone, in one place.
 *
 * Sits between the session controller — which is plain Kotlin and knows
 * nothing about Android — and the accessibility service, which knows about
 * little else. The service hands it a way to read the screen and a way to
 * draw; it decides what to read, what is too private to read, and which of
 * Dot's existing overlays says what.
 */
class AiGuideHost(
    private val context: Context,
    private val overlays: () -> GuideOverlayController?,
    private val foreground: () -> Foreground,
    private val keyboardVisible: () -> Boolean,
    private val isHomeScreen: (String) -> Boolean,
    private val dotOverlayRects: () -> List<Bounds>,
    private val screenshots: ScreenshotSource,
    private val onTarget: (ValidatedTarget, RuntimeInstruction) -> Unit,
    private val onClearTarget: (samePage: Boolean) -> Unit,
    /** Keeps the card off areas it must not hide. */
    private val onKeepClear: (List<Bounds>) -> Unit = {},
    private val sessionId: () -> String,
    /** The app's name as the phone shows it, for asking about an account. */
    private val appName: (String) -> String = { "" },
    private val screenGeneration: () -> Long,
    private val deviceContext: () -> DeviceContext,
    /** Told every change of state first, before the card for it is drawn. */
    private val onSessionState: (AiSessionState) -> Unit = {},
    /** The display less its bars, in screen pixels; null when not known. */
    private val viewport: () -> Bounds? = { null },
    /** The whole display, bars included, in screen pixels. */
    private val display: () -> Bounds? = { null },
    /** The session's current step; see [AiSessionController.stepId]. */
    private val step: () -> Long = { 0L },
    /** What is in front for its structure alone; see [mayReadStructure]. */
    private val structure: () -> Foreground = foreground,
    /** The learner's own practice example, kept for the course. */
    private val onPracticeChoice: (String) -> Unit = {},
    /** The keyboard's labelled action key, if it has one. */
    private val keyboardActionKey: () -> KeyboardAction? = { null },
) : AiSessionController.Host {

    /**
     * What this session has watched of forms' boxes: fingerprints only, in
     * memory, gone with the session.
     */
    val forms = FormMemory()

    /** In a lesson: readings list the page's information too, for teaching points. */
    var readInformation: Boolean = false

    /** True once the person has been told screen content goes to the service. */
    var uploadsAllowed: Boolean = false

    /**
     * Set while a lesson is running: what the finishing card offers next.
     * Null for a request typed in Ask.
     */
    var lessonTransition: LessonTransition? = null

    /**
     * A lesson's ending, from the course's actual records — saved before it
     * is shown — so nothing claims more than was done.
     *
     * @property learned what the learner can now say they did: "You found your first place!"
     * @property completed core lessons actually completed, of [total].
     * @property courseDone every core lesson completed — not merely none left to do.
     * @property courseName the app, as the course names it: "Google Maps".
     * @property reinforcement what the course taught, as something they can now do.
     */
    data class LessonTransition(
        val lessonTitle: String,
        val nextLessonTitle: String?,
        val learned: String = "",
        val completed: Int = 0,
        val total: Int = 0,
        val courseDone: Boolean = false,
        val courseName: String = "",
        val reinforcement: String = "",
    )

    override fun device(): DeviceContext = deviceContext()

    override suspend fun observe(
        purpose: AiSessionController.ObservePurpose,
    ): AiSessionController.ObserveResult {
        val screen = when (val front = foreground()) {
            is Foreground.App -> front.screen
            Foreground.Dot -> return AiSessionController.ObserveResult.InDot
            Foreground.SystemDialog -> return AiSessionController.ObserveResult.SystemDialog
            is Foreground.Withheld -> return AiSessionController.ObserveResult.Withheld(front.title, front.bounds, front.permission)
            Foreground.Unavailable -> return AiSessionController.ObserveResult.Nothing
        }
        // A private screen is judged first, and gets the phone's own help
        // whether or not anything may be sent: that help sends nothing, and
        // nothing uploadable has been gathered to decide it.
        SensitiveScreenPolicy.judge(screen).takeIf { it.sensitive }?.let {
            return private(screen, it.reason!!)
        }
        if (!uploadsAllowed) {
            GuideLog.d("observe") { "not looking: cloud guidance has not been agreed to" }
            return AiSessionController.ObserveResult.NotAllowed
        }
        // A handful of nodes is a window caught halfway through being rebuilt,
        // or an app that draws without describing itself. Nothing is concluded
        // from it on its own; the session looks again.
        if (screen.nodes.count { it.isVisibleToUser && !it.bounds.isEmpty } < MIN_USABLE_NODES) {
            val private = SensitiveScreenPolicy.judge(screen).sensitive
            val typeable = screen.nodes.any { it.isEditable }
            // A picture instead is only ever considered when nothing private
            // shows, nothing can be typed on it and no keyboard is up.
            val pictureAllowed = !private && !typeable && !keyboardVisible()
            GuideLog.d("observe") { "too little on screen to read (picture allowed: $pictureAllowed)" }
            if (purpose == AiSessionController.ObservePurpose.PlanningPictureOnly && pictureAllowed) {
                return pictureOnly(screen)
            }
            return AiSessionController.ObserveResult.Sparse(pictureAllowed)
        }

        // Sensitivity is judged before anything is gathered, not after. The
        // cheapest way not to send a password is never to put it in a request.
        // What comes back on a private screen is structure only, for local
        // sign-in help, and never leaves the phone.
        SensitiveScreenPolicy.judge(screen).takeIf { it.sensitive }?.let {
            return private(screen, it.reason!!)
        }

        // A reading to re-find a control is local and is never sent, so it
        // takes no picture: faster, and it leaves the platform's screenshot
        // allowance for the readings that go to the model.
        if (purpose == AiSessionController.ObservePurpose.Grounding) {
            return AiSessionController.ObserveResult.Seen(build(screen, null, ""))
        }

        repeat(CONSISTENCY_ATTEMPTS) { attempt ->
            val before = foreground() as? Foreground.App ?: return AiSessionController.ObserveResult.Nothing
            val beforeObservation = build(before.screen, null, "")

            // Words alone, when they are enough. Decided before any picture is
            // taken, so none is taken for nothing.
            if (purpose == AiSessionController.ObservePurpose.PlanningTextFirst &&
                ScreenshotPolicy.textSuffices(beforeObservation, before.screen)
            ) {
                GuideLog.d("observe") { "the description is enough; no picture" }
                return AiSessionController.ObserveResult.Seen(build(before.screen, null, "no picture: the controls list is complete"))
            }

            // A picture, if this phone can take one and this app permits it.
            // The platform refuses on windows an app has marked secure, and
            // that refusal is treated as the app saying its screen is private.
            var jpeg: ByteArray? = null
            var note = ""
            when (val shot = screenshots.capture(before.screen.windowId, dotOverlayRects())) {
                is ScreenshotSource.Result.Image -> {
                    jpeg = shot.jpeg
                    if (shot.masked) {
                        note = "grey boxes are TapBack's own instruction cards, masked out; " +
                            "an orange rounded outline, if any, is TapBack's pointer, not part of the app"
                    }
                }
                ScreenshotSource.Result.Refused ->
                    return private(before.screen, SensitiveScreenPolicy.Reason.ProtectedByTheApp)

                is ScreenshotSource.Result.Unavailable ->
                    GuideLog.d("observe") { "accessibility-only observation: ${shot.why}" }
            }

            // The tree is read again after the picture, and the two must
            // describe the same moment. The comparison includes where every
            // control is, not only what it says: a scroll keeps the same
            // words on the page in different places, and a picture of one
            // position sent with the positions of another describes nothing.
            val after = foreground() as? Foreground.App ?: return AiSessionController.ObserveResult.Nothing
            SensitiveScreenPolicy.judge(after.screen).takeIf { it.sensitive }?.let {
                return private(after.screen, it.reason!!)
            }
            val afterObservation = build(after.screen, null, "")
            val consistent = afterObservation.layoutKey == beforeObservation.layoutKey
            if (consistent || jpeg == null) {
                return AiSessionController.ObserveResult.Seen(build(after.screen, jpeg, note))
            }
            GuideLog.d("observe") { "the screen moved between the picture and the reading (attempt ${attempt + 1})" }
            if (attempt + 1 < CONSISTENCY_ATTEMPTS) delay(RESETTLE_MS)
        }

        // Still moving after a second look: send the words without a picture
        // rather than a picture that does not match them.
        val last = foreground() as? Foreground.App ?: return AiSessionController.ObserveResult.Nothing
        return AiSessionController.ObserveResult.Seen(
            build(last.screen, null, "no picture: the screen was still changing"),
        )
    }

    /**
     * The picture alone, for a screen that describes itself too little to
     * guide from. No controls are offered — there are none to name — so the
     * planner can describe but never point, and nothing is outlined.
     */
    private suspend fun pictureOnly(screen: ScreenSnapshot): AiSessionController.ObserveResult =
        when (val shot = screenshots.capture(screen.windowId, dotOverlayRects())) {
            is ScreenshotSource.Result.Image -> AiSessionController.ObserveResult.Seen(
                build(
                    screen, shot.jpeg,
                    "the screen's description is nearly empty; only the picture shows it. " +
                        "There are no control ids: describe what to do in words, never highlight",
                ),
            )
            ScreenshotSource.Result.Refused -> private(screen, SensitiveScreenPolicy.Reason.ProtectedByTheApp)
            is ScreenshotSource.Result.Unavailable -> AiSessionController.ObserveResult.Sparse(pictureAllowed = false)
        }

    private fun private(screen: ScreenSnapshot, reason: SensitiveScreenPolicy.Reason) =
        AiSessionController.ObserveResult.Sensitive(
            reason,
            AuthScreenReader.read(
                screen, reason, keyboardVisible(), context.resources.displayMetrics.density,
                appName(screen.packageName.orEmpty()),
            ),
        )

    private fun build(screen: ScreenSnapshot, jpeg: ByteArray?, note: String): Observation =
        ObservationBuilder.build(
            sessionId = sessionId(),
            screen = screen,
            screenGeneration = screenGeneration(),
            windowTitle = screen.rootClassName.orEmpty(),
            takenAtMs = System.currentTimeMillis(),
            screenshotJpeg = jpeg,
            keyboardVisible = keyboardVisible(),
            homeScreen = isHomeScreen(screen.packageName.orEmpty()),
            screenshotNote = note,
            pxPerDp = context.resources.displayMetrics.density,
            forms = forms,
            readable = readInformation,
        )

    /** Whether cards currently offer to change the last answer. */
    private var changeAnswer: Boolean = false

    override fun offerChangeAnswer(question: String?) {
        changeAnswer = question != null
    }

    private val changeLink get() = if (changeAnswer) AiCardAction.ChangeAnswer else null

    override fun showTarget(target: ValidatedTarget, instruction: RuntimeInstruction) {
        val confirm = needsTypingConfirmation(instruction)
        overlays()?.showRuntimeCard(
            label = context.getString(R.string.ai_helping),
            instruction = instruction.instruction,
            didNotWorkLabel = context.getString(R.string.ai_did_not_work),
            // Words Dot knows are watched for in the field, so the step needs
            // no button at all. Words only the person knows — a name, an
            // email, a password, a birthday — cannot be seen to be finished,
            // so the way on is the card's own button, saying exactly what
            // it confirms and drawn the way every recommended action in Dot
            // is: the outline round the app's box does not make it quiet.
            done = if (confirm) AiCardAction.DoneTyping else null,
            doneText = if (confirm) typingDoneLabel(instruction, target) else null,
            link = changeLink,
            emphasis = if (confirm) CardEmphasis.DoneButton else CardEmphasis.Target,
            step = step(),
        )
        onTarget(target, instruction)
    }

    override fun showMessage(state: AiSessionState) {
        val overlay = overlays() ?: run {
            // No overlay can be shown just now (Dot's own screen is in front,
            // or the service has no window): a question is put where it can
            // still be answered, in Dot, and the session waits for it.
            if (state is AiSessionState.AskingClarification) GuideCoordinator.askQuestion(state.question, state.options)
            return
        }
        val helping = context.getString(R.string.ai_helping)
        when (state) {
            // Asked over whatever app is in front, and answered there: a
            // choice as buttons, anything else typed on the card. Never by
            // sending the person back into Dot, which took the question away
            // from the screen it was about.
            is AiSessionState.AskingClarification ->
                if (state.options.size >= 2 && !state.freeText) {
                    overlay.showChoices(state.question, state.options, state.id)
                } else {
                    // Their own words, with any quick answers beside the box;
                    // a personal answer says where it goes and that the app
                    // itself is the other way to give it.
                    overlay.showTextQuestion(
                        state.question, state.id,
                        quickAnswers = state.options,
                        note = if (state.personal) context.getString(R.string.ai_answer_personal_note) else null,
                    )
                }

            is AiSessionState.WaitingForExternal -> overlay.showRuntimeCard(
                label = context.getString(R.string.ai_waiting),
                instruction = waitingWords(state.reason),
                didNotWorkLabel = null,
                waiting = WaitingKind.External,
            )

            // No countdown: the wait is the service's or a guess, and a number
            // that runs out and starts again would be a small lie.
            is AiSessionState.Reconnecting -> overlay.showRuntimeCard(
                label = helping,
                instruction = context.getString(reconnectingWords(state.why)),
                didNotWorkLabel = null,
                waiting = WaitingKind.External,
            )

            // Looking again, calmly: Pebble waits and the words say what is
            // happening. Pause is on the card, as always.
            AiSessionState.Reacquiring -> overlay.showRuntimeCard(
                label = helping,
                instruction = context.getString(R.string.ai_reacquiring),
                didNotWorkLabel = null,
                waiting = WaitingKind.Looking,
            )

            is AiSessionState.CannotRead -> when (state.why) {
                // A system question in front: it is the person's to answer,
                // and the card stays out of its way. It carries on by itself
                // once the dialog is gone.
                Unreadable.SystemDialog -> {
                    overlay.showRuntimeCard(
                        label = helping,
                        instruction = context.getString(R.string.ai_system_dialog),
                        didNotWorkLabel = null,
                        link = AiCardAction.CheckAgain,
                    )
                    overlay.keepCardAtTop()
                }
                // Android asking for a permission, in a window it keeps from
                // TapBack: what the choices generally mean, never which to
                // pick, and plainly that TapBack cannot point inside it. The
                // card keeps off the window; the task carries on by itself
                // once it closes.
                Unreadable.Permission -> overlay.showRuntimeCard(
                    label = helping,
                    instruction = permissionWords(state.title),
                    didNotWorkLabel = null,
                    waiting = WaitingKind.External,
                )
                Unreadable.Protected -> overlay.showRuntimeCard(
                    label = helping,
                    instruction = context.getString(R.string.ai_protected_window),
                    didNotWorkLabel = null,
                    waiting = WaitingKind.External,
                )
                Unreadable.NotAllowed -> overlay.showRuntimeCard(
                    label = helping,
                    instruction = context.getString(R.string.ai_not_allowed_to_look),
                    primary = AiCardAction.Exit,
                    reassuring = true,
                )
                Unreadable.Unclear -> actionsFor(state).let { a ->
                    overlay.showRuntimeCard(
                        label = helping,
                        instruction = context.getString(
                            if (state.checks >= AiSessionController.MAX_CHECK_AGAIN) {
                                R.string.ai_cannot_read_still
                            } else {
                                R.string.ai_cannot_read
                            },
                        ),
                        primary = a.primary,
                        secondary = a.secondary,
                        preferred = a.preferred,
                        reassuring = true,
                    )
                }
            }

            AiSessionState.HoldStill -> overlay.showRuntimeCard(
                label = helping,
                instruction = context.getString(R.string.ai_hold_still),
                didNotWorkLabel = null,
                waiting = WaitingKind.HoldStill,
            )

            is AiSessionState.WaitExpired -> overlay.showRuntimeCard(
                label = helping,
                instruction = context.getString(R.string.ai_still_waiting, state.reason.ensureSentence()),
                primary = AiCardAction.KeepWaiting,
                secondary = AiCardAction.Exit,
                reassuring = true,
            )

            // Stopping is the recommended way forward when nothing is moving:
            // everything is kept and nothing tries again by itself. Keeping on
            // is offered beside it, quietly, while there is something
            // different left to try.
            is AiSessionState.Stuck -> actionsFor(state, lessonTransition).let { a ->
                overlay.showRuntimeCard(
                    label = helping,
                    instruction = stallWords(state.cause),
                    primary = a.primary,
                    secondary = a.secondary,
                    preferred = a.preferred,
                    reassuring = true,
                )
            }

            is AiSessionState.LessonUnavailable -> actionsFor(state, lessonTransition).let { a ->
                overlay.showRuntimeCard(
                    label = helping,
                    instruction = context.getString(R.string.lesson_unavailable, state.explanation.ensureSentence()),
                    primary = a.primary,
                    secondary = a.secondary,
                    preferred = a.preferred,
                    reassuring = true,
                )
            }

            // A step card, not a status card: it can be moved or folded out of
            // the way of what is being done, and "I'm done" is highlighted
            // only once something has been done.
            is AiSessionState.ManualHandoff -> overlay.showRuntimeCard(
                label = helping,
                instruction = if (state.reason.startsWith("this ")) {
                    context.getString(R.string.ai_private_screen, state.instruction)
                } else {
                    state.instruction
                },
                done = AiCardAction.Continue,
                doneText = state.confirmLabel,
                emphasis = if (state.emphasizeDone) CardEmphasis.DoneButton else CardEmphasis.Card,
                freshPlacement = state.instruction,
                step = step(),
            )

            is AiSessionState.WaitingForUserAction -> {
                val leavingDot = state.instruction.kind == AiSessionController.KIND_LEAVE_DOT ||
                    state.instruction.kind == AiSessionController.KIND_RETURN_TO_APP
                overlay.showRuntimeCard(
                    label = helping,
                    instruction = state.instruction.instruction,
                    gesture = state.instruction.gesture,
                    // Nothing to have not worked yet, while Dot itself is in front.
                    didNotWorkLabel = if (leavingDot) null else context.getString(R.string.ai_did_not_work),
                    link = changeLink,
                )
                // Over Dot's own screen: the card keeps to the top, and Dot
                // moves its own screen down to make room, so none of Dot's
                // controls is covered and the swipe at the bottom is free.
                if (leavingDot) overlay.keepCardAtTop(overDot = true)
            }

            // A set-up problem cannot be waited out, so there is nothing to
            // recommend but closing. A spent allowance cannot be tried out of:
            // stopping, with everything kept, is the one way on. A failed
            // connection offers to keep trying beside it.
            is AiSessionState.RecoverableFailure -> actionsFor(state, lessonTransition).let { a ->
                overlay.showRuntimeCard(
                    label = helping,
                    instruction = (
                        context.getString(R.string.ai_blocked, state.explanation, state.suggestion).trim()
                        ),
                    primary = a.primary,
                    secondary = a.secondary,
                    preferred = a.preferred,
                    reassuring = true,
                )
            }

            is AiSessionState.Completed -> celebrate(overlay, state)

            is AiSessionState.Thinking -> overlay.showRuntimeCard(
                label = helping,
                instruction = context.getString(if (state.slow) R.string.ai_thinking_slow else R.string.ai_thinking),
                didNotWorkLabel = null,
                waiting = WaitingKind.Thinking,
                // Past the usual time: a way to ask again, beside Pause.
                link = if (state.slow) AiCardAction.Retry else null,
            )

            AiSessionState.Observing -> overlay.showRuntimeCard(
                label = helping,
                instruction = context.getString(R.string.ai_looking),
                didNotWorkLabel = null,
                waiting = WaitingKind.Looking,
            )

            // Paused is the one state where Dot has stopped reading the
            // screen, so it has to say so and offer the way back.
            AiSessionState.Paused -> overlay.showRuntimeCard(
                label = context.getString(R.string.guide_paused),
                instruction = context.getString(R.string.ai_paused_body),
                primary = AiCardAction.Resume,
                secondary = AiCardAction.Exit,
                reassuring = true,
            )

            is AiSessionState.AuthGuidance -> overlay.showRuntimeCard(
                label = context.getString(R.string.ai_sign_in_label),
                instruction = state.instruction.instruction,
                gesture = state.instruction.gesture,
                done = state.confirmLabel?.let { AiCardAction.Continue },
                doneText = state.confirmLabel,
                freshPlacement = state.instruction.instruction,
                link = changeLink,
                emphasis = if (state.emphasizeDone && state.confirmLabel != null) CardEmphasis.DoneButton else CardEmphasis.Card,
            )

            is AiSessionState.LocalChoice -> overlay.showChoices(
                question = state.question,
                options = state.choices.map { it.label },
                questionId = state.id,
            )

            is AiSessionState.Guiding -> Unit

            // A lesson's own card: short words and one way on, which the
            // learner chooses. Information it is about is outlined, to read.
            is AiSessionState.Teaching -> {
                overlay.showRuntimeCard(
                    label = context.getString(R.string.teach_label),
                    instruction = state.words,
                    didNotWorkLabel = null,
                    done = when (state.stage) {
                        TeachStage.Intro -> AiCardAction.Begin
                        TeachStage.Practice -> AiCardAction.ShowMeHow
                        TeachStage.Checkpoint, TeachStage.Result -> AiCardAction.Next
                    },
                    emphasis = CardEmphasis.DoneButton,
                    freshPlacement = "teach|${step()}",
                    step = step(),
                )
                state.target?.takeIf { !it.bounds.isEmpty }?.let { target ->
                    onTarget(target, RuntimeInstruction(state.words, "", target.naming, AiSessionController.KIND_LOOK))
                }
            }
        }
    }

    /** What a retry is actually waiting out, in the person's terms. */
    private fun reconnectingWords(why: PlanningFailure): Int = when (why) {
        PlanningFailure.Offline -> R.string.ai_reconnecting_offline
        PlanningFailure.ServiceDown -> R.string.ai_reconnecting_service
        PlanningFailure.SlowAnswer -> R.string.ai_reconnecting_slow
        PlanningFailure.LocalLimit -> R.string.ai_reconnecting_pacing
        PlanningFailure.Throttled, PlanningFailure.ProviderLimited, PlanningFailure.Capacity -> R.string.ai_reconnecting_busy
        PlanningFailure.UnusableAnswer -> R.string.ai_reconnecting_unusable
        else -> R.string.ai_reconnecting
    }

    /**
     * A completion, celebrated once: a task, a lesson, or a whole course.
     * Honest about what was done — a course is "completed" only when every
     * core lesson is, and otherwise wrapped up with what was.
     */
    private fun celebrate(overlay: GuideOverlayController, state: AiSessionState.Completed) {
        overlay.showCompletion(lessonTransition, state.summary, step())
    }

    override fun planGesture(
        purpose: GesturePurpose,
        from: Observation?,
        reveal: Reveal?,
        area: Bounds?,
    ): GesturePlan? {
        val unit = context.resources.displayMetrics.density
        return when (purpose) {
            GesturePurpose.OpenAppList -> {
                val screen = from ?: return null
                val bounds = viewport() ?: return null
                GesturePlanner.appList(
                    screen.packageName, GesturePlanner.viewOf(screen), bounds,
                    screen.candidates.map { it.bounds }, unit,
                )
            }
            GesturePurpose.ScrollContent -> {
                val direction = reveal ?: return null
                GesturePlanner.scroll(direction, area ?: viewport() ?: return null)
            }
            else -> display()?.let { GesturePlanner.system(purpose, device().navigation, it, unit) }
        }
    }

    /** The choices a permission window of this kind offers, in plain words. Never a recommendation. */
    private fun permissionWords(title: String): String = context.getString(
        when (PermissionPrompt.kindOf(title)) {
            PermissionPrompt.Kind.Location -> R.string.ai_permission_location
            else -> R.string.ai_permission_other
        },
    )

    override fun showGesture(plan: GesturePlan) {
        val overlay = overlays() ?: return
        overlay.showGestureDemo(plan)
        // The card stays off the path, with room for the finger that follows
        // it — alongside the step's target, never instead of it.
        val margin = (40 * context.resources.displayMetrics.density).toInt()
        overlay.setClearRegions(ClearSource.Gesture, listOf(plan.area(margin)))
    }

    override fun appLabel(packageName: String): String = appName(packageName)

    override fun keyboardAction(): KeyboardAction? = keyboardActionKey()

    override fun screenShape(): ScreenShape? = when (val front = structure()) {
        is Foreground.App -> ScreenShape.of(front.screen)
        Foreground.Dot -> ScreenShape.DOT
        // A page of its own, known by its title: so a question about the page
        // underneath is seen to be out of date when it comes up.
        is Foreground.Withheld -> ScreenShape("withheld", front.title.hashCode())
        Foreground.SystemDialog, Foreground.Unavailable -> {
            GuideLog.d("ai") { "no page shape: ${front::class.simpleName}" }
            null
        }
    }

    override fun confirmField(fieldKey: String) = forms.confirm(fieldKey)

    override fun rememberPracticeChoice(value: String) = onPracticeChoice(value)

    override fun clearGesture() {
        overlays()?.let {
            it.clearGestureDemo()
            it.setClearRegions(ClearSource.Gesture, emptyList())
        }
    }

    /** An empty list means this step names nothing to keep clear, and clears what the last one named. */
    override fun keepClear(regions: List<Bounds>) {
        onKeepClear(regions)
    }

    override fun keepInView(regions: List<Bounds>) {
        overlays()?.setClearRegions(ClearSource.Helpful, regions)
    }

    override fun hideGuidance() {
        overlays()?.hideAll()
    }

    override fun clearTarget(samePage: Boolean) {
        overlays()?.let {
            it.clearGestureDemo()
            it.setClearRegions(ClearSource.Gesture, emptyList())
        }
        onClearTarget(samePage)
    }

    override fun onStateChanged(state: AiSessionState) {
        GuideLog.d("ai") { "session is now ${state::class.simpleName}" }
        onSessionState(state)
        GuideCoordinator.setGeneralSessionActive(state !is AiSessionState.Completed)
    }

    /**
     * The button's words: what the box is for, from the app's own name for
     * it. Never anything typed into it.
     */
    private fun typingDoneLabel(instruction: RuntimeInstruction, target: ValidatedTarget): String =
        FormFields.doneTypingLabel(instruction.field ?: FormFields.kindOf(target.naming).takeIf { it != FieldKind.Unknown })

    /** Why the task stopped, saying only what the screen showed. */
    private fun stallWords(cause: StallCause): String = when (cause) {
        StallCause.Unknown -> context.getString(R.string.ai_stalled_unknown)
        is StallCause.AppNotOpening -> context.getString(R.string.ai_stalled_app, cause.appName)
        is StallCause.Observed -> context.getString(R.string.ai_stalled_observed, cause.description.ensureSentence())
        is StallCause.AppError -> context.getString(R.string.ai_stalled_app_error, cause.description.ensureSentence())
    }

    /**
     * A download said in Dot's own friendly words; anything else in the
     * planner's, which knows what is being waited for.
     */
    private fun waitingWords(reason: String): String {
        val r = reason.lowercase()
        // What is happening, in the planner's words, and what to do now.
        return if ("download" in r || "install" in r) {
            context.getString(R.string.ai_waiting_download)
        } else {
            context.getString(R.string.ai_waiting_app, reason.ensureSentence())
        }
    }

    private fun String.ensureSentence(): String =
        trim().let { if (it.isEmpty() || it.last() in ".!?") it else "$it." }

    /** A card's buttons: the primary, whether Dot recommends it, and a quieter second. */
    data class CardActions(val primary: AiCardAction?, val secondary: AiCardAction?, val preferred: Boolean)

    companion object {
        /**
         * Which buttons the cards that end or interrupt a task offer, and
         * which one Dot recommends. Pure, so the hierarchy is tested rather
         * than trusted: stopping with everything saved is recommended when a
         * task has stopped moving, "Keep trying" sits quietly beside it while
         * there is something different left to try, a set-up problem only
         * closes, and the next lesson is the way on from a finished one.
         */
        /**
         * Whether a step asks for something only the person knows — so its
         * finish cannot be seen — and needs the card's own "I'm done" button.
         */
        fun needsTypingConfirmation(instruction: RuntimeInstruction): Boolean = when (instruction.kind) {
            AiSessionController.KIND_TYPE -> instruction.expectedText.isBlank()
            AiSessionController.KIND_AUTH_TYPE, AiSessionController.KIND_SET_FIELD -> true
            else -> false
        }

        fun actionsFor(state: AiSessionState, lesson: LessonTransition? = null): CardActions = when (state) {
            is AiSessionState.Stuck -> CardActions(
                AiCardAction.TryLater,
                AiCardAction.KeepTrying.takeIf { state.canKeepTrying },
                preferred = true,
            )
            is AiSessionState.RecoverableFailure -> when {
                state.setupProblem -> CardActions(AiCardAction.Exit, null, preferred = false)
                else -> CardActions(AiCardAction.TryLater, AiCardAction.KeepTrying.takeIf { state.canRetry }, preferred = true)
            }
            is AiSessionState.LessonUnavailable -> CardActions(AiCardAction.OpenCourse, AiCardAction.Exit, preferred = true)
            // One screen that could not be read is not a reason to stop: look
            // again is the recommendation, with pausing beside it. Only after
            // looking again has not helped does stopping, with everything
            // saved, become the suggestion.
            is AiSessionState.CannotRead -> when {
                state.checks >= AiSessionController.MAX_CHECK_AGAIN ->
                    CardActions(AiCardAction.TryLater, AiCardAction.CheckAgain, preferred = true)
                else -> CardActions(AiCardAction.CheckAgain, AiCardAction.Pause, preferred = true)
            }
            is AiSessionState.Completed -> when {
                lesson == null -> CardActions(AiCardAction.Finish, null, preferred = true)
                lesson.courseDone -> CardActions(AiCardAction.Finish, AiCardAction.ReviewCourse, preferred = true)
                lesson.nextLessonTitle != null -> CardActions(AiCardAction.NextLesson, AiCardAction.FinishForNow, preferred = true)
                // Nothing left, not everything done: finish, or see the course.
                else -> CardActions(AiCardAction.Finish, AiCardAction.OpenCourse, preferred = true)
            }
            // A question's answers are the person's: nothing is recommended.
            is AiSessionState.AskingClarification, is AiSessionState.LocalChoice -> CardActions(null, null, preferred = false)
            else -> CardActions(null, null, preferred = false)
        }

        private const val CONSISTENCY_ATTEMPTS = 2
        private const val MIN_USABLE_NODES = 3
        private const val RESETTLE_MS = 400L
    }
}
