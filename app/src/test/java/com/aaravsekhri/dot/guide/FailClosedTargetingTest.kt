package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.matching.TargetMatcher
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.model.TargetSpec
import com.aaravsekhri.dot.guide.tracking.TargetTracker
import com.aaravsekhri.dot.guide.tracking.TrackerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pointing at the wrong setting is worse than pointing at nothing.
 *
 * This is the behaviour that made these necessary: with the Display step
 * running, someone scrolled past Display & touch, and the outline attached
 * itself to the Accessibility row — which is summarised "Display, interaction,
 * audio". That row is clickable, enabled, the same shape, in roughly the same
 * place, and shares the word being looked for. Everything except its actual
 * name agreed. So its actual name is now the only thing that counts.
 */
class FailClosedTargetingTest {

    private val displayStep = GuideCatalog.textSize.steps[0].target
    private val viewport = Bounds(0, 0, 1080, 2400)

    /** The Settings list scrolled past Display & touch. */
    private fun scrolledPastDisplay() = ScreenBuilder().apply {
        scrollingList()
        settingsRowWithSummary("Wallpaper & style", "Colors, themed icons, app grid", top = 300)
        settingsRowWithSummary("Accessibility", "Display, interaction, audio", top = 700)
        settingsRowWithSummary("Security & privacy", "App security, device lock", top = 1100)
    }.build()

    // --------------------------------------------------- the reported bug

    @Test
    fun `Accessibility is never accepted in place of Display and touch`() {
        val screen = scrolledPastDisplay()

        val match = TargetMatcher.findBest(screen, displayStep)

        assertNull(
            "the Accessibility row must never stand in for Display & touch",
            match,
        )
    }

    @Test
    fun `a summary mentioning the word is not the same as the row being named it`() {
        val screen = scrolledPastDisplay()

        // The wording that used to score past the threshold.
        val accessibilitySummary = screen.nodes.first {
            it.text == "Display, interaction, audio"
        }
        assertNotNull(accessibilitySummary)

        // It is still on screen, still clickable, still the right shape — and
        // still not a valid target.
        assertTrue(TargetMatcher.findAll(screen, displayStep).isEmpty())
    }

    @Test
    fun `no valid target produces a scroll cue, never a substitute row`() {
        val tracker = TargetTracker()
        val screen = scrolledPastDisplay()

        repeat(4) { tracker.onScreen(screen, displayStep, viewport, ScrollDirection.Down) }

        assertTrue(
            "must ask for a scroll rather than point at something else",
            tracker.state is TrackerState.ScrollNeeded,
        )
        assertNull(tracker.trackedIdentity)
    }

    @Test
    fun `the real row is still found when it is actually there`() {
        val builder = ScreenBuilder().apply { scrollingList() }
        builder.settingsRowWithSummary("Accessibility", "Display, interaction, audio", top = 300)
        val display = builder.settingsRowWithSummary(
            "Display & touch",
            "Dark theme, font size, touch",
            top = 700,
        )
        val screen = builder.build()

        val match = TargetMatcher.findBest(screen, displayStep)

        assertNotNull("the genuine row must still match", match)
        assertEquals(display, match!!.highlightNodeId)
    }

    // ------------------------------------------- the contract, generically

    @Test
    fun `position and shape alone can never make a candidate valid`() {
        // A row identical to the target in every structural respect, named
        // something else entirely.
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRowWithSummary("Sound & vibration", "Volume, haptics", top = 700)
        }.build()

        assertNull(TargetMatcher.findBest(screen, displayStep))
    }

    @Test
    fun `being where the target used to be does not make a row the target`() {
        val tracker = TargetTracker()

        // Settle on the real row.
        val withDisplay = ScreenBuilder().apply {
            scrollingList()
            settingsRowWithSummary("Display & touch", "Dark theme, font size", top = 700)
        }.build()
        repeat(2) { tracker.onScreen(withDisplay, displayStep, viewport, ScrollDirection.Down) }
        assertTrue(tracker.state is TrackerState.TargetVisible)

        // The list scrolls so another row now occupies that exact position.
        val replaced = ScreenBuilder().apply {
            scrollingList()
            settingsRowWithSummary("Accessibility", "Display, interaction, audio", top = 700)
        }.build()
        repeat(4) { tracker.onScreen(replaced, displayStep, viewport, ScrollDirection.Down) }

        assertTrue(
            "the row that took its place is not the target",
            tracker.state !is TrackerState.TargetVisible,
        )
    }

    @Test
    fun `the gate applies to every step, not just the one that failed`() {
        val fontSizeStep = GuideCatalog.textSize.steps[2].target
        // A slider with the right class and a plausible id, on a page that
        // never says "font size" anywhere.
        val screen = ScreenBuilder().apply {
            node(
                className = "android.widget.SeekBar",
                viewId = "com.android.settings:id/display_size_slider",
                bounds = Bounds(60, 500, 1020, 620),
            )
            node(text = "Display size", bounds = Bounds(60, 380, 500, 460))
        }.build()

        assertNull(TargetMatcher.findBest(screen, fontSizeStep))
    }

    @Test
    fun `an approved alternative spelling is still accepted`() {
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRowWithSummary("Display", "Brightness, font size", top = 700)
        }.build()

        assertNotNull(
            "Display is an approved name for this target",
            TargetMatcher.findBest(screen, displayStep),
        )
    }

    @Test
    fun `an explicit identity list overrides the looser match labels`() {
        val strict = TargetSpec(
            labels = listOf("Display", "Display & touch"),
            // Only the full name is acceptable for this hypothetical step.
            identityLabels = listOf("Display & touch"),
        )
        val onlyShortName = ScreenBuilder().apply {
            settingsRowWithSummary("Display", "Brightness", top = 700)
        }.build()

        assertNull(TargetMatcher.findBest(onlyShortName, strict))
    }
}
