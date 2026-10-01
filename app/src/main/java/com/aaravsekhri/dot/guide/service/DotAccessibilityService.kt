package com.aaravsekhri.dot.guide.service

import com.aaravsekhri.dot.guide.ai.mayReadStructure
import com.aaravsekhri.dot.guide.ai.finger
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.engine.GuideEngine
import com.aaravsekhri.dot.guide.model.FailureReason
import com.aaravsekhri.dot.guide.model.GuideSessionState
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.matching.TargetIdentityGate
import com.aaravsekhri.dot.guide.motion.MotionClock
import com.aaravsekhri.dot.guide.follow.TargetProbe
import com.aaravsekhri.dot.guide.overlay.HighlightMetrics
import com.aaravsekhri.dot.guide.positional.PositionalGuideController
import com.aaravsekhri.dot.guide.positional.PositionalGuideState
import com.aaravsekhri.dot.guide.positional.ScanCandidate
import com.aaravsekhri.dot.guide.positional.ScreenContext
import com.aaravsekhri.dot.guide.motion.TargetPose
import com.aaravsekhri.dot.guide.motion.OutlineVisibilityPolicy
import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.continuity.ScreenChangeKind
import com.aaravsekhri.dot.guide.continuity.ScreenContinuityClassifier
import com.aaravsekhri.dot.guide.continuity.ScreenFingerprint
import com.aaravsekhri.dot.guide.tracking.ReconcileOutcome
import com.aaravsekhri.dot.guide.tracking.RenderedTargetTracker
import com.aaravsekhri.dot.guide.visual.VerifiedTarget
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.Insets
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.model.stepIndexOrNull
import com.aaravsekhri.dot.guide.model.StepAction
import com.aaravsekhri.dot.guide.model.isActive
import com.aaravsekhri.dot.guide.overlay.GuideOverlayController
import com.aaravsekhri.dot.guide.overlay.OverlayCommands

/**
 * Dot's accessibility service.
 *
 * What it does: reads the names and positions of visible controls while a
 * guide is running, and draws a highlight and an instruction card over them.
 *
 * What it deliberately never does: press anything. There is no
 * `performAction(ACTION_CLICK)` and no `dispatchGesture` anywhere in Dot, and
 * the service does not declare `canPerformGestures`. Every tap and every drag
 * is the person's own. That is the whole point of the product, so it is
 * enforced by simply never writing the code that would do otherwise.
 *
 * Screen content is read only while [GuideSessionState.isActive], only while
 * the foreground app is one the current step expects, and never leaves this
 * process.
 */
class DotAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: GuideEngine? = null
    private var overlays: GuideOverlayController? = null
    private lateinit var preferences: GuidePreferences

    /** Timestamps of the last handled event per type, for debouncing. */
    private val lastHandledAt = HashMap<Int, Long>()

    /** A re-inspection queued while events were arriving in a burst. */
    private var pendingInspection: Runnable? = null

    /**
     * Bumped every time the page changes or the step moves on.
     *
     * A scan scheduled before the bump carries the old value and is dropped
     * when it finally runs, so a delayed look at the previous page can never
     * come back and redraw a target that is no longer there.
     */
    private var searchGeneration: Long = 0L

    /** The page the last accepted scan belonged to. */
    private var lastPageSignature: String? = null

    /** The repeating pass that follows a target while a scroll is happening. */
    private var trackingJob: Runnable? = null

    /** When the current tracking burst should stop, or 0 when not tracking. */
    private var trackingUntil: Long = 0L

    /**
     * Moves the drawn outline with the content, from scroll deltas.
     *
     * Separate from the semantic tracker on purpose: this one is cheap and runs
     * at frame cadence, that one is authoritative and runs far less often.
     */
    private val rendered = RenderedTargetTracker()

    /**
     * The single owner of everything drawn over another app.
     *
     * Nothing else in this class may show or hide a highlight or a cue; they
     * feed events in and call [renderPositional]. Four independent owners of
     * those pixels is what the flickering was.
     */
    private val positional = PositionalGuideController()

    /** When the page last moved, for the idle detector. */
    private var lastScrollAtNanos: Long = Long.MIN_VALUE

    /**
     * Asks the target where it is, once per scroll event.
     *
     * This is not the old follower. Nothing runs between requests, there is no
     * timer, and what comes back never moves an outline — it decides which of
     * four short sentences the guidance card shows. A screen nobody is touching
     * costs nothing.
     */
    private val probe = TargetProbe()

    /** The control the probe is holding, so re-settling on it is a no-op. */
    private var probeIdentity: com.aaravsekhri.dot.guide.matching.TargetIdentity? = null

    /** The list the target lives in, from the last full reading. */
    private var lastContentBounds: Bounds? = null

    /** The last positional summary logged, so only changes are traced. */
    private var lastPositionalSummary: String? = null

    /** Consecutive readings too thin to judge, so waiting cannot go on forever. */
    private var skippedThinScans = 0

    /** Where the container's scroll stood, for telling real movement from noise. */
    private var lastScrollPosition: Int = Int.MIN_VALUE

    // ------------------------------------------------ general, AI-guided help
    //
    // A general session runs alongside the tutorial engine rather than through
    // it. The two want different things from the same machinery: a tutorial
    // knows its steps and its destinations in advance, a general session knows
    // only what the person asked for and what is on screen now. What they
    // share is everything below the decision — the positional owner, the
    // overlay, the scrolling behaviour — and that sharing is the point.

    private val aiScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )

    /** One frame of a settled screen, occasionally, when the platform allows. */
    private val screenshots by lazy {
        com.aaravsekhri.dot.guide.ai.ScreenshotSource(this)
    }

    private var aiSession: com.aaravsekhri.dot.guide.ai.AiSessionController? = null
    private var aiHost: com.aaravsekhri.dot.guide.ai.AiGuideHost? = null

    /** The control a general session is pointing at, re-offered as the page moves. */
    private var aiTarget: com.aaravsekhri.dot.guide.ai.ValidatedTarget? = null
    private var aiTargetIdentity: com.aaravsekhri.dot.guide.matching.TargetIdentity? = null

    /** True while a general session may read whatever app is in front. */
    private val aiActive: Boolean
        get() = aiSession?.state?.let {
            com.aaravsekhri.dot.guide.ai.mayObserve(it)
        } == true

    /**
     * Until when relayout counts as settling rather than scrolling.
     *
     * Set when a control's value changes. Everything the page does for a moment
     * afterwards is a consequence of that change, including the scroll events
     * Android sends as the list re-measures itself — and treating those as a
     * scroll is what used to take the outline down and replay Pebble's entrance
     * on every notch of the font-size slider.
     */
    private var settlingAfterValueChangeUntil: Long = 0L

    /**
     * The furthest that window may ever be pushed.
     *
     * A re-layout finishes; a person scrolling does not. Without a ceiling the
     * two are indistinguishable, because both produce a steady stream of scroll
     * events and each one was extending the window that suppresses them.
     */
    private var settlingHardDeadline: Long = 0L

    /** Set only by an acceptance run against the test fixture. */
    private var debugFollowOwnPackage = false

    /** What the screen looked like last time it was read. */
    private var lastFingerprint: ScreenFingerprint? = null

    /** A scroll seen since the last classification, for continuity evidence. */
    private var scrollSinceLastScan = false

    /** Until when a rebuilt page may still reclaim its target without a pop. */
    private var continuityWindowEndsAt: Long = 0L

    // The experimental screen-capture tracker is deliberately absent from this
    // class. Its source is preserved in the `guide.visual` package as
    // research, and nothing here can reach it: an ordinary guide starts no
    // capture, requests no consent, and runs no foreground service.
    //
    // Following a scroll is done by asking the control where it is, in
    // `guide.follow`. Three earlier attempts — rescanning the whole tree,
    // translating by scroll deltas, and predicting from captured frames — each
    // drew a rectangle from coordinates that were already out of date. The
    // fourth does not estimate anything: it reads the answer, and when the
    // answers stop it stops drawing.

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        instance = this
        GuideLog.init(this)
        preferences = GuidePreferences(this)
        overlays = GuideOverlayController(this, commands).apply {
            // Dot's own screen makes room for a card kept over it.
            onCardOverDot = GuideCoordinator::setCardOverDot
        }

        // A guide asked for while the service was still being switched on.
        GuideCoordinator.consumeRequest()?.let(::startGuide)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A general session has no tutorial engine behind it, so it is fed
        // before the engine gate below rather than through it.
        if (event != null && aiSession != null) {
            routeToGeneralSession(event)
        }

        val currentEngine = engine ?: return
        if (!currentEngine.state.isActive) return
        if (event == null) return

        // Nothing is inspected while the device is locked.
        if (isDeviceLocked()) {
            overlays?.hideAll()
            return
        }

        val packageName = event.packageName?.toString()

        // Dot's own overlay windows generate accessibility events as they are
        // added and removed. Acting on those would be a feedback loop: showing
        // a cue would look like navigation, which would take the cue down,
        // which would look like navigation again.
        //
        // The exception is an acceptance run against the test fixture, which is
        // part of Dot's own package and must behave like any other app for the
        // measurement to mean anything. Even then only the two event types a
        // scrolling list produces are let through — an overlay appearing still
        // cannot be mistaken for the page changing — and the flag can only be
        // set from a debug-manifest receiver, on a debuggable build.
        if (packageName == this.packageName) {
            val fixtureEvent = debugFollowOwnPackage &&
                (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            if (!fixtureEvent) return
        }

        // A dialog that takes the whole screen for a decision gets it to
        // itself, and Dot stands down entirely.
        if (packageName != null && packageName in FOREGROUND_DIALOG_PACKAGES) {
            GuideLog.d("service") { "standing down for $packageName" }
            overlays?.hideAll()
            return
        }

        // System UI is a different matter. It emits events constantly — the
        // status bar, the volume dialog, and every re-render after a font-size
        // change — without ever taking the foreground from the app being
        // guided. Tearing the overlay down for those was what made the
        // highlight vanish and pop back on every notch of the slider. The
        // events are simply not ours to act on.
        if (packageName != null && packageName in IGNORED_PACKAGES) return

        when (event.eventType) {
            // A tap the person made. Never debounced: this is the primary
            // evidence that a step is complete, and dropping one would leave
            // the guide stuck on a step already done.
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)

            // The person scrolled.
            //
            // A scroll cue has done its job the moment they start, so it comes
            // down at once. An outline that is already up is left alone: the
            // stabilizer holds it at its last confirmed position and moves it
            // when the list stops, rather than flickering off and on — and
            // reappearing would replay Pebble's entrance for a target that
            // never went away.
            // The person scrolled.
            //
            // The overlay follows the target while they do it, rather than
            // waiting for the list to stop — which is what made the highlight
            // lag behind the setting it points at. Any scroll cue stays up
            // until the tracker reports the target genuinely in view; taking it
            // away on the first flick would remove it while they are still on
            // their way to the thing.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onContentScrolled(event)

            // A slider or similar was moved by the person.
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                if (shouldHandle(event.eventType, SCROLL_DEBOUNCE_MS)) handleAdjustOrRefresh()
            }

            // A new page. Whatever Dot was pointing at belongs to the screen
            // being replaced, so it comes down now rather than when the next
            // scan happens to notice.
            //
            // Only this event type means navigation. TYPE_WINDOWS_CHANGED fires
            // for any window on the device, including Dot's own overlays, so it
            // is deliberately not subscribed to.
            // A page opened — or the one in front was rebuilt. This event
            // cannot tell the difference, and treating it as navigation is what
            // made a font-size change look like leaving the screen. The
            // classifier decides, on the next read.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                fruitlessScans = 0
                GuideLog.d("continuity") {
                    "window state changed pkg=${event.packageName} cls=${event.className}"
                }
                openContinuityWindow()
                scheduleInspection(PAGE_SETTLE_MS)
            }

            // The same page changing shape. Coalesced, because Settings emits
            // these in bursts; a burst is one look, not twenty.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Never navigation on its own. Settings emits these constantly,
                // including for a slider's own value changing.
                if (trackingUntil > System.currentTimeMillis()) return
                scheduleInspection()
            }

            // Dot subscribes to only the event types above. Anything else the
            // framework sends is not needed to run a guide and is ignored
            // without being read.
            else -> Unit
        }
    }

    /**
     * What a general session makes of an accessibility event.
     *
     * Only timing and kind, never content: what to say is decided from a
     * reading taken on purpose, not from whatever an event carried. What
     * matters here is telling apart the changes that deserve a new decision
     * from the ones that do not — Dot's own overlays, the keyboard, a list
     * scrolling, a page updating in place, and a genuinely different screen
     * each get a different answer, and confusing them is how a guide either
     * spends a request on every animation or never notices it has arrived.
     */
    private fun routeToGeneralSession(event: AccessibilityEvent) {
        val session = aiSession ?: return
        val packageName = event.packageName?.toString()
        // Dot's own windows, including every overlay. Showing a card must never
        // look like the screen changing, or each card would summon the next.
        // The one exception is Dot's own activity coming to the front: that
        // is the person leaving the app being guided, and an outline left
        // over from that app was drawn on top of Dot's own screen.
        if (packageName == this.packageName) {
            val activity = event.className?.toString().orEmpty()
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && activity.endsWith("Activity")) {
                val fixture = GuideLog.isEnabled && activity.contains(".experiment.Fixture")
                if (fixture != ownFixtureInFront) GuideLog.d("ai") { "fixture in front: $fixture" }
                ownFixtureInFront = fixture
            }
            // The debug sign-in fixture stands in for another app, so its
            // events are routed as one — only its own window's, never an
            // overlay's. Debug builds only.
            if (ownFixtureInFront) {
                val active = runCatching { rootInActiveWindow?.windowId }.getOrNull()
                // A window over the fixture whose contents are withheld (the
                // system's permission prompt) still counts as a change of page.
                val overFixture = active == null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    withheldInFront() != null
                if (!overFixture && (active == null || event.windowId != active)) return
            } else {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    activity.startsWith("com.aaravsekhri.dot.") && activity.endsWith("Activity")
                ) {
                    GuideLog.d("ai") { "Dot's own screen came forward: ${activity.substringAfterLast('.')}" }
                    onDotBroughtForward(session)
                }
                return
            }
        }
        if (packageName != null && packageName in IGNORED_PACKAGES) return

        if (packageName != null && packageName == keyboardPackage()) {
            val visible = keyboardVisible()
            updateKeyboardInset()
            if (visible != lastKeyboardVisible) {
                lastKeyboardVisible = visible
                session.onKeyboardChanged(visible)
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // A tap on the very field a typing step points at is the step
                // under way — putting the cursor there — not a new screen to
                // abandon it for.
                if (clickedOnTypingTarget(event, session)) {
                    scheduleGeneralSettle(com.aaravsekhri.dot.guide.ai.AiSessionController.Change.Content)
                    return
                }
                // A tap is evidence that something happened, and nothing more.
                // Whether it was the right thing is decided by looking at the
                // screen it produced.
                session.onUserActed()
                session.onScreenChanged()
                scheduleGeneralSettle(com.aaravsekhri.dot.guide.ai.AiSessionController.Change.Action)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                aiScreenGeneration++
                session.onScreenChanged()
                scheduleGeneralSettle(com.aaravsekhri.dot.guide.ai.AiSessionController.Change.Navigation)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Handled on the phone, through the same intentional-scrolling
                // path as a tutorial: the outline comes off at the first
                // movement, the card stays up and says what is true — "Stop
                // here", which way the target lies, or "Stop for a moment" —
                // from readings of the target itself. After the page settles
                // the target is re-found locally and confirmed before the
                // outline returns; only if it has gone is a request sent.
                if (aiTarget != null) onContentScrolled(event)
                // Movement, not a new screen: a request in flight carries on,
                // and its answer is checked against the page once it settles.
                session.onMovement()
                scheduleGeneralSettle(com.aaravsekhri.dot.guide.ai.AiSessionController.Change.Scroll)
            }

            // Typing, and the cursor moving to another box. Used only as a
            // cue to look again once things are still — the event's own text
            // is never read — so a typing step can notice its words have
            // arrived, and sign-in help can notice the next box has the cursor.
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> scheduleGeneralSettle(com.aaravsekhri.dot.guide.ai.AiSessionController.Change.Content)

            // Not a reason to abandon a request in flight: pages animate,
            // clocks tick, progress bars fill. Only a reason to look again
            // once things are still.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // But an outline must never stay on a control that has gone:
                // "Sign in" replaced by "Checking info…" in the same window.
                // Checked at once, cheaply, on the phone; the settle that
                // follows decides what comes next.
                withdrawVanishedTarget(session)
                scheduleGeneralSettle(com.aaravsekhri.dot.guide.ai.AiSessionController.Change.Content)
            }

            else -> Unit
        }
    }

    /** The last time the outlined control was looked for after a content change. */
    private var vanishCheckAt = 0L

    /**
     * Takes the outline and its words down the moment the control they point
     * at is no longer on the screen — not when the page next settles, which
     * on a busy page can be a second or more. Looks only for a visible node in
     * the same window with the control's own name; reads nothing else.
     */
    private fun withdrawVanishedTarget(session: com.aaravsekhri.dot.guide.ai.AiSessionController) {
        val target = aiTarget ?: return
        if (target.reading || target.naming.isBlank()) return
        if (session.state !is com.aaravsekhri.dot.guide.ai.AiSessionState.Guiding) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - vanishCheckAt < VANISH_CHECK_MS) return
        vanishCheckAt = now
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        if (root.windowId != target.windowId && target.windowId != -1) return
        val wanted = com.aaravsekhri.dot.guide.matching.TextNormalizer.normalize(target.naming)
        val present = runCatching {
            root.findAccessibilityNodeInfosByText(target.naming).any { n ->
                n.isVisibleToUser && (
                    com.aaravsekhri.dot.guide.matching.TextNormalizer.normalize(n.text?.toString().orEmpty()) == wanted ||
                        com.aaravsekhri.dot.guide.matching.TextNormalizer.normalize(n.contentDescription?.toString().orEmpty()) == wanted
                    )
            }
        }.getOrDefault(true)
        if (present) return
        GuideLog.d("ai") { "the outlined control has gone from the screen; taking the step down at once" }
        clearAiTarget(samePage = true)
        session.onTargetVanished()
    }

    /**
     * Dot itself is now in front, mid-session.
     *
     * Everything the session had on screen comes down: the outline belongs to
     * the app left behind, and the card sat over Dot's own screen — over the
     * question being answered, and over the box a new request is typed into.
     * Anything in flight is abandoned. The session is otherwise left as it
     * was, and picks up by itself from whatever screen the person goes to
     * next.
     */
    private fun onDotBroughtForward(session: com.aaravsekhri.dot.guide.ai.AiSessionController) {
        GuideLog.d("ai") { "Dot's own screen came forward" }
        aiScreenGeneration++
        // The outline belongs to the app left behind. What the card does is
        // the session's to decide: step aside, keep saying how to leave Dot,
        // or show the way back to the app.
        clearAiTarget(samePage = false)
        session.onDotInFront()
    }

    private fun clickedOnTypingTarget(
        event: AccessibilityEvent,
        session: com.aaravsekhri.dot.guide.ai.AiSessionController,
    ): Boolean {
        val kind = (session.state as? com.aaravsekhri.dot.guide.ai.AiSessionState.Guiding)?.instruction?.kind
        if (kind != com.aaravsekhri.dot.guide.ai.AiSessionController.KIND_TYPE &&
            kind != com.aaravsekhri.dot.guide.ai.AiSessionController.KIND_AUTH_TYPE
        ) {
            return false
        }
        val target = aiTarget?.bounds ?: return false
        val rect = android.graphics.Rect()
        return try {
            event.source?.getBoundsInScreen(rect) ?: return false
            target.contains(Bounds(rect.left, rect.top, rect.right, rect.bottom)) ||
                Bounds(rect.left, rect.top, rect.right, rect.bottom).contains(target)
        } catch (_: Exception) {
            false
        }
    }

    /** True while a debug fixture activity is in front. Never set in release builds. */
    private var ownFixtureInFront = false

    private var generalSettle: Runnable? = null
    private var settlePendingSince = 0L
    private var pendingChange: com.aaravsekhri.dot.guide.ai.AiSessionController.Change? = null

    /** Rises on every window change while a general session runs. */
    private var aiScreenGeneration: Long = 0L

    private var lastKeyboardVisible = false

    /**
     * Waits for the screen to stop before asking about it.
     *
     * Deliberately longer than the scrolling settle: that one decides when an
     * outline may return, which wants to feel immediate, while this one
     * decides when to spend a request, which wants to be sure. Changes that
     * arrive together are merged, and the most significant wins: a page that
     * navigated and then animated is a navigation.
     */
    private fun scheduleGeneralSettle(change: com.aaravsekhri.dot.guide.ai.AiSessionController.Change) {
        val pending = pendingChange
        pendingChange = if (pending == null || change.ordinal < pending.ordinal) change else pending
        val now = android.os.SystemClock.uptimeMillis()
        if (pending == null) settlePendingSince = now
        // A progress bar ticking every half second never lets the page be
        // "still" for the full settle time, and the session sat on a stale
        // step until the install finished. Content that keeps changing is
        // looked at anyway, every so often. Scrolling is not: it has its own
        // steady message and waits for the finger to stop.
        val contentOnly = pendingChange == com.aaravsekhri.dot.guide.ai.AiSessionController.Change.Content
        if (contentOnly && generalSettle != null && now - settlePendingSince >= GENERAL_SETTLE_MAX_MS) return
        generalSettle?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            generalSettle = null
            val settled = pendingChange ?: return@Runnable
            pendingChange = null
            val session = aiSession ?: return@Runnable
            // The keyboard closing usually announces itself only as the app
            // resizing, so its state is checked here as well as on its own
            // events. A resize that is only the keyboard is not new content.
            val keyboard = keyboardVisible()
            updateKeyboardInset()
            if (keyboard != lastKeyboardVisible) {
                lastKeyboardVisible = keyboard
                session.onKeyboardChanged(keyboard)
                // During a typing step the page resizing around the keyboard
                // is not news. Anywhere else it may well be: pressing Search
                // closes the keyboard and brings the results in the same
                // moment, and treating that as "only the keyboard" left the
                // card saying "press the search key" over the results.
                val typing = (session.state as? com.aaravsekhri.dot.guide.ai.AiSessionState.Guiding)
                    ?.instruction?.kind in TYPING_KINDS
                if (settled == com.aaravsekhri.dot.guide.ai.AiSessionController.Change.Content && typing) {
                    return@Runnable
                }
            }
            session.onScreenSettled(settled)
        }
        generalSettle = runnable
        mainHandler.postDelayed(runnable, GENERAL_SETTLE_MS)
    }

    /**
     * The keyboard's action key, when the keyboard names it. Only a key whose
     * whole name is one of a few fixed action words counts; nothing else on
     * the keyboard — suggestions, the words being typed — is looked at.
     */
    private fun keyboardActionKey(): com.aaravsekhri.dot.guide.ai.KeyboardAction? = try {
        val root = windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root
        var found: com.aaravsekhri.dot.guide.ai.KeyboardAction? = null
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 12) return
            val name = node.contentDescription?.toString()?.trim()?.lowercase()
            found = when (name) {
                "search" -> com.aaravsekhri.dot.guide.ai.KeyboardAction.Search
                "send" -> com.aaravsekhri.dot.guide.ai.KeyboardAction.Send
                "go" -> com.aaravsekhri.dot.guide.ai.KeyboardAction.Go
                "done" -> com.aaravsekhri.dot.guide.ai.KeyboardAction.Done
                "next" -> com.aaravsekhri.dot.guide.ai.KeyboardAction.Next
                "enter", "return" -> com.aaravsekhri.dot.guide.ai.KeyboardAction.Enter
                else -> null
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        GuideLog.d("ai") { "keyboard action key: ${found ?: "not labelled"}" }
        found
    } catch (_: Exception) {
        null
    }

    /** The package of the input method window, if one is on screen. */
    private fun keyboardPackage(): String? = try {
        windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.root?.packageName?.toString()
            ?: lastKeyboardPackage
    } catch (_: Exception) {
        lastKeyboardPackage
    }?.also { lastKeyboardPackage = it }

    private var lastKeyboardPackage: String? = null

    private fun keyboardVisible(): Boolean = keyboardTop() != null

    /** The keyboard's top edge in screen pixels, or null when it is closed. */
    private fun keyboardTop(): Int? = try {
        windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { window ->
                val rect = android.graphics.Rect()
                window.getBoundsInScreen(rect)
                rect.top.takeIf { !rect.isEmpty }
            }
    } catch (_: Exception) {
        null
    }

    /**
     * Keeps Dot's card off the keyboard.
     *
     * A keyboard read while it is still sliding in reports where it is, not
     * where it will stop, and a password keyboard is drawn securely and sends
     * no events of its own afterwards — the card was left lifted a quarter of
     * the way, over the keys. So a change is looked at again a few times,
     * briefly, until two readings agree. Bounded: this settles a measurement,
     * it does not follow anything.
     */
    private fun updateKeyboardInset() {
        currentViewport()?.let { overlays?.setViewport(it) }
        val top = keyboardTop()
        overlays?.setKeyboardTop(top)
        if (top != lastKeyboardTop) {
            lastKeyboardTop = top
            keyboardRechecks = KEYBOARD_RECHECKS
            mainHandler.removeCallbacks(keyboardRecheck)
            mainHandler.postDelayed(keyboardRecheck, KEYBOARD_RECHECK_MS)
        }
    }

    private var lastKeyboardTop: Int? = null
    private var keyboardRechecks = 0

    private val keyboardRecheck: Runnable = Runnable {
        if (keyboardRechecks-- <= 0) return@Runnable
        val before = lastKeyboardTop
        updateKeyboardInset()
        if (lastKeyboardTop == before && keyboardRechecks > 0) {
            // Unchanged: one more look confirms it has stopped.
            keyboardRechecks = minOf(keyboardRechecks, 1)
            mainHandler.postDelayed(keyboardRecheck, KEYBOARD_RECHECK_MS)
        }
    }

    /**
     * The page is moving under the person's finger.
     *
     * The outline stays exactly where it is — on its row — and the follower
     * moves it as the row moves. This used to take the highlight down for the
     * whole of a scroll and put a cue up instead, which was the honest thing to
     * do when nothing could say where the target was mid-gesture. Something can
     * now: asking the control itself returns a new position roughly every
     * display frame, a few milliseconds after the frame it describes.
     *
     * So a scroll event does very little here. It tells the follower to stop
     * idling, records which way the page went in case a cue is later earned,
     * and asks for a full reading once the page has stopped.
     */
    private fun onContentScrolled(event: AccessibilityEvent) {
        scrollSinceLastScan = true

        // A control whose value just changed re-lays out the page around it,
        // and the framework reports that as a scroll. It is not one: nobody
        // moved the page. Each such event extends the window a little, so the
        // long burst a large font produces is treated as one settling rather
        // than dissolving into a scroll partway through.
        val nowMs = System.currentTimeMillis()
        if (nowMs < settlingAfterValueChangeUntil) {
            // Each event extends the window a little, so the long burst a large
            // font produces is treated as one settling rather than dissolving
            // into a scroll partway through — but never past the hard deadline.
            // Without that cap, events arriving every 120ms each pushed the
            // window 500ms further out, and a person who changed the font size
            // and then scrolled found scrolling ignored for as long as they
            // kept doing it.
            settlingAfterValueChangeUntil =
                minOf(nowMs + VALUE_CHANGE_RESETTLE_MS, settlingHardDeadline)
            GuideLog.d("scroll") { "ignored: the page is settling after a value change" }
            scheduleInspection(SETTLE_AFTER_SCROLL_MS)
            return
        }

        // A scroll event whose container has not actually moved is not the
        // person scrolling. Android sends these as lists re-measure, and acting
        // on one takes the outline down over a page nobody has touched.
        // Lists like RecyclerView report the same scrollY on every event and
        // put the movement in the delta instead: a delta is movement too.
        // Seen in Google Maps, where every scroll after the first was dropped
        // and the outline stayed put over whichever row slid under it.
        val position = scrollPositionOf(event)
        val delta = scrollDeltaY(event) != 0 || scrollDeltaX(event) != 0
        if (position != Int.MIN_VALUE) {
            if (position == lastScrollPosition && !delta) {
                GuideLog.d("scroll") { "ignored: the container has not moved" }
                return
            }
            lastScrollPosition = position
        } else if (!delta) {
            GuideLog.d("scroll") { "ignored: no movement reported" }
            return
        }

        lastScrollAtNanos = MotionClock.nowNanos()
        fruitlessScans = 0
        GuideLog.d("scrollevt") {
            "dy=${scrollDeltaY(event)} dx=${scrollDeltaX(event)} " +
                "scrollY=${event.scrollY} maxY=${event.maxScrollY} " +
                "from=${event.fromIndex} to=${event.toIndex} count=${event.itemCount} " +
                "cls=${event.className}"
        }

        // The outline comes off here, in one transition, before anything is
        // known about where the target has got to.
        positional.onScrollActivity(lastScrollAtNanos)
        renderPositional()

        // And then, and only then, we go and find out where it is — so the card
        // says something true rather than something derived from which way the
        // finger went.
        requestEvidence()

        scheduleIdleCheck()
        scheduleInspection(SETTLE_AFTER_SCROLL_MS)
    }

    /**
     * Everything the page does for a moment now is a consequence of a value
     * changing, not of anybody moving it.
     */
    private fun openValueChangeWindow() {
        val now = System.currentTimeMillis()
        settlingAfterValueChangeUntil = now + VALUE_CHANGE_SETTLE_MS
        settlingHardDeadline = now + VALUE_CHANGE_MAX_MS
    }

    /**
     * Asks the target where it is, for the guidance card.
     *
     * One request per scroll event, and the probe drops it if one is already in
     * flight. Nothing is scheduled and nothing repeats: when the scroll events
     * stop, this stops with them.
     */
    private fun requestEvidence() {
        val deliver: (Bounds?) -> Unit = { bounds ->
            mainHandler.post {
                GuideLog.d("evidence") { "the target reads $bounds" }
                // A target that has arrived behind Dot's own instruction card
                // is not reachable, and the honest reading says so. Moving the
                // card is a better answer than telling somebody to stop at
                // something they cannot see.
                if (bounds != null && bounds.right > bounds.left && bounds.bottom > bounds.top) {
                    if (overlays?.ensureCardAvoids(bounds.inflate(highlightPaddingPx())) == true) {
                        // Tell the state machine where the card went before it
                        // judges the reading, or it decides the row is covered
                        // using the card's old position and says "stop for a
                        // moment" for the one reading it takes to catch up.
                        positional.onOccludersChanged(dotOccluders())
                    }
                }
                positional.onScrollEvidence(bounds, MotionClock.nowNanos())
                renderPositional()
            }
        }

        if (probe.isHolding) {
            probe.request(deliver)
            return
        }

        // Nothing held yet — this is somebody scrolling towards a row the guide
        // has not outlined before. The step knows what the row is called, and
        // the platform can find a node by its wording in one call, so the card
        // can still say something true.
        val spec = engine?.currentStep?.target
        val labels = spec?.let {
            (it.identityLabels.takeIf { names -> names.isNotEmpty() } ?: it.labels) +
                it.contentDescriptions
        }.orEmpty().filter { it.isNotBlank() }
        if (labels.isEmpty()) {
            positional.onScrollEvidence(null, MotionClock.nowNanos())
            renderPositional()
            return
        }
        val container = lastContentBounds ?: currentViewport()
        probe.requestLookup(
            root = { try { rootInActiveWindow } catch (_: Exception) { null } },
            labels = labels,
            maxHeightPx = ((container?.height ?: 0) * MAX_ROW_FRACTION).toInt(),
            onResult = deliver,
        )
    }

    /**
     * How far the container has scrolled, where the platform says.
     *
     * Used only to tell a real movement from a re-layout that Android happens
     * to report as one. It is never used to work out where the target is:
     * which way a page moved says nothing about where a control is, and an
     * arrow derived from it would be a guess.
     */
    private fun scrollPositionOf(event: AccessibilityEvent): Int =
        if (event.scrollY != -1 || event.scrollX != -1) {
            event.scrollY * 31 + event.scrollX
        } else {
            Int.MIN_VALUE
        }

    /**
     * Which way the content went, where the platform says so.
     *
     * A positive `scrollDeltaY` accompanies a rising `scrollY` and carries
     * content upward, which means the person is travelling down the page.
     */
    private fun scrollDirectionOf(event: AccessibilityEvent): ScrollDirection? {
        val deltaY = scrollDeltaY(event)
        return when {
            deltaY > 0 -> ScrollDirection.Down
            deltaY < 0 -> ScrollDirection.Up
            else -> null
        }
    }

    /**
     * Watches for the page to settle.
     *
     * Rescheduled on every scroll event, so the timer only fires once the
     * events have genuinely stopped rather than between two of them.
     */
    private fun scheduleIdleCheck() {
        mainHandler.removeCallbacks(idleCheck)
        mainHandler.postDelayed(idleCheck, IDLE_AFTER_SCROLL_MS)
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            positional.onTick(MotionClock.nowNanos())
            renderPositional()
            // Still moving: the threshold has not been reached yet. Come back,
            // rather than waiting for a scroll event that may never arrive —
            // a gesture that ends leaves the state machine mid-scroll for ever
            // if the only thing that can settle it is another event.
            if (positional.state.let {
                    it is PositionalGuideState.UserScrollingTargetAbove ||
                        it is PositionalGuideState.UserScrollingTargetBelow ||
                        it is PositionalGuideState.UserScrollingStopHere ||
                        it is PositionalGuideState.UserScrollingUncertain
                }
            ) {
                mainHandler.postDelayed(this, IDLE_RECHECK_MS)
                return
            }
            // Settled. The card holds whatever it was saying while the target
            // is checked; the confirmation the state machine needs cannot
            // arrive without a reading.
            inspectNow()
        }
    }

    /**
     * Puts the state machine's decision on screen.
     *
     * Called after every input and nowhere else. There is exactly one route
     * from state to pixels, which is what stopped the flicker.
     */
    private fun renderPositional() {
        currentViewport()?.let { overlays?.setViewport(it) }
        val output = positional.output()
        val occluders = dotOccluders()
        positional.onOccludersChanged(occluders)
        overlays?.setOccluders(occluders)
        overlays?.setInsets(systemInsets())
        overlays?.applyPositional(output)

        // On change only. This runs on every display frame while a target is
        // being followed, and a line per frame would bury the transitions that
        // actually matter under three thousand identical ones.
        val summary = "${positional.state::class.simpleName} " +
            "outline=${output.outline != null} card=${output.guidance?.message} " +
            "key=${output.appearanceKey} haptics=${output.hapticPulses}"
        if (summary != lastPositionalSummary) {
            lastPositionalSummary = summary
            GuideLog.d("positional") { "state=$summary gen=${positional.generation}" }
        }
    }

    /** Dot's own opaque layers, in screen pixels. */
    private fun dotOccluders(): List<Bounds> = listOfNotNull(
        overlays?.currentCardBounds(),
        overlays?.currentGuidanceBounds(),
        // The keyboard hides whatever is behind it as surely as the card
        // does. A target under it is not outlined — the outline would be
        // drawn over the keys — and Pebble is never put on it.
        keyboardBounds(),
    )

    /** The keyboard's window, while it is open. */
    private fun keyboardBounds(): Bounds? = keyboardTop()?.let { top ->
        val (width, height) = screenSize()
        Bounds(0, top, width, height).takeIf { !it.isEmpty }
    }

    /**
     * Scroll distance, where the platform reports it.
     *
     * Available from API 28. Verified on device: a positive `scrollDeltaY`
     * accompanies a rising `scrollY` and carries content upward, so the drawn
     * target moves by its inverse.
     */
    private fun scrollDeltaX(event: AccessibilityEvent): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            event.scrollDeltaX.takeIf { it != Int.MIN_VALUE } ?: 0
        } else {
            0
        }

    private fun scrollDeltaY(event: AccessibilityEvent): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            event.scrollDeltaY.takeIf { it != Int.MIN_VALUE } ?: 0
        } else {
            0
        }

    /**
     * The device configuration changed — font scale, density, orientation.
     *
     * Changing the system font size lands here, and it is emphatically not a
     * reason to tear the guide down: the person is still on the page that
     * changed it. Density-dependent measurements are recomputed on the next
     * read; the session, the step, the target and what is drawn all stay.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        GuideLog.d("continuity") {
            "configuration changed fontScale=${newConfig.fontScale} " +
                "densityDpi=${newConfig.densityDpi}; keeping the overlay scene"
        }
        // The definitive signal that what follows is a re-layout rather than
        // somebody moving the page. Tying the suppression to this as well as to
        // the tap covers the case the tap alone did not: a font change takes
        // about a second to work through the list, and a notch every second and
        // a half left a gap in which the tail of one relayout arrived after the
        // window opened by its own tap had closed, and was read as a scroll.
        openValueChangeWindow()
        openContinuityWindow()
        scheduleInspection(PAGE_SETTLE_MS)
    }

    override fun onInterrupt() {
        // The system asks every accessibility service to stop what it is doing
        // from time to time — it does not mean the guide is over.
        //
        // So anything pointing at a place on screen comes down at once, and
        // then the guide re-establishes itself from its own state. Treating
        // this as the end of the session was what left a live guide with no
        // overlay at all and nothing to bring it back: the session was still
        // running, the screen was static, and so no further event ever arrived
        // to redraw it.
        GuideLog.d("service") { "onInterrupt; clearing overlays and re-rendering" }
        overlays?.clearTargetLayers()
        if (engine?.state?.isActive == true) {
            renderWithoutScreen()
            scheduleInspection(AFTER_INTERRUPT_MS)
        }
    }

    override fun onDestroy() {
        isConnected = false
        instance = null
        mainHandler.removeCallbacks(idleCheck)
        mainHandler.removeCallbacks(keyboardRecheck)
        cancelPendingInspection()
        cancelTrackingBurst()
        // Cut off, not ended: the person can carry on from where they were.
        // Stopped before the probe's thread goes: stopping releases the
        // target, and that is a message to the probe.
        GuideLog.d("ai") { "the accessibility service is going; the session is saved to carry on later" }
        aiSession?.stop(keepCheckpoint = true)
        probe.shutdown()
        GuideCoordinator.setGeneralSessionActive(false)
        aiScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        overlays?.destroy()
        overlays = null
        engine?.fail(FailureReason.ServiceUnavailable)
        engine = null
        GuideCoordinator.clear()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        instance = null
        overlays?.hideAll()
        return super.onUnbind(intent)
    }

    // ----------------------------------------------------------- guide start

    /** Begins a guide. Called by the service itself or by [startRequestedGuide]. */
    private fun startGuide(guideId: String) {
        val definition = GuideCatalog.byId(guideId) ?: return
        cancelPendingInspection()
        // Whatever the last guide was pointing at is not this guide's target.
        // Holding it across a start meant the first scroll of a new guide was
        // answered with readings of an old control.
        releaseTarget()
        lastContentBounds = null

        val newEngine = GuideEngine(definition)
        newEngine.start()
        engine = newEngine
        publish()

        overlays?.showStarting()
        preferences.pendingGuideId = null

        // Dot opens Settings; it does not navigate within it. Every step from
        // here is the person's own tap.
        val settings = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(settings)
        } catch (_: Exception) {
            newEngine.fail(FailureReason.ServiceUnavailable)
            publish()
            overlays?.hideAll()
            return
        }

        newEngine.beginSearching()
        publish()
        // Give Settings a moment to come up before the first look.
        mainHandler.postDelayed({ inspectNow() }, FIRST_INSPECTION_DELAY_MS)
    }

    /**
     * Begins a general session from a request in the person's own words.
     *
     * Nothing about the request is matched against the catalogue. There is no
     * tutorial behind it and no steps decided in advance: the screen is read,
     * a next step is asked for, and what comes back is checked against the
     * screen again before anybody is told to touch anything.
     */
    private fun startGeneralHelp(goal: String, resume: Boolean = false, taskId: String = "") {
        // Which app the request is about, when it plainly names one Dot
        // knows, and whether that app is here. Alongside the goal, never in
        // place of it.
        val app = com.aaravsekhri.dot.guide.apps.AppDirectory.resolve(goal)
            ?.let { com.aaravsekhri.dot.guide.apps.InstalledApps.resolve(this, it) }
        runningLesson = null
        startAiSession(
            goal = goal,
            resume = resume,
            store = com.aaravsekhri.dot.guide.ai.AiSessionStore(this),
            context = com.aaravsekhri.dot.guide.ai.TaskContext(app = app, taskId = taskId),
            active = GuideCoordinator.ActiveGuide.Ask(goal),
        )
    }

    /** True while Dot's own decision screen is up and the card has stepped aside. */
    private var dotDecisionShowing = false

    /** The lesson running now, if the session is a lesson's. */
    private var runningLesson: Pair<com.aaravsekhri.dot.courses.Course, com.aaravsekhri.dot.courses.Lesson>? = null

    /** The lesson "Continue" goes to, once this one is done. */
    private var nextLesson: com.aaravsekhri.dot.courses.Lesson? = null

    /**
     * Runs one lesson with the adaptive guide.
     *
     * The lesson's objective is the goal; the course and the lesson travel
     * with it so the guide knows what is being taught and what "done" means.
     * Its checkpoint is the course's own, apart from Ask's, and is resumed
     * only if it belongs to this lesson at this content version — otherwise
     * the lesson starts afresh from whatever is on screen. Prerequisites that
     * are already met (the app installed, already signed in) are simply seen
     * to be met, not replayed.
     */
    private fun startLesson(request: GuideCoordinator.LessonRequest) {
        val course = com.aaravsekhri.dot.courses.CourseCatalog.course(request.courseId) ?: return
        val lesson = course.lessons.firstOrNull { it.id == request.lessonId } ?: return
        if (request.restart != GuideCoordinator.Restart.None) {
            // The same restart twice in a moment is one restart.
            val key = "${request.courseId}/${request.lessonId}/${request.restart}"
            val now = android.os.SystemClock.uptimeMillis()
            if (key == lastRestartKey && now - lastRestartAt < RESTART_DEBOUNCE_MS) {
                GuideLog.d("course") { "a second restart in a moment; ignored" }
                return
            }
            lastRestartKey = key
            lastRestartAt = now
            // A new attempt: whatever the last one had in flight stops — its
            // request, timers, cards and callbacks — and its place is let go.
            // What was earned stays earned; nothing in the app is undone.
            endGeneralSession()
            overlays?.hideAll()
            positional.onNavigation()
            com.aaravsekhri.dot.guide.ai.AiSessionStore(this, com.aaravsekhri.dot.guide.ai.AiSessionStore.courseSlot(course.id)).discard()
            if (request.restart == GuideCoordinator.Restart.Course) {
                com.aaravsekhri.dot.courses.CourseProgress.get(this).restartCourse(course.id)
            }
            GuideLog.d("course") { "restarting ${request.restart.name.lowercase()} ${lesson.id}" }
            runLesson(course, lesson, fresh = true)
            return
        }
        runLesson(course, lesson)
    }

    private var lastRestartKey: String? = null
    private var lastRestartAt = 0L
    private val RESTART_DEBOUNCE_MS = 1_500L

    /** A lesson taught by an authored guide, running now; completed when that guide completes. */
    private var runningGuideLesson: Pair<com.aaravsekhri.dot.courses.Course, com.aaravsekhri.dot.courses.Lesson>? = null

    private fun runLesson(
        course: com.aaravsekhri.dot.courses.Course,
        lesson: com.aaravsekhri.dot.courses.Lesson,
        /** A new attempt from the first teaching stage, not a resume. */
        fresh: Boolean = false,
    ) {
        lesson.guideId?.let { guideId ->
            // Taught by the step-by-step guide Dot already ships — the same
            // one, not a second copy — and recorded as this lesson.
            GuideLog.d("course") { "lesson ${lesson.id} uses the authored guide $guideId" }
            endGeneralSession()
            com.aaravsekhri.dot.courses.CourseProgress.get(this).started(course.id, lesson.id, lesson.version)
            runningGuideLesson = course to lesson
            GuideCoordinator.setActiveGuide(GuideCoordinator.ActiveGuide.Lesson(course.id, lesson.id, lesson.title))
            startGuide(guideId)
            return
        }
        val app = com.aaravsekhri.dot.guide.apps.InstalledApps.resolve(this, course.app)
        GuideLog.d("course") { "lesson ${lesson.id} starting; app ${app.installState.wire}" }
        com.aaravsekhri.dot.courses.CourseProgress.get(this).started(course.id, lesson.id, lesson.version)
        runningLesson = course to lesson
        nextLesson = null
        startAiSession(
            goal = lesson.objective,
            resume = !fresh,
            store = com.aaravsekhri.dot.guide.ai.AiSessionStore(
                this,
                com.aaravsekhri.dot.guide.ai.AiSessionStore.courseSlot(course.id),
            ),
            context = com.aaravsekhri.dot.guide.ai.TaskContext(
                app = app,
                lesson = com.aaravsekhri.dot.guide.ai.LessonContext(
                    courseId = course.id,
                    courseTitle = course.title,
                    lessonId = lesson.id,
                    lessonTitle = lesson.title,
                    objective = lesson.objective,
                    doneWhen = lesson.doneWhen,
                    rules = com.aaravsekhri.dot.courses.CourseCatalog.COMMON_RULES + lesson.rules +
                        listOfNotNull(lesson.fallback.takeIf(String::isNotBlank)?.let { "If it can't be done as written: $it" }),
                    needsAccount = lesson.needsAccount,
                    learnerConfirms = lesson.learnerConfirms,
                    contentVersion = lesson.version,
                    purpose = lesson.purpose,
                    result = lesson.result,
                    practice = lesson.practice?.let { p ->
                        val example = com.aaravsekhri.dot.courses.CourseProgress.get(this).exampleFor(course.id, p)
                        com.aaravsekhri.dot.guide.ai.PracticeContext(
                            // The learner's own example in place of the
                            // course's: the card names theirs.
                            intro = if (p.example.isNotBlank() && example != p.example) "Let's practice with $example." else p.intro,
                            example = example,
                            exampleKey = p.exampleKey,
                            typeText = p.textFor(example),
                            checkpoints = p.checkpoints,
                            ownChoice = p.ownChoice,
                            stopAt = p.stopAt,
                        )
                    },
                ),
            ),
            active = GuideCoordinator.ActiveGuide.Lesson(course.id, lesson.id, lesson.title),
            lessonCheck = { it.lessonId == lesson.id && it.contentVersion == lesson.version },
        )
    }

    /**
     * A lesson's session changed state: what it means for the course.
     *
     * Completed only from a completed session — which is either the screen
     * showing the lesson's outcome, or, for a lesson whose outcome no screen
     * shows, the learner saying so. Stopping, errors and "Try again later"
     * never complete anything.
     */
    private fun onSessionState(state: com.aaravsekhri.dot.guide.ai.AiSessionState) {
        // Before any card for this state is placed: where the keyboard is
        // now. A keyboard already up when a session starts or resumes sends
        // nothing, and the card was put over the very key it named.
        updateKeyboardInset()
        val (course, lesson) = runningLesson ?: return
        val progress = com.aaravsekhri.dot.courses.CourseProgress.get(this)
        when (state) {
            is com.aaravsekhri.dot.guide.ai.AiSessionState.Completed -> {
                progress.complete(
                    course.id, lesson.id, lesson.version,
                    if (lesson.learnerConfirms) {
                        com.aaravsekhri.dot.courses.Evidence.LearnerConfirmed
                    } else {
                        com.aaravsekhri.dot.courses.Evidence.Observed
                    },
                )
                aiHost?.lessonTransition = lessonTransition(course, lesson)
                GuideLog.d("course") { "lesson ${lesson.id} completed; next ${nextLesson?.id}" }
            }
            is com.aaravsekhri.dot.guide.ai.AiSessionState.LessonUnavailable -> {
                progress.unavailable(course.id, lesson.id, lesson.version, state.explanation)
                GuideLog.d("course") { "lesson ${lesson.id} not available here" }
            }
            else -> Unit
        }
    }

    /**
     * How a finished lesson is shown, from the course's records — saved
     * before this is read, so what the card says is what they now hold.
     */
    private fun lessonTransition(
        course: com.aaravsekhri.dot.courses.Course,
        lesson: com.aaravsekhri.dot.courses.Lesson,
    ): com.aaravsekhri.dot.guide.ai.AiGuideHost.LessonTransition {
        val summary = com.aaravsekhri.dot.courses.CourseProgress.get(this).summary(course)
        nextLesson = summary.current
        finishedCourse = course
        return com.aaravsekhri.dot.guide.ai.AiGuideHost.LessonTransition(
            lessonTitle = lesson.title,
            nextLessonTitle = nextLesson?.title,
            learned = lesson.learned,
            completed = summary.completed,
            total = summary.total,
            courseDone = summary.total > 0 && summary.completed == summary.total,
            // The course by its own name — "Google Maps", "Read comfortably" —
            // not the app it runs in, which for several is Settings.
            courseName = course.title.substringBefore(':').trim(),
            reinforcement = course.summary.replaceFirstChar(Char::lowercase),
        )
    }

    /**
     * Whether the phone has a validated internet connection, or null when
     * that cannot be read. Only ever a yes or no; nothing about which
     * network.
     */
    private fun networkUp(): Boolean? = runCatching {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrNull()

    /** The course of the lesson just finished, for "Next lesson" and "Review the course". */
    private var finishedCourse: com.aaravsekhri.dot.courses.Course? = null

    private fun startAiSession(
        goal: String,
        resume: Boolean,
        store: com.aaravsekhri.dot.guide.ai.AiSessionStore,
        context: com.aaravsekhri.dot.guide.ai.TaskContext,
        active: GuideCoordinator.ActiveGuide,
        lessonCheck: (com.aaravsekhri.dot.guide.ai.AiSessionStore.Checkpoint) -> Boolean = { true },
    ) {
        cancelPendingInspection()
        engine?.cancel()
        engine = null
        // The checkpoint is read before anything else touches the store.
        val checkpoint = if (resume) store.load()?.takeIf { it.goal == goal && lessonCheck(it) } else null

        // One session at a time. A new request ends the old one properly, so
        // nothing it had in flight can land on the new one's screen.
        aiSession?.stop(keepCheckpoint = true)
        aiSession = null
        positional.onNavigation()
        renderPositional()
        lastKeyboardVisible = keyboardVisible()
        // A debug fixture already in front when the session starts stands in
        // for another app from the first reading. Debug builds only.
        ownFixtureInFront = GuideLog.isEnabled && runCatching {
            windows.any { it.isActive && it.title?.toString().orEmpty().endsWith("(test)") }
        }.getOrDefault(false)

        val host = com.aaravsekhri.dot.guide.ai.AiGuideHost(
            context = this,
            overlays = { overlays.takeIf { !dotDecisionShowing } },
            foreground = ::foregroundForAi,
            keyboardVisible = ::keyboardVisible,
            isHomeScreen = ::isLauncher,
            dotOverlayRects = ::dotOverlayRects,
            screenshots = screenshots,
            onTarget = ::offerAiTarget,
            onClearTarget = ::clearAiTarget,
            onKeepClear = ::keepCardClearOf,
            sessionId = { aiSession?.sessionId.orEmpty() },
            appName = ::appNameOf,
            screenGeneration = { aiScreenGeneration },
            deviceContext = ::deviceContext,
            onSessionState = ::onSessionState,
            viewport = ::currentViewport,
            display = { screenSize().let { (w, h) -> Bounds(0, 0, w, h) } },
            step = { aiSession?.stepId ?: 0L },
            structure = { foregroundForAi(structureOnly = true) },
            keyboardActionKey = ::keyboardActionKey,
            onPracticeChoice = { value ->
                val (course, lesson) = runningLesson ?: return@AiGuideHost
                val key = lesson.practice?.exampleKey?.takeIf(String::isNotBlank) ?: return@AiGuideHost
                // Kept for this course only, so the next lesson and a resume
                // practise on the same thing. Never logged.
                com.aaravsekhri.dot.courses.CourseProgress.get(this).rememberExample(course.id, key, value)
            },
        ).apply {
            // The person has already accepted the disclosure by the time a
            // request can be sent; the service records it so that nothing is
            // gathered before they have.
            uploadsAllowed = preferences.cloudGuidanceAccepted
        }
        aiHost = host
        host.readInformation = context.lesson != null

        val plus = com.aaravsekhri.dot.account.PlusRepository.get(this)
        val session = com.aaravsekhri.dot.guide.ai.AiSessionController(
            client = com.aaravsekhri.dot.guide.ai.AiClient(
                networkUp = ::networkUp,
                identity = { plus.accounts.cachedIdentity()?.let { it.userId to it.key } },
            ),
            scope = aiScope,
            store = store,
            host = host,
            admission = com.aaravsekhri.dot.account.LedgerAdmission(plus),
        )
        aiSession = session
        GuideCoordinator.setActiveGuide(active)
        if (checkpoint != null) {
            GuideLog.d("ai") { "a general session is resuming" }
            session.resume(checkpoint, context)
        } else {
            GuideLog.d("ai") { "a general session has begun" }
            session.start(goal, context)
        }
    }

    /**
     * What is in front, for a general session.
     *
     * Deliberately not [readScreen]: that one is written for a tutorial, whose
     * steps name the packages they belong to. A general session follows the
     * person wherever they go, so the package check is replaced by the rules
     * that apply everywhere — Dot's own screens are never read, a dialog the
     * system puts in front is left alone, and a locked phone is not looked at.
     *
     * A screen with a password field is returned rather than dropped. It is
     * refused — by SensitiveScreenPolicy, before a single candidate is built
     * or a byte is sent — but it has to be refused as private rather than as
     * nothing, or somebody signing in is told "I cannot see a screen".
     */
    private fun foregroundForAi(structureOnly: Boolean = false): com.aaravsekhri.dot.guide.ai.Foreground {
        val allowed = if (structureOnly) {
            aiSession?.state?.let { it.mayReadStructure } == true
        } else {
            aiActive
        }
        if (!allowed || isDeviceLocked()) return com.aaravsekhri.dot.guide.ai.Foreground.Unavailable
        val root = appRootInFront() ?: return withheldInFront() ?: com.aaravsekhri.dot.guide.ai.Foreground.Unavailable

        val packageName = root.packageName?.toString()
        if (packageName == this.packageName && !ownFixtureInFront) return com.aaravsekhri.dot.guide.ai.Foreground.Dot
        if (packageName != null && packageName in FOREGROUND_DIALOG_PACKAGES) {
            return com.aaravsekhri.dot.guide.ai.Foreground.SystemDialog
        }
        val metrics = screenSize()
        return com.aaravsekhri.dot.guide.ai.Foreground.App(
            NodeHarvester.harvest(root, metrics.first, metrics.second),
        )
    }

    /**
     * The app the person is using, never Dot's own overlay.
     *
     * The active window is usually that app. But a card with a box to type in
     * takes focus, and Dot's overlay then becomes the active window: reading
     * it as "Dot is in front" would send the guide home in the middle of a
     * question. So an overlay is skipped for the topmost application window.
     */
    private fun appRootInFront(): android.view.accessibility.AccessibilityNodeInfo? = try {
        val active = rootInActiveWindow
        val overlayActive = active != null &&
            active.window?.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        if (active != null && !overlayActive) {
            active
        } else {
            val fallback = windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            if (fallback == null) GuideLog.d("ai") {
                "no app window in front (active=${active != null}, overlay=$overlayActive, windows=${windows.size}: ${windowCensus()})"
            }
            fallback ?: active.takeIf { !overlayActive }
        }
    } catch (e: Exception) {
        GuideLog.d("ai") { "reading the window in front failed: ${e::class.simpleName}" }
        null
    }

    /**
     * The app window in front when Android gives no content for it: a
     * permission request on current Android keeps its contents from every
     * service that is not an accessibility tool, and some apps protect
     * their own windows. Its title and bounds are all there is — enough to
     * say what it is and to keep the card off it, and nothing to point at.
     */
    private fun withheldInFront(): com.aaravsekhri.dot.guide.ai.Foreground.Withheld? = try {
        windows.firstOrNull { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused) && w.root == null
        }?.let { w ->
            val r = android.graphics.Rect().also(w::getBoundsInScreen)
            val title = w.title?.toString().orEmpty()
            com.aaravsekhri.dot.guide.ai.Foreground.Withheld(
                title = title,
                bounds = com.aaravsekhri.dot.guide.model.Bounds(r.left, r.top, r.right, r.bottom),
                permission = com.aaravsekhri.dot.guide.ai.PermissionPrompt.isPermission(title),
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Each window's type, whether it is active or focused, whether its content
     * can be read, its title and where it is. Structure only, for diagnostics.
     */
    internal fun windowCensus(): String = runCatching {
        windows.joinToString("; ") { w ->
            val root = w.root
            val r = android.graphics.Rect().also(w::getBoundsInScreen)
            "t${w.type}${if (w.isActive) "A" else ""}${if (w.isFocused) "F" else ""}:" +
                (root?.packageName?.toString() ?: "noroot") + " title=" + (w.title?.toString()?.take(60) ?: "-") +
                " bounds=${r.toShortString()}"
        }
    }.getOrDefault("?")

    /**
     * What an app is called, for asking whether somebody has an account in
     * it: the name the phone shows under its icon. A debug fixture is named by
     * its window's title instead, without the "(test)".
     */
    private fun appNameOf(packageName: String): String {
        if (packageName == this.packageName && ownFixtureInFront) {
            return runCatching {
                windows.firstOrNull { it.isActive }?.title?.toString().orEmpty()
                    .removeSuffix("(test)").trim()
            }.getOrDefault("")
        }
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault("")
    }

    /** The general session's screen reading, for the scrolling machinery. */
    private fun readScreenForAi(): ScreenSnapshot? =
        (foregroundForAi() as? com.aaravsekhri.dot.guide.ai.Foreground.App)?.screen

    /**
     * The launchers on this phone, found by asking which apps answer "go
     * Home" — a single, declared query rather than any wider look at what is
     * installed.
     */
    private val launcherPackages: Set<String> by lazy {
        try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            ).mapNotNull { it.activityInfo?.packageName }
                .filter { it != "com.android.settings" }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isLauncher(packageName: String): Boolean = packageName in launcherPackages

    /**
     * How this phone navigates, its Android version and its text size.
     *
     * The navigation style is the platform's own setting where it can be
     * read; anything else is reported as unknown, and the instructions for
     * unknown name both common ways rather than guessing one.
     */
    private fun deviceContext(): com.aaravsekhri.dot.guide.ai.DeviceContext {
        val navigation = try {
            when (Settings.Secure.getInt(contentResolver, "navigation_mode", -1)) {
                0 -> com.aaravsekhri.dot.guide.ai.NavigationStyle.ThreeButton
                1 -> com.aaravsekhri.dot.guide.ai.NavigationStyle.TwoButton
                2 -> com.aaravsekhri.dot.guide.ai.NavigationStyle.Gestures
                else -> com.aaravsekhri.dot.guide.ai.NavigationStyle.Unknown
            }
        } catch (_: Exception) {
            com.aaravsekhri.dot.guide.ai.NavigationStyle.Unknown
        }
        return com.aaravsekhri.dot.guide.ai.DeviceContext(
            navigation = navigation,
            sdk = android.os.Build.VERSION.SDK_INT,
            fontScale = resources.configuration.fontScale,
        )
    }

    /**
     * Dot's opaque cards, for painting out of a whole-display picture.
     *
     * Only the cards. The outline surrounds the control it points at, and
     * painting out its rectangle would paint out the control; the model is
     * told about the outline in words instead.
     */
    private fun dotOverlayRects(): List<Bounds> = listOfNotNull(
        overlays?.currentCardBounds(),
        overlays?.currentGuidanceBounds(),
    )

    /**
     * Hands a validated target to the one thing allowed to draw over an app.
     *
     * The general session does not get its own renderer. It produces a control
     * that has been checked against the live screen, and that goes through the
     * same positional owner, with the same rules, as a tutorial's target: the
     * outline appears only over a still page, comes off the moment anything
     * moves, and is replaced by the guidance card while it does.
     */
    private fun offerAiTarget(
        target: com.aaravsekhri.dot.guide.ai.ValidatedTarget,
        instruction: com.aaravsekhri.dot.guide.ai.RuntimeInstruction,
    ) {
        aiTarget = target
        overlays?.setReadingTarget(target.reading)
        // Where, not what: bounds only, for driving a device test.
        GuideLog.d("ai") { "target bounds=${target.bounds.left},${target.bounds.top},${target.bounds.right},${target.bounds.bottom}" }
        updateKeyboardInset()
        val viewport = currentViewport() ?: return
        val screen = readScreenForAi()
        // The list this control is in, if it is in one. Not simply the largest
        // list on the page: a search box above a list of suggestions is not
        // clipped by that list, and judging it against the list's bounds made
        // it look invisible.
        lastContentBounds = screen?.let { containerOf(it, target.bounds) }

        val identity = com.aaravsekhri.dot.guide.matching.TargetIdentity(
            windowId = screen?.windowId ?: -1,
            viewId = null,
            normalizedText = com.aaravsekhri.dot.guide.matching.TextNormalizer
                .normalize(target.naming),
            normalizedDescription = "",
            className = "",
        )
        // A new target is the start of a new step, and the positional
        // controller has to be told so. It parks in Transitioning after any
        // navigation and ignores readings there — which is right for a fixed
        // guide, where the engine announces each step, and wrong here unless
        // the general session announces its own. Without this the card arrived
        // and the outline never did.
        val isNewTarget = aiTargetIdentity != identity
        aiTargetIdentity = identity
        if (isNewTarget) positional.onSearching()
        // A target offered here was measured whole from a fresh reading. Its
        // shape is taken from that, never from a reading before it.
        positional.forgetShape()

        // The card keeps off the target from the start, not a moment later.
        overlays?.setClearRegions(com.aaravsekhri.dot.guide.overlay.ClearSource.Target, listOf(target.bounds.inflate(highlightPaddingPx())))
        feedAiScan(target, identity, viewport)
        renderPositional()
        holdAiTarget(target)

        // The card is a Compose window that has only just been given this
        // step's words; its height is not known until it has laid out, so the
        // reading above saw no card to avoid and could settle the outline
        // underneath it. Check again once there is something to measure, and
        // move the card rather than point at a row nobody can see.
        mainHandler.postDelayed({
            if (aiTarget === target) {
                val padded = target.bounds.inflate(highlightPaddingPx())
                val moved = overlays?.setClearRegions(listOf(padded)) == true
                GuideLog.d("ai") { "card avoid: moved=$moved" }
                if (moved) positional.onOccludersChanged(dotOccluders())
                // Offered again either way. If the card moved, the target is
                // judged against a screen where it is visible; if not, this is
                // the second agreeing reading a just-settled page needs before
                // its outline is drawn.
                currentViewport()?.let { feedAiScan(target, identity, it) }
                renderPositional()
            }
        }, CARD_LAYOUT_MS)
    }

    /**
     * Moves the card off a control the words name but Dot does not outline.
     * After the card has laid out, for the same reason as [offerAiTarget].
     */
    private fun keepCardClearOf(regions: List<Bounds>) {
        GuideLog.d("overlay") { "keep the card clear of ${regions.size} region(s)" }
        // At once, and alongside the target rather than instead of it: the
        // card places itself from its own measured size whenever it has one.
        overlays?.setClearRegions(
            com.aaravsekhri.dot.guide.overlay.ClearSource.Words,
            regions.map { it.inflate(highlightPaddingPx()) },
        )
        positional.onOccludersChanged(dotOccluders())
    }

    /** Lets the scrolling machinery ask this control where it is. */
    /** Offers one validated target to the positional state machine. */
    private fun feedAiScan(
        target: com.aaravsekhri.dot.guide.ai.ValidatedTarget,
        identity: com.aaravsekhri.dot.guide.matching.TargetIdentity,
        viewport: Bounds,
    ) {
        positional.onScanResult(
            candidate = com.aaravsekhri.dot.guide.positional.ScanCandidate(
                identity = identity,
                reportedBounds = target.bounds.inflate(highlightPaddingPx()),
                avoid = emptyList(),
                // Validated by DecisionValidator against a reading taken after
                // the answer came back. Nothing reaches here unverified.
                titleVerified = true,
                knownWhole = isWholeOnScreen(target.bounds),
            ),
            context = com.aaravsekhri.dot.guide.positional.ScreenContext(
                viewport = viewport,
                occluders = dotOccluders(),
                pebbleSizePx = px(HighlightMetrics.PebbleSizeDp.value),
                pebbleStraddlePx = px(HighlightMetrics.PebbleStraddle.value),
                contentBounds = lastContentBounds,
            ),
            scanGeneration = positional.generation,
            nowNanos = MotionClock.nowNanos(),
        )
    }

    /**
     * True when accessibility cannot have clipped these bounds: they sit
     * strictly inside the physical screen, and inside the scrolling list
     * around them where there is one. Accessibility reports only the visible
     * part of a control cut off by its list or the screen edge; a control
     * clear of both is reported whole, even where it reaches under the
     * gesture bar.
     */
    private fun isWholeOnScreen(bounds: Bounds): Boolean {
        val height = screenSize().second
        val insideScreen = bounds.top > 0 && bounds.bottom < height
        val list = lastContentBounds
        val insideList = list == null ||
            (bounds.top > list.top && bounds.bottom < list.bottom)
        return insideScreen && insideList
    }

    /** Long enough for a just-shown card to have measured itself. */
    private val CARD_LAYOUT_MS = 150L

    private fun holdAiTarget(target: com.aaravsekhri.dot.guide.ai.ValidatedTarget) {
        val located = locateRow(target.naming, target.bounds) ?: return
        val (row, labelNode, _) = located
        probeIdentity = aiTargetIdentity
        probe.hold(row, if (row === labelNode) null else labelNode, target.naming)
    }

    /**
     * Takes a general session's target down.
     *
     * @param samePage the person is still on the page — the control was just
     *   used, or the next step is being worked out — so the positional owner
     *   keeps its memory of what it drew, and the same control drawn again
     *   does not replay Pebble's entrance.
     */
    private fun clearAiTarget(samePage: Boolean) {
        aiTarget = null
        releaseTarget()
        if (samePage) {
            positional.onTargetWithdrawn()
        } else {
            aiTargetIdentity = null
            positional.onNavigation()
        }
        renderPositional()
    }

    /** Opens Dot, so a question can be answered where there is a keyboard. */
    private fun openDot() {
        try {
            startActivity(
                Intent(this, com.aaravsekhri.dot.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        } catch (_: Exception) {
            // Nothing sensible to do; the card stays up.
        }
    }

    // -------------------------------------------------------- event handling

    /**
     * The person tapped something.
     *
     * The description is taken from the event while it is still valid, before
     * anything else happens. Reading the screen first was the bug: a tap on a
     * settings row navigates, so by the time the tree had been read neither
     * the tapped control nor the step's target was on it, and the guide could
     * never leave step one.
     */
    private fun handleClick(event: AccessibilityEvent) {
        val currentEngine = engine ?: return
        fruitlessScans = 0
        val click = NodeHarvester.describeClick(event.source)
        GuideLog.d("progress") { "click event: ${click ?: "no usable source"}" }
        if (click == null) return

        // On a step where the person is working a control rather than moving
        // to another page, a tap is a value change: the plus and minus buttons
        // beside a slider report clicks, not value selections. The page
        // re-measures itself afterwards and Android calls that a scroll, which
        // it is not — and treating it as one replayed Pebble's entrance on
        // every notch.
        if (currentEngine.currentStep?.action == StepAction.Adjust) {
            openValueChangeWindow()
            positional.onSameScreenControlChange()
            scheduleInspection(SETTLE_AFTER_SCROLL_MS)
            return
        }

        val stateBefore = currentEngine.state
        currentEngine.onUserActivated(click, System.currentTimeMillis())

        if (currentEngine.state != stateBefore) {
            // Whatever was being pointed at belongs to the page being left.
            searchGeneration++
            positional.onNavigation()
            renderPositional()
            overlays?.clearTargetLayers()
        }

        publish()
        renderWithoutScreen()

        // A tap usually means a new screen is on its way.
        scheduleInspection()
    }

    /**
     * A control changed value on a screen that did not move.
     *
     * A font-size notch rebuilds every node on the page and emits a burst of
     * events, but the slider is still the same slider in the same place. It is
     * explicitly not a scroll and not navigation, so the state machine is told
     * so: the outline stays up, the cue never appears, and Pebble does not
     * play its entrance again. Getting this wrong is what made the highlight
     * blink on every notch.
     */
    private fun handleAdjustOrRefresh() {
        val currentEngine = engine ?: return
        val screen = readScreen() ?: return

        val step = currentEngine.currentStep
        if (step?.action == StepAction.Adjust) {
            currentEngine.onUserAdjusted(screen)
        }
        positional.onSameScreenControlChange()
        settlingAfterValueChangeUntil = System.currentTimeMillis() + VALUE_CHANGE_SETTLE_MS
        currentEngine.onScreenSnapshot(screen)
        publish()
        render(screen)
        offerToPositional(regionOf(currentEngine), screen)
    }

    /**
     * Coalesces a burst of window events into a single look, so a settling
     * list does not cause dozens of traversals.
     */
    /**
     * The page is being replaced.
     *
     * Everything that points at a position comes down immediately and
     * synchronously — before any new scan — so nothing is ever left hanging
     * over the outgoing screen. Pebble disappears here and reappears on the
     * new target; it never travels between the two.
     */
    /**
     * Something replaced the page. Whether that means the person went anywhere
     * is not yet known, so the overlay is left alone and a grace period opens
     * in which the same control may reclaim it.
     */
    private fun openContinuityWindow() {
        continuityWindowEndsAt = System.currentTimeMillis() + CONTINUITY_GRACE_MS
    }

    /** Genuine navigation: clear at once and start looking afresh. */
    private fun onTrueNavigation(reason: String) {
        searchGeneration++
        GuideLog.d("continuity") { "TRUE_NAVIGATION ($reason); generation now $searchGeneration" }
        cancelPendingInspection()
        cancelTrackingBurst()
        lastPageSignature = null
        lastFingerprint = null
        continuityWindowEndsAt = 0L
        rendered.forget()
        resetMotionSources()

        overlays?.clearTargetLayers()
        engine?.onPageChanged()
        publish()
        renderWithoutScreen()

        scheduleInspection(PAGE_SETTLE_MS)
    }

    /**
     * Follows the target while a scroll is happening.
     *
     * Rather than one look after the list settles, this re-reads the target a
     * few times a second for as long as scroll events keep arriving, then
     * stops. Bounded on both sides: each pass is a single tree read, and the
     * burst ends on its own if the person stops scrolling.
     *
     * Every pass is tagged with the generation in force when it was queued, so
     * a burst from before a page change cannot paint the old target.
     */
    private fun beginTrackingBurst() {
        trackingUntil = System.currentTimeMillis() + TRACKING_BURST_MS
        if (trackingJob != null) return

        val generation = searchGeneration
        val tick = object : Runnable {
            override fun run() {
                if (generation != searchGeneration) {
                    trackingJob = null
                    return
                }
                inspectNow(generation)
                if (System.currentTimeMillis() < trackingUntil) {
                    mainHandler.postDelayed(this, TRACKING_INTERVAL_MS)
                } else {
                    trackingJob = null
                    // One last look once everything has come to rest.
                    scheduleInspection(SETTLE_AFTER_SCROLL_MS)
                }
            }
        }
        trackingJob = tick
        mainHandler.post(tick)
    }

    private fun cancelTrackingBurst() {
        trackingJob?.let(mainHandler::removeCallbacks)
        trackingJob = null
        trackingUntil = 0L
    }

    private fun scheduleInspection(delayMs: Long = WINDOW_DEBOUNCE_MS) {
        cancelPendingInspection()
        val generation = searchGeneration
        val runnable = Runnable {
            pendingInspection = null
            inspectNow(generation)
        }
        pendingInspection = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingInspection() {
        pendingInspection?.let(mainHandler::removeCallbacks)
        pendingInspection = null
    }

    /**
     * One look at the screen.
     *
     * @param generation the value in force when this scan was scheduled. A scan
     *   from before a page change is discarded rather than allowed to paint a
     *   target from the old screen.
     */
    private fun inspectNow(generation: Long = searchGeneration) {
        if (generation != searchGeneration) return
        val currentEngine = engine ?: return
        if (!currentEngine.state.isActive) return

        // Where the drawn outline stood when this read began, so the result can
        // be aged against any scrolling that happens while it runs.
        val mark = rendered.markScanStart()
        val screen = readScreen() ?: return

        // A window caught halfway through being rebuilt reports almost
        // nothing. Two nodes is not a page, and asking "is this the same page
        // as before" of it can only answer no — which is how a font-size notch,
        // whose whole purpose is to rebuild the window, came to be treated as
        // navigating away. Nothing is concluded from a reading this thin; the
        // page is given a moment and read again.
        val usable = screen.nodes.count { it.isVisibleToUser && !it.bounds.isEmpty }
        if (usable < MIN_NODES_TO_JUDGE && skippedThinScans < MAX_THIN_SCANS_IN_A_ROW) {
            skippedThinScans++
            GuideLog.d("continuity") { "only $usable usable nodes; waiting for the page" }
            scheduleInspection(SETTLE_RESCAN_MS)
            return
        }
        skippedThinScans = 0

        val stepIndex = currentEngine.state.stepIndexOrNull ?: return
        val step = currentEngine.definition.steps.getOrNull(stepIndex) ?: return

        val tracked = currentEngine.tracker.trackedIdentity
        val reacquired = tracked?.let {
            currentEngine.tracker.findSameControl(screen, step.target, it)
        }

        val fingerprint = ScreenFingerprint.of(
            screen = screen,
            stepIndex = stepIndex,
            targetLabel = reacquired?.label ?: tracked?.normalizedText,
            targetRole = reacquired?.identity?.className ?: tracked?.className,
        )

        val kind = ScreenContinuityClassifier.classify(
            previous = lastFingerprint,
            current = fingerprint,
            stepAdvanced = false,
            scrollObserved = scrollSinceLastScan,
            targetStillPresent = reacquired != null,
        )
        scrollSinceLastScan = false
        lastFingerprint = fingerprint
        lastPageSignature = screen.pageSignature

        GuideLog.d("continuity") {
            "kind=$kind targetPresent=${reacquired != null} " +
                "overlap=${lastFingerprint?.let { fingerprint.landmarkOverlapWith(it) }} " +
                "scanGen=${mark.generation} nowGen=${rendered.geometry?.generation}"
        }

        // Arriving at the page this step leads to settles the question before
        // anything else gets to weigh in. Continuity reasoning compares what
        // is on screen with what was, and a destination that keeps the step's
        // own label in its toolbar looks, by that measure, like the same page.
        if (currentEngine.hasArrivedAtDestination(screen)) {
            GuideLog.d("progress") { "arrived at step ${currentEngine.state.stepIndexOrNull}'s destination" }
            searchGeneration++
            rendered.forget()
            resetMotionSources()
            overlays?.clearTargetLayers()
            currentEngine.onScreenSnapshot(screen, systemInsets())
            publish()
            render(screen)
            offerToPositional(regionOf(currentEngine), screen)
            scheduleFollowUp(currentEngine)
            return
        }

        // The person has not gone anywhere: keep everything and update in place.
        if (reacquired != null && isContinuity(kind)) {
            reconcileInPlace(currentEngine, reacquired, mark, screen)
            scheduleFollowUp(currentEngine)
            return
        }

        if (kind == ScreenChangeKind.TRUE_NAVIGATION) {
            onTrueNavigation("classifier")
            return
        }

        // A rebuild in progress, with the control not back yet. Hold what is on
        // screen for the grace period rather than tearing it down.
        if (kind == ScreenChangeKind.SAME_SCREEN_LAYOUT_REBUILD &&
            System.currentTimeMillis() < continuityWindowEndsAt
        ) {
            GuideLog.d("continuity") { "holding overlay while the page rebuilds" }
            scheduleInspection(SETTLE_RESCAN_MS)
            return
        }

        val stepBefore = currentEngine.state.stepIndexOrNull
        currentEngine.onScreenSnapshot(screen, systemInsets())

        if (currentEngine.state.stepIndexOrNull != stepBefore) {
            fruitlessScans = 0
            // The guide moved itself on: that is navigation by definition.
            searchGeneration++
            cancelTrackingBurst()
            rendered.forget()
            resetMotionSources()
            overlays?.clearTargetLayers()
        }

        adoptRenderedTarget(currentEngine)
        publish()
        render(screen)
        offerToPositional(regionOf(currentEngine), screen)
        scheduleFollowUp(currentEngine)
    }

    private fun isContinuity(kind: ScreenChangeKind): Boolean = when (kind) {
        ScreenChangeKind.SAME_SCREEN_SCROLL,
        ScreenChangeKind.SAME_SCREEN_CONTROL_MUTATION,
        ScreenChangeKind.SAME_SCREEN_LAYOUT_REBUILD,
        -> true

        ScreenChangeKind.TRUE_NAVIGATION, ScreenChangeKind.UNKNOWN -> false
    }

    /**
     * The same control, in its possibly-new position. Correct the drawing and
     * leave everything else alone.
     */
    private fun reconcileInPlace(
        currentEngine: GuideEngine,
        region: com.aaravsekhri.dot.guide.model.SemanticTargetRegion,
        mark: RenderedTargetTracker.ScrollMark,
        screen: ScreenSnapshot,
    ) {
        continuityWindowEndsAt = 0L

        val geometry = rendered.geometry
        if (geometry == null || !geometry.identity.isSameControlAs(region.identity)) {
            // First sighting, or the window id changed under a rebuild. Bind to
            // it without disturbing what is drawn.
            rendered.rebind(region.identity, region.boundsInScreen)
        } else {
            val outcome = rendered.reconcile(
                identity = geometry.identity,
                authoritative = region.boundsInScreen,
                mark = mark,
                tolerancePx = RECONCILE_TOLERANCE_PX,
            )
            // How far the outline was from the row, with both taken at the
            // same instant. Comparing what is on screen now against a scan
            // that began a scroll ago measures the scan's age, not the
            // outline's attachment, and reported hundreds of pixels of error
            // for a highlight that was in fact sitting on its row.
            GuideLog.d("motion") {
                "reconcile=$outcome authoritative=${region.boundsInScreen}"
            }
            if (outcome == ReconcileOutcome.RejectedAsStale) {
                // Newer motion is already banked; let the frame apply it and
                // read again rather than dragging the outline backwards.
                scheduleInspection(SETTLE_RESCAN_MS)
                return
            }
        }

        currentEngine.onSameScreenUpdate(region)
        publish()
        render(screen)
        offerToPositional(region, screen)
    }

    /**
     * Hands one reading of the screen to the state machine.
     *
     * The last checkpoint before anything can be drawn. The identity is
     * verified again here, against this very region, rather than trusted from
     * wherever the resolver first accepted it — a candidate that was right
     * when it was chosen can be stale by the time it reaches a renderer, and
     * the cost of being wrong is pointing someone at the wrong setting.
     */
    private fun offerToPositional(
        region: com.aaravsekhri.dot.guide.model.SemanticTargetRegion?,
        screen: ScreenSnapshot?,
    ) {
        val viewport = currentViewport() ?: return
        val spec = engine?.currentStep?.target

        // A target that has scrolled under the card is better served by moving
        // the card than by refusing to point at anything.
        region?.let { overlays?.ensureCardAvoids(it.boundsInScreen.inflate(highlightPaddingPx())) }

        val candidate = if (region == null || screen == null || spec == null) {
            null
        } else {
            val verified = TargetIdentityGate.regionCarriesIdentity(
                screen,
                region.boundsInScreen,
                spec,
            )
            if (!verified) {
                // The region does not contain the control it claims to be.
                // That is the failure this whole checkpoint exists for — it is
                // how an outline ends up around a neighbouring row — and it
                // happens when a region settled before a scroll is handed on
                // after one. Nothing is drawn, and the tracker is made to find
                // the target again from scratch rather than keep offering the
                // stale rectangle.
                GuideLog.d("positional") {
                    "gate rejected region=${region.boundsInScreen}; reacquiring"
                }
                engine?.tracker?.reset()
                scheduleInspection(SETTLE_RESCAN_MS)
            }
            ScanCandidate(
                identity = region.identity,
                reportedBounds = region.boundsInScreen.inflate(highlightPaddingPx()),
                avoid = region.matchedNodes.map { it.bounds },
                titleVerified = verified,
            )
        }

        lastContentBounds = screen?.let(::scrollContainerOf) ?: lastContentBounds

        positional.onScanResult(
            candidate = candidate,
            context = ScreenContext(
                viewport = viewport,
                occluders = dotOccluders(),
                pebbleSizePx = px(HighlightMetrics.PebbleSizeDp.value),
                pebbleStraddlePx = px(HighlightMetrics.PebbleStraddle.value),
                contentBounds = lastContentBounds,
            ),
            scanGeneration = positional.generation,
            nowNanos = MotionClock.nowNanos(),
        )
        renderPositional()

        // Settled on something: hold it, so a later scroll can ask where it is
        // without walking the tree. Re-settling on the same control changes
        // nothing — reacquiring it would throw away a perfectly good node.
        val stable = positional.state as? PositionalGuideState.TargetStable
        if (stable != null && region != null) {
            val already = probeIdentity?.isSameControlAs(stable.identity) == true
            if (!already) holdTarget(region, candidate?.reportedBounds ?: region.boundsInScreen)
        }

        // Still checking after a scroll. Two readings are wanted, at least a
        // rendered frame apart, and nothing else is going to ask for the
        // second: the page has stopped, so no more events are coming.
        if (positional.state is PositionalGuideState.WaitingForStability) {
            scheduleInspection(CONFIRMATION_RESCAN_MS)
            // And keep asking the target where it is, so the card on screen
            // goes on telling the truth while the checks run rather than
            // holding whatever it happened to say when the page stopped.
            requestEvidence()
        }
    }

    /**
     * The scrolling list a target lives in.
     *
     * The largest visible scrollable node, which on a settings page is the
     * list itself. Its bounds are what clip a row's reported rectangle, so
     * they are what decides whether a reading shows the whole control.
     */
    /** The smallest scrolling container holding [target]'s centre, if any. */
    private fun containerOf(screen: ScreenSnapshot, target: Bounds): Bounds? =
        screen.nodes
            .filter { it.isScrollable && it.isVisibleToUser && !it.bounds.isEmpty }
            .map { it.bounds }
            .filter { it.left <= target.centerX && target.centerX <= it.right && it.top <= target.centerY && target.centerY <= it.bottom }
            .minByOrNull { it.width.toLong() * it.height }

    private fun scrollContainerOf(screen: ScreenSnapshot): Bounds? =
        screen.nodes
            .filter { it.isScrollable && it.isVisibleToUser && !it.bounds.isEmpty }
            .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
            ?.bounds

    /**
     * Starts holding the control the guide has just settled on.
     *
     * Semantics has already decided which control this is and this does not
     * reopen that question: it locates the live node for the row that was
     * chosen and hands it to the probe, which from then on can answer where it
     * is without the tree being walked again.
     */
    private fun holdTarget(
        region: com.aaravsekhri.dot.guide.model.SemanticTargetRegion,
        drawn: Bounds,
    ) {
        val label = region.label
        if (label.isNullOrBlank()) {
            // Without wording there is nothing to re-check the row against, and
            // a list that recycles its rows would hand back a different control
            // wearing the same node. The guide keeps its target; it simply
            // cannot be asked where it is between readings, so a scroll will
            // say "stop for a moment" rather than guess.
            GuideLog.d("probe") { "not holding: the target has no label to verify against" }
            return
        }
        val located = locateRow(label, drawn) ?: run {
            GuideLog.d("probe") { "not holding: the row could not be located" }
            return
        }
        val (row, labelNode, rowBounds) = located

        probeIdentity = region.identity
        probe.hold(
            root = row,
            label = if (row === labelNode) null else labelNode,
            expectedLabel = label,
        )
        GuideLog.d("probe") { "holding a row ${rowBounds.width}x${rowBounds.height}" }
    }

    private fun releaseTarget() {
        probe.release()
        probeIdentity = null
    }

    private fun locateRow(
        label: String,
        drawn: Bounds,
    ): Triple<android.view.accessibility.AccessibilityNodeInfo,
        android.view.accessibility.AccessibilityNodeInfo, Bounds>? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null

        val matches = try {
            root.findAccessibilityNodeInfosByText(label)
        } catch (_: Exception) {
            null
        }.orEmpty()
        val labelNode = matches.firstOrNull {
            it.isVisibleToUser && it.text?.toString().equals(label, ignoreCase = true)
        } ?: matches.firstOrNull { it.isVisibleToUser } ?: return null

        // Which node to follow is decided by which one is the shape of the
        // thing being drawn, not by climbing to the first clickable ancestor.
        // A settings row is clickable and a font-size slider is not, and
        // climbing until something was took the follower all the way to the
        // window: a rectangle the size of the screen, which can never be seen
        // whole inside a list and so was never followed at all.
        val rect = android.graphics.Rect()
        var candidate: android.view.accessibility.AccessibilityNodeInfo? = labelNode
        var best: android.view.accessibility.AccessibilityNodeInfo? = null
        var bestBounds = Bounds.EMPTY
        var bestScore = -1.0
        var climbed = 0
        while (candidate != null && climbed <= PROBE_ANCESTOR_DEPTH) {
            candidate.getBoundsInScreen(rect)
            val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
            if (!bounds.isEmpty && bounds.right >= bounds.left && bounds.bottom >= bounds.top) {
                val score = overlapScore(bounds, drawn)
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                    bestBounds = bounds
                }
            }
            candidate = candidate.parent
            climbed++
        }

        val chosen = best ?: return null
        return Triple(chosen, labelNode, bestBounds)
    }

    /**
     * How well one rectangle stands for another: intersection over union.
     *
     * Used to pick the node that is the setting, out of the label inside it and
     * the containers around it.
     */
    private fun overlapScore(a: Bounds, b: Bounds): Double {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0).toDouble()
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0).toDouble()
        val intersection = width * height
        if (intersection <= 0.0) return 0.0
        val union = a.width.toDouble() * a.height + b.width.toDouble() * b.height - intersection
        return if (union <= 0.0) 0.0 else intersection / union
    }

    private fun px(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    private fun highlightPaddingPx(): Int = px(HighlightMetrics.Padding.value)

    /**
     * Forgets every visual and motion reference.
     *
     * Called on true navigation. A reference taken on the previous screen
     * describes pixels that no longer exist, and a pending delta describes a
     * scroll in a page that has gone — carrying either across would put the
     * outline somewhere arbitrary on the new screen.
     */
    private fun resetMotionSources() {
        releaseTarget()
        lastScrollPosition = Int.MIN_VALUE
        lastContentBounds = null
        positional.onNavigation()
        lastScrollAtNanos = Long.MIN_VALUE
        mainHandler.removeCallbacks(idleCheck)
        renderPositional()
    }

    /**
     * Semantics has vouched for the target again.
     *
     * This is the only thing that may bring the outline back after it was
     * taken down, and it deliberately lives here rather than on the capture
     * path: capture is optional and can stop at any moment, and hanging the
     * one route back to a visible highlight off an optional component is how
     * an outline disappears for the rest of a guide.
     */
    /** The region the engine is currently pointing at, if any. */
    private fun regionOf(currentEngine: GuideEngine): com.aaravsekhri.dot.guide.model.SemanticTargetRegion? =
        when (val state = currentEngine.state) {
            is GuideSessionState.TargetFound -> state.region
            is GuideSessionState.WaitingForUser -> state.region
            else -> null
        }

    /** Starts following whatever the engine has just settled on. */
    private fun adoptRenderedTarget(currentEngine: GuideEngine) {
        val state = currentEngine.state
        val region = when (state) {
            is GuideSessionState.TargetFound -> state.region
            is GuideSessionState.WaitingForUser -> state.region
            else -> null
        }
        if (region == null) {
            rendered.forget()
            return
        }
        val geometry = rendered.geometry
        if (geometry == null || !geometry.identity.isSameControlAs(region.identity)) {
            rendered.adopt(region.identity, region.boundsInScreen)
        }
    }

    private fun scheduleFollowUp(currentEngine: GuideEngine) {
        when (currentEngine.state) {
            is GuideSessionState.Searching -> scheduleInspection(backedOff(SETTLE_RESCAN_MS))
            is GuideSessionState.ScrollNeeded -> scheduleInspection(backedOff(CUE_RECHECK_MS))
            // Keep looking until the page a tap should have produced arrives,
            // or until the engine gives up on it. Window events alone are not
            // dependable enough to be the only thing watching for it.
            is GuideSessionState.AwaitingDestination ->
                scheduleInspection(AWAITING_DESTINATION_RECHECK_MS)
            else -> Unit
        }
    }

    /**
     * How long to wait before looking again, when the last look found nothing.
     *
     * A guide whose target is off screen sits in this state for as long as the
     * person takes to scroll to it, and reading the whole tree three times a
     * second the entire time cost 77% of a processor core on a screen nobody
     * was touching. Nothing about a still page changes between those reads.
     *
     * So the interval doubles while the answer keeps coming back the same, up
     * to a second and a half, and drops back to its base the moment anything
     * happens — a scroll, a tap, a page change. Somebody scrolling gets the
     * quick cadence, because scrolling resets it; somebody sitting still costs
     * nothing.
     */
    private fun backedOff(baseMs: Long): Long {
        val delay = baseMs shl fruitlessScans.coerceAtMost(MAX_BACKOFF_DOUBLINGS)
        fruitlessScans++
        return delay.coerceAtMost(MAX_RESCAN_INTERVAL_MS)
    }

    /** Consecutive looks that found nothing new. Reset by anything happening. */
    private var fruitlessScans = 0

    /**
     * One pass over the active window, or null when there is nothing safe to
     * read. Returning null is normal — windows change constantly.
     */
    private fun readScreen(): ScreenSnapshot? {
        val currentEngine = engine ?: return null
        if (!currentEngine.state.isActive) return null
        if (isDeviceLocked()) return null

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null

        val metrics = screenSize()
        val screen = NodeHarvester.harvest(root, metrics.first, metrics.second)
        GuideLog.d("scan") {
            "read ${screen.nodes.size} nodes from ${screen.packageName} " +
                "window=${screen.windowId} screen=${metrics.first}x${metrics.second} " +
                "eligible=${screen.nodes.count { it.isVisibleToUser && !it.bounds.isEmpty }}"
        }

        // A password on screen means Dot stands down entirely, whatever app
        // it belongs to.
        if (screen.containsPasswordField) {
            overlays?.hideAll()
            return null
        }
        return screen
    }

    // ----------------------------------------------------------- presentation

    private fun render(screen: ScreenSnapshot) {
        val currentEngine = engine ?: return
        val controller = overlays ?: return

        // Settling the highlight is what turns "found" into "your turn".
        val state = currentEngine.state
        if (state is GuideSessionState.TargetFound) {
            currentEngine.onHighlightPlaced(state.region)
            publish()
        }

        controller.render(
            state = currentEngine.state,
            definition = currentEngine.definition,
            viewport = screen.viewport(systemInsets()),
        )

        // The engine's own idea of "searching" must reach the state machine
        // too, or a target that goes away leaves an outline behind.
        when (val engineState = currentEngine.state) {
            is GuideSessionState.Searching -> {
                positional.onSearching(null)
                renderPositional()
            }

            // The engine knows where the target is even though it cannot see
            // it. That direction is the most useful thing Dot can show.
            is GuideSessionState.ScrollNeeded -> {
                positional.onSearching(engineState.direction)
                renderPositional()
            }

            is GuideSessionState.Completed, is GuideSessionState.Cancelled -> {
                positional.onNavigation()
                renderPositional()
            }

            else -> Unit
        }

        if (currentEngine.state is GuideSessionState.Completed) {
            preferences.pendingGuideId = null
        }
    }

    private fun publish() {
        engine?.let { GuideCoordinator.publish(it.state) }
        // A lesson taught by an authored guide is complete when that guide
        // is — the learner adjusted the setting and said it was done.
        val guideLesson = runningGuideLesson
        if (guideLesson != null && engine?.state is GuideSessionState.Completed) {
            val (course, lesson) = guideLesson
            com.aaravsekhri.dot.courses.CourseProgress.get(this).complete(
                course.id, lesson.id, lesson.version, com.aaravsekhri.dot.courses.Evidence.LearnerConfirmed,
            )
            GuideLog.d("course") { "lesson ${lesson.id} completed through its authored guide" }
            runningGuideLesson = null
            // Celebrated like any lesson, once: this is the one completion event.
            overlays?.showCompletion(lessonTransition(course, lesson), "", -System.currentTimeMillis())
            GuideCoordinator.setActiveGuide(null)
        }
    }

    private val commands = object : OverlayCommands {
        // Every one of these arrives from the same card, which serves both a
        // fixed tutorial and a general session. Each has to reach whichever is
        // running — the general session especially, because pausing it is the
        // thing that stops Dot reading and uploading the screen, and a command
        // that only reached the tutorial engine left it doing both.
        override fun onPause() = afterCommand {
            engine?.pause()
            aiSession?.pause()
            positional.onPaused()
            renderPositional()
        }

        override fun onResume() = afterCommand {
            engine?.resume()
            aiSession?.resumeFromPause()
            positional.onResumed()
            renderPositional()
        }

        override fun onExit() {
            engine?.cancel()
            aiSession?.endGuide()
            endGeneralSession()
            positional.onNavigation()
            publish()
            overlays?.hideAll()
            engine = null
            preferences.pendingGuideId = null
            GuideCoordinator.clear()
            // Back to TapBack, where the person can begin again or carry on.
            openDot()
        }

        override fun onRetry() = afterCommand {
            engine?.retry()
            aiSession?.retry()
        }

        override fun onCannotSeeTarget() = afterCommand {
            engine?.requestHelp()
            // In a general session the link reads "That didn't work": the
            // person telling Dot its instruction failed. Recorded against the
            // instruction so it is not simply repeated, and answered with a
            // fresh look.
            aiSession?.reportProblem()
        }

        override fun onDoneTyping(step: Long) = afterCommand { aiSession?.doneTyping(step) }

        override fun onTeachingNext(step: Long) = afterCommand { aiSession?.continueTeaching(step) }

        override fun onTryAnotherWay() = afterCommand { aiSession?.tryAnotherWay() }

        override fun onKeepWaiting() = afterCommand { aiSession?.keepWaiting() }

        override fun onKeepTrying() = afterCommand { aiSession?.keepTrying() }

        override fun onCheckAgain() = afterCommand { aiSession?.checkAgain() }

        override fun onTryLater() {
            // Everything is saved by the session as it stops; then Dot steps
            // away completely. No reminder is scheduled and nothing retries.
            aiSession?.tryLater()
            endGeneralSession()
            positional.onNavigation()
            overlays?.hideAll()
            GuideCoordinator.clear()
        }

        override fun onNextLesson() {
            val course = runningLesson?.first ?: finishedCourse
            val next = nextLesson
            engine = null
            endGeneralSession()
            overlays?.hideAll()
            if (course != null && next != null) {
                startLesson(GuideCoordinator.LessonRequest(course.id, next.id))
            }
        }

        override fun onOpenCourse() {
            val course = runningLesson?.first ?: finishedCourse
            endGeneralSession()
            positional.onNavigation()
            overlays?.hideAll()
            GuideCoordinator.clear()
            GuideCoordinator.showCourse(course?.id)
            openDot()
        }

        override fun onChoice(index: Int, questionId: Int?) = afterCommand { aiSession?.chooseAt(index, questionId) }

        override fun onAnswerText(text: String, questionId: Int) = afterCommand {
            aiSession?.answerClarification(text, questionId)
        }

        override fun onChangeAnswer() = afterCommand { aiSession?.changeAnswer() }

        override fun onCardMoved() {
            // The card no longer covers what it covered, and may now cover
            // something else: the outline is judged again against where it is.
            positional.onOccludersChanged(dotOccluders())
            aiTarget?.let { target ->
                aiTargetIdentity?.let { identity -> currentViewport()?.let { feedAiScan(target, identity, it) } }
            }
            renderPositional()
        }

        override fun onGoBackAStep() = afterCommand { engine?.goBackAStep() }

        override fun onConfirmDone() = afterCommand {
            engine?.confirmStepDone()
            aiSession?.confirmDone()
        }

        override fun onReopenSettings() {
            val settings = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                startActivity(settings)
            } catch (_: Exception) {
                // Nothing further to do; the recovery card stays up.
            }
            engine?.retry()
            publish()
            renderWithoutScreen()
            mainHandler.postDelayed({ inspectNow() }, FIRST_INSPECTION_DELAY_MS)
        }

        override fun onContinueAfterHandoff() = afterCommand {
            aiSession?.continueAfterHandoff()
        }

        override fun onRetryPlanning() = afterCommand {
            GuideLog.d("ai") { "card: try again (state=${aiSession?.state?.let { it::class.simpleName }})" }
            aiSession?.retry()
        }

        override fun onAnswerInApp() {
            // The question is typed in Dot, where there is a proper text field
            // and a keyboard that is not fighting somebody else's app.
            (aiSession?.state as? com.aaravsekhri.dot.guide.ai.AiSessionState.AskingClarification)
                ?.let { GuideCoordinator.askQuestion(it.question, it.options) }
            // The card steps aside while the question is answered in Dot. Left
            // up, it sat over Dot's own question screen and hid the question
            // and its choices.
            overlays?.hideAll()
            openDot()
        }

        override fun onFinish() {
            overlays?.hideAll()
            endGeneralSession()
            engine = null
            GuideCoordinator.clear()
        }
    }

    /** Ends a general session and lets go of everything it was holding. */
    private fun endGeneralSession() {
        aiSession?.stop()
        aiSession = null
        runningLesson = null
        GuideCoordinator.setActiveGuide(null)
        aiHost = null
        aiTarget = null
        aiTargetIdentity = null
        generalSettle?.let(mainHandler::removeCallbacks)
        generalSettle = null
        pendingChange = null
        GuideCoordinator.setGeneralSessionActive(false)
    }

    /**
     * Applies a command from the overlay, then redraws.
     *
     * The redraw happens before the fresh look, because a command can end the
     * session — and a finished guide still has a card to show. Only an active
     * session goes on to read the screen again.
     */
    private inline fun afterCommand(change: () -> Unit) {
        change()
        publish()
        renderWithoutScreen()
        if (engine?.state?.isActive == true) inspectNow()
    }

    /**
     * The area that is app content: the display minus the system bars.
     *
     * Both the visibility test and the global motion estimator work in this
     * space, so the status bar's clock ticking over is never mistaken for the
     * page moving.
     */
    private fun currentViewport(): Bounds? = try {
        val metrics = screenSize()
        val insets = systemInsets()
        Bounds(
            left = insets.left,
            top = insets.top,
            right = metrics.first - insets.right,
            bottom = metrics.second - insets.bottom,
        )
    } catch (_: Exception) {
        null
    }

    /** Re-renders from state alone, for commands that do not need a fresh look. */
    private fun renderWithoutScreen() {
        val currentEngine = engine ?: return
        val metrics = screenSize()
        val insets = systemInsets()
        overlays?.render(
            state = currentEngine.state,
            definition = currentEngine.definition,
            viewport = Bounds(
                left = insets.left,
                top = insets.top,
                right = metrics.first - insets.right,
                bottom = metrics.second - insets.bottom,
            ),
        )
    }

    /**
     * The system bars and cutout, in pixels.
     *
     * Read here and applied in exactly one place, so overlay positions and node
     * bounds never end up in two different coordinate spaces.
     */
    private fun systemInsets(): Insets {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return Insets.NONE
        val windowManager = getSystemService(WindowManager::class.java) ?: return Insets.NONE
        return try {
            val metrics = windowManager.currentWindowMetrics
            val system = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout(),
            )
            Insets(system.left, system.top, system.right, system.bottom)
        } catch (_: Exception) {
            Insets.NONE
        }
    }

    // --------------------------------------------------------------- helpers

    private fun shouldHandle(eventType: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = lastHandledAt[eventType] ?: 0L
        if (now - last < windowMs) return false
        lastHandledAt[eventType] = now
        return true
    }

    private fun isDeviceLocked(): Boolean = try {
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val windowManager = getSystemService(WindowManager::class.java)
            ?: return DEFAULT_SCREEN
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
        } catch (_: Exception) {
            DEFAULT_SCREEN
        }
    }

    companion object {
        /**
         * The bound service, or null.
         *
         * Nulled in both [onUnbind] and [onDestroy], so this never outlives the
         * service it points at. It exists so the app can hand over a guide the
         * moment the person presses the button, rather than waiting for the
         * next accessibility event to arrive.
         */
        @Volatile
        private var instance: DotAccessibilityService? = null

        /** True while the service is bound and able to act. */
        @Volatile
        var isConnected: Boolean = false
            private set

        /**
         * Starts the guide the app has left in [GuideCoordinator], if the
         * service is running. Returns false when it is not, which is the
         * app's cue to send the person to Accessibility settings.
         */
        fun startRequestedGuide(): Boolean {
            val service = instance ?: return false

            // A lesson, from Learn.
            GuideCoordinator.consumeLesson()?.let { lesson ->
                service.mainHandler.post { service.startLesson(lesson) }
                return true
            }

            // A request in the person's own words takes precedence: it is the
            // thing they just typed, and there is no tutorial behind it.
            GuideCoordinator.consumeGoal()?.let { request ->
                service.mainHandler.post { service.startGeneralHelp(request.goal, request.resume, request.taskId) }
                return true
            }

            val guideId = GuideCoordinator.consumeRequest() ?: return false
            service.mainHandler.post { service.startGuide(guideId) }
            return true
        }

        /**
         * Whether a control the tester names survives into a general
         * session's candidate list, and if not, where it was lost.
         *
         * Debug only (refuses unless the build is debuggable), and it reports
         * yes/no and counts about the one label it is given — never any other
         * screen text.
         */
        /** The window census, for a debug diagnostic. Structure only. */
        fun debugWindows(): String? = instance?.windowCensus()

        fun debugProbeCandidate(label: String): String {
            if (!GuideLog.isEnabled) return "probe refused"
            val service = instance ?: return "no service"
            val root = service.rootInActiveWindow ?: return "no window"
            val (w, h) = service.screenSize()
            val screen = NodeHarvester.harvest(root, w, h)
            val nodes = screen.nodes.filter { it.text == label || it.contentDescription == label }
            val observation = com.aaravsekhri.dot.guide.ai.ObservationBuilder.build("probe", screen, 0, "", 0)
            val candidate = observation.candidates.any { it.naming == label }
            // The same walk with every node refreshed from the app, bypassing
            // the service's cache: a different count means the cache is stale.
            fun countFresh(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int): Int {
                if (node == null || depth > 40) return 0
                node.refresh()
                return 1 + (0 until node.childCount).sumOf { countFresh(node.getChild(it), depth + 1) }
            }
            val fresh = countFresh(service.rootInActiveWindow, 0)
            return "probe: harvested=${screen.nodes.size} fresh=$fresh matching=${nodes.size} " +
                nodes.joinToString(" ") { "[vis=${it.isVisibleToUser} click=${it.isClickable} empty=${it.bounds.isEmpty}]" } +
                " candidates=${observation.candidates.size} truncated=${observation.truncated} kept=$candidate"
        }

        /**
         * Shows the production step card for one gesture, for looking at.
         * Debug builds only; the words are LocalWording's, the picture is
         * the real overlay's.
         */
        fun debugShowGestureCard(kind: String) {
            if (!GuideLog.isEnabled) return
            val service = instance ?: return
            service.mainHandler.post {
                val nav = service.deviceContext().navigation
                val (words, gesture) = when (kind) {
                    "up" -> LocalWordingPair(com.aaravsekhri.dot.guide.ai.Reveal.Above)
                    "down" -> LocalWordingPair(com.aaravsekhri.dot.guide.ai.Reveal.Below)
                    "left" -> LocalWordingPair(com.aaravsekhri.dot.guide.ai.Reveal.Left)
                    "right" -> LocalWordingPair(com.aaravsekhri.dot.guide.ai.Reveal.Right)
                    "back" -> com.aaravsekhri.dot.guide.ai.LocalWording.back(nav) to
                        com.aaravsekhri.dot.guide.ai.Gesture.Back(nav)
                    else -> com.aaravsekhri.dot.guide.ai.LocalWording.home(nav) to
                        com.aaravsekhri.dot.guide.ai.Gesture.Home(nav)
                }
                service.overlays?.showRuntimeCard(
                    label = service.getString(com.aaravsekhri.dot.R.string.ai_helping),
                    instruction = words,
                    didNotWorkLabel = service.getString(com.aaravsekhri.dot.R.string.ai_did_not_work),
                    gesture = gesture,
                )
            }
        }

        /**
         * Points the running general session at the control named [label], as
         * a model's answer would. Debug builds only.
         */
        fun debugPointAt(label: String) {
            if (!GuideLog.isEnabled) return
            val service = instance ?: return
            service.mainHandler.post { service.aiSession?.debugPointAt(label) }
        }

        /** A manual step naming a control, as the planner would. Debug builds only. */
        fun debugManual(words: String, label: String) {
            if (!GuideLog.isEnabled) return
            val service = instance ?: return
            service.mainHandler.post { service.aiSession?.debugManual(words, label) }
        }

        @Suppress("FunctionName")
        private fun LocalWordingPair(reveal: com.aaravsekhri.dot.guide.ai.Reveal) =
            com.aaravsekhri.dot.guide.ai.LocalWording.scroll(reveal, "") to
                com.aaravsekhri.dot.guide.ai.Gesture.Slide(
                    reveal.finger(),
                )

        /**
         * Dot is showing one of its own decision screens — consent, "Start
         * something new?", "Carry on where you left off?" — or has closed it.
         *
         * While one is up, the guide's card steps aside completely: it is a
         * choice made in Dot, and a card over it would sit on its buttons.
         * When it closes, the card for wherever the guide is comes back.
         */
        fun setDotDecisionShowing(showing: Boolean) {
            val service = instance ?: return
            service.mainHandler.post {
                if (showing == service.dotDecisionShowing) return@post
                service.dotDecisionShowing = showing
                val session = service.aiSession ?: return@post
                if (showing) {
                    GuideLog.d("overlay") { "Dot's own decision screen is up; the card steps aside" }
                    service.overlays?.hideAll()
                } else if (session.state !is com.aaravsekhri.dot.guide.ai.AiSessionState.AskingClarification) {
                    // Not a question: drawing it again would ask it again,
                    // just after it was answered in Dot. Everything else comes
                    // back as it was — card, outline and demonstration.
                    session.redraw()
                }
            }
        }

        /** A lesson that is not in the catalogue, over the test screens. Debug builds only. */
        fun debugRunLesson(course: com.aaravsekhri.dot.courses.Course, lesson: com.aaravsekhri.dot.courses.Lesson) {
            if (!GuideLog.isEnabled) return
            val service = instance ?: return
            service.mainHandler.post { service.runLesson(course, lesson) }
        }

        /** A question over whatever app is in front, for a device test. Debug builds only. */
        fun debugAsk(question: String, options: List<String>) {
            if (!GuideLog.isEnabled) return
            val service = instance ?: return
            service.mainHandler.post { service.aiSession?.debugAsk(question, options) }
        }

        /** The running session's card commands, for a device test. Debug builds only. */
        fun debugCommand(name: String) {
            if (!GuideLog.isEnabled) return
            val service = instance ?: return
            service.mainHandler.post {
                when (name) {
                    "try_later" -> service.commands.onTryLater()
                    "keep_trying" -> service.commands.onKeepTrying()
                    "next_lesson" -> service.commands.onNextLesson()
                    "pause" -> service.commands.onPause()
                    "move" -> service.overlays?.moveCard()
                }
            }
        }

        /**
         * Something else is being started in its place: the running guide
         * stops with its checkpoint kept, to be carried on later.
         */
        fun suspendGeneralSession() {
            val service = instance ?: return
            service.mainHandler.post {
                service.aiSession?.stop(keepCheckpoint = true, status = com.aaravsekhri.dot.guide.ai.AiSessionStore.STATUS_LATER)
                service.endGeneralSession()
                service.overlays?.hideAll()
                service.positional.onNavigation()
            }
        }

        /** "End guide" from inside the app. */
        fun endGeneralSession() {
            val service = instance ?: return
            service.mainHandler.post {
                service.endGeneralSession()
                service.overlays?.hideAll()
                service.positional.onNavigation()
            }
        }

        /** An answer the person typed in the app, on its way to the session. */
        fun deliverAnswer(): Boolean {
            val service = instance ?: return false
            val answer = GuideCoordinator.consumeAnswer() ?: return false
            service.mainHandler.post { service.aiSession?.answerClarification(answer) }
            return true
        }

        /**
         * Finds the live nodes for a row by its wording, for the benchmark.
         *
         * Debug only, twice over: nothing in the shipped app calls it, and it
         * refuses outright unless the installed package is debuggable. It is
         * here rather than in the debug source set because it needs the live
         * accessibility tree, which only the service has.
         */
        fun debugLocateRow(
            label: String,
        ): Triple<android.view.accessibility.AccessibilityNodeInfo,
            android.view.accessibility.AccessibilityNodeInfo, Bounds>? {
            if (!GuideLog.isEnabled) return null
            val service = instance ?: return null
            return service.locateRow(label, service.currentViewport() ?: return null)
        }

        /** Acceptance harness: clear, then later print, what was drawn. */
        fun debugDrawTrace(dump: Boolean) {
            if (!GuideLog.isEnabled) return
            val service = instance ?: return
            service.mainHandler.post {
                if (dump) service.overlays?.dumpDrawTrace() else service.overlays?.resetDrawTrace()
            }
        }

        /**
         * Runs a guide against whatever is already in front.
         *
         * For acceptance runs against the test fixture, which is not Settings
         * and must not be replaced by it. Identical to an ordinary start from
         * [GuideEngine.beginSearching] onwards — the only thing skipped is
         * opening Settings — so what it exercises is the real guide.
         */
        fun debugStartGuideHere(guideId: String): Boolean {
            if (!GuideLog.isEnabled) return false
            val service = instance ?: return false
            val definition = GuideCatalog.byId(guideId) ?: return false
            service.mainHandler.post {
                service.debugFollowOwnPackage = true
                service.releaseTarget()
                service.lastContentBounds = null
                service.cancelPendingInspection()
                val newEngine = GuideEngine(definition)
                newEngine.start()
                service.engine = newEngine
                service.publish()
                newEngine.beginSearching()
                service.publish()
                service.inspectNow()
            }
            return true
        }

        fun debugStopGuide(): Boolean {
            if (!GuideLog.isEnabled) return false
            val service = instance ?: return false
            service.mainHandler.post {
                service.debugFollowOwnPackage = false
                service.releaseTarget()
                service.engine?.cancel()
                service.publish()
                service.overlays?.hideAll()
            }
            return true
        }

        /**
         * Packages Dot never draws over. Permission dialogs and system UI are
         * decisions the person must make with nothing on top of them.
         */
        /**
         * Dialogs that genuinely take the foreground for a decision. Dot takes
         * everything down rather than sit on top of one.
         */
        private val FOREGROUND_DIALOG_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
        )

        /**
         * Packages whose events say nothing about what the person is looking
         * at. Ignored without disturbing anything on screen.
         */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
        )

        /** How often, at most, a content change looks for the outlined control. */
        private const val VANISH_CHECK_MS = 150L

        /** How far up from a label the clickable row is looked for. */
        private const val PROBE_ANCESTOR_DEPTH = 4

        /**
         * Shortest gap between two readings of the followed control.
         *
         * One display frame, because a position nobody will draw is a round
         * trip into the app the person is trying to use, made on the same main
         * thread that app is scrolling with.
         *
         * Measured three ways on the emulator. Unpaced, the follower made about
         * 1,100 readings a second to produce 58 genuinely new positions. At 8ms
         * it made 354 for the same 58. At one frame it drew 41.5 positions a
         * second with a median error of 5px — slightly better than at 8ms,
         * because it was no longer competing with the app it was asking — and
         * halved Dot's processor use during a scroll, from 63% of a core to 30%.
         */
        private const val FOLLOW_INTERVAL_NANOS = 16_000_000L

        /** How often the follower is checked for having gone quiet. */
        private const val FOLLOW_WATCHDOG_MS = 32L

        /**
         * Fewer visible nodes than this and the reading says nothing.
         *
         * A real page has dozens. A window in the middle of being rebuilt has
         * one or two, and every question asked of it gets a misleading answer.
         */
        private const val MIN_NODES_TO_JUDGE = 4

        /** After this many thin readings in a row, take one at face value. */
        private const val MAX_THIN_SCANS_IN_A_ROW = 12

        /**
         * The tallest a single row may plausibly be, as a share of the list.
         *
         * Used to notice when the search for a row's clickable container has
         * climbed past the row and reached the page.
         */
        private const val MAX_ROW_FRACTION = 0.5f

        /**
         * How long a general session waits for the screen to stop.
         *
         * Longer than the scrolling settle on purpose. That one decides when an
         * outline may come back, and wants to feel immediate; this one decides
         * when to spend a planning request, and wants to be sure the page it is
         * describing is the page that will still be there when the answer
         * arrives.
         */
        private const val GENERAL_SETTLE_MS = 700L

        private val TYPING_KINDS = setOf(
            com.aaravsekhri.dot.guide.ai.AiSessionController.KIND_TYPE,
            com.aaravsekhri.dot.guide.ai.AiSessionController.KIND_AUTH_TYPE,
        )

        /** The longest content that keeps changing can postpone a look. */
        private const val GENERAL_SETTLE_MAX_MS = 1_500L

        /** How often, and how many times, a keyboard that just moved is measured again. */
        private const val KEYBOARD_RECHECK_MS = 250L
        private const val KEYBOARD_RECHECKS = 6

        private const val WINDOW_DEBOUNCE_MS = 180L
        private const val SCROLL_DEBOUNCE_MS = 120L
        private const val FIRST_INSPECTION_DELAY_MS = 700L

        /** How long a new page is given to lay itself out before being read. */
        private const val PAGE_SETTLE_MS = 260L

        /** How long a flung list is given to come to rest. */
        private const val SETTLE_AFTER_SCROLL_MS = 320L

        /** How long after the last scroll event the page counts as settled. */
        /**
         * How soon after a scroll event to ask whether the page has stopped.
         *
         * Comfortably past the state machine's own threshold, so the first
         * check after the last event is the one that ends the session, and
         * re-armed while the answer is still no.
         */
        private const val IDLE_AFTER_SCROLL_MS = 640L

        /** How often to keep asking while the page is still moving. */
        private const val IDLE_RECHECK_MS = 120L

        /** How soon to look again while a candidate is being confirmed. */
        private const val CONFIRMATION_RESCAN_MS = 90L

        /** How often to check whether a tap produced the page it should have. */
        private const val AWAITING_DESTINATION_RECHECK_MS = 150L

        /** How long a page is allowed to re-measure after a value change. */
        private const val VALUE_CHANGE_SETTLE_MS = 900L

        /** How much each further relayout event extends that window. */
        private const val VALUE_CHANGE_RESETTLE_MS = 500L

        /**
         * However many events arrive, a value change stops excusing them here.
         *
         * A font-size relayout on this emulator works through the list in about
         * a second; two and a half is generous and bounded.
         */
        private const val VALUE_CHANGE_MAX_MS = 2_500L

        /**
         * The gap between the two scans the stabilizer needs before it will
         * show a target. Long enough that a page mid-layout will have changed,
         * short enough not to feel like a delay.
         */
        private const val SETTLE_RESCAN_MS = 150L

        /** How long after the last scroll event the target keeps being followed. */
        private const val TRACKING_BURST_MS = 400L

        /**
         * The gap between passes while following a scroll. Fast enough that the
         * outline stays with the setting, slow enough that each pass is one
         * cheap tree read rather than per-frame polling.
         */
        private const val TRACKING_INTERVAL_MS = 60L

        /**
         * How often to look again while a scroll cue is up. Slow enough to be
         * nothing on a battery, quick enough that the cue gives way promptly
         * once the target arrives.
         */
        private const val CUE_RECHECK_MS = 400L

        /** How far the fruitless-scan interval may double. */
        private const val MAX_BACKOFF_DOUBLINGS = 4

        /** And the ceiling it may never pass, so the guide stays responsive. */
        private const val MAX_RESCAN_INTERVAL_MS = 1_500L

        /** How soon after an interruption the guide redraws itself. */
        private const val AFTER_INTERRUPT_MS = 120L

        /**
         * How long a rebuilt page may take to bring its control back before
         * Dot gives up and calls it navigation. Long enough for Settings to
         * re-inflate after a font-size change, short enough that a genuine
         * departure is not left with a stale outline.
         */
        private const val ACTION_DEBUG_VISUAL_ON = "com.aaravsekhri.dot.DEBUG_VISUAL_ON"
        private const val ACTION_DEBUG_VISUAL_START = "com.aaravsekhri.dot.DEBUG_VISUAL_START"
        private const val ACTION_DEBUG_VISUAL_OFF = "com.aaravsekhri.dot.DEBUG_VISUAL_OFF"
        private const val ACTION_DEBUG_VISUAL_METRICS = "com.aaravsekhri.dot.DEBUG_VISUAL_METRICS"

        private const val CONTINUITY_GRACE_MS = 1_200L

        /** Drift below this is left alone rather than snapped. */
        private const val RECONCILE_TOLERANCE_PX = 3

        /** How long after a scroll event the content is assumed to still be moving. */
        private const val MOTION_EXPECTED_MS = 350L


        private val DEFAULT_SCREEN = 1080 to 2400
    }
}
