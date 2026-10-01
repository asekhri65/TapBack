package com.aaravsekhri.dot.experiment

import com.aaravsekhri.dot.guide.model.Bounds

/** Where one reading of the target's position came from. */
enum class GeometrySource {
    /** A full walk of the accessibility tree. Authoritative, and slow. */
    SemanticScan,

    /** [android.view.accessibility.AccessibilityNodeInfo.refresh] on a node we
     *  already hold. One round trip, no traversal. */
    TargetedRefresh,

    /** The held node stopped answering and the target was found again by identity. */
    Reacquisition,
}

/**
 * One completed attempt to learn where the target is.
 *
 * Recorded for every attempt, including the ones that came back identical, so
 * that "positions per second" can be counted honestly: a reading that repeats
 * the previous coordinates is a successful call and a useless sample, and
 * conflating the two is how a tracker gets reported as fast when it is not.
 *
 * Numbers only. Nothing here describes what the control says.
 */
data class GeometrySample(
    /** Monotonic nanoseconds, taken the instant before the read began. */
    val startedAtNanos: Long,

    /** Monotonic nanoseconds, taken the instant the read returned. */
    val finishedAtNanos: Long,

    /** The target's rectangle as this read reported it. */
    val bounds: Bounds,

    /** The target's full size, learned from an unclipped reading. */
    val canonicalWidthPx: Int,
    val canonicalHeightPx: Int,

    val source: GeometrySource,

    /** True when the page was still moving when this read began. */
    val scrollActive: Boolean,

    /** True when [bounds] differs from the previous accepted read. */
    val changed: Boolean,

    /** True when the retained node still carried the label it was acquired with. */
    val identityHeld: Boolean,
) {
    val durationNanos: Long get() = finishedAtNanos - startedAtNanos
}

/** A run's worth of samples, reduced to the numbers the gate is decided on. */
data class RefreshBenchmark(
    val gesture: String,
    val totalReads: Int,
    val changedReads: Int,
    val movingChangedReads: Int,
    val movingWallNanos: Long,
    val p50AgeNanos: Long,
    val p95AgeNanos: Long,
    val maxGapNanos: Long,
    val identityFailures: Int,
    val canonicalWidths: Set<Int>,
    val canonicalHeights: Set<Int>,
) {
    /** Genuinely new positions per second, counted only while the page moved. */
    val movingUpdatesPerSecond: Double
        get() = if (movingWallNanos <= 0L) 0.0
        else movingChangedReads * 1_000_000_000.0 / movingWallNanos

    companion object {

        /**
         * Reduces raw samples.
         *
         * Only samples taken while the page was moving count towards the rate:
         * a still page produces identical reads for ever, and averaging those
         * in would let a tracker that never updates during a gesture report a
         * respectable number.
         */
        fun of(gesture: String, samples: List<GeometrySample>): RefreshBenchmark {
            val moving = samples.filter { it.scrollActive }
            val movingChanged = moving.filter { it.changed }
            val ages = movingChanged.map { it.durationNanos }.sorted()

            // The longest a caller would have gone without a new position.
            var maxGap = 0L
            var previous: Long? = null
            for (sample in moving) {
                if (!sample.changed) continue
                previous?.let { maxGap = maxOf(maxGap, sample.finishedAtNanos - it) }
                previous = sample.finishedAtNanos
            }

            val span = if (moving.size < 2) 0L
            else moving.last().finishedAtNanos - moving.first().startedAtNanos

            return RefreshBenchmark(
                gesture = gesture,
                totalReads = samples.size,
                changedReads = samples.count { it.changed },
                movingChangedReads = movingChanged.size,
                movingWallNanos = span,
                p50AgeNanos = ages.percentile(50),
                p95AgeNanos = ages.percentile(95),
                maxGapNanos = maxGap,
                identityFailures = samples.count { !it.identityHeld },
                canonicalWidths = samples.map { it.canonicalWidthPx }.toSet(),
                canonicalHeights = samples.map { it.canonicalHeightPx }.toSet(),
            )
        }

        private fun List<Long>.percentile(p: Int): Long {
            if (isEmpty()) return 0L
            // Nearest-rank, so p95 of 20 samples is the 19th rather than an
            // interpolation between two readings that never happened.
            val rank = kotlin.math.ceil(p / 100.0 * size).toInt().coerceIn(1, size)
            return this[rank - 1]
        }
    }
}
