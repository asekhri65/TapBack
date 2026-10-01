package com.aaravsekhri.dot.guide.positional

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry
import com.aaravsekhri.dot.guide.motion.OutlineVisibilityPolicy
import com.aaravsekhri.dot.guide.motion.reconstructFrom

/** Where the target is, relative to the part of the list a person can use. */
enum class TargetLocation {
    /** Enough of it is on screen, and the middle of it is not covered. */
    Visible,

    /** Proven to be past the top of the list. */
    Above,

    /** Proven to be past the bottom of the list. */
    Below,

    /** Not proven to be anywhere. The only honest answer to a reading that
     *  cannot place the control. */
    Unknown,
}

/** What the person should be told while the page is moving. */
enum class ScrollGuidance {
    /** It is below: keep going the way they are going. */
    KeepScrollingDown,

    /** It is above: they have gone past it. */
    ScrollUp,

    /** It is here. */
    StopHere,

    /** It cannot be placed. No arrow, no guess. */
    StopForAMoment,
}

/**
 * Decides where the target is, from a reading of the target itself.
 *
 * The one rule that matters: nothing here looks at which way the page moved.
 * A person swiping upward tells you what their finger did, not where the
 * control is — they may be scrolling towards it, past it, or back to it — and
 * an arrow derived from that is a confident instruction built on no evidence.
 * Every answer below comes from the control's own rectangle measured against
 * the list's, or is [TargetLocation.Unknown].
 */
class TargetLocator(
    private val visibility: OutlineVisibilityPolicy = OutlineVisibilityPolicy(),
    private val enterVisibleFraction: Float = ENTER_VISIBLE_FRACTION,
    private val leaveVisibleFraction: Float = LEAVE_VISIBLE_FRACTION,
) {

    /**
     * @param reported the target's rectangle exactly as accessibility gave it,
     *   which for a row partly out of its list is only the visible part and for
     *   one entirely out has its edges crossed.
     * @param geometry the target's full size, learned from an unclipped
     *   reading. Never learned here.
     * @param clip the scrolling container — what actually cuts a row off, which
     *   is not the display: a list sits below a toolbar.
     * @param occluders Dot's own layers. A row behind the card is not visible,
     *   however much of it is on screen.
     * @param holdingVisible true when [TargetLocation.Visible] is the answer
     *   currently being shown, which lowers the bar for keeping it. Without the
     *   gap a row resting near the boundary alternates between "stop here" and
     *   an arrow on every reading.
     */
    fun classify(
        reported: Bounds,
        geometry: CanonicalTargetGeometry?,
        clip: Bounds,
        occluders: List<Bounds>,
        holdingVisible: Boolean,
    ): TargetLocation {
        // Crossed edges mean the control is laid out entirely outside the part
        // of the list that is on screen. The rectangle is still informative:
        // which edge ended up where says which side it went.
        val inverted = reported.right < reported.left || reported.bottom < reported.top
        if (inverted || reported.isEmpty) {
            return when {
                reported.top >= clip.bottom -> TargetLocation.Below
                reported.bottom <= clip.top -> TargetLocation.Above
                else -> TargetLocation.Unknown
            }
        }

        // A clipped reading is put back to full size before being judged, so
        // "how much of it can be seen" is a fraction of the control rather than
        // a fraction of whatever survived the clipping.
        val full = geometry?.let {
            val pose = it.reconstructFrom(reported, clip)
            Bounds(
                left = pose.leftPx.toInt(),
                top = pose.topPx.toInt(),
                right = (pose.leftPx + it.widthPx).toInt(),
                bottom = (pose.topPx + it.heightPx).toInt(),
            )
        } ?: reported

        val threshold = if (holdingVisible) leaveVisibleFraction else enterVisibleFraction
        val fraction = visibility.visibleFraction(full, clip, occluders)
        if (fraction >= threshold && !visibility.actionableCentreCovered(full, occluders)) {
            return TargetLocation.Visible
        }

        // Not enough of it to use. Whichever end is further outside the list is
        // the way it has gone.
        val above = (clip.top - full.top).coerceAtLeast(0)
        val below = (full.bottom - clip.bottom).coerceAtLeast(0)
        return when {
            above > below -> TargetLocation.Above
            below > above -> TargetLocation.Below
            // Fully inside the list and still not usable: it is behind
            // something Dot drew. Neither direction would help.
            else -> TargetLocation.Unknown
        }
    }

    companion object {
        /** Enough of the control to say "this is it". */
        const val ENTER_VISIBLE_FRACTION = 0.60f

        /** And how little may remain before that stops being true. */
        const val LEAVE_VISIBLE_FRACTION = 0.45f
    }
}
