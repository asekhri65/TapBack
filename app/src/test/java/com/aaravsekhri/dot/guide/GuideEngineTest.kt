package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.model.GuideDefinition
import com.aaravsekhri.dot.guide.engine.GuideEngine
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.GuideSessionState
import com.aaravsekhri.dot.guide.model.RecoveryReason
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.model.stepIndexOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine must only ever move forward on evidence. Someone reading slowly
 * is not someone who has finished, so most of these check that it stays put.
 */
class GuideEngineTest {

    private fun engineOnStep(stepIndex: Int): GuideEngine =
        GuideEngine(GuideCatalog.textSize).apply {
            start()
            beginSearching()
            repeat(stepIndex) { advanceByTappingCurrentTarget(this) }
        }

    /** The Settings home screen, with Display on it. */
    private fun settingsHome() = ScreenBuilder().apply {
        node(text = "Search settings", bounds = Bounds(60, 20, 1020, 90))
        settingsRow("Network & internet", top = 100)
        settingsRow("Apps", top = 400)
        settingsRow("Display & touch", top = 700)
    }

    /** The Display screen, with the text size row on it. */
    private fun displayScreen() = ScreenBuilder().apply {
        settingsRow("Brightness level", top = 100)
        settingsRow("Screen timeout", top = 400)
        settingsRow("Display size and text", top = 700)
    }

    /** The page one tap further on, as its signature describes it. */
    private fun displaySizeScreen() = ScreenBuilder().apply {
        node(text = "Font size", bounds = Bounds(60, 300, 600, 380))
        node(text = "Make text bigger or smaller", bounds = Bounds(60, 390, 800, 450))
        node(
            className = "android.widget.SeekBar",
            viewId = "com.android.settings:id/font_size_slider",
            bounds = Bounds(60, 500, 1020, 620),
        )
    }

    /** A tap on a row, described the way the framework would describe it. */
    private fun clickOn(label: String) = com.aaravsekhri.dot.guide.model.ClickDescriptor(
        packageName = "com.android.settings",
        className = "android.widget.LinearLayout",
        viewId = null,
        text = null,
        contentDescription = null,
        labels = listOf(label),
    )

    /** The text size screen, with the slider on it. */
    private fun fontSizeScreen() = ScreenBuilder().apply {
        node(text = "Font size", bounds = Bounds(60, 300, 600, 380))
        node(text = "Make text bigger or smaller", bounds = Bounds(60, 390, 800, 450))
        node(
            text = null,
            bounds = Bounds(60, 250, 600, 290),
        )
        node(
            className = "android.widget.SeekBar",
            viewId = "com.android.settings:id/font_size_slider",
            bounds = Bounds(60, 500, 1020, 620),
        )
    }

    /**
     * Two looks at the same screen.
     *
     * The engine will not point at anything until a candidate has won twice,
     * which is exactly the behaviour that stops the outline flickering between
     * controls while a page settles. Tests go through this so they exercise
     * the real path.
     */
    private fun GuideEngine.settleOn(screen: com.aaravsekhri.dot.guide.model.ScreenSnapshot) {
        onScreenSnapshot(screen)
        onScreenSnapshot(screen)
    }

    /**
     * Taps the current step's control and lets the page it leads to arrive.
     *
     * Both halves are needed now: a tap moves the engine to
     * AwaitingDestination, and it is the destination page that finishes the
     * step. That split is the whole point — a tap alone is an intention, and
     * the page arriving is the evidence it worked.
     */
    private fun advanceByTappingCurrentTarget(engine: GuideEngine) {
        when (engine.state.stepIndexOrNull) {
            0 -> {
                engine.onScreenSnapshot(settingsHome().build())
                engine.onUserActivated(clickOn("Display & touch"), 0L)
                engine.onScreenSnapshot(displayScreen().build(), nowMs = 10L)
            }

            1 -> {
                engine.onScreenSnapshot(displayScreen().build())
                engine.onUserActivated(clickOn("Display size and text"), 0L)
                engine.onScreenSnapshot(displaySizeScreen().build(), nowMs = 10L)
            }

            else -> {
                engine.onScreenSnapshot(fontSizeScreen().build())
                engine.confirmStepDone()
            }
        }
    }

    // ------------------------------------------------------------- progress

    @Test
    fun `a new session starts inactive`() {
        val engine = GuideEngine(GuideCatalog.textSize)
        assertEquals(GuideSessionState.Inactive, engine.state)
    }

