package com.aaravsekhri.dot.guide.motion

import com.aaravsekhri.dot.guide.model.Bounds

/**
 * When the outline may be on screen, and when it must come off.
 *
 * Pulled out of the service because these are the decisions that determine
 * whether a person is ever pointed at the wrong thing, and they should be
 * readable and testable on their own rather than inferred from a device.
 *
 * Two rules, both asymmetric on purpose.
 *
 * A target sliding off the edge stops being worth pointing at before it
 * disappears, but has to come back further than it went before the outline
 * returns. Equal thresholds would flicker between outline and cue for anyone
 * holding a list still near the boundary.
 *
 * And tracking that has gone quiet while content is known to be moving is
 * treated as failure quickly. An outline left behind during a scroll does not
 * stay neutral — it comes to rest over whichever row happens to arrive under
 * it, which is a confident, specific, wrong instruction.
 */
class OutlineVisibilityPolicy(
    private val hideFraction: Float = DEFAULT_HIDE_FRACTION,
    private val showFraction: Float = DEFAULT_SHOW_FRACTION,
    private val lostHideNanos: Long = DEFAULT_LOST_HIDE_NANOS,
) {

    /**
     * How much of [region] a person can actually see, from 0 to 1.
     *
     * [occluders] are Dot's own positional layers — the instruction card above
     * all. A card Dot drew hides the row underneath exactly as thoroughly as
     * anything else would; treating Dot's own furniture as transparent is why
     * an outline could sit around a control nobody could see or reach.
     */
    fun visibleFraction(
        region: Bounds,
        viewport: Bounds,
        occluders: List<Bounds> = emptyList(),
    ): Float {
        if (region.isEmpty) return 0f
        val total = region.width.toFloat() * region.height
        val onScreen = intersectionArea(region, viewport)
        if (onScreen == 0) return 0f

        // Occluders are subtracted from the on-screen part. They are few and
        // roughly disjoint in practice — a card at one edge, a cue in the
        // middle — so the union is approximated by summing, clamped so an
        // overlap between two of them can never push the answer negative.
        val hidden = occluders.sumOf { occluder ->
            intersectionArea(region, occluder.intersectedWith(viewport))
        }.coerceAtMost(onScreen)

        return (onScreen - hidden) / total
    }

    /**
     * Whether an occluder covers the part of the target that has to be tapped.
     *
     * A row can be three-quarters visible and still useless to point at if the
     * quarter behind the card is its title and its tap target. Fraction alone
     * cannot express that, so the middle of the row is tested outright.
     */
    fun actionableCentreCovered(region: Bounds, occluders: List<Bounds>): Boolean {
        if (region.isEmpty) return false
        val centre = Bounds(
            left = region.left + (region.width * (1f - ACTIONABLE_FRACTION) / 2f).toInt(),
            top = region.top + (region.height * (1f - ACTIONABLE_FRACTION) / 2f).toInt(),
            right = region.right - (region.width * (1f - ACTIONABLE_FRACTION) / 2f).toInt(),
            bottom = region.bottom - (region.height * (1f - ACTIONABLE_FRACTION) / 2f).toInt(),
        )
        return occluders.any { it.intersects(centre) }
    }

    private fun intersectionArea(a: Bounds, b: Bounds): Int {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return width * height
    }

    private fun Bounds.intersectedWith(other: Bounds) = Bounds(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = minOf(right, other.right),
        bottom = minOf(bottom, other.bottom),
    )

    /**
     * Whether the outline belongs on screen at this position.
     *
     * @param currentlyHidden raises the bar, so returning takes more than
     *   staying did.
     */
    fun isUsefullyVisible(
        region: Bounds,
        viewport: Bounds,
        currentlyHidden: Boolean,
        occluders: List<Bounds> = emptyList(),
    ): Boolean {
        val threshold = if (currentlyHidden) showFraction else hideFraction
        return visibleFraction(region, viewport, occluders) >= threshold
    }

    /**
     * The whole judgement for one frame, in one place.
     *
     * Order matters and is deliberate: something behind the card is reported as
     * occluded rather than as merely too small a fraction, because the two have
     * different remedies — one waits for the card to move, the other waits for
     * the person to scroll.
     */
    fun alignmentFor(
        region: Bounds,
        viewport: Bounds,
        occluders: List<Bounds>,
        currentlyHidden: Boolean,
        predicted: AlignmentState,
    ): AlignmentState {
        if (!predicted.drawable) return predicted
        if (visibleFraction(region, viewport) <= 0f) return AlignmentState.OFFSCREEN
        if (actionableCentreCovered(region, occluders)) return AlignmentState.OCCLUDED
        if (!isUsefullyVisible(region, viewport, currentlyHidden, occluders)) {
            val onScreen = visibleFraction(region, viewport)
            return if (onScreen >= showFraction) AlignmentState.OCCLUDED else AlignmentState.OFFSCREEN
        }
        return predicted
    }

    /**
     * Whether tracking has been unusable long enough to stop drawing.
     *
     * Only counts while content is believed to be moving: a still screen with
     * no signals is not a failure, it is a screen nobody is touching.
     */
    fun shouldHideForLostTracking(
        unusableSinceNanos: Long,
        nowNanos: Long,
        motionExpected: Boolean,
    ): Boolean {
        if (!motionExpected || unusableSinceNanos == 0L) return false
        return nowNanos - unusableSinceNanos >= lostHideNanos
    }

    /** Which way to point once the outline is gone. */
    fun cueDirectionFor(region: Bounds, viewport: Bounds): CueDirection =
        if (region.centerY < viewport.centerY) CueDirection.Up else CueDirection.Down

    companion object {
        const val DEFAULT_HIDE_FRACTION = 0.25f
        const val DEFAULT_SHOW_FRACTION = 0.45f

        /** Comfortably inside the 100 ms the outline is allowed to linger. */
        const val DEFAULT_LOST_HIDE_NANOS = 80_000_000L

        /** The middle of a row: its title, and the part a finger lands on. */
        const val ACTIONABLE_FRACTION = 0.6f
    }
}

/** Kept free of the guide model so the policy stays plain. */
enum class CueDirection { Up, Down }
