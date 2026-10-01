package com.aaravsekhri.dot.experiment

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import com.aaravsekhri.dot.guide.model.Bounds

/**
 * Asks one control, repeatedly, where it is.
 *
 * The guide already knows which control the person must touch; that question
 * is settled semantically and is not reopened here. All this does is keep the
 * answer to "where is it now" current while the page moves, by calling
 * [AccessibilityNodeInfo.refresh] on the node it was handed rather than walking
 * the tree again. A traversal of a Settings page costs tens of milliseconds and
 * allocates hundreds of objects; a refresh is one round trip for one node.
 *
 * Three rules hold the whole thing together:
 *
 * **One outstanding operation, ever.** Work runs on a single handler thread and
 * the next read is posted only once the previous one has returned. There is no
 * queue to fall behind on, so a slow page produces fewer readings rather than a
 * growing backlog of stale ones.
 *
 * **Identity is checked before the position is offered.** A list recycles its
 * rows: the node that was the target can quietly become a different row with
 * the same view underneath it, and a refresh of it will happily succeed. So the
 * label the target was acquired with is re-read and compared every time, and a
 * disagreement means the target is lost, not moved.
 *
 * **Nothing is invented.** No interpolation, no prediction, no smoothing. Every
 * position given out was read from the control itself.
 *
 * The label is held in memory for comparison and is never logged or stored.
 */
