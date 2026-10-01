package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.FingerDirection
import com.aaravsekhri.dot.guide.ai.GestureMotion
import com.aaravsekhri.dot.guide.ai.LocalWording
import com.aaravsekhri.dot.guide.ai.Reveal
import com.aaravsekhri.dot.guide.ai.finger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules every gesture demonstration keeps. */
class GestureTest {

    private val samples = (0..2000).map { it / 2000f }

    @Test
    fun `while the fingertip can be seen it only ever moves forward`() {
        var last = -1f
        samples.map(GestureMotion::frame).forEach { frame ->
            if (frame.alpha > 0f) {
                assertTrue("went backwards to ${frame.progress} from $last", frame.progress >= last - 1e-6f)
                last = frame.progress
            } else {
                // Invisible: this is where it resets, unseen.
                last = -1f
            }
        }
    }

    @Test
    fun `it reaches the end, fades out there, and pauses before repeating`() {
        val frames = samples.map(GestureMotion::frame)
        assertTrue(frames.any { it.progress == 1f && it.alpha == 1f })
        val fading = frames.filter { it.alpha in 0.01f..0.99f && it.progress == 1f }
        assertTrue("fades at the end, not the start", fading.isNotEmpty())
        val invisible = samples.filter { GestureMotion.frame(it).alpha == 0f }
        assertTrue("a pause of at least a fifth of the cycle", invisible.size > samples.size / 5)
        assertEquals("the cycle ends invisible, so the restart cannot be seen", 0f, GestureMotion.frame(1f).alpha)
    }

    @Test
    fun `the finger goes opposite to the content it reveals`() {
        assertEquals(FingerDirection.Up, Reveal.Below.finger())
        assertEquals(FingerDirection.Down, Reveal.Above.finger())
        assertEquals(FingerDirection.Left, Reveal.Right.finger())
        assertEquals(FingerDirection.Right, Reveal.Left.finger())
    }

    @Test
    fun `the picture and the words agree for every direction`() {
        Reveal.entries.forEach { reveal ->
            val words = LocalWording.scroll(reveal, "")
            val finger = reveal.finger().name.lowercase()
            assertTrue("$reveal: '$words' should send the finger $finger", words.contains("finger $finger") || words.contains("finger to the $finger"))
        }
    }
}
