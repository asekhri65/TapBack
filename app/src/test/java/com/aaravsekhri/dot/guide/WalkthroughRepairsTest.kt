package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.DatePickerHelp
import com.aaravsekhri.dot.guide.ai.FieldKind
import com.aaravsekhri.dot.guide.ai.FormFields
import com.aaravsekhri.dot.guide.ai.FormMemory
import com.aaravsekhri.dot.guide.ai.ObservationBuilder
import com.aaravsekhri.dot.guide.ai.PermissionPrompt
import com.aaravsekhri.dot.guide.ai.ScreenShape
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.overlay.CardDensity
import com.aaravsekhri.dot.guide.overlay.CardEdge
import com.aaravsekhri.dot.guide.overlay.CardPlacement
import com.aaravsekhri.dot.guide.overlay.CardSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces behind the fixes from the 2026-09-30 walkthrough, each on its own. */
class WalkthroughRepairsTest {

    // ------------------------------------------------------------ date picker

    @Test
    fun `each wheel is named from what it shows, and one wheel is asked for at a time`() {
        assertEquals(DatePickerHelp.WheelKind.Month, DatePickerHelp.kindOf("May"))
        assertEquals(DatePickerHelp.WheelKind.Day, DatePickerHelp.kindOf("22"))
        assertEquals(DatePickerHelp.WheelKind.Year, DatePickerHelp.kindOf("2026"))
        val words = DatePickerHelp.wheelWords(DatePickerHelp.Wheel(Bounds(0, 0, 1, 1), DatePickerHelp.WheelKind.Month), 0, 3)
        assertEquals("Swipe the month wheel up or down until it shows your birth month, then tell me once it does.", words)
        assertFalse("one action, not the whole picker", words.contains("Set") || words.contains(" and "))
    }

    // ------------------------------------------------------------ permission windows

    @Test
    fun `a permission window is told from its title, and so is what it asks for`() {
        val title = "Allow Facebook to access this device’s location?"
        assertTrue(PermissionPrompt.isPermission(title))
        assertEquals(PermissionPrompt.Kind.Location, PermissionPrompt.kindOf(title))
        assertEquals(PermissionPrompt.Kind.Notifications, PermissionPrompt.kindOf("Allow Example to send you notifications?"))
        assertFalse(PermissionPrompt.isPermission("Save password?"))
    }

    // ------------------------------------------------------------ what the planner is offered

    @Test
    fun `a button whose words are two levels down is offered by those words`() {
        val screen = ScreenBuilder(packageName = "com.android.vending").apply {
            node(text = "Welcome to Google Play", bounds = Bounds(40, 900, 1040, 1000))
            val button = node(className = "android.view.View", bounds = Bounds(60, 2150, 1020, 2280), clickable = true)
            val row = node(className = "android.view.View", bounds = Bounds(60, 2150, 1020, 2280), parentId = button)
            node(text = "Get started", bounds = Bounds(400, 2190, 680, 2240), parentId = row)
        }.build()
        val o = ObservationBuilder.build("s", screen, 1, "", 0)
        assertNotNull("nameless at its own level, named by its words", o.candidates.singleOrNull { it.naming == "Get started" && it.clickable })
    }

    @Test
    fun `when a crowded page is trimmed, its own buttons are kept ahead of rows deep in a list`() {
        val screen = ScreenBuilder(packageName = "com.android.vending").apply {
            val list = node(className = "android.widget.ScrollView", bounds = Bounds(0, 200, 1080, 2000))
            val scrollable = list
            repeat(100) { i -> node(text = "Category $i", className = "android.widget.Button", bounds = Bounds(0, 200 + i * 10, 540, 210 + i * 10), parentId = scrollable, clickable = true) }
            node(text = "Skip", className = "android.widget.Button", bounds = Bounds(40, 2200, 500, 2320), clickable = true)
        }.build().let { s -> s.copy(nodes = s.nodes.map { if (it.className == "android.widget.ScrollView") it.copy(isScrollable = true) else it }) }
        val o = ObservationBuilder.build("s", screen, 1, "", 0)
        assertTrue(o.truncated)
        assertTrue("the bottom Skip survives the trim", o.candidates.any { it.naming == "Skip" })
    }

