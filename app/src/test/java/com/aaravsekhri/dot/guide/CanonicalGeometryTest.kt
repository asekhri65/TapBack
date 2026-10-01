package com.aaravsekhri.dot.guide

import androidx.compose.ui.geometry.Offset
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry
import com.aaravsekhri.dot.guide.motion.TargetPose
import com.aaravsekhri.dot.guide.motion.reconstructFrom
import com.aaravsekhri.dot.guide.overlay.PebbleCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target's shape, and everything that has tried to change it.
 *
 * Two separate causes have shrunk this rectangle in shipped behaviour. The
 * renderer used to clamp each edge into the viewport, and — the one the first
 * fix missed — accessibility reports the *visible* bounds of a partly
 * scrolled row, so a clipped rectangle arrived already shortened and was
 * believed. Both are covered here, the second with the real shape of the data.
 */
class CanonicalGeometryTest {

    private val viewport = Bounds(0, 135, 1170, 2400)

    private val canonical = CanonicalTargetGeometry(
        widthPx = 1170f,
        heightPx = 317f,
        pebbleAnchorOffsets = mapOf(
            PebbleCorner.TopEnd to Offset(1085f, -17f),
            PebbleCorner.TopStart to Offset(-17f, -17f),
            PebbleCorner.BottomEnd to Offset(1085f, 215f),
            PebbleCorner.BottomStart to Offset(-17f, 215f),
        ),
    )

    @Test
    fun `width and height survive any amount of scrolling`() {
        var pose = TargetPose(0f, 1600f)
        repeat(40) {
            pose = pose.translatedBy(0f, -71f)
            val rect = canonical.fullRect(pose)
            assertEquals(1170f, rect.width, 0f)
            assertEquals(317f, rect.height, 0f)
        }
    }

    @Test
    fun `the rectangle keeps its size at every viewport boundary`() {
        val positions = listOf(
            viewport.top - 317f,     // exactly above
            viewport.top - 200f,     // mostly above
            viewport.top - 1f,       // one pixel above
            viewport.top.toFloat(),  // flush with the top
            1200f,                   // comfortably inside
            viewport.bottom - 317f,  // flush with the bottom
            viewport.bottom - 1f,    // one pixel of it left
            viewport.bottom.toFloat(),
        )
        positions.forEach { top ->
            val rect = canonical.fullRect(TargetPose(0f, top))
            assertEquals("at top=$top", 1170f, rect.width, 0f)
            assertEquals("at top=$top", 317f, rect.height, 0f)
        }
    }

    // ------------------------------------------- clipped accessibility bounds

    @Test
    fun `a row clipped at the top is reconstructed to its full height`() {
        // Android reports only the visible part: the row's true top is 100
        // pixels above the viewport, so it hands back a 217-pixel rectangle.
        val reported = Bounds(0, viewport.top, 1170, viewport.top + 217)

        val pose = canonical.reconstructFrom(reported, viewport)
        val rect = canonical.fullRect(pose)

        assertEquals(317f, rect.height, 0f)
        assertEquals((viewport.top + 217 - 317).toFloat(), rect.top, 0.5f)
        // The bottom edge is the one Android told the truth about.
        assertEquals((viewport.top + 217).toFloat(), rect.bottom, 0.5f)
    }

    @Test
    fun `a row clipped at the bottom is reconstructed to its full height`() {
        val reported = Bounds(0, viewport.bottom - 120, 1170, viewport.bottom)

        val pose = canonical.reconstructFrom(reported, viewport)
        val rect = canonical.fullRect(pose)

        assertEquals(317f, rect.height, 0f)
        // The top edge is the trustworthy one here.
        assertEquals((viewport.bottom - 120).toFloat(), rect.top, 0.5f)
    }

    @Test
    fun `a fully visible row is taken at face value`() {
        val reported = Bounds(0, 1200, 1170, 1517)

        val pose = canonical.reconstructFrom(reported, viewport)

        assertEquals(1200f, pose.topPx, 0f)
        assertEquals(0f, pose.leftPx, 0f)
    }

    @Test
    fun `a clipped reading is recognised as the same shape, not a smaller one`() {
        val clipped = Bounds(0, viewport.top, 1170, viewport.top + 90)
        assertTrue("a short reading is still this row", canonical.matches(clipped))

        // Something genuinely taller is a different control.
        val taller = Bounds(0, 1200, 1170, 1200 + 500)
        assertTrue("a taller region is not", !canonical.matches(taller))
    }

    // --------------------------------------------------------------- Pebble

    @Test
    fun `Pebble is carried by the same translation as the outline`() {
        val start = TargetPose(0f, 1200f)
        val moved = start.translatedBy(0f, -310f)

        val firstOutline = canonical.fullRect(start)
        val firstPebble = canonical.pebbleRect(start, PebbleCorner.TopEnd, 102f)!!
        val secondOutline = canonical.fullRect(moved)
        val secondPebble = canonical.pebbleRect(moved, PebbleCorner.TopEnd, 102f)!!

        assertEquals(
            firstOutline.top - secondOutline.top,
            firstPebble.top - secondPebble.top,
            0f,
        )
        assertEquals(firstPebble.left, secondPebble.left, 0f)
        assertEquals(102f, secondPebble.width, 0f)
    }

    @Test
    fun `every corner is available to the anchor resolver`() {
        assertEquals(4, canonical.pebbleAnchorOffsets.size)
        PebbleCorner.entries.forEach { corner ->
            assertTrue("$corner missing", canonical.pebbleAnchorOffsets.containsKey(corner))
        }
    }
}
