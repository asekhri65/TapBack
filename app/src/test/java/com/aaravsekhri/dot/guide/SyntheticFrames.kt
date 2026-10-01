package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.visual.CaptureTransform
import com.aaravsekhri.dot.guide.visual.CapturedFrame
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic synthetic screens, so the estimator can be measured rather
 * than eyeballed.
 *
 * Each builds a small grayscale image resembling a Settings list — bands of
 * background with textured "rows" on them — which can then be translated by a
 * known number of pixels. The estimator's answer is compared against the
 * translation that was actually applied.
 */
object SyntheticFrames {

    const val WIDTH = 120
    const val HEIGHT = 300

    /**
     * A list-like screen with a distinctive row at [rowTop].
     *
     * @param seed changes the row's texture, for building distractors that look
     *   similar but are not identical.
     */
    fun listScreen(
        rowTop: Int,
        rowHeight: Int = 40,
        seed: Int = 1,
        noise: Int = 0,
        distractorTop: Int? = null,
        distractorSeed: Int = 2,
        scale: Float = 1f / 3f,
        timestampNanos: Long = 1_000L,
    ): CapturedFrame {
        val pixels = ByteArray(WIDTH * HEIGHT)
        val random = Random(4242)

        // Background with a gentle gradient, so it is not perfectly flat.
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                pixels[y * WIDTH + x] = (200 + (y % 7)).toByte()
            }
        }

        drawRow(pixels, rowTop, rowHeight, seed)
        distractorTop?.let { drawRow(pixels, it, rowHeight, distractorSeed) }

        if (noise > 0) {
            for (i in pixels.indices) {
                val value = (pixels[i].toInt() and 0xFF) + random.nextInt(-noise, noise + 1)
                pixels[i] = value.coerceIn(0, 255).toByte()
            }
        }

        return CapturedFrame(WIDTH, HEIGHT, pixels, transformFor(WIDTH, HEIGHT, scale), timestampNanos)
    }

    /** A row of text-like marks, distinctive enough to match against. */
    private fun drawRow(pixels: ByteArray, top: Int, height: Int, seed: Int) {
        val random = Random(seed)
        for (y in top until (top + height).coerceAtMost(HEIGHT)) {
            if (y < 0) continue
            for (x in 0 until WIDTH) {
                val index = y * WIDTH + x
                // A title-ish band and a summary-ish band, with per-seed marks.
                val inTitle = y - top in 6..16 && x in 8..(8 + 60 + seed * 7)
                val inSummary = y - top in 22..30 && x in 8..(8 + 40 + seed * 5)
                val texture = (sin((x + seed * 13) * 0.7) * 40).toInt()
                pixels[index] = when {
                    inTitle -> (40 + texture + random.nextInt(20)).coerceIn(0, 255).toByte()
                    inSummary -> (110 + texture / 2).coerceIn(0, 255).toByte()
                    else -> pixels[index]
                }
            }
        }
    }

    const val TALL_WIDTH = 120
    const val TALL_HEIGHT = 600

    /**
     * A whole list of rows, tall enough for a pyramid to have room to search.
     *
     * The rows are deliberately similar to each other, because that is the
     * property of a real Settings screen that broke the first estimator.
     */
    fun scrollingList(
        offset: Int = 0,
        rows: Int = 10,
        scale: Float = 1f / 3f,
        timestampNanos: Long = 1_000L,
    ): CapturedFrame {
        val pixels = ByteArray(TALL_WIDTH * TALL_HEIGHT)
        for (y in 0 until TALL_HEIGHT) {
            for (x in 0 until TALL_WIDTH) {
                pixels[y * TALL_WIDTH + x] = (200 + (y % 7)).toByte()
            }
        }
        val pitch = TALL_HEIGHT / rows
        for (index in -1..rows) {
            val top = index * pitch + offset
            if (top + 40 < 0 || top > TALL_HEIGHT) continue
            // Each row gets its own texture. Real Settings rows share a
            // layout but carry different words, so a perfectly periodic
            // fixture would be a harder problem than the real one — and an
            // unsolvable one, since a shift of one period is then genuinely
            // indistinguishable from any other.
            drawTallRow(pixels, top, 40, seed = index + 3)
        }
        return CapturedFrame(
            TALL_WIDTH,
            TALL_HEIGHT,
            pixels,
            transformFor(TALL_WIDTH, TALL_HEIGHT, scale),
            timestampNanos,
        )
    }

    /**
     * Two halves moving differently: a page transition, not a scroll.
     *
     * The global estimator has to notice that no single translation explains
     * this, because reporting one would drag the outline across a page change.
     */
    fun divergent(
        source: CapturedFrame,
        topDy: Int,
        bottomDy: Int,
        timestampNanos: Long = source.timestampNanos + 16_000_000L,
    ): CapturedFrame {
        val pixels = ByteArray(source.width * source.height)
        val split = source.height / 2
        for (y in 0 until source.height) {
            val dy = if (y < split) topDy else bottomDy
            for (x in 0 until source.width) {
                pixels[y * source.width + x] = source.at(x, y - dy).toByte()
            }
        }
        return CapturedFrame(source.width, source.height, pixels, source.transform, timestampNanos)
    }

    private fun drawTallRow(pixels: ByteArray, top: Int, height: Int, seed: Int) {
        val random = Random(seed)
        for (y in top until (top + height).coerceAtMost(TALL_HEIGHT)) {
            if (y < 0) continue
            for (x in 0 until TALL_WIDTH) {
                val index = y * TALL_WIDTH + x
                val inTitle = y - top in 6..16 && x in 8..(8 + 60 + seed * 7)
                val inSummary = y - top in 22..30 && x in 8..(8 + 40 + seed * 5)
                val texture = (sin((x + seed * 13) * 0.7) * 40).toInt()
                pixels[index] = when {
                    inTitle -> (40 + texture + random.nextInt(20)).coerceIn(0, 255).toByte()
                    inSummary -> (110 + texture / 2).coerceIn(0, 255).toByte()
                    else -> pixels[index]
                }
            }
        }
    }

    /** The same screen, shifted vertically — what a scroll looks like. */
    fun translated(
        source: CapturedFrame,
        dy: Int,
        dx: Int = 0,
        timestampNanos: Long = source.timestampNanos + 16_000_000L,
    ): CapturedFrame {
        val pixels = ByteArray(source.width * source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                pixels[y * source.width + x] = source.at(x - dx, y - dy).toByte()
            }
        }
        return CapturedFrame(source.width, source.height, pixels, source.transform, timestampNanos)
    }

    /** A capture surface of exactly these dimensions, with no letterboxing. */
    fun transformFor(width: Int, height: Int, scale: Float) = CaptureTransform.fit(
        screenWidth = (width / scale).toInt(),
        screenHeight = (height / scale).toInt(),
        captureWidth = width,
        captureHeight = height,
    )
}
