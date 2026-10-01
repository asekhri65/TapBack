package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.AiDecision
import com.aaravsekhri.dot.guide.ai.AiSessionController
import com.aaravsekhri.dot.guide.ai.AiSessionController.Change
import com.aaravsekhri.dot.guide.ai.AiSessionController.ObserveResult
import com.aaravsekhri.dot.guide.ai.AiSessionState
import com.aaravsekhri.dot.guide.ai.AiSessionStore
import com.aaravsekhri.dot.guide.ai.Assessment
import com.aaravsekhri.dot.guide.ai.CandidateControl
import com.aaravsekhri.dot.guide.ai.DecisionMeta
import com.aaravsekhri.dot.guide.ai.Destination
import com.aaravsekhri.dot.guide.ai.DeviceContext
import com.aaravsekhri.dot.guide.ai.NavigationStyle
import com.aaravsekhri.dot.guide.ai.Observation
import com.aaravsekhri.dot.guide.ai.PlanningFailure
import com.aaravsekhri.dot.guide.ai.PlanningOutcome
import com.aaravsekhri.dot.guide.ai.PlanningRequest
import com.aaravsekhri.dot.guide.ai.PlanningService
import com.aaravsekhri.dot.guide.ai.QuestionAnswer
import com.aaravsekhri.dot.guide.ai.Reveal
import com.aaravsekhri.dot.guide.ai.RuntimeInstruction
import com.aaravsekhri.dot.guide.ai.SensitiveScreenPolicy
import com.aaravsekhri.dot.guide.ai.SessionCheckpoints
import com.aaravsekhri.dot.guide.ai.ValidatedTarget
import com.aaravsekhri.dot.guide.ai.AiGuideHost
import com.aaravsekhri.dot.guide.ai.InstallState
import com.aaravsekhri.dot.guide.ai.LessonContext
import com.aaravsekhri.dot.guide.ai.StallCause
import com.aaravsekhri.dot.guide.ai.TargetApp
import com.aaravsekhri.dot.guide.ai.TaskContext
import com.aaravsekhri.dot.guide.ai.Unreadable
import com.aaravsekhri.dot.guide.overlay.AiCardAction
import com.aaravsekhri.dot.guide.ai.progressKey
import com.aaravsekhri.dot.guide.model.Bounds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop: look, ask, point, watch, look again.
 *
 * Driven entirely without a device. The controller is plain Kotlin over an
 * interface, which is the reason it was kept out of the accessibility service:
 * the interesting failures here — an answer arriving late, a wrong turn, a
 * network that drops, a wait that never ends — are all about timing, and
 * timing is the one thing that cannot be tested by hand on a phone.
 *
 * Several of these were written against defects found by reading the
 * previous version, and fail on it: a stale answer that left the session
 * looking at nothing, a wait allowance that refilled itself, "That didn't
 * work" doing nothing, and a resume that forgot every answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSessionLoopTest {

    /** The cost measures are process-wide; every test starts from the shipped defaults. */
    @org.junit.Before
    fun shippedTuning() {
        com.aaravsekhri.dot.guide.ai.PlanningTuning.localWaitChecks = true
        com.aaravsekhri.dot.guide.ai.PlanningTuning.selectiveScreenshots = true
    }

    private fun control(
        id: String,
        label: String,
        top: Int = 100,
        editable: Boolean = false,
        checked: Boolean? = null,
        value: String = "",
    ) = CandidateControl(
        id = id,
        label = label,
        description = "",
        role = if (editable) "EditText" else "LinearLayout",
        bounds = Bounds(0, top, 1170, top + 200),
        clickable = true,
        editable = editable,
        enabled = true,
        scrollable = false,
        parentId = null,
        checked = checked,
        value = value,
    )

    private fun observation(
        id: String,
        controls: List<CandidateControl> = listOf(control("n1", "Display & touch")),
        generation: Long = 1L,
        packageName: String = "com.android.settings",
        homeScreen: Boolean = false,
    ) = Observation(
        homeScreen = homeScreen,
        sessionId = "s",
        observationId = id,
        takenAtMs = 0,
        screenGeneration = generation,
        packageName = packageName,
        windowTitle = "",
        windowId = 7,
        screenWidth = 1170,
        screenHeight = 2532,
        candidates = controls,
        scrollContainerIds = emptyList(),
        truncated = false,
    )

    /**
     * A host that records what it was told to show.
     *
     * Readings are handed out in order; once they run out, the last one keeps
     * being returned, as a screen that has stopped changing would be.
     */
    private class Recorder(vararg readings: ObserveResult) : AiSessionController.Host {
        val queue = ArrayDeque(readings.toList())
        private var last: ObserveResult = ObserveResult.Nothing
        val shown = mutableListOf<String>()
        val targets = mutableListOf<ValidatedTarget>()
        var observeCalls = 0
        var planningReads = 0
        var cleared = 0
        var samePageClears = 0
        var navigation = NavigationStyle.Gestures

        /** When set, the next reading never comes back — as a platform callback once did not. */
        var hangNext = false

        fun then(vararg more: ObserveResult) {
            queue.addAll(more)
        }

        val purposes = mutableListOf<AiSessionController.ObservePurpose>()
        val keptClear = mutableListOf<List<Bounds>>()
        override fun keepClear(regions: List<Bounds>) {
            keptClear += regions
        }

        val practiceChoices = mutableListOf<String>()
        override fun rememberPracticeChoice(value: String) {
            practiceChoices += value
        }

        /** What a picture-only look sees, when a test says. */
        var pictureOnlyReply: ObserveResult? = null

        override suspend fun observe(purpose: AiSessionController.ObservePurpose): ObserveResult {
            observeCalls++
            purposes += purpose
            if (purpose == AiSessionController.ObservePurpose.PlanningPictureOnly) pictureOnlyReply?.let { return it }
            if (hangNext) {
                hangNext = false
                kotlinx.coroutines.awaitCancellation()
            }
            if (purpose != AiSessionController.ObservePurpose.Grounding) planningReads++
            last = queue.removeFirstOrNull() ?: last
            return last
        }

        override fun showTarget(target: ValidatedTarget, instruction: RuntimeInstruction) {
            targets += target
            shown += "target:${instruction.instruction}"
        }

        override fun showMessage(state: AiSessionState) {
            shown += when (state) {
                is AiSessionState.WaitingForUserAction -> "words:${state.instruction.instruction}"
                else -> state::class.simpleName.orEmpty()
            }
        }

        override fun clearTarget(samePage: Boolean) {
            cleared++
            if (samePage) samePageClears++
        }

        override fun onStateChanged(state: AiSessionState) = Unit

        var hidden = 0
        override fun hideGuidance() {
            hidden++
        }

        override fun device() = DeviceContext(navigation = navigation, sdk = 35)

        val gestures = mutableListOf<com.aaravsekhri.dot.guide.ai.GesturePlan>()
        var gestureCleared = 0

        override fun planGesture(
            purpose: com.aaravsekhri.dot.guide.ai.GesturePurpose,
            from: Observation?,
            reveal: Reveal?,
            area: Bounds?,
        ): com.aaravsekhri.dot.guide.ai.GesturePlan? {
            val viewport = Bounds(0, 139, 1170, 2460)
            return when (purpose) {
                com.aaravsekhri.dot.guide.ai.GesturePurpose.OpenAppList -> from?.let {
                    com.aaravsekhri.dot.guide.ai.GesturePlanner.appList(
                        it.packageName, com.aaravsekhri.dot.guide.ai.GesturePlanner.viewOf(it), viewport, it.candidates.map { c -> c.bounds }, 3f,
                    )
                }
                com.aaravsekhri.dot.guide.ai.GesturePurpose.ScrollContent ->
                    reveal?.let { com.aaravsekhri.dot.guide.ai.GesturePlanner.scroll(it, area ?: viewport) }
                else -> com.aaravsekhri.dot.guide.ai.GesturePlanner.system(purpose, navigation, Bounds(0, 0, 1170, 2532), 3f)
            }
        }

        override fun showGesture(plan: com.aaravsekhri.dot.guide.ai.GesturePlan) {
            gestures += plan
        }

        override fun clearGesture() {
            gestureCleared++
        }

        /** The page's shape, as the service would read it; null means not known. */
        var shape: com.aaravsekhri.dot.guide.ai.ScreenShape? = null
        override fun screenShape() = shape

        val confirmedFields = mutableListOf<String>()
        override fun confirmField(fieldKey: String) {
            confirmedFields += fieldKey
        }

    }

    /** A planning service whose answers can be held back until released. */
    private class FakeClient(vararg outcomes: PlanningOutcome) : PlanningService {
        val outcomes = ArrayDeque(outcomes.toList())
        val requests = mutableListOf<PlanningRequest>()
        var gate: CompletableDeferred<Unit>? = null
        val calls get() = requests.size
        override val configured = true

        fun then(vararg more: PlanningOutcome) {
            outcomes.addAll(more)
        }

        override suspend fun nextStep(request: PlanningRequest): PlanningOutcome {
            requests += request
            gate?.await()
            return outcomes.removeFirstOrNull() ?: PlanningOutcome.Refused("nothing left to say")
        }
    }

    private class MemoryStore : SessionCheckpoints {
        var saved: AiSessionStore.Checkpoint? = null
        var discarded = false
        override fun save(checkpoint: AiSessionStore.Checkpoint) {
            saved = checkpoint
            discarded = false
        }

        override fun load() = saved?.takeIf { it.status != AiSessionStore.STATUS_COMPLETED }
        override fun discard() {
            saved = null
            discarded = true
        }
    }

    private fun decided(decision: AiDecision) = PlanningOutcome.Decided(decision, 120)

    private fun seen(observation: Observation) = ObserveResult.Seen(observation)

    private fun tap(on: String, id: String = "n1", meta: DecisionMeta = DecisionMeta()) =
        decided(AiDecision.Highlight(on, id, "Tap Display & touch.", "It opens.", meta))

    private fun kotlinx.coroutines.test.TestScope.session(
        host: Recorder,
        client: FakeClient,
        store: SessionCheckpoints = MemoryStore(),
        clock: () -> Long = { testScheduler.currentTime },
        admission: AiSessionController.TaskAdmission? = null,
    ) = AiSessionController(
        client = client,
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        store = store,
        host = host,
        now = clock,
        jitter = { 0.5 },
        admission = admission,
    )

    /** A ledger that admits (or refuses) and records what it was asked. */
    private class FakeAdmission(var answer: AiSessionController.AdmitOutcome = AiSessionController.AdmitOutcome.Admitted) :
        AiSessionController.TaskAdmission {
        val admitted = mutableListOf<Pair<String, String>>()
        val released = mutableListOf<String>()
        val finished = mutableListOf<String>()
        override suspend fun admit(taskId: String, kind: String, courseId: String): AiSessionController.AdmitOutcome {
            admitted += taskId to kind
            return answer
        }
        override fun release(taskId: String) { released += taskId }
        override fun finish(taskId: String, kind: String, summary: String) { finished += taskId }
    }

    // ------------------------------------------------------------ admission

    @Test
    fun `a task is admitted once, before its first request, and every request carries it`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"), tap("o2"))
        val admission = FakeAdmission()
        val session = session(host, client, admission = admission)
        session.start("make the text bigger")
        session.onUserActed()
        runCurrent()
        assertEquals(1, admission.admitted.size)
        val taskId = admission.admitted.single().first
        assertTrue(client.requests.all { it.context.taskId == taskId })
    }

    @Test
    fun `resuming a saved task never admits it again`() = runTest {
        val store = MemoryStore()
        val admission = FakeAdmission()
        val first = session(Recorder(seen(observation("o1"))), FakeClient(tap("o1")), store, admission = admission)
        first.start("make the text bigger")
        first.stop(keepCheckpoint = true)
        val saved = store.saved!!
        assertTrue(saved.taskId.isNotBlank())

        val again = FakeAdmission()
        val resumed = session(Recorder(seen(observation("o2"))), FakeClient(tap("o2")), MemoryStore(), admission = again)
        resumed.resume(saved)
        runCurrent()
        assertTrue("no second admission", again.admitted.isEmpty())
    }

    @Test
    fun `a task that is not admitted asks nothing and says so calmly`() = runTest {
        val client = FakeClient(tap("o1"))
        val admission = FakeAdmission(AiSessionController.AdmitOutcome.Refused("premium_course"))
        val session = session(Recorder(seen(observation("o1"))), client, admission = admission)
        session.start("a lesson")
        val failure = session.state as AiSessionState.RecoverableFailure
        assertEquals(0, client.calls)
        assertEquals(com.aaravsekhri.dot.guide.ai.PlanningFailure.NotAdmitted, failure.kind)
        assertTrue(failure.explanation.contains("TapBack Plus"))
    }

    @Test
    fun `a session that ends unfinished asks for its credit back, and a finished one is closed`() = runTest {
        val admission = FakeAdmission()
        val stopped = session(Recorder(seen(observation("o1"))), FakeClient(tap("o1")), admission = admission)
        stopped.start("something")
        stopped.stop()
        assertEquals(admission.admitted.single().first, admission.released.single())

        val done = FakeAdmission()
        val finished = session(
            Recorder(seen(observation("o1"))),
            FakeClient(decided(AiDecision.Complete("o1", "Your text is bigger.", "Font size shows 5 of 7"))),
            admission = done,
        )
        finished.start("make the text bigger")
        finished.stop(keepCheckpoint = true)
        assertEquals(1, done.finished.size)
        assertTrue("a finished task is never released", done.released.isEmpty())
    }

    @Test
    fun `the service being unreachable during admission is treated like any failed request`() = runTest {
        val admission = FakeAdmission(AiSessionController.AdmitOutcome.Failed(com.aaravsekhri.dot.guide.ai.PlanningFailure.ServiceDown))
        val session = session(Recorder(seen(observation("o1"))), FakeClient(tap("o1")), admission = admission)
        session.start("something")
        assertTrue(session.state is AiSessionState.Reconnecting)
    }

    // ------------------------------------------------------------ pointing

    @Test
    fun `a grounded answer becomes something on screen`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val session = session(host, FakeClient(tap("o1")))

        session.start("make the text bigger")

        assertTrue(session.state is AiSessionState.Guiding)
        assertEquals("Display & touch", host.targets.single().naming)
    }

    @Test
    fun `a stale answer is replaced by a fresh one rather than leaving the session stuck`() = runTest {
        // The answer is checked against a reading from after the page moved,
        // so it is stale. The old loop asked for a new plan from inside the
        // running one, which silently did nothing, and the session sat in
        // Observing for ever.
        val host = Recorder(
            seen(observation("o1", generation = 1)),
            seen(observation("o2", generation = 9)),
            seen(observation("o3", generation = 9)),
            seen(observation("o4", generation = 9)),
        )
        val client = FakeClient(tap("o1"), tap("o3"))
        val session = session(host, client)

        session.start("make the text bigger")

        assertEquals("a second request was made", 2, client.calls)
        assertTrue("and the session is pointing again: ${session.state}", session.state is AiSessionState.Guiding)
        assertEquals("never the stale target", 1, host.targets.size)
    }

    @Test
    fun `an answer that arrives after the screen changed is thrown away`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"))
        client.gate = CompletableDeferred()
        val session = session(host, client)

        session.start("make the text bigger")
        assertTrue(session.state is AiSessionState.Thinking)

        // The person navigates while the answer is on its way.
        session.onScreenChanged()
        client.gate!!.complete(Unit)
        runCurrent()

        assertTrue("the late answer drew nothing", host.targets.isEmpty())
        assertFalse(
            "and a cancelled request is not reported as a network failure",
            session.state is AiSessionState.RecoverableFailure,
        )
    }

    @Test
    fun `a control that cannot be found becomes words, and the session keeps watching`() = runTest {
        val host = Recorder(
            seen(observation("o1")),
            seen(observation("o2", controls = listOf(control("n5", "Something else")))),
        )
        val session = session(host, FakeClient(tap("o1")))

        session.start("something")

        val state = session.state
        assertTrue("a text-only step, not a handoff: $state", state is AiSessionState.WaitingForUserAction)
        assertTrue("nothing is outlined", host.targets.isEmpty())
        assertTrue("and Dot may still look", AiSessionController.mayPlan(state))
    }

    @Test
    fun `typing goes into a field that is still a field`() = runTest {
        val field = control("f1", "", editable = true).copy(hint = "Search settings")
        val host = Recorder(
            seen(observation("o1", controls = listOf(field))),
            seen(observation("o2", controls = listOf(field))),
        )
        val client = FakeClient(
            decided(AiDecision.TypeText("o1", "f1", "Tap the search box.", "brightness", "Results appear.")),
        )
        val session = session(host, client)

        session.start("make my screen darker")

        val state = session.state as AiSessionState.Guiding
        assertEquals("Search settings", state.target.naming)
        assertEquals(AiSessionController.KIND_TYPE, state.instruction.kind)
        assertTrue("the words to type are on the card", state.instruction.instruction.contains("“brightness”"))
    }

    // ------------------------------------------------------ what is sent

    @Test
    fun `a private screen with nothing to point at is never described, and never handed back wholesale`() = runTest {
        val host = Recorder(ObserveResult.Sensitive(SensitiveScreenPolicy.Reason.PasswordField))
        val client = FakeClient()
        val session = session(host, client)

        session.start("sign me in")

        val asking = session.state as AiSessionState.LocalChoice
        assertEquals("TapBack can't see this part of the screen. What do you see?", asking.question)
        assertFalse(host.shown.any { it.contains("yourself") })
        assertEquals("nothing was asked about it", 0, client.calls)
    }

    @Test
    fun `an error the person sees on a private screen is an honest pause, blaming nothing Dot did not see`() = runTest {
        val host = Recorder(ObserveResult.Sensitive(SensitiveScreenPolicy.Reason.ProtectedByTheApp))
        val session = session(host, FakeClient())
        session.start("make a Facebook account")
        val asking = session.state as AiSessionState.LocalChoice
        session.chooseAt(asking.choices.indexOf(com.aaravsekhri.dot.guide.ai.AuthChoice.SeeError), asking.id)
        val stuck = session.state as AiSessionState.Stuck
        assertEquals(StallCause.AppError("The app is showing a message instead of going on."), stuck.cause)
        val actions = AiGuideHost.actionsFor(stuck)
        assertEquals(com.aaravsekhri.dot.guide.overlay.AiCardAction.TryLater, actions.primary)
        assertTrue(actions.preferred)
    }

    @Test
    fun `asking what they see is bounded, and ends in an honest pause rather than abandonment`() = runTest {
        val host = Recorder(ObserveResult.Sensitive(SensitiveScreenPolicy.Reason.ProtectedByTheApp))
        val session = session(host, FakeClient())
        session.start("check my bank balance")
        repeat(3) {
            val asking = session.state as? AiSessionState.LocalChoice ?: return@repeat
            session.chooseAt(asking.choices.indexOf(com.aaravsekhri.dot.guide.ai.AuthChoice.SeeSomethingElse), asking.id)
        }
        val stuck = session.state as AiSessionState.Stuck
        assertTrue(stuck.cause is StallCause.Observed)
        assertEquals("I can't guide this part reliably, because I can't see it.", (stuck.cause as StallCause.Observed).description)
    }

    @Test
    fun `an app that says the step failed is named as such, apart from Dot not seeing`() = runTest {
        val host = Recorder(seen(observation("o1", packageName = "com.facebook.katana")))
        val client = FakeClient(
            decided(AiDecision.Blocked("o1", "Facebook couldn't finish creating the account.", "Try again later.", appError = true)),
        )
        val session = session(host, client)
        session.start("make a Facebook account")
        val stuck = session.state as AiSessionState.Stuck
        assertEquals(StallCause.AppError("Facebook couldn't finish creating the account."), stuck.cause)
    }

    @Test
    fun `a manual step that hands over the whole task is not shown, and one step is asked for instead`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")), seen(observation("o3")))
        val client = FakeClient(
            decided(AiDecision.Manual("o1", "Finish signing up yourself, then tap I'm done.", "sign-up")),
            decided(AiDecision.Manual("o2", "Complete the rest of the form on your own.", "sign-up")),
        )
        val session = session(host, client)
        session.start("make a Facebook account")
        runCurrent()
        assertEquals(2, client.calls)
        assertTrue(client.requests.last().failedApproaches.any { it.startsWith("Handing the whole task") })
        assertTrue("never shown as a hand-off", session.state is AiSessionState.Stuck)
        assertTrue(host.shown.none { it.contains("yourself") })
    }

    @Test
    fun `starting inside Dot gives a local instruction and asks nobody anything`() = runTest {
        val host = Recorder(ObserveResult.InDot)
        val client = FakeClient()
        val session = session(host, client)

        session.start("make the text bigger")

        val state = session.state as AiSessionState.WaitingForUserAction
        assertEquals(AiSessionController.KIND_LEAVE_DOT, state.instruction.kind)
        assertTrue(
            state.instruction.instruction,
            state.instruction.instruction.contains("slide one finger up from the very bottom", ignoreCase = true),
        )
        assertEquals("no request for Dot's own screen", 0, client.calls)
        assertEquals(
            "the Home swipe is shown where it starts",
            com.aaravsekhri.dot.guide.ai.GesturePurpose.GoHome,
            host.gestures.single().purpose,
        )

        // Settling on Dot again does not ask either; the same step stays up.
        val said = host.shown.last()
        session.onScreenSettled(Change.Content)
        assertEquals(0, client.calls)
        assertEquals(said, host.shown.last())
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.GoHome, host.gestures.last().purpose)
    }

    @Test
    fun `the Home instruction follows how this phone navigates`() = runTest {
        val host = Recorder(ObserveResult.InDot)
        host.navigation = NavigationStyle.ThreeButton
        val session = session(host, FakeClient())

        session.start("make the text bigger")

        val words = (session.state as AiSessionState.WaitingForUserAction).instruction.instruction
        assertTrue(words, words.contains("round Home button"))
    }

    @Test
    fun `navigating home is worded by the phone, not the model`() = runTest {
        val host = Recorder(seen(observation("o1", packageName = "com.example.app")))
        val client = FakeClient(
            decided(AiDecision.Navigate("o1", Destination.Home, "Apps start from Home.", "Home shows.")),
        )
        val session = session(host, client)

        session.start("help me use ChatGPT")

        val instruction = (session.state as AiSessionState.WaitingForUserAction).instruction
        assertEquals(AiSessionController.KIND_HOME, instruction.kind)
        assertTrue(instruction.instruction.startsWith("Go to your Home screen"))
    }

    @Test
    fun `a scroll suggestion says which way the finger goes and what should appear`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(
            decided(AiDecision.Scroll("o1", Reveal.Below, null, "Brightness", "Brightness shows.")),
        )
        val session = session(host, client)

        session.start("make my screen darker")

        val words = (session.state as AiSessionState.WaitingForUserAction).instruction.instruction
        assertEquals("Look for “Brightness”. Slide your finger up the screen to see more below.", words)
        assertTrue("a suggestion outlines nothing", host.targets.isEmpty())
    }

    @Test
    fun `a screen that has not changed is not asked about twice`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")), seen(observation("o3")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)

        session.start("something")
        session.onScreenSettled(Change.Content)

        assertEquals("the same screen is the same question", 1, client.calls)
    }

    @Test
    fun `a switch that flipped is a different screen`() = runTest {
        val off = listOf(control("n1", "Dark theme", checked = false))
        val on = listOf(control("n1", "Dark theme", checked = true))
        val host = Recorder(seen(observation("o1", off)), seen(observation("o2", off)), seen(observation("o3", on)))
        val client = FakeClient(tap("o1"), tap("o3"))
        val session = session(host, client)

        session.start("make my screen darker")
        session.onUserActed()
        session.onScreenSettled(Change.Action)

        assertEquals("the new state is worth asking about", 2, client.calls)
    }

    // ------------------------------------------------------------- failing

    @Test
    fun `a network failure is tried again by itself, and then offers another go`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val offline = PlanningOutcome.Unreachable("offline", PlanningFailure.Offline)
        val client = FakeClient(offline, offline, offline, offline, tap("o5"))
        val session = session(host, client)

        session.start("something")
        assertTrue(session.state is AiSessionState.Reconnecting)
        advanceTimeBy(AiSessionController.RECOVERY_BUDGET_MS + 1_000)
        runCurrent()

        val failure = session.state as AiSessionState.RecoverableFailure
        assertEquals("the first try and three more, then it stops", 4, client.calls)
        assertTrue(failure.canRetry)
        assertEquals(AiSessionController.failureWords(PlanningFailure.Offline).first, failure.explanation)

        host.then(seen(observation("o5")), seen(observation("o6")))
        session.retry()
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `several quick taps on Try again are one request`() = runTest {
        var clock = 0L
        val host = Recorder(seen(observation("o1")))
        val broken = PlanningOutcome.Refused("server", PlanningFailure.ServiceProblem)
        val client = FakeClient(broken, broken, broken)
        val session = session(host, client, clock = { clock })
        session.start("something")
        repeat(5) {
            session.retry()
            clock += 200
        }
        assertEquals(2, client.calls)
    }

    @Test
    fun `a misconfigured service is not something to retry`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val session = session(
            host,
            FakeClient(PlanningOutcome.Refused("no", PlanningFailure.NotAllowed)),
        )

        session.start("something")

        assertFalse((session.state as AiSessionState.RecoverableFailure).canRetry)
    }

    @Test
    fun `after a blocked answer, keeping on trying asks for another way`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(
            decided(AiDecision.Blocked("o1", "That setting isn't here.", "Try Settings.")),
            tap("o2"),
        )
        val session = session(host, client)

        session.start("something")
        val stuck = session.state as AiSessionState.Stuck
        assertEquals("what the screen showed, not a guess", StallCause.Observed("That setting isn't here."), stuck.cause)
        session.keepTrying()

        assertTrue(client.requests.last().reportedProblem)
    }

    // -------------------------------------------------------- repetition

    @Test
    fun `the same suggestion without progress is not made a third time`() = runTest {
        val unclear = DecisionMeta(assessment = Assessment.Unclear)
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("something")

        repeat(2) { i ->
            host.then(seen(observation("r$i", controls = listOf(control("n1", "Display & touch"), control("x$i", "row $i")))))
            host.then(seen(observation("g$i", controls = listOf(control("n1", "Display & touch"), control("x$i", "row $i")))))
            client.then(tap("r$i", meta = unclear))
            session.onUserActed()
            session.onScreenSettled(Change.Action)
        }

        assertTrue("offered another way: ${session.state}", session.state is AiSessionState.Stuck)
    }

    @Test
    fun `repeating a step that keeps working is not being stuck`() = runTest {
        // "Tap the plus" while the text keeps getting bigger.
        val worked = DecisionMeta(assessment = Assessment.Succeeded, progressNote = "Text is bigger")
        val host = Recorder(seen(observation("o1", listOf(control("n1", "Larger", value = "1 of 7")))))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("make the text bigger")

        for (level in 2..5) {
            val controls = listOf(control("n1", "Larger", value = "$level of 7"))
            host.then(seen(observation("r$level", controls)), seen(observation("g$level", controls)))
            client.then(tap("r$level", meta = worked))
            session.onUserActed()
            session.onScreenSettled(Change.Action)
        }

        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `that didn't work is recorded and the next request is told`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"), tap("o3"))
        val session = session(host, client)
        session.start("something")

        host.then(seen(observation("o3")), seen(observation("o4")))
        session.reportProblem()

        val request = client.requests.last()
        assertTrue(request.reportedProblem)
        assertTrue(request.failedApproaches.single().contains("Tap Display & touch."))
    }

    @Test
    fun `saying it didn't work twice offers another way`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"), tap("o3"))
        val session = session(host, client)
        session.start("something")

        host.then(seen(observation("o3")), seen(observation("o4")))
        session.reportProblem()
        session.reportProblem()

        assertTrue(session.state is AiSessionState.Stuck)
    }

    // ------------------------------------------------------------ waiting

    @Test
    fun `a wait rechecks by itself, and runs out rather than refilling`() = runTest {
        // The planner's own recheck allowance, as it works when every
        // recheck asks: local checks would skip an unchanged page entirely.
        com.aaravsekhri.dot.guide.ai.PlanningTuning.localWaitChecks = false
        val loading = decided(AiDecision.Wait("o", "Installing.", recheckAfterMs = 1_000, maxRechecks = 3))
        val host = Recorder(seen(observation("o")))
        val client = FakeClient(*Array(12) { loading })
        val session = session(host, client)

        session.start("install ChatGPT")
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue("the person decides now: ${session.state}", session.state is AiSessionState.WaitExpired)
        assertEquals("three rechecks after the first look, and no more", 4, client.calls)

        session.keepWaiting()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(session.state is AiSessionState.WaitExpired)
        assertTrue("keep waiting grants a bounded amount more", client.calls <= 4 + 3 + 1)
    }

    @Test
    fun `a wait that alternates with other answers does not refill its allowance`() = runTest {
        val loading = decided(AiDecision.Wait("o", "Loading.", recheckAfterMs = 500, maxRechecks = 2))
        val host = Recorder(seen(observation("o")))
        val client = FakeClient(loading)
        val session = session(host, client)
        session.start("something")

        repeat(4) { i ->
            client.then(decided(AiDecision.Scroll("o", Reveal.Below, null, "", "More.")))
            client.then(loading)
            advanceTimeBy(600)
            runCurrent()
            // A genuinely different screen each time, so nothing is skipped as
            // unchanged and every answer is really asked for.
            host.then(seen(observation("o", listOf(control("n1", "row $i")))))
            session.onScreenSettled(Change.Navigation)
        }

        assertTrue(
            "without verified progress the allowance only counts down: ${session.state}",
            session.state is AiSessionState.WaitExpired,
        )
    }

    @Test
    fun `progress bars moving do not trigger requests while waiting`() = runTest {
        val loading = decided(AiDecision.Wait("o", "Downloading.", recheckAfterMs = 5_000, maxRechecks = 4))
        val host = Recorder(seen(observation("o")))
        val client = FakeClient(loading)
        val session = session(host, client)
        session.start("install ChatGPT")

        repeat(10) { session.onScreenSettled(Change.Content) }

        assertEquals("only the wait's own timer looks again", 1, client.calls)
    }

    // --------------------------------------------------- pause and resume

    @Test
    fun `pausing says so, stops looking, and resuming looks again`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o1b")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("make the text bigger")
        val readsBefore = host.observeCalls

        session.pause()
        assertEquals("Paused", host.shown.last())
        session.onScreenSettled(Change.Navigation)
        session.onUserActed()
        assertEquals("nothing was read while paused", readsBefore, host.observeCalls)

        host.then(seen(observation("o2", generation = 2)))
        session.resumeFromPause()
        assertTrue("resuming reads the screen again", host.observeCalls > readsBefore)
    }

    @Test
    fun `a question stops everything, and its answer travels with it`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")), seen(observation("o3")))
        val client = FakeClient(
            decided(AiDecision.Ask("o1", "An existing photo, or a new one?", listOf("Existing photo", "Take a new one"))),
            tap("o2"),
        )
        val session = session(host, client)

        session.start("help me post a photo on Facebook")
        val asking = session.state as AiSessionState.AskingClarification
        assertEquals(listOf("Existing photo", "Take a new one"), asking.options)

        session.onScreenSettled(Change.Navigation)
        assertEquals("nothing else happens until it is answered", 1, client.calls)

        session.answerClarification("Existing photo")
        assertEquals(
            listOf(QuestionAnswer("An existing photo, or a new one?", "Existing photo")),
            client.requests.last().answers,
        )
    }

    @Test
    fun `resuming restores what was learned and plans from the screen in front now`() = runTest {
        val store = MemoryStore()
        store.saved = AiSessionStore.Checkpoint(
            sessionId = "old",
            goal = "help me post a photo on Facebook",
            answers = listOf(QuestionAnswer("Existing or new?", "Existing")),
            progress = listOf("Facebook is open"),
            subgoal = "Start a new post",
            failedApproaches = listOf("Tapped Stories"),
            lastInstruction = "Tap What's on your mind?",
            lastExpectedOutcome = "The post editor opens.",
            lastKind = "tap",
            status = "guiding",
            updatedAtMs = 0,
        )
        val host = Recorder(seen(observation("now")), seen(observation("now2")))
        val client = FakeClient(tap("now"))
        val session = session(host, client, store)

        session.resume(store.load()!!)

        val request = client.requests.single()
        assertEquals("now", request.observation.observationId)
        assertEquals("Existing", request.answers.single().answer)
        assertEquals(listOf("Facebook is open"), request.progress)
        assertEquals("Start a new post", request.subgoal)
        assertEquals(listOf("Tapped Stories"), request.failedApproaches)
        assertEquals("the old step is context for the model", "Tap What's on your mind?", request.lastInstruction)
        assertEquals("marked as from before the break, not to be judged", AiSessionController.KIND_RESUMED, request.lastKind)
        assertFalse(
            "and never shown again as a current step",
            host.shown.any { it.contains("What's on your mind") },
        )
    }

    // ------------------------------------------------------ progress, ends

    @Test
    fun `progress is recorded only when a screen showed it`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(
            tap("o1", meta = DecisionMeta(Assessment.Unclear, progressNote = "Probably opened")),
        )
        val store = MemoryStore()
        val session = session(host, client, store)
        session.start("something")
        assertTrue(store.saved!!.progress.isEmpty())

        host.then(seen(observation("o3", listOf(control("n1", "Display & touch"), control("n2", "Font size")))))
        host.then(seen(observation("o4", listOf(control("n1", "Display & touch"), control("n2", "Font size")))))
        client.then(tap("o3", meta = DecisionMeta(Assessment.Succeeded, "Display settings are open", "Find text size")))
        session.onUserActed()
        session.onScreenSettled(Change.Action)

        assertEquals(listOf("Display settings are open"), store.saved!!.progress)
        assertEquals("Find text size", store.saved!!.subgoal)
    }

    @Test
    fun `a finished session is never offered as unfinished`() = runTest {
        val store = MemoryStore()
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(decided(AiDecision.Complete("o1", "Your text is bigger.", "Font size shows 5 of 7")))
        val session = session(host, client, store)

        session.start("make the text bigger")
        assertTrue(session.state is AiSessionState.Completed)

        // The service going away afterwards must not rewrite it.
        session.stop(keepCheckpoint = true)
        assertEquals(null, store.load())
    }

    @Test
    fun `an interrupted session can be carried on, an ended one cannot`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val store = MemoryStore()
        val session = session(host, FakeClient(tap("o1")), store)
        session.start("something")

        session.stop(keepCheckpoint = true)
        assertNotNull("interrupted", store.load())

        val ended = session(Recorder(seen(observation("o1")), seen(observation("o2"))), FakeClient(tap("o1")), store)
        ended.start("something")
        ended.stop()
        assertEquals("ended", null, store.load())
    }

    @Test
    fun `nothing lands after the session is stopped`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"))
        client.gate = CompletableDeferred()
        val session = session(host, client)

        session.start("something")
        session.stop()
        client.gate!!.complete(Unit)
        runCurrent()

        assertTrue(host.targets.isEmpty())
        session.onScreenSettled(Change.Navigation)
        assertEquals(1, client.calls)
    }

    // ------------------------------------------------ typing and scrolling

    @Test
    fun `typing is not interrupted at every pause between letters`() = runTest {
        val field = control("f1", "", editable = true).copy(hint = "Search")
        val host = Recorder(seen(observation("o1", listOf(field))), seen(observation("o2", listOf(field))))
        val client = FakeClient(decided(AiDecision.TypeText("o1", "f1", "Type in the search box.", "chatgpt", "Results.")))
        val session = session(host, client)
        session.start("help me use ChatGPT")

        repeat(6) { i ->
            host.then(seen(observation("t$i", listOf(field.copy(label = "chatgpt".take(i + 1))))))
            session.onScreenSettled(Change.Content)
        }
        assertEquals("no request while typing", 1, client.calls)

        host.then(seen(observation("done", listOf(field.copy(label = "chatgpt")))))
        session.doneTyping()
        assertEquals("\"I've typed it\" asks what next", 2, client.calls)
    }

    @Test
    fun `closing the keyboard is not taken as having finished typing`() = runTest {
        // People close the keyboard to see the page. For words only they
        // know, nothing on screen says the typing is over, so nothing is asked.
        val field = control("f1", "", editable = true).copy(hint = "Email")
        val host = Recorder(seen(observation("o1", listOf(field))), seen(observation("o2", listOf(field))))
        val client = FakeClient(decided(AiDecision.TypeText("o1", "f1", "Enter your email address.", "", "Filled.")))
        val session = session(host, client)
        session.start("sign up")

        host.then(seen(observation("o3", listOf(field.copy(label = "someone")))))
        session.onKeyboardChanged(visible = false)

        assertEquals(1, client.calls)
        assertTrue(session.state is AiSessionState.Guiding)
    }

    private fun searchBox(typed: String = "") =
        control("f1", typed, editable = true).copy(hint = "What are you interested in?")

    @Test
    fun `the requested words in the field move the step on, and a partial word does not`() = runTest {
        val host = Recorder(seen(observation("o1", listOf(searchBox()))), seen(observation("o2", listOf(searchBox()))))
        val client = FakeClient(
            decided(AiDecision.TypeText("o1", "f1", "Type Facebook in the search box.", "Facebook", "Typed.")),
            decided(AiDecision.Submit("o4", viaKeyboard = true, candidateId = null, instruction = "", expectedOutcome = "")),
        )
        val session = session(host, client)
        session.start("install Facebook")
        assertTrue(session.state is AiSessionState.Guiding)
        assertEquals("no button to press: the field is watched", "Facebook", (session.state as AiSessionState.Guiding).instruction.expectedText)

        // Halfway through the word, with a pause: not done.
        host.then(seen(observation("o3", listOf(searchBox("Face")))))
        session.onScreenSettled(Change.Content)
        assertEquals(1, client.calls)

        // The whole word: the next step is asked for without anybody saying so.
        host.then(seen(observation("o4", listOf(searchBox("facebook ")))))
        session.onScreenSettled(Change.Content)
        assertEquals(2, client.calls)
        assertTrue(client.requests.last().lastExpectedOutcome.contains("requested words"))
    }

    @Test
    fun `editing the words after moving on brings the typing step back`() = runTest {
        val host = Recorder(seen(observation("o1", listOf(searchBox()))), seen(observation("o2", listOf(searchBox()))))
        val client = FakeClient(
            decided(AiDecision.TypeText("o1", "f1", "Type Facebook in the search box.", "Facebook", "Typed.")),
            decided(AiDecision.Submit("o3", viaKeyboard = true, candidateId = null, instruction = "", expectedOutcome = "")),
        )
        val session = session(host, client)
        session.start("install Facebook")
        host.then(seen(observation("o3", listOf(searchBox("Facebook")))))
        session.onScreenSettled(Change.Content)
        assertTrue("on to sending it", (session.state as AiSessionState.WaitingForUserAction).instruction.kind == AiSessionController.KIND_SUBMIT)

        host.then(seen(observation("o5", listOf(searchBox("Faceb")))))
        session.onScreenSettled(Change.Content)
        val back = session.state as AiSessionState.Guiding
        assertEquals(AiSessionController.KIND_TYPE, back.instruction.kind)
        assertEquals("and without asking anybody", 2, client.calls)
    }

    @Test
    fun `scrolling does not abandon a request, and its answer waits for the page to settle`() = runTest {
        var clock = 0L
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"))
        client.gate = CompletableDeferred()
        val session = session(host, client, clock = { clock })
        session.start("make the text bigger")

        // The person scrolls while the answer is on its way.
        session.onMovement()
        clock += 300
        session.onMovement()
        client.gate!!.complete(Unit)
        runCurrent()
        assertTrue("nothing drawn from a moving page", host.targets.isEmpty())

        session.onScreenSettled(Change.Scroll)
        assertEquals("one request, never restarted", 1, client.calls)
        assertEquals("found again on the settled page", "Display & touch", host.targets.single().naming)
    }

    @Test
    fun `scrolling that goes on shows one steady message, then one fresh look`() = runTest {
        var clock = 0L
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"), tap("o3"))
        client.gate = CompletableDeferred()
        val session = session(host, client, clock = { clock })
        session.start("make the text bigger")

        repeat(8) {
            session.onMovement()
            clock += 400
        }
        assertEquals(AiSessionState.HoldStill, session.state)
        val holds = host.shown.count { it == "HoldStill" }
        repeat(5) {
            session.onMovement()
            clock += 400
        }
        assertEquals("shown once and held, not flickering", holds, host.shown.count { it == "HoldStill" })

        client.gate!!.complete(Unit)
        runCurrent()
        host.then(seen(observation("o3")), seen(observation("o4")))
        session.onScreenSettled(Change.Scroll)
        assertTrue("carried on from the settled page: ${session.state}", session.state is AiSessionState.Guiding)
        assertEquals(1, client.calls)
    }

    @Test
    fun `a slow answer says so and offers another go`() = runTest {
        val host = Recorder(seen(observation("o1")))
        // The held-up first request never takes its answer; the retry does.
        val client = FakeClient(tap("o2"))
        client.gate = CompletableDeferred()
        val session = session(host, client)
        session.start("make the text bigger")
        assertEquals(AiSessionState.Thinking(0, slow = false), session.state)

        advanceTimeBy(AiSessionController.SLOW_MS + 100)
        runCurrent()
        assertEquals(AiSessionState.Thinking(0, slow = true), session.state)

        client.gate = null
        host.then(seen(observation("o2")), seen(observation("o3")))
        session.retry()
        assertEquals("a fresh attempt", 2, client.calls)
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `a question with choices is answered on the card and remembered`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(
            decided(AiDecision.Ask("o1", "Do you already have a Facebook account?", listOf("Yes, help me sign in", "No, help me create one", "I'm not sure"))),
            tap("o2"),
        )
        val store = MemoryStore()
        val session = session(host, client, store)
        session.start("help me make my first Facebook post")
        assertTrue(session.state is AiSessionState.AskingClarification)

        host.then(seen(observation("o2")), seen(observation("o3")))
        session.chooseAt(1)
        assertEquals("No, help me create one", client.requests.last().answers.single().answer)
        assertEquals("and kept for a resume", "No, help me create one", store.saved!!.answers.single().answer)
    }

    @Test
    fun `an answer can be changed, and the question comes back`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(
            decided(AiDecision.Ask("o1", "Do you already have a Facebook account?", listOf("Yes, help me sign in", "No, help me create one"))),
            tap("o2"),
            decided(AiDecision.Ask("o3", "Do you already have a Facebook account?", listOf("Yes, help me sign in", "No, help me create one"))),
        )
        val session = session(host, client)
        session.start("post on Facebook")
        host.then(seen(observation("o2")), seen(observation("o2b")), seen(observation("o3")))
        session.chooseAt(0)

        session.changeAnswer()
        assertTrue("the answer went with it", client.requests.last().answers.isEmpty())
        assertTrue(session.state is AiSessionState.AskingClarification)
    }

    @Test
    fun `a finish from the very screen the last step was given on is looked at again`() = runTest {
        val screen = observation("o1")
        val host = Recorder(seen(screen), seen(observation("o2")))
        val client = FakeClient(
            tap("o1"),
            decided(AiDecision.Complete("o3", "Done.", "It is done.")),
            tap("o4"),
        )
        val session = session(host, client)
        session.start("make the text bigger")

        // Nothing changed on screen, and yet a "done".
        host.then(seen(observation("o3")), seen(observation("o4")), seen(observation("o5")))
        session.reportProblem()
        assertTrue("not accepted as finished: ${session.state}", session.state !is AiSessionState.Completed)
    }

    @Test
    fun `a target that scrolled is found again on the phone without asking`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("make the text bigger")

        host.then(seen(observation("s1", listOf(control("n9", "Display & touch", top = 1400)))))
        session.onScreenChanged()
        session.onScreenSettled(Change.Scroll)

        assertEquals("no request", 1, client.calls)
        assertEquals("pointed at where it is now", 1400, host.targets.last().bounds.top)
        assertEquals("the local re-find takes no picture", 1, host.planningReads)
    }

    @Test
    fun `a target that scrolled away is asked about`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("make the text bigger")

        host.then(seen(observation("s1", listOf(control("n4", "Sound & vibration")))))
        session.onScreenChanged()
        session.onScreenSettled(Change.Scroll)

        assertEquals(2, client.calls)
    }

    @Test
    fun `touching the control withdraws the outline as the same page`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val session = session(host, FakeClient(tap("o1")))
        session.start("make the text bigger")
        val before = host.samePageClears

        session.onUserActed()

        assertTrue("Pebble does not replay its entrance for the same page", host.samePageClears > before)
    }

    @Test
    fun `a reading that never comes back cannot wedge the session`() = runTest {
        // Found on the device: a screenshot callback that never arrived held
        // the one planning slot for ever, and every later change queued behind
        // it. Nothing happened again until the app was restarted.
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        host.hangNext = true
        val client = FakeClient(tap("o1"))
        val session = session(host, client)

        session.start("make the text bigger")
        advanceTimeBy(AiSessionController.PLAN_WATCHDOG_MS + 1)
        runCurrent()

        // Tried again by itself, a little later.
        assertTrue(session.state is AiSessionState.Reconnecting)
        advanceTimeBy(AiSessionController.BACKOFF_MAX_MS)
        runCurrent()
        assertTrue("and the next attempt is not blocked behind the hung one", session.state is AiSessionState.Guiding)
    }

    @Test
    fun `a step interrupted by a break is not recorded as having failed`() = runTest {
        // Seen live: resumed, the model judged the pre-break step "failed"
        // because the screen did not show it done — nobody had done it — and
        // it was recorded as a failed approach and avoided from then on.
        val store = MemoryStore()
        store.saved = AiSessionStore.Checkpoint(
            sessionId = "old", goal = "make the text bigger", answers = emptyList(),
            progress = listOf("Settings is open"), subgoal = "", failedApproaches = emptyList(),
            lastInstruction = "Tap Display & touch.", lastExpectedOutcome = "It opens.", lastKind = "tap",
            status = "interrupted", updatedAtMs = 0,
        )
        val host = Recorder(seen(observation("now")), seen(observation("now2")))
        val client = FakeClient(tap("now", meta = DecisionMeta(Assessment.Failed)))
        val session = session(host, client, store)

        session.resume(store.load()!!)

        assertTrue(store.saved!!.failedApproaches.isEmpty())
    }

    @Test
    fun `a window caught mid-rebuild is looked at again, not reported as unreadable`() = runTest {
        // Seen live after a font-size change.
        val host = Recorder(ObserveResult.Nothing, seen(observation("o1")), seen(observation("o2")))
        val session = session(host, FakeClient(tap("o1")))

        session.start("make the text bigger")
        advanceTimeBy(AiSessionController.REREAD_MS + 1)
        runCurrent()

        assertTrue("${session.state}", session.state is AiSessionState.Guiding)
    }

    @Test
    fun `an unanswered question comes back after leaving Dot, without asking anything`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(decided(AiDecision.Ask("o1", "Is this big enough?", listOf("Yes", "A bit more"))))
        val session = session(host, client)
        session.start("make the text bigger")
        val shown = host.shown.count { it == "AskingClarification" }

        session.onScreenSettled(Change.Navigation)

        assertEquals(shown + 1, host.shown.count { it == "AskingClarification" })
        assertEquals(1, client.calls)
    }

    @Test
    fun `scrolling when asked to is looked at even if no control changed`() = runTest {
        // Seen live on a sideways preview: page two had no controls on it,
        // the content key did not move, and the step never advanced.
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(
            decided(AiDecision.Scroll("o1", Reveal.Right, "pager", "next page", "")),
            decided(AiDecision.Complete("o1", "That is the next page.", "Page 2 of 3")),
        )
        val session = session(host, client)
        session.start("show me the other preview pages")

        session.onScreenChanged()
        session.onScreenSettled(Change.Scroll)

        assertEquals(2, client.calls)
        assertTrue(session.state is AiSessionState.Completed)
    }

    @Test
    fun `coming back into Dot mid-task shows the way back to the app through recent apps, and asks nothing`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("make the text bigger")

        host.then(ObserveResult.InDot)
        session.onDotInFront()

        val waiting = session.state as AiSessionState.WaitingForUserAction
        assertEquals(AiSessionController.KIND_RETURN_TO_APP, waiting.instruction.kind)
        assertTrue(waiting.instruction.instruction, waiting.instruction.instruction.startsWith("Now go back to the app you were using."))
        assertTrue("held, then let go", waiting.instruction.instruction.contains("hold for a moment"))
        assertEquals(com.aaravsekhri.dot.guide.ai.Gesture.Recents(NavigationStyle.Gestures), waiting.instruction.gesture)
        val shown = host.gestures.last()
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.OpenRecents, shown.purpose)
        assertTrue("recent apps is demonstrated with its hold", shown.holdMs > 0)
        assertEquals("the card is kept up, not hidden", 0, host.hidden)
        assertEquals("no request about Dot's own screen", 1, client.calls)

        // Dot's screen changing again, or a look while it is in front, keeps
        // the same step up and its demonstration with it.
        val before = host.gestures.size
        session.onScreenSettled(Change.Content)
        runCurrent()
        assertEquals(AiSessionController.KIND_RETURN_TO_APP, (session.state as AiSessionState.WaitingForUserAction).instruction.kind)
        assertTrue(host.gestures.size >= before)
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.OpenRecents, host.gestures.last().purpose)
        assertEquals(1, client.calls)
    }

    @Test
    fun `with navigation buttons, the way back from Dot is the button, with no path drawn`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2"))).also { it.navigation = NavigationStyle.ThreeButton }
        val session = session(host, FakeClient(tap("o1")))
        session.start("make the text bigger")
        session.onDotInFront()
        val waiting = session.state as AiSessionState.WaitingForUserAction
        assertTrue(waiting.instruction.instruction.contains("square Recent apps button"))
        assertEquals(com.aaravsekhri.dot.guide.ai.Gesture.Recents(NavigationStyle.ThreeButton), waiting.instruction.gesture)
        assertTrue("no coordinates are guessed for a button", host.gestures.isEmpty())
    }

    @Test
    fun `pausing and then opening Dot steps the card aside, and the pause comes back with the app`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val session = session(host, FakeClient(tap("o1")))
        session.start("make the text bigger")
        session.pause()
        session.onDotInFront()
        assertEquals(AiSessionState.Paused, session.state)
        assertEquals("paused on purpose: nothing over Dot", 1, host.hidden)

        host.shown.clear()
        session.onScreenSettled(Change.Navigation)
        assertEquals(listOf("Paused"), host.shown)
    }

    @Test
    fun `asked to leave Dot, the card and its Home demonstration stay up while Dot is in front`() = runTest {
        val host = Recorder(ObserveResult.InDot)
        val client = FakeClient()
        val session = session(host, client)
        session.start("make the text bigger")
        assertEquals(1, host.gestures.size)

        // Dot's own screen changes — a dialog closing, the lesson list — and
        // the session is told Dot is in front again.
        session.onDotInFront()
        assertEquals(AiSessionController.KIND_LEAVE_DOT, (session.state as AiSessionState.WaitingForUserAction).instruction.kind)
        assertEquals(0, host.hidden)
        assertEquals("put back up, not left down", 2, host.gestures.size)
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.GoHome, host.gestures.last().purpose)

        // A second look while still in Dot: the same step, still demonstrated.
        session.onScreenSettled(Change.Navigation)
        runCurrent()
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.GoHome, host.gestures.last().purpose)
        assertEquals(0, client.calls)
    }

    @Test
    fun `a demonstration comes back with its card when Dot's own decision screen closes`() = runTest {
        val home = observation(
            "o1",
            controls = listOf(control("i1", "Gmail", top = 1900), control("i2", "Chrome", top = 1900), control("s", "Search", top = 2150)),
            packageName = "com.google.android.apps.nexuslauncher",
            homeScreen = true,
        )
        val host = Recorder(seen(home))
        val session = session(host, FakeClient(decided(AiDecision.Scroll("o1", Reveal.Below, null, "Apps list", "The app list opens."))))
        session.start("open Maps")
        val plan = host.gestures.single()
        session.redraw()
        assertEquals(listOf(plan, plan), host.gestures)

        // Once the person moves, the demonstration is over and is not brought back.
        session.onMovement()
        session.redraw()
        assertEquals(2, host.gestures.size)
    }

    @Test
    fun `gesture steps carry their movement as data, not in their wording`() = runTest {
        val host = Recorder(seen(observation("o1", packageName = "com.example")))
        host.navigation = NavigationStyle.ThreeButton
        val client = FakeClient(decided(AiDecision.Scroll("o1", Reveal.Right, null, "Alarms", "")))
        val session = session(host, client)
        session.start("set an alarm")
        val scroll = (session.state as AiSessionState.WaitingForUserAction).instruction
        assertEquals(com.aaravsekhri.dot.guide.ai.Gesture.Slide(com.aaravsekhri.dot.guide.ai.FingerDirection.Left), scroll.gesture)

        val home = Recorder(ObserveResult.InDot).also { it.navigation = NavigationStyle.ThreeButton }
        val fresh = session(home, FakeClient())
        fresh.start("set an alarm")
        assertEquals(
            com.aaravsekhri.dot.guide.ai.Gesture.Home(NavigationStyle.ThreeButton),
            (fresh.state as AiSessionState.WaitingForUserAction).instruction.gesture,
        )
    }

    // --------------------------------------------------------------- forms

    private fun birthdayBox(readiness: com.aaravsekhri.dot.guide.ai.Readiness, date: String = "September 29, 2026", error: String = "") =
        control("b", date, top = 480, editable = true).copy(
            hint = "Birthday", readiness = readiness, error = error, fieldKey = "com.example.social|EditText|birthday|0",
        )

    private fun nextButton() = control("next", "Next", top = 700).copy(role = "Button")

    private fun birthdayForm(id: String, box: CandidateControl, notices: List<String> = emptyList()) =
        observation(id, controls = listOf(box, nextButton()), packageName = "com.example.social").copy(notices = notices)

    private fun tapNext(on: String) = decided(AiDecision.Highlight(on, "next", "Tap Next.", "The next page opens."))

    @Test
    fun `Next is not shown over a birthday that may be the app's default, and the person is asked once`() = runTest {
        val host = Recorder(seen(birthdayForm("o1", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Prefilled))))
        val client = FakeClient(tapNext("o1"))
        val session = session(host, client)
        session.start("make a Facebook account")

        val asking = session.state as AiSessionState.AskingClarification
        assertEquals(AiSessionController.BIRTHDAY_QUESTION, asking.question)
        assertEquals(listOf(AiSessionController.BIRTHDAY_YES, AiSessionController.BIRTHDAY_NO), asking.options)
        assertTrue("Next was never pointed at", host.targets.none { it.naming == "Next" })
        assertTrue("the birthday stays in view", host.keptClear.isNotEmpty())
    }

    @Test
    fun `a birthday the person confirms lets Next through`() = runTest {
        val host = Recorder(
            seen(birthdayForm("o1", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Prefilled, "March 3, 1951"))),
            seen(birthdayForm("o2", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Confirmed, "March 3, 1951"))),
        )
        val client = FakeClient(tapNext("o1"), tapNext("o2"))
        val session = session(host, client)
        session.start("make a Facebook account")
        val asking = session.state as AiSessionState.AskingClarification
        session.chooseAt(0, asking.id)
        runCurrent()

        assertEquals(listOf("com.example.social|EditText|birthday|0"), host.confirmedFields)
        val guiding = session.state as AiSessionState.Guiding
        assertEquals("Next", guiding.target.naming)
    }

    @Test
    fun `a birthday the person says is not theirs is set with the app's picker, never a suggested date`() = runTest {
        val default = birthdayForm("o1", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Prefilled))
        val host = Recorder(seen(default), seen(default.copy(observationId = "o2")))
        val client = FakeClient(tapNext("o1"), tapNext("o2"))
        val session = session(host, client)
        session.start("make a Facebook account")
        session.chooseAt(1, (session.state as AiSessionState.AskingClarification).id)
        runCurrent()

        assertTrue(host.confirmedFields.isEmpty())
        val guiding = session.state as AiSessionState.Guiding
        assertEquals(AiSessionController.KIND_SET_FIELD, guiding.instruction.kind)
        assertEquals(com.aaravsekhri.dot.guide.ai.FieldKind.Birthday, guiding.instruction.field)
        assertEquals(AiSessionController.BIRTHDAY_OPEN, guiding.instruction.instruction)
        assertFalse("no date or year is suggested", Regex("\\d").containsMatchIn(guiding.instruction.instruction))
        assertEquals("not asked twice", 1, host.shown.count { it == "AskingClarification" })
    }

    @Test
    fun `a box the app flags is put right before its Next, in the app's own words`() = runTest {
        val flagged = birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Error, "January 1, 2030", error = "Enter a valid date")
        val host = Recorder(seen(birthdayForm("o1", flagged)))
        val session = session(host, FakeClient(tapNext("o1")))
        session.start("make a Facebook account")
        val guiding = session.state as AiSessionState.Guiding
        assertEquals("Birthday", guiding.target.naming)
        assertTrue(guiding.instruction.instruction.startsWith("The app says: “Enter a valid date”."))
    }

    @Test
    fun `Next tapped on a form that comes back unchanged is not shown again, the app's message is`() = runTest {
        val ready = birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Confirmed, "March 3, 1951")
        val host = Recorder(
            seen(birthdayForm("o1", ready)),
            seen(birthdayForm("o2", ready, notices = listOf("Please be sure to use your real birthday."))),
        )
        val client = FakeClient(tapNext("o1"), tapNext("o2"))
        val session = session(host, client)
        session.start("make a Facebook account")
        assertEquals("Next", (session.state as AiSessionState.Guiding).target.naming)

        session.onUserActed()
        session.onScreenChanged()
        session.onScreenSettled(Change.Action)
        runCurrent()

        val waiting = session.state as AiSessionState.WaitingForUserAction
        assertTrue(waiting.instruction.instruction, waiting.instruction.instruction.startsWith("The app says: “Please be sure to use your real birthday.”"))
        assertEquals("Next shown once only", 1, host.targets.count { it.naming == "Next" })
        assertTrue(client.requests.last().failedApproaches.none { it.contains("March") })
    }

    @Test
    fun `a ready form's Next goes straight through, without questions`() = runTest {
        val host = Recorder(seen(birthdayForm("o1", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Confirmed, "March 3, 1951"))))
        val session = session(host, FakeClient(tapNext("o1")))
        session.start("make a Facebook account")
        assertEquals("Next", (session.state as AiSessionState.Guiding).target.naming)
        assertEquals(0, host.shown.count { it == "AskingClarification" })
    }

    // -------------------------------------------------- pages in place

    private val birthdayPage = com.aaravsekhri.dot.guide.ai.ScreenShape("com.example.social", 7, fields = listOf("text/Birthday"))
    private val passwordPage = com.aaravsekhri.dot.guide.ai.ScreenShape("com.example.social", 7, private = true, fields = listOf("password"))

    private fun passwordReading() = ObserveResult.Sensitive(
        SensitiveScreenPolicy.Reason.PasswordField,
        authScreen(SensitiveScreenPolicy.Reason.PasswordField, listOf(passwordBox())),
    )

    @Test
    fun `a password page arriving in the same window while waiting gets its own help at once, with no request`() = runTest {
        val host = Recorder(seen(observation("o1", packageName = "com.example.social"))).also { it.shape = birthdayPage }
        val client = FakeClient(decided(AiDecision.Wait("o1", "Saving your details.", recheckAfterMs = 5_000, maxRechecks = 4)))
        val session = session(host, client)
        session.start("make a Facebook account")
        assertTrue(session.state is AiSessionState.WaitingForExternal)

        // No window change: the page's content is swapped for a password page.
        host.shape = passwordPage
        host.then(passwordReading())
        session.onScreenSettled(Change.Content)
        runCurrent()

        val guiding = session.state as AiSessionState.Guiding
        assertEquals("password box", guiding.target.naming)
        assertEquals("nothing asked of the planner", 1, client.calls)
        // The old wait's timer is gone: it never asks again, or puts the wait back.
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, client.calls)
        assertTrue(session.state is AiSessionState.Guiding)
        assertFalse(host.shown.takeLast(2).any { it == "WaitingForExternal" })
    }

    @Test
    fun `an answer for the old page that arrives after a password page came in place is never shown`() = runTest {
        val host = Recorder(seen(observation("o1", packageName = "com.example.social"))).also { it.shape = birthdayPage }
        val client = FakeClient(decided(AiDecision.Wait("o1", "Saving your details.", recheckAfterMs = 5_000, maxRechecks = 4)))
        client.gate = CompletableDeferred()
        val session = session(host, client)
        session.start("make a Facebook account")
        assertTrue(session.state is AiSessionState.Thinking)

        host.shape = passwordPage
        host.then(passwordReading())
        session.onScreenSettled(Change.Content)
        runCurrent()
        assertEquals("password box", (session.state as AiSessionState.Guiding).target.naming)

        client.gate!!.complete(Unit)
        runCurrent()
        assertTrue("still helping with the password", session.state is AiSessionState.Guiding)
        assertTrue("the old waiting message never came back", host.shown.none { it == "WaitingForExternal" })
    }

    @Test
    fun `a late answer is checked against the page even before anything announced the new one`() = runTest {
        val host = Recorder(seen(observation("o1", packageName = "com.example.social"))).also { it.shape = birthdayPage }
        val client = FakeClient(tapNext("o1"))
        client.gate = CompletableDeferred()
        val session = session(host, client)
        session.start("make a Facebook account")

        host.shape = passwordPage
        host.then(passwordReading())
        client.gate!!.complete(Unit)
        runCurrent()

        val guiding = session.state as AiSessionState.Guiding
        assertEquals("the password page's own help, not the old Next", "password box", guiding.target.naming)
        assertTrue(host.targets.none { it.naming == "Next" })
    }

    @Test
    fun `typing, the cursor and the keyboard are not a new page`() = runTest {
        val field = control("f", "", top = 400, editable = true).copy(hint = "First name")
        val host = Recorder(seen(observation("o1", controls = listOf(field)))).also { it.shape = birthdayPage }
        val client = FakeClient(decided(AiDecision.TypeText("o1", "f", "Type your first name.", "", "")))
        val session = session(host, client)
        session.start("make a Facebook account")
        repeat(5) { session.onScreenSettled(Change.Content) }
        session.onKeyboardChanged(true)
        runCurrent()
        assertEquals("no request per keystroke", 1, client.calls)
        assertTrue(session.state is AiSessionState.Guiding)
    }

    // ------------------------------------------------ typing confirmation

    private fun nameBox() = control("f", "", top = 400, editable = true).copy(hint = "First name")

    private fun typeName(on: String) = decided(AiDecision.TypeText(on, "f", "Type your first name.", "", ""))

    @Test
    fun `a typing step knows what its box is for, so its button can say so`() = runTest {
        val host = Recorder(seen(observation("o1", controls = listOf(nameBox()))))
        val session = session(host, FakeClient(typeName("o1")))
        session.start("make a Facebook account")
        val guiding = session.state as AiSessionState.Guiding
        assertEquals(com.aaravsekhri.dot.guide.ai.FieldKind.FirstName, guiding.instruction.field)
        assertTrue(AiGuideHost.needsTypingConfirmation(guiding.instruction))
        assertEquals("I’m done typing my first name", com.aaravsekhri.dot.guide.ai.FormFields.doneTypingLabel(guiding.instruction.field))
    }

    @Test
    fun `words Dot can watch for need no button, and taps or scrolls never do`() {
        assertFalse(AiGuideHost.needsTypingConfirmation(RuntimeInstruction("Type Clock.", "", "", AiSessionController.KIND_TYPE, expectedText = "Clock")))
        assertTrue(AiGuideHost.needsTypingConfirmation(RuntimeInstruction("Type your email.", "", "", AiSessionController.KIND_TYPE)))
        assertTrue(AiGuideHost.needsTypingConfirmation(RuntimeInstruction("Type your password.", "", "", AiSessionController.KIND_AUTH_TYPE)))
        assertTrue(AiGuideHost.needsTypingConfirmation(RuntimeInstruction(AiSessionController.BIRTHDAY_SET, "", "", AiSessionController.KIND_SET_FIELD)))
        assertFalse(AiGuideHost.needsTypingConfirmation(RuntimeInstruction("Tap Next.", "", "", AiSessionController.KIND_TAP)))
    }

    @Test
    fun `done typing is taken once per step, and never from a card that has gone`() = runTest {
        val host = Recorder(seen(observation("o1", controls = listOf(nameBox()))), seen(observation("o2", controls = listOf(nameBox(), nextButton()))))
        val client = FakeClient(typeName("o1"), tapNext("o2"))
        client.gate = null
        val session = session(host, client)
        session.start("make a Facebook account")
        val step = session.stepId

        session.doneTyping(step - 1)
        runCurrent()
        assertEquals("an old card's tap asks nothing", 1, client.calls)

        client.gate = CompletableDeferred()
        session.doneTyping(step)
        session.doneTyping(step)
        runCurrent()
        assertEquals("two quick taps are one request", 2, client.calls)
        client.gate!!.complete(Unit)
        runCurrent()
        assertEquals("Next", (session.state as AiSessionState.Guiding).target.naming)
    }

    @Test
    fun `moving on in the app takes the typing step's button down by itself`() = runTest {
        val host = Recorder(seen(observation("o1", controls = listOf(nameBox())))).also { it.shape = birthdayPage }
        val client = FakeClient(typeName("o1"), tap("o2"))
        val session = session(host, client)
        session.start("make a Facebook account")
        val typing = session.stepId
        assertTrue(session.state is AiSessionState.Guiding)

        // The person pressed the keyboard's own Next and a new page came in place.
        host.shape = com.aaravsekhri.dot.guide.ai.ScreenShape("com.example.social", 7, fields = listOf("email/Email"))
        host.then(seen(observation("o2")))
        session.onScreenSettled(Change.Content)
        runCurrent()
        assertTrue("a new step, without anyone pressing the button", session.stepId > typing)
        assertFalse((session.state as? AiSessionState.Guiding)?.instruction?.kind == AiSessionController.KIND_TYPE)
    }

    @Test
    fun `the same words on a new screen are a new step`() = runTest {
        val secondPage = listOf(control("t", "Choose your gender", top = 200), nextButton())
        val host = Recorder(seen(observation("o1", controls = listOf(nextButton()))), seen(observation("o2", controls = secondPage)))
        val client = FakeClient(tapNext("o1"), tapNext("o2"))
        val session = session(host, client)
        session.start("make a Facebook account")
        val first = session.stepId
        session.onUserActed()
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)
        runCurrent()
        assertEquals("Tap Next.", (session.state as AiSessionState.Guiding).instruction.instruction)
        assertTrue(session.stepId > first)
    }

    @Test
    fun `a manual step about the birthday says so, and goes by itself when the app moves on`() = runTest {
        val form = birthdayForm("o1", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Prefilled))
        val picker = observation("o2", controls = listOf(control("y", "2026", top = 900, editable = true), control("ok", "OK", top = 1200)), packageName = "com.example.social")
        val host = Recorder(seen(form), seen(form), seen(picker), seen(picker.copy(observationId = "o2b"))).also { it.shape = birthdayPage }
        val client = FakeClient(
            decided(AiDecision.Highlight("o1", "b", "Tap the birthday box to set your real date of birth.", "A date picker opens.")),
            decided(AiDecision.Manual("o2", "Scroll the wheels to set your real birthday, then tap OK.", "a picker")),
            tapNext("o3"),
        )
        val session = session(host, client)
        session.start("make a Facebook account")
        assertEquals("Birthday", (session.state as AiSessionState.Guiding).target.naming)

        // The picker opens in a window of its own.
        host.shape = com.aaravsekhri.dot.guide.ai.ScreenShape("com.example.social", 9, fields = listOf("number/Unknown", "number/Unknown", "number/Unknown"))
        session.onUserActed()
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)
        runCurrent()
        val manual = session.state as AiSessionState.ManualHandoff
        assertEquals("I’ve set my birthday", manual.confirmLabel)

        // They set it and tap OK: the picker closes and the form is back.
        host.shape = birthdayPage
        host.then(seen(birthdayForm("o3", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Confirmed, "March 3, 1951"))))
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)
        runCurrent()
        assertFalse("not left waiting for \"I'm done\"", session.state is AiSessionState.ManualHandoff)
        assertEquals(3, client.calls)
    }

    // ------------------------------------------------ essential details

    @Test
    fun `a personal request asks for its detail in the person's words, with a way to say they are not sure`() = runTest {
        val host = Recorder(seen(observation("o1", packageName = "com.google.android.apps.nexuslauncher", homeScreen = true)))
        val client = FakeClient(
            decided(
                AiDecision.Ask(
                    "o1", "What's your son's address?", listOf("I'm not sure", "I'll type it in Maps"),
                    freeText = true, personal = true,
                ),
            ),
        )
        val store = MemoryStore()
        val session = session(host, client, store)
        session.start("Show me how to get to my son's house")
        val asking = session.state as AiSessionState.AskingClarification
        assertTrue(asking.freeText && asking.personal)
        assertEquals(listOf("I'm not sure", "I'll type it in Maps"), asking.options)
        assertTrue("kept for a resume, as it was asked", store.saved!!.pendingFreeText && store.saved!!.pendingPersonal)
    }

    @Test
    fun `a personal question always lets the person say they are not sure`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(decided(AiDecision.Ask("o1", "What's your son's address?", personal = true)))
        val session = session(host, client)
        session.start("Show me how to get to my son's house")
        val asking = session.state as AiSessionState.AskingClarification
        assertEquals(listOf("I'm not sure"), asking.options)
        assertTrue(asking.freeText)
    }

    @Test
    fun `what the person settles is kept as a fact, sent with each request, and kept with the task`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val details = DecisionMeta(taskDetails = mapOf("destination" to "12 Oak Lane, Springfield", "search_text" to "12 Oak Lane, Springfield"))
        val client = FakeClient(
            decided(AiDecision.Ask("o1", "What's your son's address?", listOf("I'm not sure"), details, freeText = true, personal = true)),
            tap("o2"),
        )
        val store = MemoryStore()
        val session = session(host, client, store)
        session.start("Show me how to get to my son's house")
        session.answerClarification("12 Oak Lane, Springfield", (session.state as AiSessionState.AskingClarification).id)
        runCurrent()
        assertEquals("12 Oak Lane, Springfield", client.requests.last().taskDetails["destination"])
        assertEquals("12 Oak Lane, Springfield", store.saved!!.taskDetails["search_text"])
        assertEquals("the goal is kept as asked", "Show me how to get to my son's house", client.requests.last().goal)
    }

    @Test
    fun `a result whose house number is not the one searched for is not pointed at`() = runTest {
        val wrong = control("r1", "21 Oak Lane, Springfield", top = 600)
        val right = control("r2", "12 Oak Lane, Springfield", top = 800)
        val results = observation("o2", controls = listOf(wrong, right), packageName = "com.google.android.apps.maps")
        val host = Recorder(seen(observation("o1")), seen(results), seen(results.copy(observationId = "o3")), seen(results.copy(observationId = "o3")))
        val client = FakeClient(
            decided(AiDecision.Ask("o1", "What's the address?", listOf("I'm not sure"), DecisionMeta(taskDetails = mapOf("search_text" to "12 Oak Lane, Springfield")), freeText = true)),
            decided(AiDecision.Highlight("o2", "r1", "Tap 21 Oak Lane.", "Its details open.")),
            decided(AiDecision.Highlight("o3", "r2", "Tap 12 Oak Lane.", "Its details open.")),
        )
        val session = session(host, client)
        session.start("Show me how to get to my son's house")
        session.answerClarification("12 Oak Lane, Springfield", (session.state as AiSessionState.AskingClarification).id)
        runCurrent()
        assertTrue(host.targets.none { it.naming.startsWith("21 ") })
        assertEquals("12 Oak Lane, Springfield", (session.state as AiSessionState.Guiding).target.naming)
        assertTrue(client.requests.last().failedApproaches.any { it.contains("does not match") })
    }

    @Test
    fun `the house number decides whether a result can be the address`() {
        assertTrue(com.aaravsekhri.dot.guide.ai.AddressMatch.contradicts("21 Oak Lane", "12 Oak Lane, Springfield"))
        assertFalse(com.aaravsekhri.dot.guide.ai.AddressMatch.contradicts("12 Oak Ln, Springfield, IL 62704", "12 Oak Lane, Springfield"))
        assertFalse("no number shown: the words decide", com.aaravsekhri.dot.guide.ai.AddressMatch.contradicts("Oak Lane", "12 Oak Lane"))
        assertFalse("nothing to check against", com.aaravsekhri.dot.guide.ai.AddressMatch.contradicts("21 Oak Lane", "Smithsonian museum"))
    }

    @Test
    fun `in a lesson, the learner's own practice choice is kept for the course`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o1")))
        val practice = com.aaravsekhri.dot.guide.ai.PracticeContext("Let's practice finding a place.", "Smithsonian", "maps.place", "{example}")
        val lesson = LessonContext("maps", "Maps", "maps.search", "Search", "search", "done", practice = practice)
        val client = FakeClient(tap("o1", meta = DecisionMeta(practiceChoice = "Joe's Diner")))
        val session = session(host, client)
        session.start("search", TaskContext(lesson = lesson))
        session.continueTeaching()
        assertEquals(listOf("Joe's Diner"), host.practiceChoices)
    }

    // ------------------------------------------------------------ teaching

    private fun mapsLesson() = LessonContext(
        "maps", "Google Maps", "maps.details", "Check the details", "open the details", "details open",
        practice = com.aaravsekhri.dot.guide.ai.PracticeContext(
            intro = "Let's practice with the Smithsonian museum in Washington, DC.",
            example = "Smithsonian", exampleKey = "maps.place",
            checkpoints = listOf("This section tells you when the museum opens and closes."),
        ),
        purpose = "By the end of this lesson, you'll know how to read a place's details in Google Maps.",
        result = "A place's details show when it is open and where it is.",
    )

    private fun hoursPage(id: String) = observation(id, controls = listOf(control("d", "Directions", top = 400)), packageName = "com.google.android.apps.maps")
        .copy(readable = listOf(control("t9", "Open · Closes 5:30 PM", top = 900).copy(role = "Text", clickable = false)))

    @Test
    fun `a lesson begins with its purpose and practice, asking the planner nothing until the learner continues`() = runTest {
        val host = Recorder(seen(hoursPage("o1")))
        val client = FakeClient(tap("o1", "d"))
        val session = session(host, client)
        session.start("open the details", TaskContext(lesson = mapsLesson()))

        val intro = session.state as AiSessionState.Teaching
        assertEquals(com.aaravsekhri.dot.guide.ai.TeachStage.Intro, intro.stage)
        assertTrue(intro.words.startsWith("By the end of this lesson"))
        assertEquals(0, client.calls)

        session.continueTeaching(session.stepId)
        val practice = session.state as AiSessionState.Teaching
        assertEquals(com.aaravsekhri.dot.guide.ai.TeachStage.Practice, practice.stage)
        assertEquals(0, client.calls)

        session.continueTeaching(session.stepId)
        assertEquals("guiding starts only now", 1, client.calls)
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `screen changes and late answers never dismiss a teaching card, and a tap on an old card does nothing`() = runTest {
        val host = Recorder(seen(hoursPage("o1")))
        val client = FakeClient()
        val session = session(host, client)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        val card = session.state as AiSessionState.Teaching
        val step = session.stepId
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)
        session.onScreenSettled(Change.Content)
        session.onScreenSettled(Change.Scroll)
        runCurrent()
        assertEquals(card, session.state)
        assertEquals(0, client.calls)

        session.continueTeaching(step - 1)
        assertEquals("an old card's tap is refused", card, session.state)
    }

    @Test
    fun `a teaching point about information is outlined to read, and Next acknowledges it without completing anything`() = runTest {
        val host = Recorder(seen(hoursPage("o1")), seen(hoursPage("o1")), seen(hoursPage("o2")))
        val client = FakeClient(
            decided(AiDecision.Explain("o1", "c1", candidateId = "t9")),
            tap("o2", "d"),
        )
        val session = session(host, client)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        session.continueTeaching(session.stepId)
        session.continueTeaching(session.stepId)
        runCurrent()

        val point = session.state as AiSessionState.Teaching
        assertEquals(com.aaravsekhri.dot.guide.ai.TeachStage.Checkpoint, point.stage)
        assertEquals("the lesson's own words", "This section tells you when the museum opens and closes.", point.words)
        assertEquals("Open · Closes 5:30 PM", point.target!!.naming)
        assertTrue("to read, not to tap", point.target!!.reading)

        // Tapping around, or the person pressing something, is not "Next".
        session.onUserActed()
        assertEquals(point, session.state)

        session.continueTeaching(session.stepId)
        runCurrent()
        assertEquals(listOf("c1"), client.requests.last().explained)
        assertFalse(session.state is AiSessionState.Completed)
    }

    @Test
    fun `information that is not on the screen is never claimed, and the point is said to be missing`() = runTest {
        val noHours = observation("o1", controls = listOf(control("d", "Directions", top = 400)), packageName = "com.google.android.apps.maps")
        val host = Recorder(seen(noHours), seen(noHours), seen(noHours.copy(observationId = "o2")), seen(noHours.copy(observationId = "o2")))
        val client = FakeClient(
            decided(AiDecision.Explain("o1", "c1", candidateId = "t9")),
            decided(AiDecision.Explain("o2", "c1", missing = true, instruction = "This museum's hours aren't listed here.")),
        )
        val session = session(host, client)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        session.continueTeaching(session.stepId)
        session.continueTeaching(session.stepId)
        runCurrent()

        val point = session.state as AiSessionState.Teaching
        assertEquals("This museum's hours aren't listed here.", point.words)
        assertNull(point.target)
        assertTrue(client.requests.last().failedApproaches.any { it.contains("not found") })
    }

    @Test
    fun `a teaching point that names nothing is asked once which part shows it, then outlined`() = runTest {
        val host = Recorder(seen(hoursPage("o1")), seen(hoursPage("o2")), seen(hoursPage("o2")))
        val client = FakeClient(
            decided(AiDecision.Explain("o1", "c1")),
            decided(AiDecision.Explain("o2", "c1", candidateId = "t9")),
        )
        val session = session(host, client)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        session.continueTeaching(session.stepId)
        session.continueTeaching(session.stepId)
        runCurrent()

        assertEquals(2, client.calls)
        assertTrue(client.requests.last().failedApproaches.any { it.contains("without candidate_id") })
        val point = session.state as AiSessionState.Teaching
        assertEquals("Open · Closes 5:30 PM", point.target!!.naming)
    }

    @Test
    fun `a teaching point that still names nothing keeps the lesson's words without an outline`() = runTest {
        val host = Recorder(seen(hoursPage("o1")), seen(hoursPage("o1")), seen(hoursPage("o2")))
        val client = FakeClient(
            decided(AiDecision.Explain("o1", "c1")),
            decided(AiDecision.Explain("o2", "c1")),
        )
        val session = session(host, client)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        session.continueTeaching(session.stepId)
        session.continueTeaching(session.stepId)
        runCurrent()

        assertEquals(2, client.calls)
        val point = session.state as AiSessionState.Teaching
        assertEquals("This section tells you when the museum opens and closes.", point.words)
        assertNull(point.target)
    }

    @Test
    fun `a lesson's result is explained before it is celebrated, and the completion was seen on screen`() = runTest {
        val host = Recorder(seen(hoursPage("o1")))
        val client = FakeClient(decided(AiDecision.Complete("o1", "Details open.", "The details page shows the hours.")))
        val session = session(host, client)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        session.continueTeaching(session.stepId)
        session.continueTeaching(session.stepId)
        runCurrent()
        val result = session.state as AiSessionState.Teaching
        assertEquals(com.aaravsekhri.dot.guide.ai.TeachStage.Result, result.stage)
        session.continueTeaching(session.stepId)
        assertEquals(AiSessionState.Completed("Details open."), session.state)
    }

    @Test
    fun `a teaching card is put back after pausing, and after the process is recreated`() = runTest {
        val host = Recorder(seen(hoursPage("o1")), seen(hoursPage("o1")), seen(hoursPage("o1")))
        val store = MemoryStore()
        val client = FakeClient(decided(AiDecision.Explain("o1", "c1", candidateId = "t9")))
        val session = session(host, client, store)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        session.continueTeaching(session.stepId)
        session.continueTeaching(session.stepId)
        runCurrent()
        val point = session.state as AiSessionState.Teaching

        session.pause()
        session.resumeFromPause()
        assertEquals(point.words, (session.state as AiSessionState.Teaching).words)

        // A new process, from the saved checkpoint.
        val saved = store.saved!!
        assertEquals("checkpoint", saved.teachStage)
        assertEquals("c1", saved.teachCheckpoint)
        val again = session(Recorder(seen(hoursPage("o3"))), FakeClient(), MemoryStore())
        again.resume(saved, TaskContext(lesson = mapsLesson()))
        runCurrent()
        val back = again.state as AiSessionState.Teaching
        assertEquals(point.words, back.words)
        assertEquals("the information found again on the screen now", "Open · Closes 5:30 PM", back.target?.naming)

        // Resumed from Dot's own screen: "this section" would point at
        // nothing, so guiding carries on and the point waits for the app.
        val fromDot = FakeClient()
        val overDot = session(Recorder(AiSessionController.ObserveResult.InDot, AiSessionController.ObserveResult.InDot), fromDot, MemoryStore())
        overDot.resume(saved, TaskContext(lesson = mapsLesson()))
        runCurrent()
        assertFalse(overDot.state is AiSessionState.Teaching)
    }

    @Test
    fun `a teaching point about a control is found again after the process is recreated`() = runTest {
        // Maps offers the hours row as a control, not as plain text.
        val page = { id: String ->
            observation(id, controls = listOf(control("h", "Hours: Closed · Opens 10 AM Wed", top = 900)), packageName = "com.google.android.apps.maps")
        }
        val store = MemoryStore()
        val client = FakeClient(decided(AiDecision.Explain("o1", "c1", candidateId = "h")))
        val session = session(Recorder(seen(page("o1")), seen(page("o1")), seen(page("o1"))), client, store)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        session.continueTeaching(session.stepId)
        session.continueTeaching(session.stepId)
        runCurrent()
        assertEquals("Hours: Closed · Opens 10 AM Wed", (session.state as AiSessionState.Teaching).target?.naming)

        val again = session(Recorder(seen(page("o2"))), FakeClient(), MemoryStore())
        again.resume(store.saved!!, TaskContext(lesson = mapsLesson()))
        runCurrent()
        val back = again.state as AiSessionState.Teaching
        assertEquals("found again as the control it was", "Hours: Closed · Opens 10 AM Wed", back.target?.naming)
        assertFalse(back.target!!.bounds.isEmpty)
    }

    @Test
    fun `a restart abandons the old attempt, and its late answer is never shown`() = runTest {
        val host = Recorder(seen(hoursPage("o1")))
        val client = FakeClient(tap("o1", "d"))
        client.gate = CompletableDeferred()
        val session = session(host, client)
        session.start("open the details", TaskContext(lesson = mapsLesson()))
        session.continueTeaching(session.stepId)
        session.continueTeaching(session.stepId)
        assertTrue(session.state is AiSessionState.Thinking)

        // Restart: the old session stops; a new one begins at the first stage.
        session.stop(keepCheckpoint = false)
        val fresh = session(Recorder(seen(hoursPage("o2"))), FakeClient())
        fresh.start("open the details", TaskContext(lesson = mapsLesson()))
        client.gate!!.complete(Unit)
        runCurrent()
        assertTrue("the old answer drew nothing", host.targets.isEmpty())
        assertEquals(com.aaravsekhri.dot.guide.ai.TeachStage.Intro, (fresh.state as AiSessionState.Teaching).stage)
    }

    @Test
    fun `a manual tap on a listed control is pointed at, but a consequential one is left to the person`() = runTest {
        val next = control("n", "Next", top = 2000)
        val host = Recorder(seen(observation("o1", listOf(next))), seen(observation("o1", listOf(next))))
        val session = session(host, FakeClient(decided(AiDecision.Manual("o1", "Tap the button at the bottom right.", "", candidateId = "n"))))
        session.start("set an alarm")
        val guiding = session.state as AiSessionState.Guiding
        assertEquals("Next", guiding.target.naming)
        assertEquals("Tap Next.", guiding.instruction.instruction)
    }

    @Test
    fun `a result left on screen from before is not this attempt's practice`() = runTest {
        val place = observation("o1", packageName = "com.google.android.apps.maps")
        val search = control("s", "", top = 150, editable = true).copy(hint = "Search here")
        val host = Recorder(seen(place), seen(observation("o2", listOf(search), packageName = "com.google.android.apps.maps")))
        val lesson = LessonContext(
            "maps", "Google Maps", "maps.search", "Search", "search for the practice place", "the place shows",
            practice = com.aaravsekhri.dot.guide.ai.PracticeContext("", "Smithsonian", "maps.place", typeText = "Smithsonian"),
        )
        val client = FakeClient(
            decided(AiDecision.Complete("o1", "Maps shows it.", "The place card.")),
            decided(AiDecision.TypeText("o2", "s", "Type Smithsonian.", "Smithsonian", "")),
        )
        val session = session(host, client)
        session.start("search", TaskContext(lesson = lesson))
        runCurrent()
        assertFalse(session.state is AiSessionState.Completed)
        assertTrue(client.requests.last().failedApproaches.any { it.contains("before the learner practised") })
        assertEquals(AiSessionController.KIND_TYPE, (session.state as AiSessionState.Guiding).instruction.kind)
    }

    @Test
    fun `a result judged done twice is accepted, not argued with again`() = runTest {
        val place = observation("o1", packageName = "com.google.android.apps.maps")
        val host = Recorder(seen(place), seen(place.copy(observationId = "o2")))
        val lesson = LessonContext(
            "maps", "Google Maps", "maps.details", "Details", "open the details", "the details show",
            practice = com.aaravsekhri.dot.guide.ai.PracticeContext("", "Smithsonian", "maps.place", typeText = "Smithsonian"),
        )
        val client = FakeClient(
            decided(AiDecision.Complete("o1", "Details open.", "The details page.")),
            decided(AiDecision.Complete("o2", "Details open.", "The details page.")),
        )
        val session = session(host, client)
        session.start("details", TaskContext(lesson = lesson))
        runCurrent()
        assertEquals(2, client.calls)
        assertTrue(session.state is AiSessionState.Completed)
    }

    // ------------------------------------------------------- completions

    @Test
    fun `in a lesson the learner confirms, the same manual step twice on a page becomes a question, and yes completes it`() = runTest {
        val slider = observation("o1", packageName = "com.android.settings")
        val host = Recorder(seen(slider), seen(slider.copy(observationId = "o2")), seen(slider.copy(observationId = "o3")))
        val lesson = LessonContext("reading", "Read", "reading.brightness", "Brightness", "adjust", "set", learnerConfirms = true)
        val client = FakeClient(
            decided(AiDecision.Manual("o1", "Drag the slider to adjust the brightness.", "slider")),
            decided(AiDecision.Manual("o2", "Drag the slider left or right.", "slider")),
        )
        val session = session(host, client)
        session.start("adjust", TaskContext(lesson = lesson))
        assertTrue(session.state is AiSessionState.ManualHandoff)
        session.continueAfterHandoff()
        runCurrent()
        val asking = session.state as AiSessionState.AskingClarification
        assertEquals(AiSessionController.DONE_QUESTION, asking.question)
        session.chooseAt(0, asking.id)
        assertTrue(session.state is AiSessionState.Completed)
        assertEquals("no request to be told the same thing again", 2, client.calls)
    }


    @Test
    fun `a lesson's ending says only what the records hold`() {
        val next = AiGuideHost.LessonTransition("Search for a place", "Check the details", completed = 1, total = 4)
        assertEquals(com.aaravsekhri.dot.guide.overlay.AiCardAction.NextLesson, AiGuideHost.actionsFor(AiSessionState.Completed("done"), next).primary)
        assertEquals(com.aaravsekhri.dot.guide.overlay.AiCardAction.FinishForNow, AiGuideHost.actionsFor(AiSessionState.Completed("done"), next).secondary)

        val all = AiGuideHost.LessonTransition("Preview the route", null, completed = 4, total = 4, courseDone = true)
        assertEquals(com.aaravsekhri.dot.guide.overlay.AiCardAction.Finish, AiGuideHost.actionsFor(AiSessionState.Completed("done"), all).primary)
        assertEquals(com.aaravsekhri.dot.guide.overlay.AiCardAction.ReviewCourse, AiGuideHost.actionsFor(AiSessionState.Completed("done"), all).secondary)

        // Nothing left, but a lesson was skipped: no claim the course is done.
        val wrap = AiGuideHost.LessonTransition("Preview the route", null, completed = 3, total = 4, courseDone = false)
        assertEquals(com.aaravsekhri.dot.guide.overlay.AiCardAction.OpenCourse, AiGuideHost.actionsFor(AiSessionState.Completed("done"), wrap).secondary)
    }

    // ------------------------------------------------------------ sign-in

    private fun authScreen(
        reason: SensitiveScreenPolicy.Reason,
        fields: List<com.aaravsekhri.dot.guide.ai.AuthField>,
        submit: com.aaravsekhri.dot.guide.ai.AuthButton? = null,
        keyboard: Boolean = false,
    ) = com.aaravsekhri.dot.guide.ai.AuthScreen("com.example.bank", 3, reason, fields, submit, keyboard, 1080, 2400)

    private fun passwordBox(focused: Boolean = false) = com.aaravsekhri.dot.guide.ai.AuthField(
        com.aaravsekhri.dot.guide.ai.FieldRole.Password, Bounds(60, 700, 1020, 840), focused, enabled = true,
    )

    private fun codeBox() = com.aaravsekhri.dot.guide.ai.AuthField(
        com.aaravsekhri.dot.guide.ai.FieldRole.Code, Bounds(60, 600, 1020, 740), focused = false, enabled = true,
    )

    @Test
    fun `a password screen is guided box by box and nothing is ever sent`() = runTest {
        val private = ObserveResult.Sensitive(
            SensitiveScreenPolicy.Reason.PasswordField,
            authScreen(SensitiveScreenPolicy.Reason.PasswordField, listOf(passwordBox())),
        )
        val host = Recorder(private)
        val client = FakeClient()
        val session = session(host, client)

        session.start("sign in to my bank app")

        val state = session.state as AiSessionState.Guiding
        assertEquals("password box", state.target.naming)
        assertEquals("outlined on the phone", 1, host.targets.size)
        assertEquals("and nothing asked of the planning service", 0, client.calls)
    }

    @Test
    fun `typing a password is followed by the send button, then a check of the result`() = runTest {
        val submit = com.aaravsekhri.dot.guide.ai.AuthButton("Next", Bounds(600, 900, 1020, 1030))
        val focused = ObserveResult.Sensitive(
            SensitiveScreenPolicy.Reason.PasswordField,
            authScreen(SensitiveScreenPolicy.Reason.PasswordField, listOf(passwordBox(focused = true)), submit, keyboard = true),
        )
        val host = Recorder(focused)
        val client = FakeClient(tap("after"))
        val session = session(host, client)
        session.start("sign in")
        assertEquals(AiSessionController.KIND_AUTH_TYPE, (session.state as AiSessionState.Guiding).instruction.kind)

        session.doneTyping()
        val send = session.state as AiSessionState.Guiding
        assertEquals("Now tap “Next”.", send.instruction.instruction)

        // They tap Next; an ordinary screen arrives.
        host.then(seen(observation("after")), seen(observation("after2")))
        session.onUserActed()
        session.onScreenSettled(Change.Navigation)

        val request = client.requests.single()
        assertTrue("told to check, not told it worked", request.lastInstruction.contains("finished a sign-in step"))
        assertEquals(AiSessionController.KIND_AUTH, request.lastKind)
    }

    @Test
    fun `while a code is fetched from another app, that app is never described`() = runTest {
        val code = ObserveResult.Sensitive(
            SensitiveScreenPolicy.Reason.VerificationCode,
            authScreen(SensitiveScreenPolicy.Reason.VerificationCode, listOf(codeBox())),
        )
        val host = Recorder(code)
        val client = FakeClient()
        val session = session(host, client)
        session.start("sign in")
        assertTrue(session.state is AiSessionState.LocalChoice)

        session.chooseAt(0) // on this phone
        assertTrue(session.state is AiSessionState.AuthGuidance)
        val picturesBefore = host.planningReads

        // Messages opens. Even an ordinary-looking screen there is not sent.
        host.then(seen(observation("inbox", packageName = "com.google.android.apps.messaging")))
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)
        assertEquals(0, client.calls)
        val away = (session.state as AiSessionState.AuthGuidance).instruction
        assertEquals(com.aaravsekhri.dot.guide.ai.Gesture.Back(NavigationStyle.Gestures), away.gesture)
        assertEquals("and no picture was taken of it", picturesBefore, host.planningReads)

        // Back in the sign-in: the code box is pointed at.
        host.then(code)
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)
        assertEquals("code box", (session.state as AiSessionState.Guiding).target.naming)
        assertEquals(0, client.calls)
    }

    @Test
    fun `arriving at a sign-in asks only whether the goal is met, describing nothing`() = runTest {
        val login = ObserveResult.Sensitive(
            SensitiveScreenPolicy.Reason.PasswordField,
            authScreen(SensitiveScreenPolicy.Reason.PasswordField, listOf(passwordBox())),
        )
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(
            tap("o1"),
            decided(AiDecision.Complete("x", "It is installed and open.", "Its sign-in screen shows.")),
        )
        val session = session(host, client)
        session.start("help me install Example Social")

        // "Open" was tapped; the app opened at its sign-in screen.
        host.then(login)
        session.onUserActed()
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)

        val asked = client.requests.last().observation
        assertTrue("no controls", asked.candidates.isEmpty())
        assertEquals("no picture", null, asked.screenshotJpeg)
        assertEquals(AiSessionController.PRIVATE_SIGN_IN_NOTE, asked.screenshotNote)
        assertTrue("installing was the goal: done", session.state is AiSessionState.Completed)
    }

    @Test
    fun `when signing in is needed, the phone's own help takes over after the one check`() = runTest {
        val login = ObserveResult.Sensitive(
            SensitiveScreenPolicy.Reason.PasswordField,
            authScreen(SensitiveScreenPolicy.Reason.PasswordField, listOf(passwordBox())),
        )
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(
            tap("o1"),
            decided(AiDecision.Manual("x", "Sign in to continue.", "Signing in is needed.")),
        )
        val session = session(host, client)
        session.start("help me make my first post")

        host.then(login)
        session.onUserActed()
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)
        assertTrue(session.state is AiSessionState.Guiding)
        assertEquals("password box", (session.state as AiSessionState.Guiding).target.naming)

        // And not asked again for every box.
        session.onScreenSettled(Change.Content)
        assertEquals(2, client.calls)
    }

    @Test
    fun `an account answer given before a break is not asked again after it`() = runTest {
        val login = ObserveResult.Sensitive(
            SensitiveScreenPolicy.Reason.PasswordField,
            com.aaravsekhri.dot.guide.ai.AuthScreen(
                "com.example.social", 3, SensitiveScreenPolicy.Reason.PasswordField, listOf(passwordBox()),
                submit = com.aaravsekhri.dot.guide.ai.AuthButton("Log in", Bounds(60, 900, 1020, 1000)),
                keyboardVisible = false, screenWidth = 1080, screenHeight = 2400,
                create = com.aaravsekhri.dot.guide.ai.AuthButton("Create new account", Bounds(60, 1500, 1020, 1600)),
                appName = "Example Social",
            ),
        )
        val store = MemoryStore()
        val first = session(Recorder(login), FakeClient(), store)
        first.start("post on Example Social")
        assertTrue(first.state is AiSessionState.LocalChoice)
        first.chooseAt(0) // yes, sign me in
        first.stop(keepCheckpoint = true)

        val second = session(Recorder(login), FakeClient(), store)
        second.resume(store.saved!!)
        assertTrue("straight to the boxes: ${second.state}", second.state is AiSessionState.Guiding)
    }

    @Test
    fun `a failed request does not set off a second one queued behind it`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(PlanningOutcome.Refused("cap", PlanningFailure.QuotaExhausted))
        client.gate = CompletableDeferred()
        val session = session(host, client)
        session.start("make the text bigger")
        // Something on the page changes while the request is out.
        session.onScreenSettled(Change.Content)
        client.gate!!.complete(Unit)
        runCurrent()
        assertTrue(session.state is AiSessionState.RecoverableFailure)
        assertEquals(1, client.calls)
    }

    // ------------------------------------------------- recovering from failure

    private fun limited(ms: Long) = PlanningOutcome.Unreachable("limit", PlanningFailure.LocalLimit, retryAfterMs = ms)

    @Test
    fun `Dot's own limit is waited out for exactly as long as it said, once`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(limited(5_000), tap("o2"))
        val session = session(host, client)
        session.start("make the text bigger")
        assertTrue(session.state is AiSessionState.Reconnecting)

        advanceTimeBy(4_900)
        runCurrent()
        assertEquals("not a moment early", 1, client.calls)

        host.then(seen(observation("o2")), seen(observation("o3")))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, client.calls)
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `a provider throttle followed by an answer carries on`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(
            PlanningOutcome.Unreachable("throttled", PlanningFailure.Throttled, retryAfterMs = 2_000),
            tap("o2"),
        )
        val session = session(host, client)
        session.start("make the text bigger")
        host.then(seen(observation("o2")), seen(observation("o3")))
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(2, client.calls)
        assertEquals("Display & touch", host.targets.single().naming)
    }

    @Test
    fun `a known outage is said once and nothing on screen asks again`() = runTest {
        val store = MemoryStore()
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(PlanningOutcome.Refused("cap", PlanningFailure.QuotaExhausted))
        val session = session(host, client, store)
        session.start("make the text bigger")

        val failure = session.state as AiSessionState.RecoverableFailure
        assertEquals("Guidance has reached its usage limit for now. Your place is saved.", failure.explanation)
        assertFalse("trying again cannot fix a spent allowance; stopping is what is offered", failure.canRetry)

        // Scrolling, pages changing, time passing: none of it is a reason.
        repeat(10) {
            session.onMovement()
            session.onScreenChanged()
            session.onScreenSettled(Change.Navigation)
            session.onScreenSettled(Change.Content)
            session.onScreenSettled(Change.Scroll)
        }
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(1, client.calls)
        assertEquals("the place is kept to carry on from", "make the text bigger", store.load()!!.goal)
    }

    @Test
    fun `an unexplained limit gets one more try, not a storm`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val unknown = PlanningOutcome.Unreachable("429", PlanningFailure.ProviderLimited)
        val client = FakeClient(unknown, unknown, unknown, unknown)
        val session = session(host, client)
        session.start("make the text bigger")
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertEquals(2, client.calls)
        assertTrue(session.state is AiSessionState.RecoverableFailure)
    }

    @Test
    fun `timeouts and a dead service are bounded by the same allowance`() = runTest {
        for (outcome in listOf(
            PlanningOutcome.Unreachable("slow", PlanningFailure.SlowAnswer),
            PlanningOutcome.Unreachable("offline", PlanningFailure.Offline),
            PlanningOutcome.Refused("garbled", PlanningFailure.UnusableAnswer, retryable = true),
        )) {
            val host = Recorder(seen(observation("o1")))
            val client = FakeClient(outcome, outcome, outcome, outcome, outcome, outcome)
            val session = session(host, client)
            session.start("make the text bigger")
            advanceTimeBy(5 * 60_000)
            runCurrent()
            assertEquals("${outcome}", 1 + AiSessionController.MAX_AUTO_RETRIES, client.calls)
            assertTrue(session.state is AiSessionState.RecoverableFailure)
        }
    }

    @Test
    fun `while waiting to try again, screen events ask nothing and no old target returns`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"), limited(10_000), tap("o9"))
        val session = session(host, client)
        session.start("make the text bigger")
        assertEquals(1, host.targets.size)

        // The page changes and the next request is refused for a while.
        host.then(seen(observation("o3", controls = listOf(control("n2", "Sound & vibration")))))
        session.onScreenChanged()
        session.onScreenSettled(Change.Navigation)
        assertTrue(session.state is AiSessionState.Reconnecting)
        repeat(5) {
            session.onScreenChanged()
            session.onScreenSettled(Change.Navigation)
            session.onScreenSettled(Change.Content)
        }
        assertEquals("nothing jumps the wait", 2, client.calls)
        assertEquals("and the old outline is not put back", 1, host.targets.size)

        // When it is time, the screen is read afresh; the answer is about that.
        host.then(seen(observation("o9", controls = listOf(control("n2", "Sound & vibration")))), seen(observation("o10", controls = listOf(control("n2", "Sound & vibration")))))
        advanceTimeBy(10_100)
        runCurrent()
        assertEquals(3, client.calls)
        assertEquals("o9", client.requests.last().observation.observationId)
    }

    @Test
    fun `pausing or ending while waiting to try again sends nothing more`() = runTest {
        for (end in listOf("pause", "stop")) {
            val host = Recorder(seen(observation("o1")))
            val client = FakeClient(limited(5_000), tap("o2"))
            val session = session(host, client)
            session.start("make the text bigger")
            if (end == "pause") session.pause() else session.stop(keepCheckpoint = true)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(end, 1, client.calls)
        }
    }

    @Test
    fun `resuming after a pause during recovery asks once, from the screen in front`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(limited(5_000), tap("o2"))
        val session = session(host, client)
        session.start("make the text bigger")
        session.pause()
        host.then(seen(observation("o2")), seen(observation("o3")))
        session.resumeFromPause()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, client.calls)
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `answers survive a failure and go with the next request`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(
            decided(AiDecision.Ask("o1", "Which one?", listOf("A", "B"))),
            limited(3_000),
            tap("o3"),
        )
        val session = session(host, client)
        session.start("something")
        host.then(seen(observation("o2")))
        session.chooseAt(0)
        host.then(seen(observation("o3")), seen(observation("o4")))
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals("A", client.requests.last().answers.single().answer)
    }

    // ------------------------------------------------------ cost and placement

    private fun storePage(id: String, vararg buttons: String) =
        observation(id, buttons.mapIndexed { i, b -> control("b$i", b, top = 400 + i * 220) }, packageName = "com.example.store")

    @Test
    fun `a download ticking over is watched on the phone, and Open appearing is asked about once`() = runTest {
        com.aaravsekhri.dot.guide.ai.PlanningTuning.localWaitChecks = true
        val host = Recorder(seen(storePage("o1", "Cancel")))
        val client = FakeClient(
            decided(AiDecision.Wait("o1", "Example Social is installing.", recheckAfterMs = 2_000, maxRechecks = 8)),
            decided(AiDecision.Highlight("o10", "b0", "Tap Open.", "It opens.")),
        )
        val session = session(host, client)
        session.start("install Example Social")
        assertTrue(session.state is AiSessionState.WaitingForExternal)

        // Five rechecks with nothing but the percentage moving.
        repeat(5) {
            host.then(seen(storePage("p$it", "Cancel")))
            advanceTimeBy(2_100)
            runCurrent()
        }
        assertEquals("no request while only numbers change", 1, client.calls)

        host.then(seen(storePage("o9", "Open")), seen(storePage("o10", "Open")), seen(storePage("o11", "Open")))
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(2, client.calls)
        assertEquals("wait_changed", client.requests.last().trigger)
        assertEquals(5, session.costs.avoided)
    }

    @Test
    fun `ticks during the request that decides to wait do not queue another`() = runTest {
        // Seen live: each tick while an answer was on its way queued a
        // request that fired as the wait began — one request per tick.
        com.aaravsekhri.dot.guide.ai.PlanningTuning.localWaitChecks = true
        val host = Recorder(seen(storePage("o1", "Cancel")))
        val client = FakeClient(
            decided(AiDecision.Wait("o1", "Example Social is installing.", recheckAfterMs = 2_000, maxRechecks = 8)),
        )
        client.gate = CompletableDeferred()
        val session = session(host, client)
        session.start("install Example Social")
        repeat(3) { session.onScreenSettled(Change.Content) }
        client.gate!!.complete(Unit)
        runCurrent()

        assertTrue(session.state is AiSessionState.WaitingForExternal)
        assertEquals("the ticks queued nothing", 1, client.calls)
        host.then(seen(storePage("p1", "Cancel")))
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals("and the wait's own check still asks nothing", 1, client.calls)
    }

    @Test
    fun `numbers moving are not a change, and a new control is`() {
        fun page(vararg labels: String) = observation("x", labels.mapIndexed { i, l -> control("n$i", l, top = 100 + i * 220) })
        assertEquals(page("Installing 40%").progressKey(), page("Installing 70%").progressKey())
        assertTrue(page("Cancel").progressKey() != page("Open").progressKey())
    }

    @Test
    fun `every request says why it was made`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"), tap("o3"))
        val session = session(host, client)
        session.start("make the text bigger")
        assertEquals("start", client.requests.first().trigger)
        host.then(seen(observation("o3", controls = listOf(control("n1", "Display & touch"), control("n2", "Sound")))), seen(observation("o4")))
        session.reportProblem()
        assertEquals("did_not_work", client.requests.last().trigger)
    }

    @Test
    fun `words alone are tried only while things are going well`() = runTest {
        com.aaravsekhri.dot.guide.ai.PlanningTuning.selectiveScreenshots = true
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(PlanningOutcome.Unreachable("offline", PlanningFailure.Offline), tap("o2"))
        val session = session(host, client)
        session.start("make the text bigger")
        assertEquals(AiSessionController.ObservePurpose.PlanningTextFirst, host.purposes.first())
        host.then(seen(observation("o2")), seen(observation("o3")))
        advanceTimeBy(AiSessionController.BACKOFF_MAX_MS)
        runCurrent()
        assertTrue(
            "after a failure the picture always goes",
            host.purposes.contains(AiSessionController.ObservePurpose.Planning),
        )
    }

    @Test
    fun `a manual step keeps its card off the control it names, and highlights done only after`() = runTest {
        val post = control("p1", "Post", top = 2100)
        val page = observation("o1", listOf(control("t1", "Title"), post))
        val host = Recorder(seen(page), seen(page))
        val client = FakeClient(decided(AiDecision.Manual("o1", "Tap Post yourself.", "your choice", candidateId = "p1")))
        val session = session(host, client)
        session.start("make my first post")

        assertEquals(listOf(post.bounds), host.keptClear.single())
        val waiting = session.state as AiSessionState.ManualHandoff
        assertFalse("not highlighted before anything was done", waiting.emphasizeDone)

        session.onScreenSettled(Change.Action)
        assertTrue((session.state as AiSessionState.ManualHandoff).emphasizeDone)
        assertEquals("and nothing was asked", 1, client.calls)
    }

    // ------------------------------------------------ stopping and trying on

    private val chatGptApp = TargetApp("ChatGPT", "com.openai.chatgpt", "OpenAI", InstallState.Installed)

    /** Two unhelpful repeats of the same tap: the session stops moving. */
    private fun kotlinx.coroutines.test.TestScope.stallOn(
        session: AiSessionController,
        host: Recorder,
        client: FakeClient,
        page: (Int) -> Observation,
    ) {
        val unclear = DecisionMeta(assessment = Assessment.Unclear)
        repeat(2) { n ->
            val i = n + 1
            host.then(seen(page(i)), seen(page(i)))
            client.then(decided(AiDecision.Highlight("r$i", "n1", "Tap it.", "It opens.", unclear)))
            session.onUserActed()
            session.onScreenSettled(Change.Action)
        }
    }

    @Test
    fun `try again later saves everything, stops all work, and completes nothing`() = runTest {
        val store = MemoryStore()
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"), tap("o3"))
        val session = session(host, client, store)
        session.start("make the text bigger")
        host.then(seen(observation("o3")), seen(observation("o4")))
        session.reportProblem()
        session.reportProblem()
        assertTrue(session.state is AiSessionState.Stuck)
        val calls = client.calls

        session.tryLater()

        val saved = store.saved!!
        assertEquals(AiSessionStore.STATUS_LATER, saved.status)
        assertEquals("make the text bigger", saved.goal)
        assertTrue("what did not work is kept", saved.failedApproaches.isNotEmpty())
        assertFalse(store.discarded)
        // Nothing on screen and no timer asks again.
        session.onScreenSettled(Change.Navigation)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(calls, client.calls)
        assertFalse(session.state is AiSessionState.Completed)
        // And the service letting go afterwards keeps it too.
        session.stop()
        assertFalse(store.discarded)
    }

    @Test
    fun `end guide on an ask stops everything at once, completes nothing, and its late answer draws nothing`() = runTest {
        val admission = FakeAdmission()
        val store = MemoryStore()
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(tap("o1", "d"))
        client.gate = CompletableDeferred()
        val session = session(host, client, store, admission = admission)
        session.start("make the text bigger")
        assertTrue(session.state is AiSessionState.Thinking)

        session.endGuide()
        client.gate!!.complete(Unit)
        runCurrent()
        session.onScreenSettled(Change.Navigation)
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertTrue("the late answer drew nothing", host.targets.isEmpty())
        assertFalse(session.state is AiSessionState.Completed)
        assertEquals("the credit is asked back once, by the service's own rule", 1, admission.released.size)
        assertTrue(admission.finished.isEmpty())
        assertTrue("an ask that was ended is not kept to carry on", store.discarded)
    }

    @Test
    fun `end guide on a lesson keeps its place and leaves the lesson unfinished`() = runTest {
        val store = MemoryStore()
        val host = Recorder(seen(hoursPage("o1")))
        val session = session(host, FakeClient(tap("o1", "d")), store)
        session.start("open the details", TaskContext(lesson = mapsLesson()))

        session.endGuide()

        assertEquals(AiSessionStore.STATUS_LATER, store.saved!!.status)
        assertFalse(store.discarded)
        assertFalse(session.state is AiSessionState.Completed)
    }

    @Test
    fun `a start that could not be made says what happened, and never claims a place was saved`() {
        for (kind in PlanningFailure.entries) {
            val (said, then) = AiSessionController.startFailureWords(kind)
            assertFalse("$kind: nothing was guided yet", said.contains("place is saved", ignoreCase = true))
            assertTrue("$kind", (said + then).isNotBlank())
        }
        val (down, _) = AiSessionController.startFailureWords(PlanningFailure.ServiceDown)
        assertTrue(down.contains("couldn't reach"))
        assertTrue("a retry is the same task", down.contains("won't use another guided task"))
    }

    @Test
    fun `keep trying looks again with the failure on record, and never shows the stalled step again`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("something")
        stallOn(session, host, client) { observation("r$it", controls = listOf(control("n1", "Display & touch"), control("x$it", "row $it"))) }
        assertTrue(session.state is AiSessionState.Stuck)
        val shownBefore = host.targets.size

        // The planner suggests the very same thing again.
        host.then(seen(observation("r9", controls = listOf(control("n1", "Display & touch")))))
        client.then(decided(AiDecision.Highlight("r9", "n1", "Tap Display & touch.", "It opens.", DecisionMeta(assessment = Assessment.Unclear))))
        session.keepTrying()

        val asked = client.requests.last()
        assertTrue("told it did not work", asked.reportedProblem)
        assertTrue(asked.failedApproaches.any { it.startsWith("Repeated") })
        assertTrue("not shown again: ${session.state}", session.state is AiSessionState.Stuck)
        assertEquals(shownBefore, host.targets.size)
    }

    @Test
    fun `keeping on trying is bounded, and then only stopping is offered`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("something")
        stallOn(session, host, client) { observation("r$it", controls = listOf(control("n1", "Display & touch"), control("x$it", "row $it"))) }
        repeat(AiSessionController.MAX_KEEP_TRYING) {
            host.then(seen(observation("k$it")))
            client.then(tap("k$it"))
            session.keepTrying()
        }
        val stuck = session.state as AiSessionState.Stuck
        assertFalse(stuck.canKeepTrying)
        val actions = AiGuideHost.actionsFor(stuck)
        assertEquals(AiCardAction.TryLater, actions.primary)
        assertEquals(null, actions.secondary)
    }

    @Test
    fun `an app tapped on the Home screen that never opens is named, and nothing else is blamed`() = runTest {
        fun home(i: Int) = observation(
            "r$i",
            controls = listOf(control("n1", "ChatGPT"), control("x$i", "Clock $i")),
            packageName = "com.google.android.apps.nexuslauncher",
            homeScreen = true,
        )
        val host = Recorder(seen(home(0)))
        val client = FakeClient(decided(AiDecision.Highlight("r0", "n1", "Tap ChatGPT.", "It opens.")))
        val session = session(host, client)
        session.start("open ChatGPT", TaskContext(app = chatGptApp))
        stallOn(session, host, client, ::home)
        assertEquals(StallCause.AppNotOpening("ChatGPT"), (session.state as AiSessionState.Stuck).cause)

        // The same pattern without knowing which app was meant: no name is given.
        val host2 = Recorder(seen(home(0)))
        val client2 = FakeClient(decided(AiDecision.Highlight("r0", "n1", "Tap ChatGPT.", "It opens.")))
        val session2 = session(host2, client2)
        session2.start("open ChatGPT")
        stallOn(session2, host2, client2, ::home)
        assertEquals(StallCause.Unknown, (session2.state as AiSessionState.Stuck).cause)
    }

    @Test
    fun `the recommended action on every stopping card is the right one`() {
        assertEquals(
            AiGuideHost.CardActions(AiCardAction.TryLater, AiCardAction.KeepTrying, preferred = true),
            AiGuideHost.actionsFor(AiSessionState.Stuck()),
        )
        // A spent allowance cannot be tried out of: stopping only.
        assertEquals(
            AiGuideHost.CardActions(AiCardAction.TryLater, null, preferred = true),
            AiGuideHost.actionsFor(AiSessionState.RecoverableFailure("x", "", canRetry = false)),
        )
        // A set-up problem only closes, and nothing is recommended.
        assertEquals(
            AiGuideHost.CardActions(AiCardAction.Exit, null, preferred = false),
            AiGuideHost.actionsFor(AiSessionState.RecoverableFailure("x", "", canRetry = false, setupProblem = true)),
        )
        // A finished lesson goes on to the next.
        assertEquals(
            AiCardAction.NextLesson,
            AiGuideHost.actionsFor(AiSessionState.Completed("done"), AiGuideHost.LessonTransition("A", "B")).primary,
        )
        // A question is the person's: no answer is recommended.
        assertFalse(AiGuideHost.actionsFor(AiSessionState.AskingClarification("Existing account?", listOf("Yes", "No"))).preferred)
    }

    @Test
    fun `a spent allowance is not asked again from the card`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(PlanningOutcome.Refused("cap", PlanningFailure.QuotaExhausted), tap("o1"))
        val session = session(host, client)
        session.start("something")
        assertFalse((session.state as AiSessionState.RecoverableFailure).canRetry)
        session.retry()
        session.keepTrying()
        assertEquals(1, client.calls)
    }

    private val shareLesson = LessonContext(
        courseId = "whatsapp", courseTitle = "WhatsApp", lessonId = "whatsapp.share-photo",
        lessonTitle = "Share a photo", objective = "Share a photo the learner picks.",
        doneWhen = "The photo shows as sent.", rules = listOf("The learner picks the photo."),
        needsAccount = true, contentVersion = 3,
    )

    @Test
    fun `a lesson travels with every request, and the goal stays the objective`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start(shareLesson.objective, TaskContext(chatGptApp, shareLesson))
        val request = client.requests.single()
        assertEquals(shareLesson.objective, request.goal)
        assertEquals(shareLesson, request.context.lesson)
        assertEquals(chatGptApp, request.context.app)
    }

    @Test
    fun `in a lesson, a feature that is not here is unavailable, never completed`() = runTest {
        val store = MemoryStore()
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(decided(AiDecision.Blocked("o1", "Photos can't be shared here.", "", unavailable = true)))
        val session = session(host, client, store)
        session.start(shareLesson.objective, TaskContext(lesson = shareLesson))
        assertEquals(AiSessionState.LessonUnavailable("Photos can't be shared here."), session.state)
        assertEquals(AiSessionStore.STATUS_UNAVAILABLE, store.saved?.status)
        assertEquals("whatsapp.share-photo", store.saved?.lessonId)
        assertEquals(3, store.saved?.contentVersion)
    }

    @Test
    fun `outside a lesson, unavailable is just a supportive stop`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(decided(AiDecision.Blocked("o1", "That isn't offered here.", "", unavailable = true)))
        val session = session(host, client)
        session.start("make a picture")
        assertTrue(session.state is AiSessionState.Stuck)
    }

    // --------------------------------------------------- finding an app

    private fun launcherResults(id: String) = observation(
        id,
        controls = listOf(
            control("q", "Search apps", top = 100, editable = true),
            control("w", "chatgpt.com — search the web", top = 300),
            control("s", "ChatGPT Assistant AI · Install", top = 500),
            control("l", "Chat GPT AI Chatbot", top = 700),
            control("a", "ChatGPT", top = 900),
        ),
        packageName = "com.google.android.apps.nexuslauncher",
        homeScreen = true,
    )

    @Test
    fun `in launcher results the installed app is pointed at, not the web, the store or a look-alike`() = runTest {
        for (wrong in listOf("w", "s", "l")) {
            val host = Recorder(seen(launcherResults("o1")), seen(launcherResults("o2")))
            val client = FakeClient(decided(AiDecision.Highlight("o1", wrong, "Tap it.", "It opens.")))
            val session = session(host, client)
            session.start("open ChatGPT", TaskContext(app = chatGptApp))
            assertEquals("from $wrong", "ChatGPT", host.targets.single().naming)
        }
    }

    @Test
    fun `without an unambiguous installed result the planner's choice stands`() = runTest {
        val results = launcherResults("o1").let { it.copy(candidates = it.candidates.filter { c -> c.id != "a" }) }
        val host = Recorder(seen(results), seen(results.copy(observationId = "o2")))
        val client = FakeClient(decided(AiDecision.Highlight("o1", "s", "Tap Install.", "It installs.")))
        val session = session(host, client)
        session.start("install ChatGPT", TaskContext(app = chatGptApp.copy(installState = InstallState.NotInstalled)))
        assertEquals("ChatGPT Assistant AI · Install", host.targets.single().naming)
    }

    @Test
    fun `paging sideways through Home screens is bounded, and search is asked for instead`() = runTest {
        val home = observation("o1", packageName = "com.google.android.apps.nexuslauncher", homeScreen = true)
        val host = Recorder(seen(home))
        val swipe = { id: String -> decided(AiDecision.Scroll(id, Reveal.Right, null, "the ChatGPT icon", "Another page shows.")) }
        val client = FakeClient(swipe("o1"))
        val session = session(host, client)
        session.start("open ChatGPT", TaskContext(app = chatGptApp))
        // One sideways page is fine.
        assertTrue(session.state is AiSessionState.WaitingForUserAction)
        host.then(seen(home.copy(observationId = "o2", screenGeneration = 2)))
        client.then(swipe("o2"), swipe("o2"))
        session.onScreenSettled(Change.Scroll)
        runCurrent()
        assertTrue(client.requests.last().failedApproaches.any { "app search" in it })
        assertTrue("not a third page: ${session.state}", session.state !is AiSessionState.WaitingForUserAction)
    }

    @Test
    fun `a manual step's card keeps clear of the controls its words name`() = runTest {
        val page = observation(
            "o1",
            controls = listOf(
                control("p", "Use password", top = 2100),
                control("b", "Use biometrics", top = 2100),
                control("x", "Help", top = 300),
            ),
        )
        val host = Recorder(seen(page), seen(page))
        val client = FakeClient(decided(AiDecision.Manual("o1", "Tap Use password or Use biometrics to continue.", "a choice only they can make")))
        val session = session(host, client)
        session.start("install ChatGPT")
        assertTrue(session.state is AiSessionState.ManualHandoff)
        assertEquals(2, host.keptClear.flatten().size)
    }

    // ------------------------------------------- questions over the app

    private val accountQuestion = "Do you already have a Facebook account?"
    private val accountOptions = listOf("Yes, help me sign in", "No, help me create one", "I'm not sure")

    private fun askAccount(on: String) = decided(AiDecision.Ask(on, accountQuestion, accountOptions))

    @Test
    fun `a question is asked over the app and answering carries the same task on`() = runTest {
        val lesson = shareLesson.copy(objective = "Make a first post on Facebook.")
        for ((index, answer) in accountOptions.withIndex()) {
            val host = Recorder(seen(observation("o1")), seen(observation("o2")))
            val client = FakeClient(askAccount("o1"), tap("o2"))
            val session = session(host, client)
            session.start(lesson.objective, TaskContext(lesson = lesson))
            val asking = session.state as AiSessionState.AskingClarification
            assertEquals(accountQuestion, asking.question)
            assertEquals("the question is on the overlay, not in Dot", "AskingClarification", host.shown.last())

            session.chooseAt(index, asking.id)

            val next = client.requests.last()
            assertEquals("answer $answer", QuestionAnswer(accountQuestion, answer), next.answers.single())
            assertEquals("the goal is the lesson's still", lesson.objective, next.goal)
            assertEquals(lesson, next.context.lesson)
            assertTrue("a fresh look, then a step", session.state is AiSessionState.Guiding)
        }
    }

    @Test
    fun `an answer from an earlier question, or a second tap, is not applied`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(askAccount("o1"), tap("o2"))
        val session = session(host, client)
        session.start("make my first post")
        val asking = session.state as AiSessionState.AskingClarification

        session.chooseAt(0, asking.id - 1)
        assertTrue("a stale card's tap changes nothing", session.state is AiSessionState.AskingClarification)
        assertEquals(1, client.calls)

        session.chooseAt(0, asking.id)
        session.chooseAt(1, asking.id)
        assertEquals("answered once", 2, client.calls)
        assertEquals(1, client.requests.last().answers.size)
    }

    @Test
    fun `window events do not dismiss, repeat or re-ask a waiting question`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(askAccount("o1"))
        val session = session(host, client)
        session.start("make my first post")
        val asking = session.state
        repeat(10) {
            session.onScreenSettled(Change.Content)
            session.onScreenSettled(Change.Scroll)
            session.onMovement()
            session.onScreenChanged()
        }
        assertEquals(asking, session.state)
        assertEquals("nothing was asked of the planner", 1, client.calls)
    }

    @Test
    fun `a waiting question survives a pause and a restart, and is put back without asking again`() = runTest {
        val store = MemoryStore()
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(askAccount("o1"))
        val session = session(host, client, store)
        session.start("make my first post")

        session.pause()
        session.resumeFromPause()
        val back = session.state as AiSessionState.AskingClarification
        assertEquals(accountQuestion, back.question)
        assertEquals(1, client.calls)

        // The process goes; a new session resumes from what was saved.
        val saved = store.saved!!
        assertEquals(accountQuestion, saved.pendingQuestion)
        assertEquals(accountOptions, saved.pendingOptions)
        val host2 = Recorder(seen(observation("o5")), seen(observation("o6")))
        val client2 = FakeClient(tap("o5"))
        val again = session(host2, client2, store)
        again.resume(saved)
        val restored = again.state as AiSessionState.AskingClarification
        assertEquals(accountOptions, restored.options)
        assertEquals("nothing asked while the question waits", 0, client2.calls)
        again.chooseAt(1, restored.id)
        assertEquals(QuestionAnswer(accountQuestion, "No, help me create one"), client2.requests.single().answers.single())
    }

    @Test
    fun `a question already answered is not put to the person twice`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")), seen(observation("o3")))
        val client = FakeClient(askAccount("o1"), askAccount("o2"), tap("o3"))
        val session = session(host, client)
        session.start("make my first post")
        session.chooseAt(0, (session.state as AiSessionState.AskingClarification).id)
        assertTrue("sent back with a reminder instead: ${session.state}", session.state is AiSessionState.Guiding)
        assertTrue(client.requests.last().failedApproaches.any { it.startsWith("Asked again") })
        assertEquals(1, host.shown.count { it == "AskingClarification" })
    }

    @Test
    fun `a typed question that seems to want a password never gets a box`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(decided(AiDecision.Ask("o1", "What is your Facebook password?")))
        val session = session(host, client)
        session.start("log in to Facebook")
        assertTrue(session.state is AiSessionState.ManualHandoff)
        assertFalse(host.shown.contains("AskingClarification"))
    }

    @Test
    fun `an ordinary typed question is asked on the card`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        val client = FakeClient(decided(AiDecision.Ask("o1", "What would you like to search for?")), tap("o2"))
        val session = session(host, client)
        session.start("find something")
        val asking = session.state as AiSessionState.AskingClarification
        assertTrue(asking.options.isEmpty())
        session.answerClarification("  tomato soup ", asking.id)
        assertEquals("tomato soup", client.requests.last().answers.single().answer)
    }

    // ------------------------------------------ screens that cannot be read

    @Test
    fun `an empty reading is looked at again on the phone, and the task carries on`() = runTest {
        val host = Recorder(
            ObserveResult.Nothing, ObserveResult.Nothing, ObserveResult.Nothing,
            seen(observation("o1")), seen(observation("o1")), seen(observation("o2")),
        )
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("make the text bigger")
        advanceTimeBy(AiSessionController.REREAD_MS + 1)
        runCurrent()
        assertEquals(AiSessionState.Reacquiring, session.state)
        assertEquals("nothing asked while nothing can be seen", 0, client.calls)
        assertTrue("no old target left up", host.cleared > 0)

        advanceTimeBy(AiSessionController.REACQUIRE_BUDGET_MS)
        runCurrent()
        assertEquals(1, client.calls)
        assertEquals("reacquired", client.requests.single().trigger)
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `a settled screen is looked at at once rather than waiting for the timer`() = runTest {
        val host = Recorder(
            ObserveResult.Nothing, ObserveResult.Nothing,
            seen(observation("o1")), seen(observation("o2")), seen(observation("o3")),
        )
        val client = FakeClient(tap("o2"))
        val session = session(host, client)
        session.start("make the text bigger")
        advanceTimeBy(AiSessionController.REREAD_MS + 1)
        runCurrent()
        assertEquals(AiSessionState.Reacquiring, session.state)
        session.onScreenSettled(Change.Navigation)
        runCurrent()
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `a screen that stays unreadable says so and recommends looking again, not stopping`() = runTest {
        val host = Recorder(ObserveResult.Nothing)
        val client = FakeClient()
        val session = session(host, client)
        session.start("make the text bigger")
        advanceTimeBy(AiSessionController.REACQUIRE_BUDGET_MS + 5_000)
        runCurrent()
        val cannot = session.state as AiSessionState.CannotRead
        assertEquals(Unreadable.Unclear, cannot.why)
        assertEquals("a local problem never reaches the planner", 0, client.calls)
        assertEquals(
            AiGuideHost.CardActions(AiCardAction.CheckAgain, AiCardAction.Pause, preferred = true),
            AiGuideHost.actionsFor(cannot),
        )

        // Checking again, and again, without luck: only now is stopping suggested.
        repeat(AiSessionController.MAX_CHECK_AGAIN) {
            session.checkAgain()
            advanceTimeBy(AiSessionController.REACQUIRE_BUDGET_MS + 5_000)
            runCurrent()
        }
        val tired = session.state as AiSessionState.CannotRead
        assertEquals(AiCardAction.TryLater, AiGuideHost.actionsFor(tired).primary)
        assertEquals(0, client.calls)
    }

    @Test
    fun `the task carries on by itself once an unreadable screen becomes readable`() = runTest {
        val host = Recorder(ObserveResult.Nothing)
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("make the text bigger")
        advanceTimeBy(AiSessionController.REACQUIRE_BUDGET_MS + 5_000)
        runCurrent()
        assertTrue(session.state is AiSessionState.CannotRead)
        host.then(seen(observation("o1")), seen(observation("o1")), seen(observation("o2")))
        session.onScreenSettled(Change.Content)
        runCurrent()
        assertTrue("same session, no restart: ${session.state}", session.state is AiSessionState.Guiding)
        assertEquals("make the text bigger", client.requests.single().goal)
    }

    @Test
    fun `a nearly empty screen that may be pictured gets one request with the picture alone`() = runTest {
        val host = Recorder(ObserveResult.Sparse(pictureAllowed = true))
        host.pictureOnlyReply = seen(observation("o1", controls = emptyList()))
        val client = FakeClient(decided(AiDecision.Manual("o1", "Tap the big green button.", "the screen has no descriptions")))
        val session = session(host, client)
        session.start("start the game")
        advanceTimeBy(AiSessionController.REACQUIRE_BUDGET_MS + 5_000)
        runCurrent()
        assertEquals(AiSessionController.ObservePurpose.PlanningPictureOnly, host.purposes.last { it != AiSessionController.ObservePurpose.Grounding })
        assertEquals(1, client.calls)
        assertTrue("words, never a guessed outline", host.targets.isEmpty())
    }

    @Test
    fun `a nearly empty screen that may not be pictured is never sent`() = runTest {
        val host = Recorder(ObserveResult.Sparse(pictureAllowed = false))
        val client = FakeClient()
        val session = session(host, client)
        session.start("sign in")
        advanceTimeBy(AiSessionController.REACQUIRE_BUDGET_MS + 5_000)
        runCurrent()
        assertTrue(session.state is AiSessionState.CannotRead)
        assertEquals(0, client.calls)
        assertFalse(host.purposes.contains(AiSessionController.ObservePurpose.PlanningPictureOnly))
    }

    @Test
    fun `a system dialog is left to the person and the guide waits for it to go`() = runTest {
        val host = Recorder(ObserveResult.SystemDialog)
        val client = FakeClient(tap("o1"))
        val session = session(host, client)
        session.start("take a photo")
        assertEquals(AiSessionState.CannotRead(Unreadable.SystemDialog), session.state)
        assertEquals(0, client.calls)
        host.then(seen(observation("o1")), seen(observation("o1")), seen(observation("o2")))
        session.onScreenSettled(Change.Navigation)
        runCurrent()
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `a protected screen gets local help, no outline and no upload`() = runTest {
        val host = Recorder(ObserveResult.Sensitive(SensitiveScreenPolicy.Reason.ProtectedByTheApp))
        val client = FakeClient()
        val session = session(host, client)
        session.start("check my bank balance")
        assertTrue("a question, not a hand-off", session.state is AiSessionState.LocalChoice)
        assertEquals(0, client.calls)
        assertTrue(host.targets.isEmpty())
    }

    @Test
    fun `a service that cannot be reached is not mistaken for an unreadable screen`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(PlanningOutcome.Unreachable("offline", PlanningFailure.Offline))
        val session = session(host, client)
        session.start("make the text bigger")
        assertTrue(session.state is AiSessionState.Reconnecting)
        assertFalse(session.state is AiSessionState.CannotRead)
    }

    // ------------------------------------------------------ gestures shown

    private val pixelLauncher = "com.google.android.apps.nexuslauncher"

    private fun homeScreen(id: String, generation: Long = 1) = observation(
        id,
        controls = listOf(control("n1", "Phone", top = 1965), control("n2", "Chrome", top = 1965), control("n3", "Photos", top = 300)),
        packageName = pixelLauncher,
        homeScreen = true,
        generation = generation,
    )

    @Test
    fun `on the Home screen, "see more below" becomes the app list swipe, shown where it starts`() = runTest {
        val host = Recorder(seen(homeScreen("o1")))
        val client = FakeClient(decided(AiDecision.Scroll("o1", Reveal.Below, null, "Apps list", "The app list opens.")))
        val session = session(host, client)
        session.start("open Maps")
        val waiting = session.state as AiSessionState.WaitingForUserAction
        assertEquals(AiSessionController.KIND_APP_LIST, waiting.instruction.kind)
        assertEquals("Start where Pebble is and slide your finger up.", waiting.instruction.instruction)
        val plan = host.gestures.single()
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.OpenAppList, plan.purpose)
        assertTrue("not from the bottom edge", plan.start.y < 1965)
    }

    @Test
    fun `an unknown launcher gets the app list in words, with no invented path`() = runTest {
        val host = Recorder(seen(homeScreen("o1").copy(packageName = "com.example.launcher")))
        val client = FakeClient(decided(AiDecision.Scroll("o1", Reveal.Below, null, "Apps list", "The app list opens.")))
        val session = session(host, client)
        session.start("open Maps")
        val waiting = session.state as AiSessionState.WaitingForUserAction
        assertTrue(waiting.instruction.instruction.contains("Apps button"))
        assertTrue(host.gestures.isEmpty())
    }

    @Test
    fun `landing in recent apps after the app list swipe is put right on the phone`() = runTest {
        val host = Recorder(seen(homeScreen("o1")))
        val client = FakeClient(decided(AiDecision.Scroll("o1", Reveal.Below, null, "Apps list", "The app list opens.")))
        val session = session(host, client)
        session.start("open Maps")
        val recents = observation(
            "o2",
            controls = listOf(control("s", "Screenshot", top = 2100), control("x", "Select", top = 2100), control("m", "Maps", top = 800)),
            packageName = pixelLauncher,
            homeScreen = true,
            generation = 2,
        )
        host.then(seen(recents))
        session.onScreenSettled(Change.Navigation)
        runCurrent()
        val waiting = session.state as AiSessionState.WaitingForUserAction
        assertTrue(waiting.instruction.instruction.startsWith("That opened your recent apps."))
        assertEquals(AiSessionController.KIND_HOME, waiting.instruction.kind)
        assertEquals("put right without asking the planner", 1, client.calls)
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.GoHome, host.gestures.last().purpose)
    }

    @Test
    fun `going Home is shown at the bottom edge, and the demonstration stops when the person moves`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val client = FakeClient(decided(AiDecision.Navigate("o1", Destination.Home, "Start from Home.", "Home shows.")))
        val session = session(host, client)
        session.start("open Maps")
        val plan = host.gestures.single()
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.GoHome, plan.purpose)
        val before = host.gestureCleared
        session.onMovement()
        assertTrue(host.gestureCleared > before)
    }

    @Test
    fun `an ordinary scroll is shown inside the list that moves`() = runTest {
        val list = control("list", "Settings list", top = 400).copy(scrollable = true, bounds = Bounds(0, 400, 1170, 2200))
        val host = Recorder(seen(observation("o1", controls = listOf(list, control("n1", "Display", top = 500)))))
        val client = FakeClient(decided(AiDecision.Scroll("o1", Reveal.Below, "list", "Font size", "Font size shows.")))
        val session = session(host, client)
        session.start("make the text bigger")
        val plan = host.gestures.single()
        assertEquals(com.aaravsekhri.dot.guide.ai.GesturePurpose.ScrollContent, plan.purpose)
        assertTrue(plan.start.y in 400..2200 && plan.end.y < plan.start.y)
    }

    // ------------------------------------ the 2026-09-30 walkthrough repairs

    private fun wheel(id: String, left: Int, value: String) = listOf(
        control(id, "").copy(role = "NumberPicker", scrollable = true, clickable = false, bounds = Bounds(left, 1012, left + 168, 1485)),
        control("$id-v", value, editable = true).copy(parentId = id, bounds = Bounds(left, 1185, left + 168, 1311)),
    )

    /** Android's spinner date picker, as the accessibility tree gives it. */
    private fun picker(id: String, month: String = "Sep", day: String = "30", year: String = "2026") = observation(
        id,
        controls = wheel("w1", 235, month) + wheel("w2", 445, day) + wheel("w3", 655, year) + listOf(
            control("cancel", "Cancel", top = 1529).copy(role = "Button", bounds = Bounds(183, 1529, 539, 1671)),
            control("ok", "OK", top = 1529).copy(role = "Button", bounds = Bounds(541, 1529, 897, 1671)),
        ),
        packageName = "com.example.social",
    )

    @Test
    fun `a date picker is helped with one wheel at a time, then Set, then the date shown is confirmed before Next`() = runTest {
        val host = Recorder(seen(picker("p1")))
        val client = FakeClient()
        val session = session(host, client)
        session.start("make a Facebook account")

        val first = session.state as AiSessionState.ManualHandoff
        assertTrue(first.instruction, first.instruction.contains("month wheel"))
        assertEquals("Done", first.confirmLabel)
        assertTrue("the card keeps off the picker", host.keptClear.last().single().contains(Bounds(300, 1200, 310, 1210)))
        assertFalse("no date is ever said", Regex("\\d").containsMatchIn(first.instruction))

        session.continueAfterHandoff()
        assertTrue((session.state as AiSessionState.ManualHandoff).instruction.contains("day wheel"))
        session.continueAfterHandoff()
        assertTrue((session.state as AiSessionState.ManualHandoff).instruction.contains("year wheel"))
        session.continueAfterHandoff()
        val set = session.state as AiSessionState.Guiding
        assertEquals("OK", set.target.naming)

        // Set: the picker closes onto the form, whatever it now shows.
        host.then(seen(birthdayForm("f1", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Prefilled, "May 22, 1956"))))
        session.onScreenSettled(AiSessionController.Change.Navigation)
        val asked = session.state as AiSessionState.AskingClarification
        assertEquals(AiSessionController.BIRTHDAY_QUESTION, asked.question)
        assertEquals(listOf(AiSessionController.BIRTHDAY_YES, AiSessionController.BIRTHDAY_NO), asked.options)

        // "Yes, continue": the form's Next — not the birthday box again.
        host.then(seen(birthdayForm("f2", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Confirmed, "May 22, 1956"))))
        session.chooseAt(0, asked.id)
        runCurrent()
        assertEquals(listOf("com.example.social|EditText|birthday|0"), host.confirmedFields)
        val next = session.state as AiSessionState.Guiding
        assertEquals("Next", next.target.naming)
        assertEquals("the whole picker, and Next, without asking the planner", 0, client.calls)
    }

    @Test
    fun `a picker closed with Cancel still asks, and Change it opens the box again rather than looping`() = runTest {
        val host = Recorder(seen(picker("p1")))
        val session = session(host, FakeClient())
        session.start("make a Facebook account")
        host.then(seen(birthdayForm("f1", birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Prefilled))))
        session.onScreenSettled(AiSessionController.Change.Navigation)
        val asked = session.state as AiSessionState.AskingClarification
        session.chooseAt(1, asked.id)
        runCurrent()
        val guiding = session.state as AiSessionState.Guiding
        assertEquals(AiSessionController.BIRTHDAY_OPEN, guiding.instruction.instruction)
        assertTrue(host.confirmedFields.isEmpty())
    }

    @Test
    fun `a date the app rejects after the picker is put right in the app's own words, not called done`() = runTest {
        val host = Recorder(seen(picker("p1")))
        val client = FakeClient(tapNext("f1"))
        val session = session(host, client)
        session.start("make a Facebook account")
        val rejected = birthdayBox(com.aaravsekhri.dot.guide.ai.Readiness.Error, "September 30, 2026", error = "Please use your real birthday.")
        host.then(seen(birthdayForm("f1", rejected)))
        session.onScreenSettled(AiSessionController.Change.Navigation)
        val guiding = session.state as AiSessionState.Guiding
        assertTrue(guiding.instruction.instruction.startsWith("The app says: “Please use your real birthday.”"))
    }

    @Test
    fun `an I'm done card is replaced when its page is replaced in the same window`() = runTest {
        val signIn = observation("o1", controls = listOf(control("c", "Create account", top = 900)))
        val host = Recorder(seen(signIn), seen(signIn))
        host.shape = com.aaravsekhri.dot.guide.ai.ScreenShape("com.google.android.gms", 7).also { it.controls = setOf(1, 2, 3) }
        val client = FakeClient(decided(AiDecision.Manual("o1", "Tap the blue Sign in button.", "not in the list")))
        val session = session(host, client)
        session.start("install Canva")
        assertTrue(session.state is AiSessionState.ManualHandoff)

        // "Checking info…": the same window, nothing of the old page left.
        host.shape = com.aaravsekhri.dot.guide.ai.ScreenShape("com.google.android.gms", 7).also { it.controls = emptySet() }
        host.then(seen(observation("o2", controls = emptyList())))
        client.then(decided(AiDecision.Wait("o2", "Signing in.", 2000, 3)))
        session.onScreenSettled(AiSessionController.Change.Content)
        assertFalse("the Sign in words do not stay up", session.state is AiSessionState.ManualHandoff)
        assertEquals(2, client.calls)
    }

    @Test
    fun `a manual tap that names exactly one listed control points at it instead of asking for I'm done`() = runTest {
        val page = observation("o1", controls = listOf(control("g", "Get started", top = 2200), control("h", "Help", top = 300)))
        val host = Recorder(seen(page), seen(page))
        val session = session(host, FakeClient(decided(AiDecision.Manual("o1", "Tap the blue \"Get started\" button at the bottom.", "picture"))))
        session.start("set up the Play Store")
        val guiding = session.state as AiSessionState.Guiding
        assertEquals("Get started", guiding.target.naming)
    }

    @Test
    fun `a question about a page the person has left comes down, and the new page is looked at`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        host.shape = com.aaravsekhri.dot.guide.ai.ScreenShape("com.example", 7)
        val client = FakeClient(decided(AiDecision.Ask("o1", "What would you like to do?", listOf("Share location", "Continue"))), tap("o2"))
        val session = session(host, client)
        session.start("find places near me")
        assertTrue(session.state is AiSessionState.AskingClarification)
        host.shape = com.aaravsekhri.dot.guide.ai.ScreenShape("com.example", 8)
        session.onScreenSettled(AiSessionController.Change.Navigation)
        assertTrue(session.state is AiSessionState.Guiding)
        assertEquals(2, client.calls)
    }

    @Test
    fun `a permission window whose buttons Android withholds is waited out, with the card off it and nothing asked`() = runTest {
        val dialog = Bounds(28, 502, 1052, 1966)
        val withheld = ObserveResult.Withheld("Allow Example to access this device’s location?", dialog, permission = true)
        val host = Recorder(withheld)
        val client = FakeClient(tap("o2"))
        val session = session(host, client)
        session.start("find places near me")
        val waiting = session.state as AiSessionState.CannotRead
        assertEquals(com.aaravsekhri.dot.guide.ai.Unreadable.Permission, waiting.why)
        assertEquals(listOf(dialog), host.keptClear.last())
        assertEquals("nothing about a permission window is sent", 0, client.calls)

        // Answered and closed: carried on without "Check again".
        host.then(seen(observation("o2")))
        session.onScreenSettled(AiSessionController.Change.Navigation)
        runCurrent()
        assertTrue(session.state is AiSessionState.Guiding)
    }

    @Test
    fun `a pointed-at control that vanishes takes its words and outline down at once`() = runTest {
        val host = Recorder(seen(observation("o1")))
        val session = session(host, FakeClient(tap("o1")))
        session.start("make the text bigger")
        assertTrue(session.state is AiSessionState.Guiding)
        val clearedBefore = host.cleared
        session.onTargetVanished()
        assertEquals(AiSessionState.Observing, session.state)
        assertTrue(host.cleared > clearedBefore)
    }

    @Test
    fun `a question is looked at again shortly after a tap, and comes down if a new page is up`() = runTest {
        val host = Recorder(seen(observation("o1")), seen(observation("o2")))
        host.shape = com.aaravsekhri.dot.guide.ai.ScreenShape("com.example", 7)
        val client = FakeClient(decided(AiDecision.Ask("o1", "What would you like to do?", listOf("Share location", "Continue"))), tap("o2"))
        val session = session(host, client)
        session.start("find places near me")
        assertTrue(session.state is AiSessionState.AskingClarification)
        session.onScreenSettled(AiSessionController.Change.Action)
        // The prompt the tap raised sends no event; it is found by looking again.
        host.shape = com.aaravsekhri.dot.guide.ai.ScreenShape("withheld", 99)
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(session.state is AiSessionState.AskingClarification)
    }
}