    @Test
    fun `a birthday box is offered as set or not, never with the date in it`() {
        val screen = ScreenBuilder(packageName = "com.example.social").apply {
            val box = node(className = "android.widget.LinearLayout", bounds = Bounds(40, 441, 1040, 609), clickable = true)
            node(text = "Birthday (70 years old)", bounds = Bounds(80, 465, 980, 514), parentId = box)
            node(text = "May 22, 1956", bounds = Bounds(80, 514, 980, 585), parentId = box)
            node(text = "Next", className = "android.widget.Button", bounds = Bounds(40, 649, 1040, 775), clickable = true)
        }.build()
        val o = ObservationBuilder.build("s", screen, 1, "", 0, forms = FormMemory())
        val box = o.candidates.single { FormFields.kindOf(it) == FieldKind.Birthday }
        assertEquals("", box.label)
        assertEquals("set", box.value)
        assertFalse(o.candidates.any { it.label.contains("1956") || it.hint.contains("May 22") })
        assertEquals("a date turned on a picker still needs the person's word", com.aaravsekhri.dot.guide.ai.Readiness.Prefilled, box.readiness)
        assertEquals("named without the app's working-out", "Birthday", box.hint)
    }

    // ------------------------------------------------------------ pages that change in place

    @Test
    fun `a page whose controls are almost all replaced is a new page, a list that grows is not`() {
        val before = ScreenShape("app", 7).also { it.controls = setOf(1, 2, 3, 4) }
        assertTrue(ScreenShape("app", 7).also { it.controls = emptySet() }.controlsReplacedSince(before))
        assertFalse(ScreenShape("app", 7).also { it.controls = setOf(1, 2, 3, 4, 5, 6) }.controlsReplacedSince(before))
        assertFalse(ScreenShape("app", 7).also { it.controls = setOf(1, 2, 9) }.controlsReplacedSince(before))
    }

    // ------------------------------------------------------------ card placement

    private val area = Bounds(32, 160, 1048, 2280)

    @Test
    fun `with the keyboard up and a box to type in, the strip fits where the full and compact cards cannot`() {
        // The Facebook editor: a box near the top, the keyboard from 963 down.
        val typing = Bounds(0, 296, 1080, 470)
        val space = Bounds(area.left, area.top, area.right, 940)
        val heights = mapOf(CardDensity.Full to 600, CardDensity.Compact to 520, CardDensity.Strip to 300)
        val r = CardPlacement.choose(space, heights, listOf(typing), current = null)
        assertTrue(r.reason, r.clear)
        assertEquals(CardDensity.Strip, r.spot.density)
        val card = Bounds(space.left, r.spot.top, space.right, r.spot.top + 300)
        assertFalse("the words being typed stay visible", card.intersects(typing))
        assertTrue("and the card stays above the keyboard", card.bottom <= 940)
    }

    @Test
    fun `a card dragged somewhere clear stays there, and one dragged over the step moves off it`() {
        val heights = mapOf(CardDensity.Full to 520, CardDensity.Compact to 330, CardDensity.Strip to 200)
        val button = Bounds(60, 2100, 1020, 2240)
        val kept = CardPlacement.choose(area, heights, listOf(button), current = null, pinned = CardSpot(CardDensity.Full, 700, CardEdge.Between))
        assertEquals(700, kept.spot.top)
        val refused = CardPlacement.choose(area, heights, listOf(button), current = null, pinned = CardSpot(CardDensity.Full, 1900, CardEdge.Between))
        assertTrue(refused.clear)
        assertFalse(Bounds(area.left, refused.spot.top, area.right, refused.spot.top + heights.getValue(refused.spot.density)).intersects(button))
    }

    @Test
    fun `a small card is never held to a stricter rule than a big one beside a tall dialog`() {
        // The location prompt: a dialog from 502 to 1966 on a 2280-tall space.
        val dialog = Bounds(28, 502, 1052, 1966)
        val heights = mapOf(CardDensity.Full to 450, CardDensity.Compact to 380, CardDensity.Strip to 290)
        val first = CardPlacement.choose(area, heights, listOf(dialog), current = null)
        val again = CardPlacement.choose(area, heights, listOf(dialog), current = first.spot)
        assertTrue(first.reason, first.clear)
        assertEquals("the same answer when asked again: no swinging", first.spot, again.spot)
    }
}
