package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.matching.TargetMatcher
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.TargetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher decides where Dot points. Pointing at the wrong control is
 * worse than admitting it cannot see one, so these cover both the matching
 * and the refusing.
 */
class TargetMatcherTest {

    private val displaySpec = TargetSpec(
        labels = listOf("Display", "Display & touch", "Display and brightness"),
        contentDescriptions = listOf("Display"),
        viewIdFragments = listOf("display"),
    )

    @Test
    fun `finds an exactly named row`() {
        val builder = ScreenBuilder()
        builder.settingsRow("Network & internet", top = 100)
        val displayRow = builder.settingsRow("Display", top = 400)
        builder.settingsRow("Notifications", top = 700)

        val match = TargetMatcher.findBest(builder.build(), displaySpec)

        assertNotNull(match)
        assertEquals(displayRow, match!!.highlightNodeId)
    }

    @Test
    fun `highlights the tappable row rather than the words inside it`() {
        val builder = ScreenBuilder()
        val row = builder.settingsRow("Display", top = 400)
        val screen = builder.build()

        val match = TargetMatcher.findBest(screen, displaySpec)!!

        // The wording is on the child label, but the box is drawn around the row.
        assertTrue(match.labelNodeId != match.highlightNodeId)
        assertEquals(row, match.highlightNodeId)
        assertEquals(Bounds(0, 400, 1080, 600), match.bounds)
    }

    @Test
    fun `matches a manufacturer's alternative wording`() {
        val builder = ScreenBuilder()
        val row = builder.settingsRow("Display & touch", top = 400)

        val match = TargetMatcher.findBest(builder.build(), displaySpec)

        assertEquals(row, match!!.highlightNodeId)
    }

    @Test
    fun `prefers the exact label over a row that merely contains the word`() {
        val builder = ScreenBuilder()
        val longer = builder.settingsRow("Display size and text", top = 100)
        val exact = builder.settingsRow("Display", top = 400)

        val match = TargetMatcher.findBest(builder.build(), displaySpec)!!

        assertEquals(exact, match.highlightNodeId)
        assertTrue(match.highlightNodeId != longer)
    }

    @Test
    fun `rejects a row that is not visible`() {
        val builder = ScreenBuilder()
        builder.node(
            text = "Display",
            bounds = Bounds(0, 400, 1080, 600),
            clickable = true,
            visible = false,
        )

        assertNull(TargetMatcher.findBest(builder.build(), displaySpec))
    }

    @Test
    fun `rejects a row with empty bounds`() {
        val builder = ScreenBuilder()
        builder.node(text = "Display", bounds = Bounds(0, 400, 0, 400), clickable = true)

        assertNull(TargetMatcher.findBest(builder.build(), displaySpec))
    }

    @Test
    fun `rejects a row laid out off screen`() {
        val builder = ScreenBuilder()
        builder.node(text = "Display", bounds = Bounds(0, 3000, 1080, 3200), clickable = true)

        assertNull(TargetMatcher.findBest(builder.build(), displaySpec))
    }

    @Test
    fun `never matches a password field`() {
        val builder = ScreenBuilder()
        builder.node(
            text = "Display",
            bounds = Bounds(0, 400, 1080, 600),
            clickable = true,
            password = true,
        )

        assertNull(TargetMatcher.findBest(builder.build(), displaySpec))
    }

    @Test
    fun `returns nothing when no row is close enough`() {
        val builder = ScreenBuilder()
        builder.settingsRow("Dark theme", top = 100)
        builder.settingsRow("Screen timeout", top = 400)

        assertNull(TargetMatcher.findBest(builder.build(), displaySpec))
    }

    @Test
    fun `a disabled row scores below an enabled one with the same wording`() {
        val enabled = ScreenBuilder().apply { settingsRow("Display", top = 400) }.build()
        val disabled = ScreenBuilder().apply {
            settingsRow("Display", top = 400, enabled = false)
        }.build()

        val enabledScore = TargetMatcher.findBest(enabled, displaySpec)!!.score
        val disabledMatch = TargetMatcher.findBest(disabled, displaySpec)

        assertTrue(disabledMatch == null || disabledMatch.score < enabledScore)
    }

    @Test
    fun `a resource id can carry a control that has no readable label`() {
        val builder = ScreenBuilder()
        val slider = builder.node(
            className = "android.widget.SeekBar",
            viewId = "com.android.settings:id/font_size_slider",
            bounds = Bounds(60, 800, 1020, 900),
        )

        val match = TargetMatcher.findBest(
            builder.build(),
            TargetSpec(
                labels = listOf("Font size"),
                viewIdFragments = listOf("font_size"),
                expectedClassNames = listOf("SeekBar"),
                requireInteractive = false,
            ),
        )

        assertEquals(slider, match!!.highlightNodeId)
    }

    @Test
    fun `a label with no tappable ancestor is skipped when tapping is required`() {
        val builder = ScreenBuilder()
        // A heading, not a row: nothing above it is clickable.
        builder.node(
            text = "Display",
            className = "android.widget.TextView",
            bounds = Bounds(60, 200, 600, 280),
        )

        assertNull(TargetMatcher.findBest(builder.build(), displaySpec))
    }

    @Test
    fun `the clickable ancestor search stops before swallowing the whole screen`() {
        val builder = ScreenBuilder()
        // A scrolling container covering most of the display is clickable, but
        // highlighting it would draw a box around everything.
        val list = builder.node(
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = Bounds(0, 0, 1080, 2300),
            clickable = true,
        )
        builder.node(
            text = "Display",
            bounds = Bounds(120, 440, 900, 520),
            parentId = list,
        )

        assertNull(TargetMatcher.findBest(builder.build(), displaySpec))
    }

