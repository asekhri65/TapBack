package com.aaravsekhri.dot.guide.visual

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Estimates translation by looking for the previous frame's patch in the
 * current one, within a constrained search window.
 *
 * Chosen over optical flow or feature matching after weighing what this
 * actually has to do. Scrolling a list is pure translation of a rigid patch —
 * no rotation, no perspective, and scaling only when the font size changes,
 * which is a separate event the semantic layer already detects. For that,
 * zero-mean normalised cross-correlation over a bounded search window is the
 * most direct answer: no dependency, no feature detector to tune, entirely
 * deterministic, and testable against synthetic frames down to the pixel.
 *
 * OpenCV would bring pyramidal Lucas-Kanade and a great deal else, at roughly
 * a hundred megabytes and a native build step, to solve a harder problem than
 * the one in front of us. It was not worth it here.
 *
 * The correlation is zero-mean and normalised so a change in brightness — a
 * row highlighting under a finger, the screen dimming — does not read as
 * movement.
 *
 * The part that matters most is what it refuses. A Settings list is full of
 * rows that look alike at quarter scale, so a confident-looking peak is not
 * enough: the peak has to be clearly better than the next-best candidate
 * elsewhere in the window. Without that margin the estimate is returned with
 * low confidence, and the tracker declines to move rather than sliding the
 * overlay onto the neighbouring row.
 */
