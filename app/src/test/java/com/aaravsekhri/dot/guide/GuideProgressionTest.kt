package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.engine.GuideEngine
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ClickDescriptor
import com.aaravsekhri.dot.guide.model.GuideSessionState
import com.aaravsekhri.dot.guide.model.stepIndexOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting from one step to the next.
 *
 * The guide sat on step one indefinitely, and the reason was structural: it
 * waited for a click, then read the screen, then looked for the tapped control
 * in what it had just read. A tap on a settings row navigates, so the control
 * was gone and so was the target, and the lookup failed every single time.
 *
 * The rules below are what replaced that. A tap is described while the event
 * is still valid; a tap is an intention rather than a completion; and the page
 * that arrives is what decides whether the step is done.
 */
class GuideProgressionTest {

    /** The root Settings list, as its signature describes it. */
    private fun settingsHome() = ScreenBuilder().apply {
        node(text = "Search settings", bounds = Bounds(60, 20, 1020, 90))
        settingsRow("Network & internet", top = 100)
        settingsRow("Display & touch", top = 700)
        settingsRow("Accessibility", top = 1000)
    }.build()

    /** The Display & touch page. */
    private fun displayPage() = ScreenBuilder().apply {
        settingsRow("Brightness level", top = 100)
        settingsRow("Screen timeout", top = 400)
        settingsRow("Display size and text", top = 700)
    }.build()

    /** The Display size and text page, with the font slider on it. */
    private fun fontPage() = ScreenBuilder().apply {
        node(text = "Font size", bounds = Bounds(60, 300, 600, 380))
        node(text = "Make text bigger or smaller", bounds = Bounds(60, 390, 800, 450))
        node(
            className = "android.widget.SeekBar",
            viewId = "com.android.settings:id/font_size_slider",
            bounds = Bounds(60, 500, 1020, 620),
        )
    }.build()

    private fun click(label: String, pkg: String = "com.android.settings") = ClickDescriptor(
        packageName = pkg,
        className = "android.widget.LinearLayout",
        viewId = null,
        text = null,
        contentDescription = null,
        labels = listOf(label),
    )

    private fun started(): GuideEngine {
        val engine = GuideEngine(GuideCatalog.textSize)
        engine.start()
        engine.beginSearching()
        engine.onScreenSnapshot(settingsHome())
        return engine
    }

    // ------------------------------------------------------------- forward

    @Test
    fun `tapping the exact target advances exactly one step`() {
        val engine = started()

        engine.onUserActivated(click("Display & touch"), 0L)
        assertTrue(engine.state is GuideSessionState.AwaitingDestination)

        engine.onScreenSnapshot(displayPage(), nowMs = 20L)
        assertEquals(1, engine.state.stepIndexOrNull)
    }

    @Test
    fun `an unrelated tap does not advance and does not start waiting`() {
        val engine = started()

        engine.onUserActivated(click("Accessibility"), 0L)

        assertEquals(0, engine.state.stepIndexOrNull)
        assertTrue(engine.state !is GuideSessionState.AwaitingDestination)
    }

    @Test
    fun `a tap in another app is ignored entirely`() {
        val engine = started()

        engine.onUserActivated(click("Display & touch", pkg = "com.example.other"), 0L)

        assertEquals(0, engine.state.stepIndexOrNull)
        assertTrue(engine.state !is GuideSessionState.AwaitingDestination)
    }

    @Test
    fun `the destination alone advances when no click event arrives`() {
        val engine = started()

        // No click at all: the page simply changes underneath.
        engine.onScreenSnapshot(displayPage(), nowMs = 20L)

        assertEquals(1, engine.state.stepIndexOrNull)
    }

    @Test
    fun `a target disappearing during a scroll never advances`() {
        val engine = started()

        // The row scrolls out of view. The page is the same page.
        val scrolledAway = ScreenBuilder().apply {
            node(text = "Search settings", bounds = Bounds(60, 20, 1020, 90))
            settingsRow("Network & internet", top = 100)
            settingsRow("Accessibility", top = 700)
        }.build()
        repeat(3) { engine.onScreenSnapshot(scrolledAway, nowMs = 20L + it) }

        assertEquals(0, engine.state.stepIndexOrNull)
    }

