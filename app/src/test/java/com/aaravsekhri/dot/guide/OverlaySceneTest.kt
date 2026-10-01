package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.engine.GuideEngine
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.GuideSessionState
import com.aaravsekhri.dot.guide.model.Insets
import com.aaravsekhri.dot.guide.model.ScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is on screen must be a function of the guide's state and nothing else.
 *
 * The controller that applies these is Android-bound, so these exercise the
 * rule one level down: for any state, exactly one set of layers is implied, and
 * two states that mean the same thing imply the same set. That is what makes it
 * impossible to end up with two highlights, two Pebbles, or a layer left behind
 * by whichever branch happened to run last.
 */
class OverlaySceneTest {

    private val insets = Insets(top = 100, bottom = 120)

    /**
     * Which positional layers a state calls for. Mirrors the controller's
     * scene rules without needing a Context.
     */
    private fun layersFor(state: GuideSessionState): Set<String> = when (state) {
        is GuideSessionState.TargetFound,
        is GuideSessionState.WaitingForUser,
        -> setOf("highlight", "card")

        is GuideSessionState.ScrollNeeded -> setOf("cue", "card")

        // Between a tap and the page it opens there is nothing honest to point
        // at: the control tapped belongs to the page being left, and the next
        // step's control does not exist yet.
        is GuideSessionState.AwaitingDestination -> setOf("card")

        is GuideSessionState.Searching,
        is GuideSessionState.Starting,
        is GuideSessionState.Paused,
        is GuideSessionState.Recovery,
        is GuideSessionState.Completed,
        -> setOf("card")

        GuideSessionState.Inactive,
        is GuideSessionState.Cancelled,
        is GuideSessionState.Failed,
        -> emptySet()
    }

    /** A guide of just the Font size step, so the page under test is step one. */
    private fun engineOnFontSizePage(): GuideEngine {
        val definition = com.aaravsekhri.dot.guide.model.GuideDefinition(
            id = "font-size-only",
            titleRes = 0,
            steps = listOf(GuideCatalog.textSize.steps[2]),
        )
        val engine = GuideEngine(definition)
        engine.start()
        engine.beginSearching()
        return engine
    }

    private fun fontSizePage(fontTop: Int = 900) = ScreenBuilder().apply {
        scrollingList()
        fontSizeSection(fontTop = fontTop, displaySizeTop = fontTop + 700)
    }.build()

    @Test
    fun `the highlight and the scroll cue can never be shown together`() {
        listOf(
            GuideSessionState.ScrollNeeded("g", 0, ScrollDirection.Down),
            GuideSessionState.Searching("g", 0),
        ).forEach { state ->
            val layers = layersFor(state)
            assertFalse(
                "$state must not ask for both",
                layers.contains("highlight") && layers.contains("cue"),
            )
        }
    }

    @Test
    fun `searching shows nothing positional at all`() {
        val layers = layersFor(GuideSessionState.Searching("g", 0))

        assertFalse(layers.contains("highlight"))
        assertFalse(layers.contains("cue"))
    }

    @Test
    fun `pausing removes every positional layer`() {
        val layers = layersFor(GuideSessionState.Paused("g", 0))

        assertEquals(setOf("card"), layers)
    }

    @Test
    fun `exiting removes every layer`() {
        assertTrue(layersFor(GuideSessionState.Cancelled("g")).isEmpty())
        assertTrue(layersFor(GuideSessionState.Inactive).isEmpty())
    }

    @Test
    fun `the same state always asks for the same layers`() {
        val state = GuideSessionState.ScrollNeeded("g", 0, ScrollDirection.Down)

        assertEquals(layersFor(state), layersFor(state.copy()))
    }

    // ------------------------------------------------- state-driven changes

    @Test
    fun `a window change clears the highlight before anything new is drawn`() {
        val engine = engineOnFontSizePage()
        engine.onScreenSnapshot(fontSizePage(), insets)
        engine.onScreenSnapshot(fontSizePage(), insets)

        // The guide is pointing at something on this page.
        val before = engine.state
        assertTrue(before is GuideSessionState.TargetFound || before is GuideSessionState.WaitingForUser)
        assertTrue(layersFor(before).contains("highlight"))

        engine.onPageChanged()

        assertFalse(
            "nothing positional survives the page change",
            layersFor(engine.state).contains("highlight"),
        )
    }

    @Test
    fun `a stale scan after a window change cannot bring the old highlight back`() {
        val engine = engineOnFontSizePage()
        val page = fontSizePage()
        engine.onScreenSnapshot(page, insets)
        engine.onScreenSnapshot(page, insets)

        engine.onPageChanged()

        // A scan queued before the change finally arrives, still holding the
        // old page. One look proves nothing, so nothing is drawn.
        engine.onScreenSnapshot(page, insets)

        assertFalse(layersFor(engine.state).contains("highlight"))
    }

    @Test
    fun `following a target does not change its appearance key`() {
        val engine = engineOnFontSizePage()
        engine.onScreenSnapshot(fontSizePage(fontTop = 900), insets)
        engine.onScreenSnapshot(fontSizePage(fontTop = 900), insets)
        val first = engine.state as GuideSessionState.TargetFound
        engine.onHighlightPlaced(first.region)

        // Scrolled a long way up the page: same setting, new position.
        engine.onScreenSnapshot(fontSizePage(fontTop = 500), insets)

        val later = engine.state as GuideSessionState.WaitingForUser
        assertEquals(
            "the same target must not pop in again",
            first.targetGeneration,
            later.targetGeneration,
        )
        assertTrue("and it followed", later.region.boundsInScreen.top < first.region.boundsInScreen.top)
    }

    @Test
    fun `the highlighted region is the whole setting, not the slider`() {
        val engine = engineOnFontSizePage()
        engine.onScreenSnapshot(fontSizePage(fontTop = 900), insets)
        engine.onScreenSnapshot(fontSizePage(fontTop = 900), insets)

        val region = (engine.state as GuideSessionState.TargetFound).region

        assertNotNull(region)
        // Title at 900, slider ending at 1180.
        assertTrue("title included", region.boundsInScreen.top <= 900)
        assertTrue("slider included", region.boundsInScreen.bottom >= 1180)
        // Display size starts at 1600 and must stay out.
        assertTrue("next setting excluded", region.boundsInScreen.bottom < 1600)
    }

    @Test
    fun `the viewport used for visibility comes from the insets`() {
        val screen = fontSizePage()

        val viewport = screen.viewport(insets)

        assertEquals(100, viewport.top)
        assertEquals(screen.screenHeight - 120, viewport.bottom)
    }

    @Test
    fun `a region below the visible area is not treated as visible`() {
        val screen = fontSizePage(fontTop = 2300)
        val engine = engineOnFontSizePage()

        repeat(3) { engine.onScreenSnapshot(screen, insets) }

        // Mostly under the navigation bar: keep asking for the scroll.
        assertTrue(engine.state is GuideSessionState.ScrollNeeded)
        assertNull(
            "and nothing is outlined",
            (engine.state as? GuideSessionState.TargetFound)?.region,
        )
    }
}
