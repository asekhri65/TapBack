package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.experiment.GeometrySample
import com.aaravsekhri.dot.experiment.GeometrySource
import com.aaravsekhri.dot.experiment.RefreshBenchmark
import com.aaravsekhri.dot.guide.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic the tracking decision was made on.
 *
 * Worth testing in its own right: a benchmark that counts the wrong thing is
 * worse than no benchmark, because it produces a number everyone then believes.
 * The specific way this could flatter the tracker is by counting reads that
 * returned the same position as though they were new ones, which would let a
 * tracker that never updates during a gesture report hundreds of updates a
 * second.
 */
class RefreshBenchmarkTest {

    private val ms = 1_000_000L

    private fun sample(
        at: Long,
        top: Int,
        changed: Boolean,
        moving: Boolean = true,
        duration: Long = 2 * ms,
        identity: Boolean = true,
    ) = GeometrySample(
        startedAtNanos = at - duration,
        finishedAtNanos = at,
        bounds = Bounds(0, top, 1080, top + 200),
        canonicalWidthPx = 1080,
        canonicalHeightPx = 200,
        source = GeometrySource.TargetedRefresh,
        scrollActive = moving,
        changed = changed,
        identityHeld = identity,
    )

    @Test
    fun `a read that repeats the previous position is not a new position`() {
        val samples = (1..100).map { sample(at = it * ms, top = 500, changed = false) }

        val result = RefreshBenchmark.of("still", samples)

        assertEquals(100, result.totalReads)
        assertEquals(0, result.changedReads)
        assertEquals(0.0, result.movingUpdatesPerSecond, 0.001)
    }

    @Test
    fun `only readings taken while the page moved count towards the rate`() {
        // One second of movement at 50 a second, then a second of stillness.
        val moving = (1..50).map { sample(at = it * 20 * ms, top = 500 - it, changed = true) }
        val still = (51..100).map {
            sample(at = it * 20 * ms, top = 450, changed = false, moving = false)
        }

        val result = RefreshBenchmark.of("mixed", moving + still)

        assertEquals(50, result.movingChangedReads)
        assertTrue(
            "about fifty a second, not twenty-five",
            result.movingUpdatesPerSecond in 45.0..55.0,
        )
    }

    @Test
    fun `the reported age is the slowest of the readings, not the average`() {
        val quick = (1..18).map { sample(at = it * 20 * ms, top = 500 - it, changed = true) }
        val slow = (19..20).map {
            sample(at = it * 20 * ms, top = 500 - it, changed = true, duration = 30 * ms)
        }

        val result = RefreshBenchmark.of("mixed", quick + slow)

        assertEquals(2 * ms, result.p50AgeNanos)
        assertEquals("p95 must show the outlier", 30 * ms, result.p95AgeNanos)
    }

    @Test
    fun `the longest a caller went without a position is reported`() {
        val samples = listOf(
            sample(at = 0, top = 500, changed = true),
            sample(at = 20 * ms, top = 499, changed = true),
            sample(at = 200 * ms, top = 498, changed = true),
        )

        assertEquals(180 * ms, RefreshBenchmark.of("gappy", samples).maxGapNanos)
    }

    @Test
    fun `a lapse of identity is counted and never hidden`() {
        val samples = listOf(
            sample(at = 0, top = 500, changed = true),
            sample(at = 20 * ms, top = 400, changed = true, identity = false),
        )

        assertEquals(1, RefreshBenchmark.of("recycled", samples).identityFailures)
    }
}