class TemplateMatchEstimator(
    /** How far, in reduced-frame pixels, the patch is searched for. */
    private val searchRadiusY: Int = DEFAULT_SEARCH_RADIUS_Y,
    private val searchRadiusX: Int = DEFAULT_SEARCH_RADIUS_X,
    /** Correlation below this is never trusted, however unique. */
    private val minimumCorrelation: Float = DEFAULT_MINIMUM_CORRELATION,
    /** Regions with less texture than this cannot be tracked at all. */
    private val minimumVariance: Float = DEFAULT_MINIMUM_VARIANCE,
    /** Rectangles to keep out of matching — Dot's own overlay. */
    private var excluded: List<FrameRect> = emptyList(),
) : VisualMotionEstimator {

    /** Replaces the regions excluded from matching. */
    fun setExcludedRegions(regions: List<FrameRect>) {
        excluded = regions
    }

    override fun initialize(
        frame: CapturedFrame,
        verifiedTarget: FrameRect,
    ): TrackingInitializationResult {
        val patch = verifiedTarget.clampInto(frame.width, frame.height)
        if (patch.isEmpty) {
            return TrackingInitializationResult(false, 0f, "target is off-frame")
        }
        if (excluded.any { it.intersects(patch) }) {
            return TrackingInitializationResult(
                false,
                0f,
                "target overlaps Dot's own overlay",
            )
        }

        // A flat patch — an empty row, a plain background — has nothing to
        // match against and would lock onto anything. Better to decline.
        val variance = varianceOf(frame, patch)
        if (variance < minimumVariance) {
            return TrackingInitializationResult(
                false,
                variance / minimumVariance,
                "not enough texture to track",
            )
        }

        return TrackingInitializationResult(true, 1f)
    }

    override fun estimate(
        previousFrame: CapturedFrame,
        currentFrame: CapturedFrame,
        currentTarget: FrameRect,
        hint: SearchHint,
    ): MotionEstimate {
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000

        val patch = currentTarget.clampInto(previousFrame.width, previousFrame.height)
        if (patch.isEmpty || patch.width < MIN_PATCH || patch.height < MIN_PATCH) {
            return MotionEstimate.none(currentFrame.timestampNanos, elapsedMs())
        }

        val reference = extract(previousFrame, patch) ?: return MotionEstimate.none(
            currentFrame.timestampNanos,
            elapsedMs(),
        )

        var bestScore = -1f
        var bestDx = 0
        var bestDy = 0
        // The best score found far enough away from the winner to be a
        // genuinely different candidate rather than its own shoulder.
        var runnerUp = -1f

        val fromY = hint.predictedDy - hint.radiusY
        val toY = hint.predictedDy + hint.radiusY
        val fromX = hint.predictedDx - hint.radiusX
        val toX = hint.predictedDx + hint.radiusX

        // A narrow window is searched outright. It is cheap, it is exact, and
        // a window this small physically cannot contain the next row down,
        // which is the only wrong answer that matters here.
        if (hint.radiusY <= DIRECT_SEARCH_RADIUS) {
            for (dy in fromY..toY) {
                for (dx in fromX..toX) {
                    val candidate = patch.offsetBy(dx, dy)
                    if (!isUsable(candidate, currentFrame, excluded)) continue
                    val score = correlate(reference, currentFrame, candidate)
                    if (score > bestScore) {
                        if (isDistinctFrom(dx, dy, bestDx, bestDy)) {
                            runnerUp = max(runnerUp, bestScore)
                        }
                        bestScore = score
                        bestDx = dx
                        bestDy = dy
                    } else if (isDistinctFrom(dx, dy, bestDx, bestDy)) {
                        runnerUp = max(runnerUp, score)
                    }
                }
            }
        } else {
            // Coarse pass: a quarter of the template's pixels, every fourth
            // offset. A full-resolution scan of every offset in a wide window
            // costs about 5,000 correlations per frame, which measured at
            // ~60 ms — slower than the thing it was meant to improve on.
            var dy = fromY
            while (dy <= toY) {
                var dx = fromX
                while (dx <= toX) {
                    val candidate = patch.offsetBy(dx, dy)
                    if (!isUsable(candidate, currentFrame, excluded)) {
                        dx += COARSE_STEP
                        continue
                    }
                    val score = correlate(reference, currentFrame, candidate, COARSE_SAMPLE_STEP)
                    if (score > bestScore) {
                        if (isDistinctFrom(dx, dy, bestDx, bestDy, COARSE_NEIGHBOURHOOD)) {
                            runnerUp = max(runnerUp, bestScore)
                        }
                        bestScore = score
                        bestDx = dx
                        bestDy = dy
                    } else if (isDistinctFrom(dx, dy, bestDx, bestDy, COARSE_NEIGHBOURHOOD)) {
                        runnerUp = max(runnerUp, score)
                    }
                    dx += COARSE_STEP
                }
                dy += COARSE_STEP
            }

            // Fine pass: full template, every offset around the coarse winner,
            // so the answer is still exact to the pixel.
            if (bestScore > -1f) {
                val coarseDx = bestDx
                val coarseDy = bestDy
                bestScore = -1f
                for (fineDy in (coarseDy - COARSE_STEP)..(coarseDy + COARSE_STEP)) {
                    for (fineDx in (coarseDx - COARSE_STEP)..(coarseDx + COARSE_STEP)) {
                        val candidate = patch.offsetBy(fineDx, fineDy)
                        if (!isUsable(candidate, currentFrame, excluded)) continue
                        val score = correlate(reference, currentFrame, candidate)
                        if (score > bestScore) {
                            bestScore = score
                            bestDx = fineDx
                            bestDy = fineDy
                        }
                    }
                }
            }
        }

        if (bestScore < minimumCorrelation) {
            return MotionEstimate(
                deltaX = 0f,
                deltaY = 0f,
                confidence = bestScore.coerceAtLeast(0f),
                processingTimeMs = elapsedMs(),
                timestampNanos = currentFrame.timestampNanos,
            )
        }

        // How much better the winner is than the best unrelated candidate.
        // On a list of near-identical rows this collapses, and the estimate is
        // handed back as untrustworthy rather than confidently wrong.
        val margin = if (runnerUp <= 0f) 1f else ((bestScore - runnerUp) / bestScore).coerceIn(0f, 1f)
        val uniqueness = (margin / UNIQUENESS_SATURATION).coerceIn(0f, 1f)

        // A strong peak that is also clearly the only peak earns full
        // confidence. A strong peak with an equally strong rival is capped at
        // half — below any usable threshold — so an ambiguous match is reported
        // as ambiguous rather than merely slightly less certain. Background
        // correlates with background everywhere, so some rivalry is normal and
        // multiplying it straight through would understate every honest match.
        val confidence = (bestScore * (AMBIGUOUS_CEILING + (1f - AMBIGUOUS_CEILING) * uniqueness))
            .coerceIn(0f, 1f)

        return MotionEstimate(
            deltaX = bestDx.toFloat(),
            deltaY = bestDy.toFloat(),
            confidence = confidence,
            processingTimeMs = elapsedMs(),
            timestampNanos = currentFrame.timestampNanos,
            uniquenessMargin = margin,
        )
    }

    /** Two offsets far enough apart to be different answers, not one blurred peak. */
    private fun isUsable(
        candidate: FrameRect,
        frame: CapturedFrame,
        excluded: List<FrameRect>,
    ): Boolean {
        if (candidate.left < 0 || candidate.top < 0 ||
            candidate.right > frame.width || candidate.bottom > frame.height
        ) {
            return false
        }
        // Never match against Dot's own overlay.
        return excluded.none { it.intersects(candidate) }
    }

    private fun isDistinctFrom(
        dx: Int,
        dy: Int,
        bestDx: Int,
        bestDy: Int,
        neighbourhood: Int = PEAK_NEIGHBOURHOOD,
    ): Boolean =
        abs(dx - bestDx) > neighbourhood || abs(dy - bestDy) > neighbourhood

    /** The patch, mean-centred, or null when it is featureless. */
    private fun extract(frame: CapturedFrame, patch: FrameRect): Reference? {
        val count = patch.width * patch.height
        val values = FloatArray(count)
        var sum = 0f
        var index = 0
        for (y in patch.top until patch.bottom) {
            for (x in patch.left until patch.right) {
                val value = frame.at(x, y).toFloat()
                values[index++] = value
                sum += value
            }
        }
        val mean = sum / count
        var energy = 0f
        for (i in values.indices) {
            values[i] -= mean
            energy += values[i] * values[i]
        }
        if (energy < MIN_ENERGY) return null
        return Reference(values, sqrt(energy), patch.width, patch.height)
    }

    /** Zero-mean normalised cross-correlation of the reference against a window. */
    /**
     * Zero-mean normalised cross-correlation of [reference] against [window].
     *
     * [step] samples every nth pixel in both axes. The reference's own energy
     * is summed over the same sampled pixels rather than taken from its stored
     * norm, so a subsampled score stays a true ZNCC and remains comparable
     * with a full-resolution one.
     */
    private fun correlate(
        reference: Reference,
        frame: CapturedFrame,
        window: FrameRect,
        step: Int = 1,
    ): Float {
        var count = 0
        var sum = 0f
        var y = 0
        while (y < reference.height) {
            val frameY = window.top + y
            var x = 0
            while (x < reference.width) {
                sum += frame.at(window.left + x, frameY).toFloat()
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return 0f
        val mean = sum / count

        var dot = 0f
        var energy = 0f
        var referenceEnergy = 0f
        y = 0
        while (y < reference.height) {
            val frameY = window.top + y
            val row = y * reference.width
            var x = 0
            while (x < reference.width) {
                val value = frame.at(window.left + x, frameY).toFloat() - mean
                val referenceValue = reference.values[row + x]
                dot += referenceValue * value
                energy += value * value
                referenceEnergy += referenceValue * referenceValue
                x += step
            }
            y += step
        }
        if (energy < MIN_ENERGY || referenceEnergy < MIN_ENERGY) return 0f
        return dot / (sqrt(referenceEnergy) * sqrt(energy))
    }

    private fun varianceOf(frame: CapturedFrame, patch: FrameRect): Float {
        var sum = 0f
        var sumSquares = 0f
        var count = 0
        for (y in patch.top until patch.bottom) {
            for (x in patch.left until patch.right) {
                val value = frame.at(x, y).toFloat()
                sum += value
                sumSquares += value * value
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        return (sumSquares / count) - (mean * mean)
    }

    private class Reference(
        val values: FloatArray,
        val norm: Float,
        val width: Int,
        val height: Int,
    )

    companion object {
        /**
         * Vertical reach, in reduced-frame pixels. At quarter scale this covers
         * roughly 400 screen pixels between frames — more than a fast scroll
         * covers in 40ms, without widening the search enough to invite
         * confusion with a row two positions away.
         */
        const val DEFAULT_SEARCH_RADIUS_Y = 100

        /** Sideways movement is rare but not assumed to be zero. */
        const val DEFAULT_SEARCH_RADIUS_X = 12

        const val DEFAULT_MINIMUM_CORRELATION = 0.6f
        const val DEFAULT_MINIMUM_VARIANCE = 25f

        /** Offsets within this of the peak are the same peak. */
        private /** Offsets stepped through in the coarse pass, and the fine pass's radius. */
        /** At or below this radius the window is searched outright. */
        const val DIRECT_SEARCH_RADIUS = 24

        const val COARSE_STEP = 4

        /** Pixel stride used by the coarse pass's correlation. */
        const val COARSE_SAMPLE_STEP = 2

        /** Coarse offsets are 4 apart, so the peak's own shoulder is wider. */
        const val COARSE_NEIGHBOURHOOD = COARSE_STEP * 2

        const val PEAK_NEIGHBOURHOOD = 3

        /** Margin at which a match counts as fully unambiguous. */
        private const val UNIQUENESS_SATURATION = 0.15f

        /**
         * The most a match with a equally good rival can score. Deliberately
         * below every threshold that would act on it.
         */
        private const val AMBIGUOUS_CEILING = 0.5f

        private const val MIN_PATCH = 4
        private const val MIN_ENERGY = 1e-3f
    }
}
