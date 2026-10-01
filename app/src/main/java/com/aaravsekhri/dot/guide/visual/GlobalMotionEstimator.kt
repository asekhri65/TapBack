package com.aaravsekhri.dot.guide.visual

import com.aaravsekhri.dot.guide.motion.MotionClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the whole screen appears to be doing, as opposed to any one row. */
enum class GlobalMotionKind {
    /** Everything moved together. The ordinary case, and the useful one. */
    Scrolling,

    /** Parts of the screen moved differently: a page change, not a scroll. */
    Transition,

    /** Too little texture, or too little agreement, to say anything. */
    Indeterminate,
}

/**
 * How the underlying content moved between two frames.
 *
 * Deliberately says nothing about *what* moved. This estimator is not allowed
 * to have an opinion about which row is the target, and keeping it structurally
 * incapable of expressing one is the cheapest way to guarantee that.
 */
data class GlobalMotionResult(
    /** Frame-space translation of the content. Divide by scale for screen pixels. */
    val dx: Float,
    val dy: Float,
    val confidence: Float,
    val inliers: Int,
    val blocks: Int,
    val processingTimeMs: Long,
    val kind: GlobalMotionKind,
) {
    val inlierRatio: Float get() = if (blocks == 0) 0f else inliers.toFloat() / blocks

    companion object {
        fun none(processingTimeMs: Long = 0L) = GlobalMotionResult(
            dx = 0f,
            dy = 0f,
            confidence = 0f,
            inliers = 0,
            blocks = 0,
            processingTimeMs = processingTimeMs,
            kind = GlobalMotionKind.Indeterminate,
        )
    }
}

/**
 * Estimates the dominant translation between two captured frames.
 *
 * Built around two facts learned the hard way from the first experiment.
 *
 * A fling moves content much further than a sensible search window. A flat
 * search wide enough to catch it is far too slow, so the search happens on a
 * pyramid: a coarse level where 400 screen pixels is a short hop, refined down
 * to the original resolution.
 *
 * A Settings list is made of rows that look alike, so any single patch can
 * match the wrong one confidently. The answer therefore comes from several
 * wide bands spread down the viewport, combined by median rather than by
 * average, with the spread reported as an inlier count. Bands that disagree
 * are not evidence of motion — they are evidence that this is not a scroll at
 * all, which is what separates a page transition from a drag.
 */
