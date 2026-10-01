package com.aaravsekhri.dot.guide.visual

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.aaravsekhri.dot.guide.model.Bounds
import kotlin.math.roundToInt

/**
 * The mapping between screen pixels and the smaller surface capture runs on.
 *
 * Capture no longer mirrors the display at full size and then shrinks each
 * frame in software. The virtual display is created at roughly a third of each
 * dimension, so the compositor does the scaling and every frame that arrives is
 * already the size tracking wants. That removes about three million pixel reads
 * per frame from the capture thread, which was the throughput ceiling.
 *
 * It also introduces a coordinate space that is not the screen's, and a
 * rectangle silently stretched between the two would put the outline
 * everywhere except where the row is. So the conversion is explicit, it is
 * one object, and it is the only place the arithmetic exists.
 *
 * Aspect ratio is preserved, which means the capture may be letterboxed inside
 * the surface when the system hands back a size Dot did not ask for — the
 * `onCapturedContentResize` callback does exactly that on rotation. The
 * resulting bars are accounted for by [offsetX] and [offsetY] rather than
 * absorbed into the scale.
 */
data class CaptureTransform(
    val screenWidth: Int,
    val screenHeight: Int,
    val captureWidth: Int,
    val captureHeight: Int,
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
) {

    /** Uniform scale. Equal to [scaleX] whenever aspect ratio is preserved. */
    val scale: Float get() = scaleX

    fun captureToScreen(point: Offset): Offset =
        Offset((point.x - offsetX) / scaleX, (point.y - offsetY) / scaleY)

    fun screenToCapture(point: Offset): Offset =
        Offset(point.x * scaleX + offsetX, point.y * scaleY + offsetY)

    fun captureToScreen(rect: Rect): Rect = Rect(
        (rect.left - offsetX) / scaleX,
        (rect.top - offsetY) / scaleY,
        (rect.right - offsetX) / scaleX,
        (rect.bottom - offsetY) / scaleY,
    )

    fun screenToCapture(rect: Rect): Rect = Rect(
        rect.left * scaleX + offsetX,
        rect.top * scaleY + offsetY,
        rect.right * scaleX + offsetX,
        rect.bottom * scaleY + offsetY,
    )

    /** A screen rectangle in capture pixels, rounded for indexing. */
    fun screenToCapture(bounds: Bounds): FrameRect = FrameRect(
        (bounds.left * scaleX + offsetX).roundToInt(),
        (bounds.top * scaleY + offsetY).roundToInt(),
        (bounds.right * scaleX + offsetX).roundToInt(),
        (bounds.bottom * scaleY + offsetY).roundToInt(),
    )

    /** A capture rectangle back in screen pixels. */
    fun captureToScreen(rect: FrameRect): Bounds = Bounds(
        ((rect.left - offsetX) / scaleX).roundToInt(),
        ((rect.top - offsetY) / scaleY).roundToInt(),
        ((rect.right - offsetX) / scaleX).roundToInt(),
        ((rect.bottom - offsetY) / scaleY).roundToInt(),
    )

    /** A capture-space distance in screen pixels. */
    fun captureToScreenDistanceY(dy: Float): Float = dy / scaleY

    fun captureToScreenDistanceX(dx: Float): Float = dx / scaleX

    companion object {

        /**
         * Roughly a third of each dimension.
         *
         * Enough detail for a settings row to be matched, and small enough that
         * a whole frame is about a hundred kilobytes rather than twelve
         * megabytes.
         */
        const val DEFAULT_TRACKING_SCALE = 1f / 3f

        /** Encoders and display pipelines prefer even dimensions. */
        private fun even(value: Int) = (value / 2) * 2

        /** The capture size to ask the virtual display for. */
        fun captureSizeFor(
            screenWidth: Int,
            screenHeight: Int,
            scale: Float = DEFAULT_TRACKING_SCALE,
        ): Pair<Int, Int> = even((screenWidth * scale).roundToInt()).coerceAtLeast(2) to
            even((screenHeight * scale).roundToInt()).coerceAtLeast(2)

        /**
         * The transform for a capture surface of [captureWidth] x [captureHeight].
         *
         * The content is fitted rather than stretched, so a surface whose
         * aspect ratio does not match the display letterboxes and the bars are
         * reported as offsets.
         */
        fun fit(
            screenWidth: Int,
            screenHeight: Int,
            captureWidth: Int,
            captureHeight: Int,
        ): CaptureTransform {
            if (screenWidth <= 0 || screenHeight <= 0) {
                return CaptureTransform(
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    captureWidth = captureWidth,
                    captureHeight = captureHeight,
                    scaleX = 1f,
                    scaleY = 1f,
                    offsetX = 0f,
                    offsetY = 0f,
                )
            }
            val uniform = minOf(
                captureWidth.toFloat() / screenWidth,
                captureHeight.toFloat() / screenHeight,
            )
            val usedWidth = screenWidth * uniform
            val usedHeight = screenHeight * uniform
            return CaptureTransform(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                scaleX = uniform,
                scaleY = uniform,
                offsetX = (captureWidth - usedWidth) / 2f,
                offsetY = (captureHeight - usedHeight) / 2f,
            )
        }
    }
}
