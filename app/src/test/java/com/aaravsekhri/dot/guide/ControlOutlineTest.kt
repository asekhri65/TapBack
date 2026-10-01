package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.ControlOutline
import com.aaravsekhri.dot.guide.ai.ControlOutline.Verdict
import com.aaravsekhri.dot.guide.ai.ObservationBuilder
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an outline goes round, and when there should be none.
 *
 * The shapes here are the ones that went wrong on real phones: a login box
 * whose text field is only its lower part, a search pill with icons either
 * side of its field, and a card holding buttons of its own.
 */
class ControlOutlineTest {

    /** 3x density, so 1dp is 3px and the numbers read as a real phone's. */
    private val dp = 3f

    private class Tree {
        val nodes = mutableListOf<NodeSnapshot>()

        fun add(
            parent: Int?,
            bounds: Bounds,
            cls: String = "android.view.ViewGroup",
            text: String? = null,
            hint: String? = null,
            clickable: Boolean = false,
            editable: Boolean = false,
            scrollable: Boolean = false,
            description: String? = null,
        ): Int {
            val id = nodes.size
            nodes += NodeSnapshot(
                id = id, parentId = parent, depth = 0, windowId = 1, packageName = "p",
                className = cls, viewId = null, text = text, contentDescription = description, bounds = bounds,
                isVisibleToUser = true, isEnabled = true, isClickable = clickable, isFocusable = clickable || editable,
                isScrollable = scrollable, isPassword = false, isCheckable = false,
                isEditable = editable, hintText = hint,
            )
            return id
        }

        fun screen() = ScreenSnapshot("p", nodes, 1080, 2400, windowId = 1)
    }

    private fun Tree.page() = add(null, Bounds(0, 0, 1080, 2400), "android.widget.FrameLayout")

    @Test
    fun `a login box is outlined whole, not just the field in its lower part`() {
        val t = Tree()
        val page = t.page()
        // The bordered box: clickable, puts the cursor in the field.
        val box = t.add(page, Bounds(48, 419, 1032, 593), clickable = true)
        val field = t.add(box, Bounds(48, 479, 1032, 593), "android.widget.EditText", hint = "Mobile number or email", editable = true)

        val verdict = ControlOutline.resolve(t.nodes[field], t.screen(), dp)
        assertEquals(Verdict.Outline(Bounds(48, 419, 1032, 593), container = true), verdict)
    }

    @Test
    fun `a search pill is outlined with its icons, not the field between them`() {
        val t = Tree()
        val page = t.page()
        val pill = t.add(page, Bounds(48, 1400, 1032, 1570), clickable = true)
        t.add(pill, Bounds(60, 1425, 180, 1545), "android.widget.ImageButton", description = "Search", clickable = true)
        val field = t.add(pill, Bounds(180, 1434, 900, 1565), "android.widget.EditText", hint = "What are you interested in?", editable = true)
        t.add(pill, Bounds(900, 1425, 1020, 1545), "android.widget.ImageButton", description = "Voice search", clickable = true)

        val verdict = ControlOutline.resolve(t.nodes[field], t.screen(), dp) as Verdict.Outline
        assertEquals(Bounds(48, 1400, 1032, 1570), verdict.bounds)
    }

    @Test
    fun `a wrapper holding a second field is not either field's box`() {
        val t = Tree()
        val page = t.page()
        val form = t.add(page, Bounds(48, 400, 1032, 700), clickable = true)
        val first = t.add(form, Bounds(48, 400, 1032, 540), "android.widget.EditText", hint = "First name", editable = true)
        t.add(form, Bounds(48, 560, 1032, 700), "android.widget.EditText", hint = "Last name", editable = true)

        assertEquals(Verdict.Outline(Bounds(48, 400, 1032, 540), container = false), ControlOutline.resolve(t.nodes[first], t.screen(), dp))
    }

    @Test
    fun `a large ancestor is never taken for being clickable`() {
        val t = Tree()
        val page = t.page()
        // A whole clickable panel, much taller than the field.
        val panel = t.add(page, Bounds(0, 300, 1080, 1500), clickable = true)
        val field = t.add(panel, Bounds(48, 400, 1032, 540), "android.widget.EditText", hint = "Search", editable = true)

        assertEquals(Bounds(48, 400, 1032, 540), (ControlOutline.resolve(t.nodes[field], t.screen(), dp) as Verdict.Outline).bounds)
    }