    @Test
    fun `finding the first target puts the guide in front of the user`() {
        val engine = engineOnStep(0)

        engine.settleOn(settingsHome().build())

        val state = engine.state
        assertTrue(state is GuideSessionState.TargetFound)
        assertEquals(0, state.stepIndexOrNull)
    }

    @Test
    fun `placing the highlight hands the turn to the user`() {
        val engine = engineOnStep(0)
        engine.settleOn(settingsHome().build())
        val found = engine.state as GuideSessionState.TargetFound

        engine.onHighlightPlaced(found.region)

        assertTrue(engine.state is GuideSessionState.WaitingForUser)
    }

    @Test
    fun `tapping the right row waits for the page it leads to`() {
        val engine = engineOnStep(0)
        engine.onScreenSnapshot(settingsHome().build())

        engine.onUserActivated(clickOn("Display & touch"), 0L)

        // A tap is an intention, not a completion.
        assertTrue(engine.state is GuideSessionState.AwaitingDestination)
        assertEquals(0, engine.state.stepIndexOrNull)

        engine.onScreenSnapshot(displayScreen().build(), nowMs = 10L)
        assertEquals(1, engine.state.stepIndexOrNull)
    }

    @Test
    fun `a tap advances exactly one step, however many screens arrive`() {
        val engine = engineOnStep(0)
        engine.onScreenSnapshot(settingsHome().build())
        engine.onUserActivated(clickOn("Display & touch"), 0L)

        repeat(5) { engine.onScreenSnapshot(displayScreen().build(), nowMs = 10L + it) }

        assertEquals(1, engine.state.stepIndexOrNull)
    }

    @Test
    fun `arriving on the next screen by any route moves the guide along`() {
        val engine = engineOnStep(0)

        // The person navigated to Display themselves; step 1's target is now visible.
        engine.settleOn(displayScreen().build())

        assertEquals(1, engine.state.stepIndexOrNull)
    }

    @Test
    fun `the last step completes when the user says they are happy`() {
        val engine = engineOnStep(2)
        engine.settleOn(fontSizeScreen().build())

        engine.confirmStepDone()

        assertTrue(engine.state is GuideSessionState.Completed)
    }

    // ---------------------------------------------------------- no progress

    @Test
    fun `tapping something unrelated does not advance`() {
        val engine = engineOnStep(0)
        engine.onScreenSnapshot(settingsHome().build())

        engine.onUserActivated(clickOn("Apps"), 0L)

        assertEquals(0, engine.state.stepIndexOrNull)
        assertTrue(engine.state !is GuideSessionState.AwaitingDestination)
    }

    @Test
    fun `re-reading the same screen does not advance`() {
        val engine = engineOnStep(0)
        val screen = settingsHome().build()

        repeat(5) { engine.onScreenSnapshot(screen) }

        assertEquals(0, engine.state.stepIndexOrNull)
    }

    @Test
    fun `an adjustment is noted but never completes the step by itself`() {
        val engine = engineOnStep(2)
        val screen = fontSizeScreen().build()
        engine.settleOn(screen)
        (engine.state as? GuideSessionState.TargetFound)?.let { engine.onHighlightPlaced(it.region) }

        engine.onUserAdjusted(screen)

        val state = engine.state
        assertTrue(state is GuideSessionState.WaitingForUser)
        assertTrue((state as GuideSessionState.WaitingForUser).adjustmentSeen)
        assertFalse(engine.state is GuideSessionState.Completed)
    }

    @Test
    fun `confirming has no effect on a step the user taps rather than adjusts`() {
        val engine = engineOnStep(0)
        engine.onScreenSnapshot(settingsHome().build())

        engine.confirmStepDone()

        assertEquals(0, engine.state.stepIndexOrNull)
    }

    @Test
    fun `nothing happens while paused`() {
        val engine = engineOnStep(0)
        engine.pause()

        engine.onScreenSnapshot(displayScreen().build())

        assertTrue(engine.state is GuideSessionState.Paused)
        assertEquals(0, engine.state.stepIndexOrNull)
    }

    // ------------------------------------------------------------- recovery

    @Test
    fun `a screen without the target eventually offers help`() {
        val engine = engineOnStep(0)
        val unrelated = ScreenBuilder().apply {
            settingsRow("Dark theme", top = 100)
            settingsRow("Screen timeout", top = 400)
        }.build()

        repeat(3) { engine.onScreenSnapshot(unrelated) }

        val state = engine.state
        assertTrue(state is GuideSessionState.Recovery)
        assertEquals(RecoveryReason.TargetNotFound, (state as GuideSessionState.Recovery).reason)
    }

