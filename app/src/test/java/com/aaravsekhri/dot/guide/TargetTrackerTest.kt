package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.tracking.TargetTracker
import com.aaravsekhri.dot.guide.tracking.TrackerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracker's job is to keep pointing at the same thing. Once it has settled
 * on a target it stops asking "what scores highest here" and starts asking
 * "where has that gone" — which is what stops the outline hopping between a
 * setting's title, its description and its slider, all of which say the same
 * words.
 */
class TargetTrackerTest {

    private val displaySpec = GuideCatalog.textSize.steps[0].target
    private val fontSizeSpec = GuideCatalog.textSize.steps[2].target
    private val viewport = Bounds(0, 0, 1080, 2400)

    private fun settingsAt(top: Int) = ScreenBuilder().apply {
        scrollingList()
        settingsRow("Display", top = top)
    }.build()

    private fun TargetTracker.look(screen: com.aaravsekhri.dot.guide.model.ScreenSnapshot) =
        onScreen(screen, displaySpec, viewport, ScrollDirection.Down)

    @Test
    fun `nothing is pointed at until a candidate has been seen twice`() {
        val tracker = TargetTracker()

        assertTrue(tracker.look(settingsAt(400)) !is TrackerState.TargetVisible)
        assertTrue(tracker.look(settingsAt(400)) is TrackerState.TargetVisible)
    }

