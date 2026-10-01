package com.aaravsekhri.dot.guide

import android.view.WindowManager
import com.aaravsekhri.dot.guide.overlay.overlayFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Dot points at things; the person does the touching.
 *
 * The full-screen surface the highlight is drawn on covers the display, so the
 * one thing that must never regress is its inability to receive a touch. These
 * assert the flag combination directly rather than leaving it to review.
 */
class OverlayFlagsTest {

    private fun Int.hasFlag(flag: Int) = (this and flag) == flag

    @Test
    fun `the highlight surface cannot receive touches`() {
        val flags = overlayFlags(touchable = false)

        assertEquals(
            "FLAG_NOT_TOUCHABLE must be set on the full-screen surface",
            true,
            flags.hasFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        )
    }

    @Test
    fun `the scroll cue cannot receive touches either`() {
        // Same construction path; the cue sits over the list being scrolled.
        val flags = overlayFlags(touchable = false)

        assertEquals(true, flags.hasFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
    }

    @Test
    fun `no overlay ever takes focus from the app being guided`() {
        listOf(true, false).forEach { touchable ->
            val flags = overlayFlags(touchable)
            assertEquals(
                "focusable overlay would steal input, touchable=$touchable",
                true,
                flags.hasFlag(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            )
        }
    }

    @Test
    fun `the instruction card is touchable, because its buttons must work`() {
        val flags = overlayFlags(touchable = true)

        assertEquals(false, flags.hasFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
        assertNotEquals(overlayFlags(touchable = false), flags)
    }

    @Test
    fun `overlays are laid out in screen coordinates`() {
        // Node bounds arrive in raw screen pixels; the surface has to agree.
        listOf(true, false).forEach { touchable ->
            assertEquals(
                true,
                overlayFlags(touchable).hasFlag(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN),
            )
        }
    }
}