class TargetRefreshTracker(
    private val listener: Listener,
    /**
     * Shortest gap between two reads while the page is moving.
     *
     * Zero reads back to back, which is what the benchmark wants: it measures
     * what the platform can actually supply. In use it is set to a display
     * frame, because a position nobody will draw is a binder call into the app
     * being guided for nothing, and that app is using its main thread to
     * scroll.
     */
    private val minIntervalNanos: Long = 0L,
    private val clock: () -> Long = System::nanoTime,
) {

    interface Listener {
        /** Every completed read, whether or not anything moved. Probe thread. */
        fun onSample(sample: GeometrySample)

        /**
         * A fresh position that passed identity. Probe thread.
         *
         * @param usable false when the control reported a rectangle with its
         *   edges crossed, which is how a node laid out entirely outside its
         *   container comes back. It is still the target, and it is still
         *   itself — it is simply not anywhere that can be pointed at, and the
         *   caller needs to hear that rather than keep the last good position.
         */
        fun onGeometry(bounds: Bounds, atNanos: Long, source: GeometrySource, usable: Boolean)

        /** The control stopped answering, or stopped being itself. Probe thread. */
        fun onLost()
    }

    /** What the target was when it was acquired, for checking it still is. */
    private class Held(
        val root: AccessibilityNodeInfo,
        /** The node carrying the wording, when it is not the root itself. */
        val label: AccessibilityNodeInfo?,
        val expectedLabel: String?,
        val viewId: String?,
        val className: String?,
    )

    private val thread = HandlerThread("dot-follow", Process.THREAD_PRIORITY_DISPLAY)
    private val handler: Handler

    /** Probe-thread only. */
    private var held: Held? = null
    private var lastBounds: Bounds? = null
    private var canonical: Bounds? = null
    private var running = false

    /** When the label was last re-read, and where the row was then. */
    private var lastVerifiedAtNanos: Long = 0L
    private var lastVerifiedTop: Int = Int.MIN_VALUE

    /**
     * Written from the service thread, read from the probe thread.
     *
     * Zero means "no scroll has ever been seen", and is checked for rather
     * than being some very negative number: subtracting a sentinel from a
     * timestamp overflows, and a negative result compares as "recent", which
     * made the follower treat a motionless screen as a gesture in progress and
     * spin on it.
     */
    @Volatile
    private var lastScrollAtNanos: Long = 0L

    @Volatile
    private var stopped = false

    init {
        thread.start()
        handler = Handler(thread.looper)
    }

    /**
     * Begins following [root].
     *
     * @param label the node inside [root] that carries the wording, when the
     *   row's clickable container carries none of its own — which on a settings
     *   list is the usual case.
     * @param canonicalBounds the target's full size, from a reading that showed
     *   all of it. Never learned here: a refresh during a scroll routinely
     *   returns a clipped rectangle, and letting that set the size is exactly
     *   how an outline shrinks as its row leaves the screen.
     */
    fun acquire(
        root: AccessibilityNodeInfo,
        label: AccessibilityNodeInfo?,
        expectedLabel: String?,
        canonicalBounds: Bounds?,
    ) {
        if (stopped) return
        handler.post {
            held = Held(
                root = root,
                label = label,
                expectedLabel = expectedLabel,
                viewId = safely { root.viewIdResourceName },
                className = safely { root.className?.toString() },
            )
            canonical = canonicalBounds
            lastBounds = null
            lastVerifiedAtNanos = 0L
            lastVerifiedTop = Int.MIN_VALUE
            if (!running) {
                running = true
                handler.post(cycle)
            }
        }
    }

    /** Stops following, and lets go of every node. */
    fun release() {
        handler.post {
            running = false
            held = null
            lastBounds = null
            canonical = null
        }
    }

    /**
     * The page moved. Told from the accessibility callback, on its thread.
     *
     * Also wakes the loop. Between gestures the loop sleeps on a slow
     * heartbeat, and leaving it to notice the gesture on its own schedule cost
     * the first quarter-second of every scroll — measured at sixteen frames
     * during which the page moved and the follower was asleep. The first
     * scroll event is the earliest anything can know a gesture started, so it
     * is what starts the reading.
     */
    fun onScrollSignal(atNanos: Long) {
        lastScrollAtNanos = atNanos
        if (wakePending) return
        wakePending = true
        handler.post(wake)
    }

    /**
     * Cuts a heartbeat short, without ever leaving two loops running.
     *
     * This executes on the probe thread, so [cycle] is provably not executing
     * while it runs: removing the sleeping one and posting a fresh one leaves
     * exactly one, which is the invariant the whole design rests on.
     */
    private val wake = Runnable {
        wakePending = false
        if (!running) return@Runnable
        handler.removeCallbacks(cycle)
        handler.post(cycle)
    }

    @Volatile
    private var wakePending = false

    /** Ends the thread. The tracker cannot be used afterwards. */
    fun shutdown() {
        stopped = true
        release()
        handler.post { thread.quitSafely() }
    }

    /** True while the page counts as moving, so reads run back to back. */
    /**
     * True while the loop should read back to back.
     *
     * The benchmark drives this directly. In the shipped app nothing does:
     * following a scroll with an outline was measured, found to produce a
     * rectangle always a little behind its row, and abandoned. What remains
     * here is the measurement, kept because the number it produced is the
     * reason the product went the way it did.
     */
    private fun scrollActive(nowNanos: Long): Boolean = true

    private val rect = Rect()

    /**
     * One read, then the next.
     *
     * Re-posted from its own tail, which is what makes "at most one
     * outstanding" a property of the shape of the code rather than a flag
     * somebody has to remember to clear.
     */
    private val cycle = object : Runnable {
        override fun run() {
            if (!running) return
            val current = held ?: run { running = false; return }

            val startedAt = clock()
            val active = scrollActive(startedAt)

            val alive = safely { current.root.refresh() } ?: false
            if (!alive) {
                val finishedAt = clock()
                emit(current, startedAt, finishedAt, Bounds.EMPTY, active, changed = false, identityHeld = false)
                running = false
                held = null
                listener.onLost()
                return
            }

            safely { current.root.getBoundsInScreen(rect) }
            val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)

            // The label is re-read on its own node, because refreshing the row
            // alone proves the view is still there, not that it still holds the
            // control the guide is pointing at — a recycled row passes that
            // test while showing something else entirely.
            //
            // It is a second round trip, so it is not made on every reading.
            // Recycling is exactly what makes a row jump: the view is handed to
            // an item at the opposite end of the list, and its rectangle moves
            // further in one reading than the row is tall. So the check is made
            // whenever the row moves further than half its own height, and on a
            // timer besides, and skipped for the ordinary case of a row that
            // has crept a few pixels.
            val jumped = lastVerifiedTop == Int.MIN_VALUE ||
                kotlin.math.abs(bounds.top - lastVerifiedTop) > (bounds.height / 2).coerceAtLeast(1)
            val due = startedAt - lastVerifiedAtNanos >= IDENTITY_RECHECK_NANOS
            val identityHeld = if (jumped || due) {
                checkIdentity(current).also {
                    lastVerifiedAtNanos = startedAt
                    lastVerifiedTop = bounds.top
                }
            } else {
                lastVerifiedTop = bounds.top
                true
            }
            val finishedAt = clock()

            val usable = !bounds.isInverted && !bounds.isEmpty
            val changed = identityHeld && bounds != lastBounds
            // The benchmark counts a genuinely new *position*, which a
            // rectangle nobody could draw is not.
            emit(current, startedAt, finishedAt, bounds, active, changed && usable, identityHeld)

            if (!identityHeld) {
                running = false
                held = null
                listener.onLost()
                return
            }

            if (changed) {
                lastBounds = bounds
                listener.onGeometry(bounds, finishedAt, GeometrySource.TargetedRefresh, usable)
            }

            // Back to back while the page moves; a slow heartbeat when it does
            // not, because a still page cannot have moved the target and
            // spinning on it would cost battery for nothing.
            if (!scrollActive(finishedAt)) {
                handler.postDelayed(this, IDLE_INTERVAL_MS)
                return
            }
            val wait = minIntervalNanos - (finishedAt - startedAt)
            if (wait > 0) handler.postDelayed(this, wait / 1_000_000L) else handler.post(this)
        }
    }

    private fun checkIdentity(current: Held): Boolean {
        val expected = current.expectedLabel ?: return true
        val labelNode = current.label
        if (labelNode != null) {
            val refreshed = safely { labelNode.refresh() } ?: false
            if (!refreshed) return false
            val text = safely { labelNode.text?.toString() ?: labelNode.contentDescription?.toString() }
            return text != null && text.equals(expected, ignoreCase = true)
        }
        val text = safely {
            current.root.text?.toString() ?: current.root.contentDescription?.toString()
        }
        return text != null && text.equals(expected, ignoreCase = true)
    }

    private fun emit(
        current: Held,
        startedAt: Long,
        finishedAt: Long,
        bounds: Bounds,
        active: Boolean,
        changed: Boolean,
        identityHeld: Boolean,
    ) {
        val size = canonical
        listener.onSample(
            GeometrySample(
                startedAtNanos = startedAt,
                finishedAtNanos = finishedAt,
                bounds = bounds,
                canonicalWidthPx = size?.width ?: 0,
                canonicalHeightPx = size?.height ?: 0,
                source = GeometrySource.TargetedRefresh,
                scrollActive = active,
                changed = changed,
                identityHeld = identityHeld,
            ),
        )
    }

    /**
     * A node that has gone away throws rather than returning null, and it does
     * so from inside a loop that must keep running.
     */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** How long after the last scroll event the page still counts as moving. */
        const val ACTIVE_WINDOW_NANOS = 250_000_000L

        /** Heartbeat while the page is still. */
        const val IDLE_INTERVAL_MS = 250L

        /**
         * However smoothly a row is moving, its label is re-read this often.
         *
         * A backstop for any way a view could be reused without its rectangle
         * jumping. Four times a second is far more often than a list recycles
         * and costs one extra round trip in every twenty.
         */
        const val IDENTITY_RECHECK_NANOS = 250_000_000L
    }
}

/**
 * True when accessibility reported a rectangle with its edges crossed.
 *
 * That is what a node laid out entirely outside its parent's visible area
 * comes back as, and it is a statement that the control is not on screen — not
 * a rectangle to draw or to measure a size from.
 */
val Bounds.isInverted: Boolean get() = right < left || bottom < top