    @Test
    fun `a content description stands in for missing text`() {
        val builder = ScreenBuilder()
        val row = builder.node(
            contentDescription = "Display",
            className = "android.widget.ImageButton",
            bounds = Bounds(0, 400, 1080, 600),
            clickable = true,
        )

        assertEquals(row, TargetMatcher.findBest(builder.build(), displaySpec)!!.highlightNodeId)
    }

    @Test
    fun `a banner that merely mentions the words is not mistaken for the control`() {
        val builder = ScreenBuilder()
        // Settings shows this after a change; it says "font size" but is not it.
        builder.node(
            text = "Font size added to Quick Settings. Swipe down to change the font size anytime.",
            className = "android.widget.TextView",
            bounds = Bounds(30, 300, 1050, 560),
        )
        val slider = builder.node(
            className = "android.widget.SeekBar",
            viewId = "com.android.settings:id/font_size_slider",
            bounds = Bounds(60, 800, 1020, 900),
        )

        val spec = TargetSpec(
            labels = listOf("Font size"),
            viewIdFragments = listOf("font_size"),
            expectedClassNames = listOf("SeekBar"),
            requireInteractive = false,
            requireStructuralMatch = true,
        )

        assertEquals(slider, TargetMatcher.findBest(builder.build(), spec)!!.highlightNodeId)
    }

    @Test
    fun `a sentence is never offered back as the control's name`() {
        val builder = ScreenBuilder()
        builder.node(
            text = "Font size added to Quick Settings. Swipe down to change it anytime.",
            className = "android.widget.SeekBar",
            viewId = "com.android.settings:id/font_size_slider",
            bounds = Bounds(60, 800, 1020, 900),
        )

        val match = TargetMatcher.findBest(
            builder.build(),
            TargetSpec(
                labels = listOf("Font size"),
                viewIdFragments = listOf("font_size"),
                requireInteractive = false,
            ),
        )

        // Matched, but the card will fall back to the guide's own wording.
        assertNotNull(match)
        assertNull(match!!.matchedLabel)
    }

    @Test
    fun `a short row label is offered back so the card names what is on screen`() {
        val builder = ScreenBuilder()
        builder.settingsRow("Display & touch", top = 400)

        val match = TargetMatcher.findBest(builder.build(), displaySpec)!!

        assertEquals("Display & touch", match.matchedLabel)
    }

    @Test
    fun `the clickable row wins even when the label is focusable too`() {
        val builder = ScreenBuilder()
        val row = builder.node(
            className = "android.widget.LinearLayout",
            bounds = Bounds(0, 400, 1080, 600),
            clickable = true,
            focusable = true,
        )
        builder.node(
            text = "Display",
            bounds = Bounds(120, 440, 900, 520),
            parentId = row,
            // Both the row and its label report as focusable. Taking whichever
            // is found first would let the answer flip between scans.
            focusable = true,
        )

        val match = TargetMatcher.findBest(builder.build(), displaySpec)!!

        assertEquals(row, match.highlightNodeId)
        assertEquals(Bounds(0, 400, 1080, 600), match.bounds)
    }

    @Test
    fun `the same row resolves identically however its flags are reported`() {
        // One scan reports the label as focusable, the next does not. The row
        // being outlined, and its identity, must come out the same both times.
        val withFocusableLabel = ScreenBuilder().apply {
            val row = node(
                className = "android.widget.LinearLayout",
                bounds = Bounds(0, 400, 1080, 600),
                clickable = true,
            )
            node(
                text = "Display",
                viewId = "android:id/title",
                bounds = Bounds(120, 440, 900, 520),
                parentId = row,
                focusable = true,
            )
        }.build()

        val withPlainLabel = ScreenBuilder().apply {
            val row = node(
                className = "android.widget.LinearLayout",
                bounds = Bounds(0, 400, 1080, 600),
                clickable = true,
            )
            node(
                text = "Display",
                viewId = "android:id/title",
                bounds = Bounds(120, 440, 900, 520),
                parentId = row,
            )
        }.build()

        val first = TargetMatcher.findBest(withFocusableLabel, displaySpec)!!
        val second = TargetMatcher.findBest(withPlainLabel, displaySpec)!!

        assertEquals(first.bounds, second.bounds)
        assertEquals(first.identity, second.identity)
    }

    @Test
    fun `identity survives a row being reported at slightly different bounds`() {
        val first = ScreenBuilder().apply { settingsRow("Display", top = 400) }.build()
        val second = ScreenBuilder().apply { settingsRow("Display", top = 402) }.build()

        assertEquals(
            TargetMatcher.findBest(first, displaySpec)!!.identity,
            TargetMatcher.findBest(second, displaySpec)!!.identity,
        )
    }

    @Test
    fun `findAll returns only rows that are genuinely this target`() {
        val builder = ScreenBuilder()
        // Shares two words with the target and nothing else. Under the
        // fail-closed contract that is not a candidate at all.
        builder.settingsRow("Display size and text", top = 100)
        val exact = builder.settingsRow("Display", top = 400)

        val all = TargetMatcher.findAll(builder.build(), displaySpec)

        assertEquals("only the row actually named Display", 1, all.size)
        assertEquals(exact, all.first().highlightNodeId)
    }
}
