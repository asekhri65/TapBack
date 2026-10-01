package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.FieldKind
import com.aaravsekhri.dot.guide.ai.FormFields
import com.aaravsekhri.dot.guide.ai.FormMemory
import com.aaravsekhri.dot.guide.ai.ObservationBuilder
import com.aaravsekhri.dot.guide.ai.Readiness
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone can honestly say about a form's boxes, and nothing more. */
class FormReadinessTest {

    @Test
    fun `a box's kind comes from the app's words for it, never from what is in it`() {
        assertEquals(FieldKind.FirstName, FormFields.kindOf("First name"))
        assertEquals(FieldKind.LastName, FormFields.kindOf("Surname"))
        assertEquals(FieldKind.EmailOrPhone, FormFields.kindOf("Mobile number or email"))
        assertEquals(FieldKind.Email, FormFields.kindOf("Email address"))
        assertEquals(FieldKind.NewPassword, FormFields.kindOf("Create a password"))
        assertEquals(FieldKind.Password, FormFields.kindOf("Password"))
        assertEquals(FieldKind.Birthday, FormFields.kindOf("Birthday (0 years old)"))
        assertEquals(FieldKind.Birthday, FormFields.kindOf("Date of birth"))
        assertEquals(FieldKind.Unknown, FormFields.kindOf("Favourite colour"))
    }

    @Test
    fun `the done button says what the box is for, and generic only when that is unknown`() {
        assertEquals("I’m done typing my first name", FormFields.doneTypingLabel(FieldKind.FirstName))
        assertEquals("I’m done typing my email", FormFields.doneTypingLabel(FieldKind.Email))
        assertEquals("I’m done creating my password", FormFields.doneTypingLabel(FieldKind.NewPassword))
        assertEquals("I’ve set my birthday", FormFields.doneTypingLabel(FieldKind.Birthday))
        assertEquals("I’m done typing", FormFields.doneTypingLabel(FieldKind.Unknown))
        assertEquals("I’m done typing", FormFields.doneTypingLabel(null))
    }

    @Test
    fun `a value already there is prefilled until it changes or is confirmed`() {
        val memory = FormMemory()
        assertEquals(Readiness.Prefilled, memory.readiness("b", 11, error = false))
        assertEquals(Readiness.Prefilled, memory.readiness("b", 11, error = false))
        memory.confirm("b")
        assertEquals(Readiness.Confirmed, memory.readiness("b", 11, error = false))

        val edited = FormMemory()
        edited.readiness("b", 11, error = false)
        assertEquals("changed while watched", Readiness.Confirmed, edited.readiness("b", 12, error = false))
    }

    @Test
    fun `a box first seen empty and then filled was filled by the person`() {
        val memory = FormMemory()
        assertEquals(Readiness.Empty, memory.readiness("n", null, error = false))
        assertEquals(Readiness.Confirmed, memory.readiness("n", 99, error = false))
    }

    @Test
    fun `a confirmation is for the value confirmed, and an app error outranks everything`() {
        val memory = FormMemory()
        memory.readiness("b", 1, error = false)
        memory.confirm("b")
        assertEquals(Readiness.Error, memory.readiness("b", 1, error = true))
        memory.readiness("b", 2, error = false)
        assertFalse(memory.isConfirmed("b"))
    }

    private fun birthdayForm(date: String, ageLine: String = "Birthday (0 years old)", error: String? = null, hintShowing: Boolean = false): ScreenSnapshot {
        val screen = ScreenBuilder(packageName = "com.example.social").apply {
            node(text = "What's your birthday?", bounds = Bounds(40, 200, 1040, 300))
            node(text = ageLine, bounds = Bounds(40, 420, 1040, 480))
            node(text = date, className = "android.widget.EditText", bounds = Bounds(40, 480, 1040, 620), clickable = true, focusable = true)
            node(text = "Next", className = "android.widget.Button", bounds = Bounds(40, 700, 1040, 820), clickable = true)
        }.build()
        return screen.copy(
            nodes = screen.nodes.map {
                if (it.className == "android.widget.EditText") {
                    it.copy(isEditable = true, hintText = "Birthday", errorText = error, showingHint = hintShowing)
                } else {
                    it
                }
            },
        )
    }

    @Test
    fun `a default birthday reads as prefilled, and the age the app shows is passed on`() {
        val memory = FormMemory()
        val o = ObservationBuilder.build("s", birthdayForm("September 29, 2026"), 1, "", 0, forms = memory)
        val box = o.candidates.single { it.editable }
        assertEquals(Readiness.Prefilled, box.readiness)
        assertEquals(FieldKind.Birthday, FormFields.kindOf(box))
        assertTrue(o.notices.any { "0 years old" in it })
        assertTrue("the key names the box, never its value", "September" !in box.fieldKey)
    }

    @Test
    fun `changing only the day of a default birthday still reads as the default while the app says 0 years old`() {
        val memory = FormMemory()
        ObservationBuilder.build("s", birthdayForm("September 29, 2026"), 1, "", 0, forms = memory)
        val o = ObservationBuilder.build("s", birthdayForm("September 12, 2026"), 2, "", 0, forms = memory)
        assertEquals(Readiness.Prefilled, o.candidates.single { it.editable }.readiness)

        // Turned to a real-looking date: still not proof it is theirs. Only
        // the person saying so on the card makes it ready.
        val set = ObservationBuilder.build("s", birthdayForm("March 3, 1951", ageLine = "Birthday (75 years old)"), 3, "", 0, forms = memory)
        val box = set.candidates.single { it.editable }
        assertEquals(Readiness.Prefilled, box.readiness)
        memory.confirm(box.fieldKey)
        val confirmed = ObservationBuilder.build("s", birthdayForm("March 3, 1951", ageLine = "Birthday (75 years old)"), 4, "", 0, forms = memory)
        assertEquals(Readiness.Confirmed, confirmed.candidates.single { it.editable }.readiness)
    }

    @Test
    fun `a placeholder is empty, and the app's own error is carried with the box`() {
        val empty = ObservationBuilder.build("s", birthdayForm("Birthday", hintShowing = true), 1, "", 0, forms = FormMemory())
        assertEquals(Readiness.Empty, empty.candidates.single { it.editable }.readiness)

        val flagged = ObservationBuilder.build("s", birthdayForm("January 1, 2030", error = "Enter a valid date"), 1, "", 0, forms = FormMemory())
        val box = flagged.candidates.single { it.editable }
        assertEquals(Readiness.Error, box.readiness)
        assertEquals("Enter a valid date", box.error)
    }

    @Test
    fun `a form's next button is recognised, and so is its absence`() {
        val o = ObservationBuilder.build("s", birthdayForm("September 29, 2026"), 1, "", 0)
        assertTrue(o.candidates.any(FormFields::isAdvance))
        assertFalse(o.candidates.filter { it.editable }.any(FormFields::isAdvance))
    }
}
