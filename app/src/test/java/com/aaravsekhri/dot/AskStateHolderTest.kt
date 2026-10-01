package com.aaravsekhri.dot

import com.aaravsekhri.dot.ui.ask.AskStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Ask tab in V1: one editable request, and a button that starts it once. */
class AskStateHolderTest {

    @Test
    fun `nothing worth asking cannot be started`() {
        val holder = AskStateHolder()
        assertFalse(holder.canSubmit)
        assertNull(holder.submit(0))
        holder.updateDraft("   ")
        assertNull(holder.submit(10_000))
        holder.updateDraft("a")
        assertFalse(holder.canSubmit)
    }

    @Test
    fun `a request is started once, trimmed, and the words stay in the box`() {
        val holder = AskStateHolder()
        holder.updateDraft("  Make the text bigger ")
        assertEquals("Make the text bigger", holder.submit(10_000))
        assertEquals("the draft is kept to come back to", "  Make the text bigger ", holder.draft)
    }

    @Test
    fun `a double tap starts one guide, not two`() {
        val holder = AskStateHolder("Turn on dark theme")
        assertEquals("Turn on dark theme", holder.submit(10_000))
        assertNull(holder.submit(10_300))
        assertNull(holder.submit(11_200))
        assertEquals("a deliberate second go later is allowed", "Turn on dark theme", holder.submit(12_000))
    }

    @Test
    fun `editing never sends anything by itself`() {
        // There is no "finished typing" transition any more: the keyboard
        // closing, the text changing — none of it is a submission.
        val holder = AskStateHolder()
        holder.updateDraft("Make the text")
        holder.updateDraft("Make the text bigger")
        assertTrue(holder.canSubmit)
        assertEquals("Make the text bigger", holder.draft)
    }
}

private object FakeSaverScope : androidx.compose.runtime.saveable.SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}

/** Rotation and process death keep the words; older saved states come back as words. */
class AskRestoreTest {

    private fun restore(saved: List<Any>): String? = AskStateHolder.Saver.restore(saved)?.draft

    @Test
    fun `a draft survives a rotation`() {
        val saved = with(AskStateHolder.Saver) { FakeSaverScope.save(AskStateHolder("Make the text bigger")) }
        assertEquals("Make the text bigger", AskStateHolder.Saver.restore(saved!!)!!.draft)
    }

    @Test
    fun `what earlier versions saved comes back as text in the box`() {
        assertEquals("Make the text bigger", restore(listOf("voice_ready", "Make the text bigger", false)))
        assertEquals("Make the text", restore(listOf("typing", "Make the text", "ChoseToType")))
        assertEquals("Turn on dark theme", restore(listOf("typed_ready", "Turn on dark theme")))
        assertEquals("never a microphone to go back to", "", restore(listOf("idle")))
        assertEquals("", restore(listOf("something_else")))
        assertEquals("", restore(emptyList()))
    }
}
