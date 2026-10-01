package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.ScreenShape
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page's structural shape: changed by a new page, never by typing on the same one. */
class ScreenShapeTest {

    private fun page(vararg boxes: Pair<String, Int>, typed: String? = null, password: Boolean = false): ScreenSnapshot {
        val screen = ScreenBuilder(packageName = "com.example.social").apply {
            node(text = "Sign up", bounds = Bounds(40, 200, 1040, 300))
            boxes.forEachIndexed { i, _ ->
                node(text = typed, className = "android.widget.EditText", bounds = Bounds(40, 400 + i * 200, 1040, 560 + i * 200), focusable = true, clickable = true, password = password)
            }
        }.build()
        var i = 0
        return screen.copy(
            nodes = screen.nodes.map {
                if (it.className == "android.widget.EditText") {
                    val (hint, type) = boxes[i++]
                    it.copy(isEditable = true, hintText = if (password) null else hint, inputType = type)
                } else {
                    it
                }
            },
        )
    }

    private val name = "First name" to 0x61 // text, person name

    @Test
    fun `typing and the cursor leave the page's shape alone`() {
        val before = ScreenShape.of(page(name))
        val after = ScreenShape.of(page(name, typed = "Sam"))
        assertEquals(before, after)
        assertFalse(after.isNewPageAfter(before))
    }

    @Test
    fun `a password page swapped into the same window is a new, private page`() {
        val birthday = ScreenShape.of(page("Birthday" to 0x1))
        val password = ScreenShape.of(page("Password" to 0x81, password = true))
        assertTrue(password.private)
        assertFalse(birthday.private)
        assertTrue(password.isNewPageAfter(birthday))
        assertEquals(listOf("password"), password.fields)
    }

    @Test
    fun `a different set of boxes is a different page`() {
        assertTrue(ScreenShape.of(page("Email" to 0x21)).isNewPageAfter(ScreenShape.of(page(name))))
        assertTrue(ScreenShape.of(page(name, "Last name" to 0x61)).isNewPageAfter(ScreenShape.of(page(name))))
    }
}