    @Test
    fun `rapid screens cannot skip two steps at once`() {
        val engine = started()
        engine.onUserActivated(click("Display & touch"), 0L)

        // Both later pages arrive in a burst.
        engine.onScreenSnapshot(displayPage(), nowMs = 20L)
        engine.onScreenSnapshot(fontPage(), nowMs = 25L)
        engine.onScreenSnapshot(fontPage(), nowMs = 30L)

        // The font page is step two's destination, so at most one more step —
        // never a jump from step zero to the end.
        assertTrue("landed on ${engine.state.stepIndexOrNull}", engine.state.stepIndexOrNull!! <= 2)
    }

    @Test
    fun `the whole three-step path can be walked`() {
        val engine = started()

        engine.onUserActivated(click("Display & touch"), 0L)
        engine.onScreenSnapshot(displayPage(), nowMs = 20L)
        assertEquals(1, engine.state.stepIndexOrNull)

        engine.onUserActivated(click("Display size and text"), 30L)
        engine.onScreenSnapshot(fontPage(), nowMs = 50L)
        assertEquals(2, engine.state.stepIndexOrNull)

        engine.onScreenSnapshot(fontPage(), nowMs = 60L)
        engine.confirmStepDone()
        assertTrue(engine.state is GuideSessionState.Completed)
    }

    // ------------------------------------------------------ failure to move

    @Test
    fun `a tap that goes nowhere returns to the same step`() {
        val engine = started()
        engine.onUserActivated(click("Display & touch"), 0L)

        // The page never changes. After the timeout the step resumes.
        engine.onScreenSnapshot(settingsHome(), nowMs = 100L)
        assertTrue(engine.state is GuideSessionState.AwaitingDestination)

        engine.onScreenSnapshot(settingsHome(), nowMs = GuideEngine.DESTINATION_TIMEOUT_MS + 1)

        assertEquals("must not skip forward", 0, engine.state.stepIndexOrNull)
        assertTrue(engine.state !is GuideSessionState.AwaitingDestination)
    }

    // ---------------------------------------------------------------- back

    @Test
    fun `going back returns to the earlier step`() {
        val engine = started()
        engine.onUserActivated(click("Display & touch"), 0L)
        engine.onScreenSnapshot(displayPage(), nowMs = 20L)
        assertEquals(1, engine.state.stepIndexOrNull)

        // Back to the root list.
        engine.onScreenSnapshot(settingsHome(), nowMs = 40L)

        assertEquals(0, engine.state.stepIndexOrNull)
        assertTrue(engine.state is GuideSessionState.Searching)
    }

    @Test
    fun `going back two pages returns two steps`() {
        val engine = started()
        engine.onUserActivated(click("Display & touch"), 0L)
        engine.onScreenSnapshot(displayPage(), nowMs = 20L)
        engine.onUserActivated(click("Display size and text"), 30L)
        engine.onScreenSnapshot(fontPage(), nowMs = 50L)
        assertEquals(2, engine.state.stepIndexOrNull)

        engine.onScreenSnapshot(settingsHome(), nowMs = 70L)

        assertEquals(0, engine.state.stepIndexOrNull)
    }

    // ------------------------------------------------------- value changes

    @Test
    fun `changing the font size value is not navigation`() {
        val engine = started()
        engine.onUserActivated(click("Display & touch"), 0L)
        engine.onScreenSnapshot(displayPage(), nowMs = 20L)
        engine.onUserActivated(click("Display size and text"), 30L)
        engine.onScreenSnapshot(fontPage(), nowMs = 50L)
        assertEquals(2, engine.state.stepIndexOrNull)

        // Several notches: the page rebuilds each time but is the same page.
        repeat(4) { engine.onScreenSnapshot(fontPage(), nowMs = 60L + it * 10) }

        assertEquals("still on the font size step", 2, engine.state.stepIndexOrNull)
        assertTrue(engine.state !is GuideSessionState.AwaitingDestination)
    }

    @Test
    fun `a stale tap cannot advance a guide that has moved on`() {
        val engine = started()
        engine.onUserActivated(click("Display & touch"), 0L)
        engine.onScreenSnapshot(displayPage(), nowMs = 20L)
        assertEquals(1, engine.state.stepIndexOrNull)

        // The click event for step one arrives late, after step one is done.
        engine.onUserActivated(click("Display & touch"), 25L)

        assertEquals("must not advance again", 1, engine.state.stepIndexOrNull)
    }
}