    @Test
    fun `a target below the fold asks for a scroll rather than showing an error`() {
        val engine = engineOnStep(0)
        val scrollable = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Network & internet", top = 100)
            settingsRow("Apps", top = 400)
        }.build()

        // However many times Dot looks, a scrollable screen keeps the cue
        // rather than escalating at someone who is still scrolling.
        repeat(8) { engine.onScreenSnapshot(scrollable) }

        val state = engine.state
        assertTrue(state is GuideSessionState.ScrollNeeded)
        assertEquals(ScrollDirection.Down, (state as GuideSessionState.ScrollNeeded).direction)
    }

    @Test
    fun `a page that cannot scroll goes to recovery rather than a false cue`() {
        val engine = engineOnStep(0)
        // No scrollable container anywhere: telling someone to scroll would be
        // asking them to do something the screen cannot do.
        val fixed = ScreenBuilder().apply {
            settingsRow("Dark theme", top = 100)
            settingsRow("Screen timeout", top = 400)
        }.build()

        repeat(3) { engine.onScreenSnapshot(fixed) }

        assertTrue(engine.state is GuideSessionState.Recovery)
    }

    @Test
    fun `a step with no declared direction never guesses one`() {
        val undirected = GuideDefinition(
            id = "undirected",
            titleRes = 0,
            steps = listOf(
                GuideCatalog.textSize.steps[0].copy(expectedDirection = null),
            ),
        )
        val engine = GuideEngine(undirected).apply { start(); beginSearching() }
        val scrollable = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Apps", top = 400)
        }.build()

        repeat(3) { engine.onScreenSnapshot(scrollable) }

        assertFalse(engine.state is GuideSessionState.ScrollNeeded)
        assertTrue(engine.state is GuideSessionState.Recovery)
    }

    @Test
    fun `the cue direction comes from the step, not from the screen`() {
        val upwards = GuideDefinition(
            id = "upwards",
            titleRes = 0,
            steps = listOf(
                GuideCatalog.textSize.steps[0].copy(expectedDirection = ScrollDirection.Up),
            ),
        )
        val engine = GuideEngine(upwards).apply { start(); beginSearching() }
        val scrollable = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Apps", top = 400)
        }.build()

        engine.onScreenSnapshot(scrollable)

        assertEquals(
            ScrollDirection.Up,
            (engine.state as GuideSessionState.ScrollNeeded).direction,
        )
    }

    @Test
    fun `scrolling alone does not take the cue away`() {
        val engine = engineOnStep(0)
        val scrollable = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Apps", top = 400)
        }.build()
        engine.onScreenSnapshot(scrollable)
        assertTrue(engine.state is GuideSessionState.ScrollNeeded)

        // A flick of the finger is not arrival. The target is still not on
        // screen, so the cue has to stay where it is.
        engine.onUserScrolled()
        engine.onScreenSnapshot(scrollable)

        assertTrue(engine.state is GuideSessionState.ScrollNeeded)
    }

    @Test
    fun `the cue stays up for as long as the target is out of view`() {
        val engine = engineOnStep(0)
        val scrollable = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Apps", top = 400)
        }.build()

        // Scrolling, scanning, scrolling again — and still nothing to point at.
        repeat(10) {
            engine.onUserScrolled()
            engine.onScreenSnapshot(scrollable)
        }

        assertTrue(engine.state is GuideSessionState.ScrollNeeded)
    }

    @Test
    fun `the cue is replaced only once the target is properly in view`() {
        val engine = engineOnStep(0)
        val offscreen = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Apps", top = 400)
        }.build()
        engine.onScreenSnapshot(offscreen)
        assertTrue(engine.state is GuideSessionState.ScrollNeeded)

        // Creeping into view from the bottom: most of the row is still below
        // the edge, so it is not yet worth outlining.
        val barelyVisible = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Display & touch", top = 2350)
        }.build()
        engine.settleOn(barelyVisible)
        assertTrue(
            "a sliver is not enough to highlight",
            engine.state is GuideSessionState.ScrollNeeded,
        )

        // Fully on screen, and the cue gives way to the highlight.
        val fullyVisible = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Display & touch", top = 900)
        }.build()
        engine.settleOn(fullyVisible)

        assertTrue(engine.state is GuideSessionState.TargetFound)
    }

    @Test
    fun `scrolling the target into view points at it`() {
        val engine = engineOnStep(0)
        val before = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Apps", top = 400)
        }.build()
        engine.onScreenSnapshot(before)
        engine.onUserScrolled()

        val after = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Display & touch", top = 400)
        }.build()
        engine.settleOn(after)

        assertTrue(engine.state is GuideSessionState.TargetFound)
    }

    @Test
    fun `one empty look does not immediately show a recovery card`() {
        val engine = engineOnStep(0)
        val unrelated = ScreenBuilder().apply { settingsRow("Dark theme", top = 100) }.build()

        engine.onScreenSnapshot(unrelated)

        assertTrue(engine.state is GuideSessionState.Searching)
    }

    @Test
    fun `leaving Settings is recognised as leaving, not as a missing target`() {
        val engine = engineOnStep(0)
        val elsewhere = ScreenSnapshot("com.example.otherapp", emptyList(), 1080, 2400)

        engine.onScreenSnapshot(elsewhere)

        val state = engine.state as GuideSessionState.Recovery
        assertEquals(RecoveryReason.LeftExpectedApp, state.reason)
    }

    @Test
    fun `a tap in another app never advances the guide`() {
        val engine = engineOnStep(0)
        val elsewhere = com.aaravsekhri.dot.guide.model.ClickDescriptor(
            packageName = "com.example.otherapp",
            className = "android.widget.LinearLayout",
            viewId = null,
            text = null,
            contentDescription = null,
            labels = listOf("Display & touch"),
        )

        engine.onUserActivated(elsewhere, 0L)

        assertEquals(0, engine.state.stepIndexOrNull)
        assertTrue(engine.state !is GuideSessionState.AwaitingDestination)
    }

    @Test
    fun `asking for help moves to recovery on the same step`() {
        val engine = engineOnStep(1)

        engine.requestHelp()

        val state = engine.state as GuideSessionState.Recovery
        assertEquals(RecoveryReason.UserAskedForHelp, state.reason)
        assertEquals(1, state.stepIndex)
    }

    @Test
    fun `asking for help is not undone by the next look at the screen`() {
        val engine = engineOnStep(0)
        engine.requestHelp()

        // The target is plainly there, but they have said it is not working
        // for them; the card stays until they pick something.
        engine.settleOn(settingsHome().build())

        assertTrue(engine.state is GuideSessionState.Recovery)
    }

    @Test
    fun `leaving the app overrides a help request rather than following them out`() {
        val engine = engineOnStep(0)
        engine.requestHelp()

        engine.onScreenSnapshot(ScreenSnapshot("com.example.otherapp", emptyList(), 1080, 2400))

        val state = engine.state as GuideSessionState.Recovery
        assertEquals(RecoveryReason.LeftExpectedApp, state.reason)
    }

    @Test
    fun `retry looks again at the same step`() {
        val engine = engineOnStep(1)
        engine.requestHelp()

        engine.retry()

        assertTrue(engine.state is GuideSessionState.Searching)
        assertEquals(1, engine.state.stepIndexOrNull)
    }

    @Test
    fun `going back a step returns to the previous one and stops at the first`() {
        val engine = engineOnStep(1)
        engine.requestHelp()

        engine.goBackAStep()
        assertEquals(0, engine.state.stepIndexOrNull)

        engine.goBackAStep()
        assertEquals(0, engine.state.stepIndexOrNull)
    }

    @Test
    fun `recovery clears once the target comes back into view`() {
        val engine = engineOnStep(0)
        val unrelated = ScreenBuilder().apply { settingsRow("Dark theme", top = 100) }.build()
        repeat(3) { engine.onScreenSnapshot(unrelated) }
        assertTrue(engine.state is GuideSessionState.Recovery)

        engine.settleOn(settingsHome().build())

        assertTrue(engine.state is GuideSessionState.TargetFound)
    }

    // ----------------------------------------------------------- navigation

    @Test
    fun `a new page drops the target that belonged to the old one`() {
        val engine = engineOnStep(0)
        engine.settleOn(settingsHome().build())
        assertTrue(engine.state is GuideSessionState.TargetFound)

        // Same wording, different window: a page has replaced another.
        val newPage = ScreenBuilder(windowId = 2).apply {
            settingsRow("Display & touch", top = 700)
        }.build()
        engine.onScreenSnapshot(newPage)

        // Cleared, not carried across — the old outline is gone before
        // anything new is drawn.
        assertTrue(engine.state is GuideSessionState.Searching)
    }

    @Test
    fun `being told the page changed clears the target at once`() {
        val engine = engineOnStep(0)
        engine.settleOn(settingsHome().build())
        assertTrue(engine.state is GuideSessionState.TargetFound)

        engine.onPageChanged()

        assertTrue(engine.state is GuideSessionState.Searching)
    }

    @Test
    fun `a stale scan arriving after a page change cannot redraw the old target`() {
        val engine = engineOnStep(0)
        val oldPage = settingsHome().build()
        engine.settleOn(oldPage)
        val settled = engine.state as GuideSessionState.TargetFound

        engine.onPageChanged()

        // A scan scheduled before the change finally runs, still holding the
        // old page. One look proves nothing, so nothing is drawn.
        engine.onScreenSnapshot(oldPage)

        assertTrue(engine.state is GuideSessionState.Searching)
        assertEquals(settled.stepIndex, engine.state.stepIndexOrNull)
    }

    @Test
    fun `advancing a step clears the previous step's target`() {
        val engine = engineOnStep(0)
        engine.settleOn(settingsHome().build())

        engine.onUserActivated(clickOn("Display & touch"), 0L)
        engine.onScreenSnapshot(displayScreen().build(), nowMs = 10L)

        // Nothing is pointed at until the next step's target settles.
        assertTrue(engine.state is GuideSessionState.Searching)
        assertEquals(1, engine.state.stepIndexOrNull)
    }

    // --------------------------------------------------------- steadiness

    @Test
    fun `repeated content changes do not restart the entrance animation`() {
        val engine = engineOnStep(0)
        val screen = settingsHome().build()
        engine.settleOn(screen)
        val first = engine.state as GuideSessionState.TargetFound
        engine.onHighlightPlaced(first.region)

        // The burst of events Settings emits while a list settles.
        repeat(6) { engine.onScreenSnapshot(screen) }

        val state = engine.state as GuideSessionState.WaitingForUser
        assertEquals(first.targetGeneration, state.targetGeneration)
        assertEquals(first.region.boundsInScreen, state.region.boundsInScreen)
    }

    @Test
    fun `a target reported a pixel or two away is not chased`() {
        val engine = engineOnStep(0)
        engine.settleOn(settingsHome().build())
        val original = (engine.state as GuideSessionState.TargetFound).region.boundsInScreen
        engine.onHighlightPlaced((engine.state as GuideSessionState.TargetFound).region)

        // The same rows, each nudged by two pixels.
        val nudged = ScreenBuilder().apply {
            settingsRow("Network & internet", top = 102)
            settingsRow("Apps", top = 402)
            settingsRow("Display & touch", top = 702)
        }.build()
        repeat(3) { engine.onScreenSnapshot(nudged) }

        assertEquals(original, (engine.state as GuideSessionState.WaitingForUser).region.boundsInScreen)
    }

    @Test
    fun `a target that genuinely moves is followed`() {
        val engine = engineOnStep(0)
        engine.settleOn(settingsHome().build())
        val original = (engine.state as GuideSessionState.TargetFound).region.boundsInScreen
        engine.onHighlightPlaced((engine.state as GuideSessionState.TargetFound).region)

        // A real scroll: the row is now hundreds of pixels up the page.
        val scrolled = ScreenBuilder().apply {
            settingsRow("Display & touch", top = 200)
        }.build()
        engine.onScreenSnapshot(scrolled)
        engine.onScreenSnapshot(scrolled)

        val moved = (engine.state as GuideSessionState.WaitingForUser).region.boundsInScreen
        assertEquals(Bounds(0, 200, 1080, 400), moved)
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    fun `exiting cancels the session`() {
        val engine = engineOnStep(0)
        engine.cancel()
        assertTrue(engine.state is GuideSessionState.Cancelled)
    }

    @Test
    fun `a restored session comes back paused rather than pointing somewhere stale`() {
        val engine = GuideEngine(GuideCatalog.textSize)

        engine.restoreConservatively(stepIndex = 1)

        assertTrue(engine.state is GuideSessionState.Paused)
        assertEquals(1, engine.state.stepIndexOrNull)
    }

    @Test
    fun `a restored step index outside the guide is clamped`() {
        val engine = GuideEngine(GuideCatalog.textSize)

        engine.restoreConservatively(stepIndex = 99)

        assertEquals(GuideCatalog.textSize.stepCount - 1, engine.state.stepIndexOrNull)
    }
}