class GlobalMotionEstimator(
    private val blockCount: Int = DEFAULT_BLOCK_COUNT,
    private val levels: Int = DEFAULT_LEVELS,
    private val coarseRadiusY: Int = DEFAULT_COARSE_RADIUS_Y,
    private val coarseRadiusX: Int = DEFAULT_COARSE_RADIUS_X,
    private val clock: () -> Long = { MotionClock.nowNanos() / 1_000_000 },
) {

    /**
     * The pyramid built for the last frame, so consecutive frames share it.
     *
     * Keyed on the frame instance rather than its timestamp: two different
     * frames can carry the same timestamp, and handing back the wrong pyramid
     * for one of them would be a silent, unreproducible wrong answer.
     */
    private val scratch = arrayOf(PyramidScratch(), PyramidScratch())
    private val cachedFrames = arrayOfNulls<CapturedFrame>(2)
    private val cachedPyramids = arrayOfNulls<Pyramid>(2)
    private var nextSlot = 0

    private fun pyramidFor(frame: CapturedFrame): Pyramid {
        for (slot in 0..1) {
            if (cachedFrames[slot] === frame) return cachedPyramids[slot]!!
        }
        val slot = nextSlot
        nextSlot = 1 - nextSlot
        val built = scratch[slot].build(frame, levels)
        cachedFrames[slot] = frame
        cachedPyramids[slot] = built
        return built
    }

    /** Dropped when a session ends, so nothing from one screen outlives it. */
    fun reset() {
        cachedFrames.fill(null)
        cachedPyramids.fill(null)
    }

    /**
     * @param region the part of the frame that is app content worth measuring.
     * @param excluded anything inside [region] that is not app content: Dot's
     *   own card and cue, a keyboard, a system dialog.
     */
    fun estimate(
        previous: CapturedFrame,
        current: CapturedFrame,
        region: FrameRect,
        excluded: List<FrameRect> = emptyList(),
    ): GlobalMotionResult {
        val startedAt = clock()
        if (previous.width != current.width || previous.height != current.height) {
            return GlobalMotionResult.none(clock() - startedAt)
        }

        val usable = region.clampInto(current.width, current.height)
        if (usable.width < MIN_REGION || usable.height < MIN_REGION) {
            return GlobalMotionResult.none(clock() - startedAt)
        }

        val before = pyramidFor(previous)
        val after = pyramidFor(current)

        val estimates = ArrayList<Pair<Int, Int>>(blockCount)
        for (block in blocksIn(usable, excluded)) {
            trackBlock(before, after, block)?.let(estimates::add)
        }

        if (estimates.isEmpty()) return GlobalMotionResult.none(clock() - startedAt)

        val medianDx = medianOf(estimates.map { it.first })
        val medianDy = medianOf(estimates.map { it.second })
        val inliers = estimates.count { (dx, dy) ->
            abs(dx - medianDx) <= INLIER_TOLERANCE && abs(dy - medianDy) <= INLIER_TOLERANCE
        }
        val ratio = inliers.toFloat() / estimates.size

        // A real scroll moves every band by the same amount. Bands that
        // disagree mean the screen is being replaced, not moved, and reporting
        // a translation for that would drag the outline across a page change.
        val kind = when {
            ratio >= SCROLL_INLIER_RATIO && estimates.size >= MIN_BLOCKS_FOR_SCROLL ->
                GlobalMotionKind.Scrolling

            estimates.size >= MIN_BLOCKS_FOR_SCROLL && spreadOf(estimates, medianDy) > TRANSITION_SPREAD ->
                GlobalMotionKind.Transition

            else -> GlobalMotionKind.Indeterminate
        }

        val confidence = when (kind) {
            GlobalMotionKind.Scrolling -> ratio
            else -> 0f
        }

        return GlobalMotionResult(
            dx = medianDx.toFloat(),
            dy = medianDy.toFloat(),
            confidence = confidence,
            inliers = inliers,
            blocks = estimates.size,
            processingTimeMs = clock() - startedAt,
            kind = kind,
        )
    }

    // ------------------------------------------------------------- blocks

    /**
     * Wide bands, spread down the viewport.
     *
     * Wide on purpose: a band spanning several rows and their icons has a
     * texture no single row can impersonate, which is the whole reason the
     * first experiment's row-sized template kept finding its neighbours.
     */
    private fun blocksIn(region: FrameRect, excluded: List<FrameRect>): List<FrameRect> {
        val margin = (region.width * BLOCK_SIDE_MARGIN).toInt()
        val left = region.left + margin
        val right = region.right - margin
        if (right - left < MIN_REGION) return emptyList()

        // Dot's instruction card is a wide band at one edge. Trimming the
        // region back from it keeps the full complement of bands in the space
        // that is left, rather than deleting whichever bands it happened to
        // touch — which on a real screen removed a third of them and left too
        // few to agree on anything.
        var top = region.top
        var bottom = region.bottom
        excluded.filter { it.width >= (right - left) / 2 }.forEach { band ->
            if (band.top <= top && band.bottom > top) top = band.bottom
            if (band.bottom >= bottom && band.top < bottom) bottom = band.top
        }
        val usable = FrameRect(left, top, right, bottom)
        if (usable.height < MIN_REGION * 2) return emptyList()

        val slot = usable.height / blockCount
        val height = (slot * BLOCK_HEIGHT_OF_SLOT).toInt()
        if (height < MIN_REGION) return emptyList()

        val blocks = ArrayList<FrameRect>(blockCount)
        for (index in 0 until blockCount) {
            val blockTop = usable.top + index * slot + (slot - height) / 2
            val candidate = FrameRect(left, blockTop, right, blockTop + height)
            if (excluded.any { it.intersects(candidate) }) continue
            blocks += candidate
        }
        return blocks
    }

    /**
     * Follows one band down the pyramid.
     *
     * The coarse level does the searching, because that is where a fling is a
     * short distance. Every finer level only has to decide between a handful
     * of neighbours, so the cost of the whole descent is a rounding error next
     * to the one search at the top.
     */
    private fun trackBlock(
        before: Pyramid,
        after: Pyramid,
        block: FrameRect,
    ): Pair<Int, Int>? {
        val top = levels - 1
        var scaled = block.scaledBy(top)
        if (scaled.width < MIN_BLOCK || scaled.height < MIN_BLOCK) return null

        var best = search(
            before.level(top),
            after.level(top),
            scaled,
            centreX = 0,
            centreY = 0,
            radiusX = coarseRadiusX,
            radiusY = coarseRadiusY,
        ) ?: return null

        for (level in top - 1 downTo 0) {
            scaled = block.scaledBy(level)
            if (scaled.width < MIN_BLOCK || scaled.height < MIN_BLOCK) return null
            best = search(
                before.level(level),
                after.level(level),
                scaled,
                centreX = best.first * 2,
                centreY = best.second * 2,
                radiusX = REFINE_RADIUS,
                radiusY = REFINE_RADIUS,
            ) ?: return null
        }
        return best
    }

    /**
     * Zero-mean sum of absolute differences, which ignores a uniform change in
     * brightness — a dimming screen is not movement.
     */
    private fun search(
        before: Plane,
        after: Plane,
        block: FrameRect,
        centreX: Int,
        centreY: Int,
        radiusX: Int,
        radiusY: Int,
    ): Pair<Int, Int>? {
        if (block.left < 0 || block.top < 0 ||
            block.right > before.width || block.bottom > before.height
        ) {
            return null
        }

        val step = if (radiusY > REFINE_RADIUS) COARSE_SAMPLE_STEP else SAMPLE_STEP
        val referenceMean = before.meanOf(block, step)
        if (before.varianceOf(block, referenceMean, step) < MIN_VARIANCE) return null

        var bestScore = Float.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        var found = false

        for (dy in (centreY - radiusY)..(centreY + radiusY)) {
            for (dx in (centreX - radiusX)..(centreX + radiusX)) {
                val candidate = block.offsetBy(dx, dy)
                if (candidate.left < 0 || candidate.top < 0 ||
                    candidate.right > after.width || candidate.bottom > after.height
                ) {
                    continue
                }
                val score = zeroMeanDifference(before, block, referenceMean, after, candidate, step)
                if (score < bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                    found = true
                }
            }
        }
        return if (found) bestDx to bestDy else null
    }

    /**
     * Zero-mean sum of absolute differences over every [step]th pixel.
     *
     * Zero-mean because a screen that dims is not a screen that moved. The
     * step is coarser during the wide search than during refinement: at that
     * level the answer only has to be right to within a few pixels, and the
     * wide search is where nearly all of the time goes.
     */
    private fun zeroMeanDifference(
        before: Plane,
        block: FrameRect,
        referenceMean: Float,
        after: Plane,
        candidate: FrameRect,
        step: Int,
    ): Float {
        val offset = after.meanOf(candidate, step) - referenceMean
        var total = 0f
        var y = 0
        while (y < block.height) {
            val beforeY = block.top + y
            val afterY = candidate.top + y
            var x = 0
            while (x < block.width) {
                val a = before.at(block.left + x, beforeY)
                val b = after.at(candidate.left + x, afterY)
                total += abs(a - b + offset)
                x += step
            }
            y += step
        }
        return total
    }

    private fun spreadOf(estimates: List<Pair<Int, Int>>, medianDy: Int): Int =
        estimates.maxOf { abs(it.second - medianDy) }

    private fun medianOf(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    // ------------------------------------------------------------ pyramid

    /**
     * One resolution level: grayscale bytes, borrowed rather than owned.
     *
     * The first version of this copied every frame into a float array and
     * built a summed-area table beside it — about two megabytes allocated and
     * filled per captured frame. At thirty frames a second that was sixty
     * megabytes a second of garbage, and it cost more than the search it was
     * meant to accelerate: a hundred milliseconds a frame against the local
     * matcher's five, for a fifth of the arithmetic. Level zero now points
     * straight at the captured buffer and the smaller levels reuse scratch
     * space, so a pyramid allocates nothing in the steady state.
     */
    class Plane(val width: Int, val height: Int, val pixels: ByteArray) {

        fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

        fun meanOf(rect: FrameRect, step: Int): Float {
            var sum = 0
            var count = 0
            var y = rect.top
            while (y < rect.bottom) {
                val row = y * width
                var x = rect.left
                while (x < rect.right) {
                    sum += pixels[row + x].toInt() and 0xFF
                    count++
                    x += step
                }
                y += step
            }
            return if (count == 0) 0f else sum.toFloat() / count
        }

        fun varianceOf(rect: FrameRect, mean: Float, step: Int): Float {
            var sum = 0f
            var count = 0
            var y = rect.top
            while (y < rect.bottom) {
                val row = y * width
                var x = rect.left
                while (x < rect.right) {
                    val d = (pixels[row + x].toInt() and 0xFF) - mean
                    sum += d * d
                    count++
                    x += step
                }
                y += step
            }
            return if (count == 0) 0f else sum / count
        }
    }

    /** Halved repeatedly, so a long displacement becomes a short one. */
    class Pyramid(private val planes: List<Plane>) {
        fun level(index: Int): Plane = planes[min(index, planes.size - 1)]
    }

    /**
     * Scratch space for one frame's reduced levels.
     *
     * Two of these exist, so the previous frame's pyramid survives while the
     * current one is built. Buffers are grown once and then reused for the
     * life of the session.
     */
    private class PyramidScratch {
        private var buffers: Array<ByteArray> = emptyArray()

        fun build(frame: CapturedFrame, levels: Int): Pyramid {
            val planes = ArrayList<Plane>(levels)
            planes += Plane(frame.width, frame.height, frame.luminance)
            for (level in 1 until levels) {
                val previous = planes[level - 1]
                if (previous.width < 4 || previous.height < 4) break
                val width = previous.width / 2
                val height = previous.height / 2
                planes += Plane(width, height, halveInto(previous, width, height, level - 1))
            }
            return Pyramid(planes)
        }

        private fun halveInto(source: Plane, width: Int, height: Int, slot: Int): ByteArray {
            if (buffers.size <= slot) {
                buffers = Array(slot + 1) { index -> buffers.getOrNull(index) ?: ByteArray(0) }
            }
            var target = buffers[slot]
            if (target.size < width * height) {
                target = ByteArray(width * height)
                buffers[slot] = target
            }
            for (y in 0 until height) {
                val sourceRow = (y * 2) * source.width
                val nextRow = sourceRow + source.width
                val targetRow = y * width
                for (x in 0 until width) {
                    val sourceX = x * 2
                    val total = (source.pixels[sourceRow + sourceX].toInt() and 0xFF) +
                        (source.pixels[sourceRow + sourceX + 1].toInt() and 0xFF) +
                        (source.pixels[nextRow + sourceX].toInt() and 0xFF) +
                        (source.pixels[nextRow + sourceX + 1].toInt() and 0xFF)
                    target[targetRow + x] = (total shr 2).toByte()
                }
            }
            return target
        }
    }

    companion object {
        const val DEFAULT_BLOCK_COUNT = 4
        const val DEFAULT_LEVELS = 3

        /**
         * At quarter capture scale and three pyramid levels, one step at the
         * coarsest level is 16 screen pixels, so this reaches about 576 screen
         * pixels between frames — comfortably past the 400 a fling needs.
         */
        /**
         * At one-third capture scale and three pyramid levels, one step at the
         * coarsest level is twelve screen pixels, so this reaches about four
         * hundred screen pixels between frames — what a fling needs.
         */
        const val DEFAULT_COARSE_RADIUS_Y = 34
        const val DEFAULT_COARSE_RADIUS_X = 6

        private const val REFINE_RADIUS = 2
        /**
         * Only every third pixel of a band is compared.
         *
         * The step subsamples the comparison, not the offsets, so the answer
         * is still exact to the pixel — a band of several thousand pixels does
         * not need all of them to know which of twenty-five positions it is.
         */
        private const val SAMPLE_STEP = 3

        /** The wide search samples more sparsely still; it only needs to get close. */
        private const val COARSE_SAMPLE_STEP = 4
        private const val MIN_REGION = 16
        private const val MIN_BLOCK = 6
        private const val MIN_VARIANCE = 12f
        private const val INLIER_TOLERANCE = 2
        private const val SCROLL_INLIER_RATIO = 0.6f
        private const val MIN_BLOCKS_FOR_SCROLL = 3
        private const val TRANSITION_SPREAD = 6
        private const val BLOCK_SIDE_MARGIN = 0.08f
        private const val BLOCK_HEIGHT_OF_SLOT = 0.7f
    }
}

/** This rectangle at a pyramid level, where each level halves the resolution. */
internal fun FrameRect.scaledBy(level: Int): FrameRect {
    if (level <= 0) return this
    val factor = 1 shl level
    return FrameRect(left / factor, top / factor, right / factor, bottom / factor)
}
