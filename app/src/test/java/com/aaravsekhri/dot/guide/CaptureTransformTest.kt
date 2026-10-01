package com.aaravsekhri.dot.guide

import androidx.compose.ui.geometry.Offset
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.visual.CaptureTransform
import com.aaravsekhri.dot.guide.visual.FrameRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping between the screen and the smaller surface capture runs on.
 *
 * Capture is no longer the same size as the display, so every measurement has
 * to cross a coordinate boundary. A rectangle stretched across that boundary
 * rather than transformed would put the outline somewhere plausible and wrong,
 * which is the hardest kind of bug to see in a recording.
 */
class CaptureTransformTest {

    private val screenWidth = 1170
    private val screenHeight = 2532

    @Test
    fun `a third-scale capture keeps the aspect ratio`() {
        val (width, height) = CaptureTransform.captureSizeFor(screenWidth, screenHeight)

        assertEquals(390, width)
        assertEquals(844, height)
        assertEquals(
            screenWidth.toFloat() / screenHeight,
            width.toFloat() / height,
            0.005f,
        )
    }

    @Test
    fun `capture dimensions are even, as display pipelines prefer`() {
        val (width, height) = CaptureTransform.captureSizeFor(1171, 2533)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun `a screen point maps to capture and back to itself`() {
        val transform = CaptureTransform.fit(screenWidth, screenHeight, 390, 844)

        listOf(
            Offset(0f, 0f),
            Offset(585f, 1266f),
            Offset(1169f, 2531f),
        ).forEach { point ->
            val round = transform.captureToScreen(transform.screenToCapture(point))
            assertEquals("x of $point", point.x, round.x, 0.5f)
            assertEquals("y of $point", point.y, round.y, 0.5f)
        }
    }

    @Test
    fun `a settings row maps to capture pixels exactly`() {
        val transform = CaptureTransform.fit(screenWidth, screenHeight, 390, 844)
        val row = Bounds(0, 1200, 1170, 1390)

        val inCapture = transform.screenToCapture(row)

        assertEquals(0, inCapture.left)
        assertEquals(390, inCapture.right)
        assertEquals(400, inCapture.top)
        assertEquals(463, inCapture.bottom)
    }

    @Test
    fun `a distance in capture pixels becomes the right distance on screen`() {
        val transform = CaptureTransform.fit(screenWidth, screenHeight, 390, 844)

        // Thirty capture pixels is ninety screen pixels at a third scale.
        assertEquals(90f, transform.captureToScreenDistanceY(30f), 0.5f)
        assertEquals(-90f, transform.captureToScreenDistanceY(-30f), 0.5f)
    }

    @Test
    fun `letterboxing is reported as an offset, not absorbed into the scale`() {
        // A surface wider than the display's aspect ratio: bars down the sides.
        val transform = CaptureTransform.fit(screenWidth, screenHeight, 600, 844)

        assertEquals(transform.scaleX, transform.scaleY, 0.0001f)
        assertTrue("bars on the left and right", transform.offsetX > 0f)
        assertEquals(0f, transform.offsetY, 0.001f)

        // The top-left of the screen lands inside the surface, not at its edge.
        val origin = transform.screenToCapture(Offset(0f, 0f))
        assertEquals(transform.offsetX, origin.x, 0.001f)

        val round = transform.captureToScreen(transform.screenToCapture(Offset(585f, 1266f)))
        assertEquals(585f, round.x, 0.5f)
        assertEquals(1266f, round.y, 0.5f)
    }

    @Test
    fun `a rotated surface keeps points aligned`() {
        // Landscape display, landscape capture surface.
        val transform = CaptureTransform.fit(2532, 1170, 844, 390)
        val point = Offset(1266f, 585f)

        val round = transform.captureToScreen(transform.screenToCapture(point))

        assertEquals(1266f, round.x, 0.5f)
        assertEquals(585f, round.y, 0.5f)
    }

    @Test
    fun `a capture rectangle comes back as the screen rectangle it came from`() {
        val transform = CaptureTransform.fit(screenWidth, screenHeight, 390, 844)
        val inCapture = FrameRect(10, 400, 380, 463)

        val onScreen = transform.captureToScreen(inCapture)
        val roundTripped = transform.screenToCapture(onScreen)

        assertEquals(inCapture.left, roundTripped.left)
        assertEquals(inCapture.top, roundTripped.top)
        assertEquals(inCapture.right, roundTripped.right)
        assertEquals(inCapture.bottom, roundTripped.bottom)
    }

    @Test
    fun `a degenerate screen size does not produce nonsense`() {
        val transform = CaptureTransform.fit(0, 0, 390, 844)
        assertEquals(1f, transform.scaleX, 0f)
    }
}
