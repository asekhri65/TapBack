package com.aaravsekhri.dot.guide.motion

/**
 * The one clock every motion timestamp is taken from.
 *
 * There is a reason this exists rather than each site calling whatever looked
 * monotonic. Android has two monotonic clocks: `System.nanoTime` runs on
 * CLOCK_MONOTONIC and `SystemClock.elapsedRealtimeNanos` on CLOCK_BOOTTIME,
 * which also counts deep sleep. Both are perfectly good clocks and they are
 * not the same clock.
 *
 * Scroll events were being stamped with one and compared against Choreographer
 * frame times, which come from the other. On a device that had been up for a
 * while the difference was large enough that every signal looked ancient, so
 * the predictor reported STALE on every frame and the outline was hidden
 * through the whole of a scroll — a bug that looks exactly like a tracking
 * failure and is nothing of the kind.
 *
 * Choreographer decides the clock, because its frame time is the one value
 * here that cannot be chosen.
 */
object MotionClock {
    /** Matches `Choreographer.FrameCallback`'s frameTimeNanos. */
    fun nowNanos(): Long = System.nanoTime()
}
