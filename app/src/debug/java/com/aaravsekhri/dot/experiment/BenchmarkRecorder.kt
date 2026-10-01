package com.aaravsekhri.dot.experiment

import android.util.Log
import com.aaravsekhri.dot.guide.model.Bounds
import java.util.Collections

/**
 * Writes down what the tracker actually managed, for the harness to grade.
 *
 * Debug builds only. Every line is numbers: timestamps, rectangles, durations.
 * What the control says never appears here, in this build or any other.
 *
 * Samples are streamed as they happen rather than summarised at the end,
 * because the question the benchmark exists to answer is about the middle of a
 * gesture, and a summary cannot be interrogated about that.
 */
class BenchmarkRecorder : TargetRefreshTracker.Listener {

    private val samples = Collections.synchronizedList(ArrayList<GeometrySample>(4096))

    override fun onSample(sample: GeometrySample) {
        samples.add(sample)
        Log.d(
            SAMPLE_TAG,
            "${sample.finishedAtNanos} ${sample.startedAtNanos} " +
                "${sample.bounds.left} ${sample.bounds.top} " +
                "${sample.bounds.right} ${sample.bounds.bottom} " +
                "${sample.canonicalWidthPx} ${sample.canonicalHeightPx} " +
                "${sample.source} ${sample.durationNanos} " +
                "${sample.scrollActive} ${sample.changed} ${sample.identityHeld}",
        )
    }

    override fun onGeometry(
        bounds: Bounds,
        atNanos: Long,
        source: GeometrySource,
        usable: Boolean,
    ) = Unit

    override fun onLost() {
        Log.d(SAMPLE_TAG, "${System.nanoTime()} LOST")
    }

    fun reset() = samples.clear()

    /** Reduces everything collected since the last reset and prints the verdict. */
    fun dump(gesture: String) {
        val snapshot = synchronized(samples) { samples.toList() }
        val benchmark = RefreshBenchmark.of(gesture, snapshot)
        Log.d(
            RESULT_TAG,
            "gesture=${benchmark.gesture} " +
                "reads=${benchmark.totalReads} changed=${benchmark.changedReads} " +
                "movingChanged=${benchmark.movingChangedReads} " +
                "movingMs=${benchmark.movingWallNanos / 1_000_000} " +
                "updatesPerSecond=${"%.1f".format(benchmark.movingUpdatesPerSecond)} " +
                "p50AgeMs=${"%.2f".format(benchmark.p50AgeNanos / 1e6)} " +
                "p95AgeMs=${"%.2f".format(benchmark.p95AgeNanos / 1e6)} " +
                "maxGapMs=${"%.2f".format(benchmark.maxGapNanos / 1e6)} " +
                "identityFailures=${benchmark.identityFailures} " +
                "canonicalWidths=${benchmark.canonicalWidths} " +
                "canonicalHeights=${benchmark.canonicalHeights}",
        )
    }

    companion object {
        const val SAMPLE_TAG = "DotProbe"
        const val RESULT_TAG = "DotProbeResult"
    }
}