    @Test
    fun `a box with a button of its own is not the field's box`() {
        val t = Tree()
        val page = t.page()
        val row = t.add(page, Bounds(48, 400, 1032, 560), clickable = true)
        val field = t.add(row, Bounds(48, 410, 700, 550), "android.widget.EditText", hint = "Promo code", editable = true)
        t.add(row, Bounds(720, 410, 1032, 550), "android.widget.Button", text = "Apply", clickable = true)

        assertEquals(Bounds(48, 410, 700, 550), (ControlOutline.resolve(t.nodes[field], t.screen(), dp) as Verdict.Outline).bounds)
    }

    @Test
    fun `a card that holds its own buttons is said in words, not outlined`() {
        val t = Tree()
        val page = t.page()
        val card = t.add(page, Bounds(48, 300, 1032, 800), clickable = true, description = "Message from Sam")
        t.add(card, Bounds(96, 330, 900, 400), "android.widget.TextView", text = "Message from Sam")
        t.add(card, Bounds(96, 650, 400, 770), "android.widget.Button", text = "Reply", clickable = true)
        t.add(card, Bounds(420, 650, 700, 770), "android.widget.Button", text = "Share", clickable = true)

        assertTrue(ControlOutline.resolve(t.nodes[card], t.screen(), dp) is Verdict.TextOnly)
    }

    @Test
    fun `a row with its own words and one switch is still outlined`() {
        val t = Tree()
        val page = t.page()
        val row = t.add(page, Bounds(0, 500, 1080, 700), clickable = true)
        t.add(row, Bounds(48, 540, 700, 600), "android.widget.TextView", text = "Dark theme")
        t.add(row, Bounds(900, 560, 1032, 640), "android.widget.Switch", clickable = true, text = "On")

        assertEquals(Verdict.Outline(Bounds(0, 500, 1080, 700), false), ControlOutline.resolve(t.nodes[row], t.screen(), dp))
    }

    @Test
    fun `something covering most of the screen is a region, not a control`() {
        val t = Tree()
        val page = t.page()
        val region = t.add(page, Bounds(0, 200, 1080, 2300), clickable = true)
        assertTrue(ControlOutline.resolve(t.nodes[region], t.screen(), dp) is Verdict.TextOnly)
    }

    @Test
    fun `a sliver is not outlined`() {
        val t = Tree()
        val page = t.page()
        val line = t.add(page, Bounds(0, 500, 1080, 510), clickable = true, text = "Divider")
        assertTrue(ControlOutline.resolve(t.nodes[line], t.screen(), dp) is Verdict.TextOnly)
    }

    @Test
    fun `a field's label is found inside its box, and a value never is`() {
        val t = Tree()
        val page = t.page()
        val box = t.add(page, Bounds(48, 400, 1032, 580), clickable = true)
        t.add(box, Bounds(90, 410, 600, 460), "android.widget.TextView", text = "Mobile number or email")
        val field = t.add(box, Bounds(48, 460, 1032, 580), "android.widget.EditText", editable = true)
        assertEquals("Mobile number or email", ControlOutline.fieldLabel(t.nodes[field], t.screen(), dp))

        val t2 = Tree()
        val page2 = t2.page()
        val box2 = t2.add(page2, Bounds(48, 400, 1032, 580), clickable = true)
        t2.add(box2, Bounds(90, 410, 600, 460), "android.widget.TextView", text = "someone@example.com")
        val field2 = t2.add(box2, Bounds(48, 460, 1032, 580), "android.widget.EditText", editable = true)
        assertNull(ControlOutline.fieldLabel(t2.nodes[field2], t2.screen(), dp))
    }

    @Test
    fun `the model's list carries the box to draw and the field keeps its own place`() {
        val t = Tree()
        val page = t.page()
        val box = t.add(page, Bounds(48, 419, 1032, 593), clickable = true)
        t.add(box, Bounds(48, 479, 1032, 593), "android.widget.EditText", hint = "Mobile number or email", editable = true)

        val observation = ObservationBuilder.build("s", t.screen(), 1, "", 0, pxPerDp = dp)
        val field = observation.candidates.single { it.editable }
        assertEquals(Bounds(48, 479, 1032, 593), field.bounds)
        assertEquals(Bounds(48, 419, 1032, 593), field.drawBounds)
    }
}
