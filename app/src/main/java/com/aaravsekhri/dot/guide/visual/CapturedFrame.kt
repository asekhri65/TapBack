package com.aaravsekhri.dot.guide.visual

/**
 * One frame of the screen, reduced to what motion estimation actually needs.
 *
 * Grayscale, downscaled, and held as a plain byte array. Three properties of
 * this type are deliberate and load-bearing:
 *
 * It is small. A quarter-scale grayscale frame of a 1080x2400 display is about
 * 160 kB, against 10 MB for the full-colour original, so a couple of them can
 * live in memory without pressure.
 *
 * It is not an image anyone could look at meaningfully, and it never leaves
 * this process. Frames are never written to disk, never uploaded, never
 * attached to analytics or crash reports, and never logged. Only numbers
 * derived from them — displacements, confidences, timings — are ever recorded.
 *
 * It is immutable once built, so it can be handed between threads without
 * copying.
 *
 * @param scale how much the capture was reduced by, to convert measurements
 *   back to screen pixels.
 * @param timestampNanos monotonic, from the capture pipeline.
 */
class CapturedFrame(
    val width: Int,
    val height: Int,
    val luminance: ByteArray,
    val transform: CaptureTransform,
    val timestampNanos: Long,
    /** How long this frame took to turn into luminance, for the capture budget. */
    val conversionNanos: Long = 0L,
) {
    /** Uniform capture scale, for the places that only need the ratio. */
    val scale: Float get() = transform.scaleX

    init {
        require(luminance.size >= width * height) {
            "luminance buffer too small for ${width}x$height"
        }
    }

    /** Luminance at a point, 0..255, or 0 outside the frame. */
    fun at(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return luminance[y * width + x].toInt() and 0xFF
    }

    companion object {
        /**
         * Frames are processed at a quarter of each dimension.
         *
         * Enough detail for a Settings row to be matched unambiguously, a
         * sixteenth of the work, and it blurs away text detail that would
         * otherwise be the only thing distinguishing two similar rows — which
         * is why the estimator's confidence, not its peak position alone,
         * decides whether a match is used.
         */
        const val DEFAULT_SCALE = CaptureTransform.DEFAULT_TRACKING_SCALE
    }
}

/** A rectangle in the reduced frame's coordinates. */
data class FrameRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun offsetBy(dx: Int, dy: Int) = FrameRect(left + dx, top + dy, right + dx, bottom + dy)

    /** Shrinks by [amount] on every side, never past a single pixel. */
    fun insetBy(amount: Int): FrameRect {
        if (amount <= 0) return this
        val horizontal = amount.coerceAtMost((width - 1) / 2).coerceAtLeast(0)
        val vertical = amount.coerceAtMost((height - 1) / 2).coerceAtLeast(0)
        return FrameRect(left + horizontal, top + vertical, right - horizontal, bottom - vertical)
    }

    fun intersects(other: FrameRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun clampInto(width: Int, height: Int) = FrameRect(
        left.coerceIn(0, width),
        top.coerceIn(0, height),
        right.coerceIn(0, width),
        bottom.coerceIn(0, height),
    )
}

/**
 * A screen rectangle in a reduced frame's coordinates.
 *
 * Goes through the frame's own transform, so letterboxing and a capture
 * surface the system resized are accounted for rather than stretched away.
 */
fun com.aaravsekhri.dot.guide.model.Bounds.toFrameRect(transform: CaptureTransform) =
    transform.screenToCapture(this)

/** A frame rectangle back in screen coordinates. */
fun FrameRect.toBounds(transform: CaptureTransform) = transform.captureToScreen(this)