    @Test
    fun `the identity settled on is remembered`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(400)) }

        assertNotNull(tracker.trackedIdentity)
    }

    @Test
    fun `identity survives the page scrolling underneath it`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(400)) }
        val original = tracker.trackedIdentity

        listOf(360, 300, 240, 180).forEach { top -> tracker.look(settingsAt(top)) }

        assertEquals(original, tracker.trackedIdentity)
    }

    @Test
    fun `the region follows a scroll straight away, without waiting for it to stop`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(400)) }

        val state = tracker.look(settingsAt(250)) as TrackerState.TargetVisible

        assertEquals(250, state.region.boundsInScreen.top)
    }

    @Test
    fun `settling on a target does not keep bumping the generation`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(400)) }
        val generation = tracker.generation

        // Following it through a scroll is the same target, not a new one.
        listOf(360, 300, 240).forEach { tracker.look(settingsAt(it)) }

        assertEquals(generation, tracker.generation)
    }

    @Test
    fun `a target reported a pixel or two away is not chased`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(400)) }

        val state = tracker.look(settingsAt(402)) as TrackerState.TargetVisible

        assertEquals(400, state.region.boundsInScreen.top)
    }

    @Test
    fun `a target missing for a moment is held rather than torn down`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(400)) }

        val empty = ScreenBuilder().apply { scrollingList() }.build()
        val state = tracker.look(empty)

        assertTrue(state is TrackerState.TargetTemporarilyLost)
        assertEquals(400, (state as TrackerState.TargetTemporarilyLost).lastRegion.boundsInScreen.top)
    }

    @Test
    fun `a target gone for good is let go of`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(400)) }

        val empty = ScreenBuilder().apply { scrollingList() }.build()
        repeat(5) { tracker.look(empty) }

        assertNull(tracker.trackedIdentity)
        assertTrue(tracker.state is TrackerState.ScrollNeeded)
    }

    @Test
    fun `a candidate awaiting confirmation is still searching, not missing`() {
        val tracker = TargetTracker()

        // The target is plainly on screen; it has simply not been confirmed
        // twice yet. Calling that "not found" would put a scroll cue up with
        // the thing sitting in full view.
        val state = tracker.look(settingsAt(400))

        assertTrue(state is TrackerState.Searching)
    }

    @Test
    fun `a target barely on screen asks for more scrolling rather than a sliver of outline`() {
        val tracker = TargetTracker()
        // The row is 200 tall and only 50 of it is above the bottom edge.
        // Two looks, because a candidate is confirmed before it is judged.
        tracker.look(settingsAt(2350))
        val state = tracker.look(settingsAt(2350))

        assertTrue(state is TrackerState.ScrollNeeded)
    }

    @Test
    fun `no scrollable content means no scroll cue`() {
        val tracker = TargetTracker()
        val fixed = ScreenBuilder().apply { settingsRow("Apps", top = 400) }.build()

        val state = tracker.onScreen(fixed, displaySpec, viewport, ScrollDirection.Down)

        assertTrue(state is TrackerState.Searching)
    }

    @Test
    fun `a step with no declared direction is never given one`() {
        val tracker = TargetTracker()
        val scrollable = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Apps", top = 400)
        }.build()

        val state = tracker.onScreen(scrollable, displaySpec, viewport, direction = null)

        assertTrue(state is TrackerState.Searching)
    }

    @Test
    fun `a shown target survives being scrolled partly out of view`() {
        val tracker = TargetTracker()
        // Settled while fully in view.
        repeat(2) { tracker.look(settingsAt(1000)) }
        assertTrue(tracker.state is TrackerState.TargetVisible)

        // Scrolled so a little over half the row remains: below the bar for
        // getting on screen, above the bar for being given up.
        val state = tracker.look(settingsAt(2290))

        assertTrue("must not flip to the cue near the threshold", state is TrackerState.TargetVisible)
    }

    @Test
    fun `a target that genuinely leaves is released to the cue`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(1000)) }

        // Scrolled off the page altogether, and still gone after the few scans
        // a list is allowed to drop a row for while it re-lays itself out.
        var state = tracker.look(settingsAt(2600))
        repeat(4) { state = tracker.look(settingsAt(2600)) }

        assertTrue(state is TrackerState.ScrollNeeded)
    }

    @Test
    fun `hysteresis means arriving takes more than staying`() {
        // A row 200 pixels tall with its top at 2340 has 60 of them on a
        // 2400-pixel screen: thirty per cent, which sits between the two
        // thresholds. Arriving needs thirty-five; staying needs twenty-five.
        val partly = 2340

        // Arriving: thirty per cent is not yet enough to put it on screen.
        val arriving = TargetTracker()
        repeat(2) { arriving.look(settingsAt(partly)) }
        assertTrue(arriving.state is TrackerState.ScrollNeeded)

        // Staying: that same thirty per cent keeps an established target up.
        val staying = TargetTracker()
        repeat(2) { staying.look(settingsAt(1000)) }
        staying.look(settingsAt(partly))
        assertTrue(staying.state is TrackerState.TargetVisible)
    }

    @Test
    fun `a row half off the edge is still outlined, not swapped for a cue`() {
        // Half of it remains: enough to recognise and to reach, so the outline
        // stays on it and the canvas clips whatever hangs over the edge.
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(2300)) }

        assertTrue(tracker.state is TrackerState.TargetVisible)
    }

    @Test
    fun `a row nearly gone gives way to the cue`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(2340)) }

        assertTrue(tracker.state is TrackerState.ScrollNeeded)
    }

    @Test
    fun `the same control in a rebuilt window is re-adopted without starting over`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(1000)) }
        val generation = tracker.generation

        // The page was rebuilt into a new window; the row is unchanged.
        val rebuilt = ScreenBuilder(windowId = 99).apply {
            scrollingList()
            settingsRow("Display", top = 1000)
        }.build()
        val region = tracker.findSameControl(rebuilt, displaySpec, tracker.trackedIdentity!!)

        assertTrue("found the same control in the new window", region != null)
        tracker.rebindTo(region!!)

        assertTrue(tracker.state is TrackerState.TargetVisible)
        assertEquals("no new entrance", generation, tracker.generation)
    }

    @Test
    fun `a transition forgets the target immediately`() {
        val tracker = TargetTracker()
        repeat(2) { tracker.look(settingsAt(400)) }

        tracker.beginTransition()

        assertNull(tracker.trackedIdentity)
        assertTrue(tracker.state is TrackerState.Transitioning)
    }

    @Test
    fun `a paused tracker ignores the screen entirely`() {
        val tracker = TargetTracker()
        tracker.pause()

        val state = tracker.look(settingsAt(400))

        assertTrue(state is TrackerState.GuidePaused)
    }

    @Test
    fun `on the Font size page the tracker follows the setting, never its parts`() {
        val tracker = TargetTracker()
        val screen = ScreenBuilder().apply { fontSizeSection(fontTop = 900, displaySizeTop = 1600) }.build()

        repeat(2) { tracker.onScreen(screen, fontSizeSpec, viewport, ScrollDirection.Down) }
        val first = tracker.state as TrackerState.TargetVisible
        val identity = tracker.trackedIdentity

        // Repeated looks at a page full of nodes that all say "font size".
        repeat(5) { tracker.onScreen(screen, fontSizeSpec, viewport, ScrollDirection.Down) }
        val later = tracker.state as TrackerState.TargetVisible

        assertEquals(identity, tracker.trackedIdentity)
        assertEquals(first.region.boundsInScreen, later.region.boundsInScreen)
        assertEquals(first.region.boundsInScreen, later.region.boundsInScreen)
    }
}
