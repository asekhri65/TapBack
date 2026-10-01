package com.aaravsekhri.dot.guide.tracking

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds

/**
 * Where the target is, and where it is being drawn.
 *
 * Those are two different things and conflating them is what made the outline
 * trail the content. [authoritativeRegion] comes from a full read of the
 * accessibility tree, which is accurate but arrives late. [renderedRegion] is
 * what is actually on screen, moved by scroll deltas as they arrive and
 * corrected by the authoritative region when it catches up.
 */
data class TrackedTargetGeometry(
    val identity: TargetIdentity,
    val authoritativeRegion: Bounds,
    val renderedRegion: Bounds,
    val pendingScrollDeltaX: Float,
    val pendingScrollDeltaY: Float,
    /** Total scroll applied since this target was adopted, for reconciliation. */
    val appliedScrollX: Float,
    val appliedScrollY: Float,
    /** Increments on every applied delta, so stale scans can be recognised. */
    val generation: Long,
)

/** What a reconciliation did, for tracing and for tests. */
enum class ReconcileOutcome {
    /** The scan agreed with what is drawn; nothing moved. */
    Agreed,

    /** Drift was corrected in place, without replaying the entrance. */
    Corrected,

    /** The scan was older than motion already applied, and was discarded. */
    RejectedAsStale,

    /** A different target: the caller should clear and pop the new one in. */
    DifferentTarget,
}

/**
 * Moves the drawn outline with the content, and reconciles it with the truth.
 *
 * Scroll events carry the exact distance the content moved. Applying the
 * inverse of that to the drawn rectangle moves the outline with the page
 * immediately, without waiting for a tree scan — which is the difference
 * between an outline attached to a setting and one chasing it.
 *
 * Deltas accumulate and are applied once per display frame by the caller, so a
 * burst of scroll events is one movement rather than several.
 *
 * Plain Kotlin, so the arithmetic that matters here is unit tested.
 */
class RenderedTargetTracker {

    var geometry: TrackedTargetGeometry? = null
        private set

    /** Total scroll applied, used to age scans. Monotonic within a target. */
    private var scrollGeneration: Long = 0L

    /** Begins following a newly settled target. */
    fun adopt(identity: TargetIdentity, region: Bounds) {
        scrollGeneration++
        geometry = TrackedTargetGeometry(
            identity = identity,
            authoritativeRegion = region,
            renderedRegion = region,
            pendingScrollDeltaX = 0f,
            pendingScrollDeltaY = 0f,
            appliedScrollX = 0f,
            appliedScrollY = 0f,
            generation = scrollGeneration,
        )
    }

    fun forget() {
        geometry = null
    }

    /**
     * Where things stood when a scan began.
     *
     * Handed back with the scan's result so motion that happened while it was
     * in flight can be added to its answer, instead of an old position
     * dragging the outline backwards.
     */
    data class ScrollMark(
        val generation: Long,
        val appliedScrollX: Float,
        val appliedScrollY: Float,
    )

    /** Taken at the moment a semantic scan starts. */
    fun markScanStart(): ScrollMark {
        val current = geometry
        return ScrollMark(
            generation = current?.generation ?: scrollGeneration,
            appliedScrollX = current?.appliedScrollX ?: 0f,
            appliedScrollY = current?.appliedScrollY ?: 0f,
        )
    }

    /**
     * A scroll happened. The distance is banked, not drawn: drawing happens
     * once on the next frame however many events arrived in between.
     *
     * @return true when there is something to draw.
     */
    fun onScrollDelta(deltaX: Int, deltaY: Int): Boolean =
        onScrollDelta(deltaX.toFloat(), deltaY.toFloat())

    fun onScrollDelta(deltaX: Float, deltaY: Float): Boolean {
        if (deltaX == 0f && deltaY == 0f) return false
        val current = geometry ?: return false
        geometry = current.copy(
            pendingScrollDeltaX = current.pendingScrollDeltaX + deltaX,
            pendingScrollDeltaY = current.pendingScrollDeltaY + deltaY,
        )
        return true
    }

    /**
     * Banks movement expressed as the *content's* travel across the screen.
     *
     * Every motion source outside the platform's own scroll events speaks this
     * way — a positive dy means content came down the screen — so the one
     * inversion happens here rather than being re-derived, and mis-signed, at
     * each call site.
     */
    fun onContentMotion(dx: Float, dy: Float): Boolean = onScrollDelta(-dx, -dy)

    /**
     * Applies everything banked since the last frame.
     *
     * The content moved by the delta, so the target moved against it: the
     * inverse goes onto the drawn rectangle. The sign was confirmed on the
     * device rather than assumed — a positive `scrollDeltaY` accompanies a
     * rising `scrollY`, which carries content upward on screen.
     *
     * @return the rectangle to draw, or null when nothing changed.
     */
    fun applyPendingDeltas(): Bounds? {
        val current = geometry ?: return null
        val deltaX = current.pendingScrollDeltaX
        val deltaY = current.pendingScrollDeltaY
        if (deltaX == 0f && deltaY == 0f) return null

        // Rounded to whole pixels for drawing, but the fraction is kept and
        // carried into the next frame. Vision reports sub-pixel movement, and
        // truncating it every frame would lose several pixels over a long
        // scroll — drift that looks exactly like the trailing this replaced.
        val wholeX = kotlin.math.round(deltaX)
        val wholeY = kotlin.math.round(deltaY)
        if (wholeX == 0f && wholeY == 0f) return null

        scrollGeneration++
        val moved = current.renderedRegion.offsetBy(-wholeX.toInt(), -wholeY.toInt())
        geometry = current.copy(
            renderedRegion = moved,
            pendingScrollDeltaX = deltaX - wholeX,
            pendingScrollDeltaY = deltaY - wholeY,
            appliedScrollX = current.appliedScrollX + wholeX,
            appliedScrollY = current.appliedScrollY + wholeY,
            generation = scrollGeneration,
        )
        return moved
    }

    /**
     * A semantic scan has come back. Correct the drawn rectangle by it — unless
     * it describes a screen that has already moved on.
     *
     * @param mark what [markScanStart] returned when the scan began.
     */
    fun reconcile(
        identity: TargetIdentity,
        authoritative: Bounds,
        mark: ScrollMark,
        tolerancePx: Int,
    ): ReconcileOutcome {
        val current = geometry ?: return ReconcileOutcome.DifferentTarget
        if (current.identity != identity) return ReconcileOutcome.DifferentTarget

        // A whole pixel or more still banked has not been drawn yet. Applying
        // the scan now would be undone on the next frame, so the scan waits.
        // A sub-pixel remainder is the rounding carry, not pending movement,
        // and must not hold reconciliation off indefinitely.
        if (kotlin.math.abs(current.pendingScrollDeltaX) >= 1f ||
            kotlin.math.abs(current.pendingScrollDeltaY) >= 1f
        ) {
            return ReconcileOutcome.RejectedAsStale
        }

        // Scroll applied while the scan was in flight. The scan described the
        // world before that, so its answer is brought up to date.
        val missedX = current.appliedScrollX - mark.appliedScrollX
        val missedY = current.appliedScrollY - mark.appliedScrollY
        val corrected = authoritative.offsetBy(-missedX.toInt(), -missedY.toInt())

        if (corrected.isEffectivelySameAs(current.renderedRegion, tolerancePx)) {
            geometry = current.copy(authoritativeRegion = corrected)
            return ReconcileOutcome.Agreed
        }

        // Drift, or the region genuinely changed size because the control did.
        // Snap: this is a correction, not a movement worth animating.
        geometry = current.copy(
            authoritativeRegion = corrected,
            renderedRegion = corrected,
        )
        return ReconcileOutcome.Corrected
    }

    /**
     * Re-points the tracker at the same target after the page was rebuilt.
     *
     * Keeps the drawn rectangle exactly where it is and adopts the new
     * identity, so a font-size change that replaces every node — and the window
     * with them — does not look like arriving somewhere new.
     */
    fun rebind(identity: TargetIdentity, region: Bounds) {
        val current = geometry
        if (current == null) {
            adopt(identity, region)
            return
        }
        scrollGeneration++
        geometry = current.copy(
            identity = identity,
            authoritativeRegion = region,
            renderedRegion = region,
            pendingScrollDeltaX = 0f,
            pendingScrollDeltaY = 0f,
            generation = scrollGeneration,
        )
    }
}

/** Moves a rectangle without changing its size. */
internal fun Bounds.offsetBy(dx: Int, dy: Int) =
    Bounds(left + dx, top + dy, right + dx, bottom + dy)
