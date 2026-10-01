package com.aaravsekhri.dot.guide.tracking

import com.aaravsekhri.dot.guide.matching.SemanticRegionResolver
import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.matching.TargetMatcher
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.model.SemanticTargetRegion
import com.aaravsekhri.dot.guide.model.TargetSpec
import com.aaravsekhri.dot.guide.matching.TargetMatch

/** Where target acquisition currently stands. */
sealed interface TrackerState {

    /** Looking. Nothing is drawn over the page. */
    data object Searching : TrackerState

    /** The target is not in view and the step says which way it lies. */
    data class ScrollNeeded(val direction: ScrollDirection) : TrackerState

    /** The target is in view, and this is the area it occupies. */
    data class TargetVisible(val region: SemanticTargetRegion) : TrackerState

    /**
     * The target was here a moment ago and is not in this scan.
     *
     * Held briefly rather than torn down at once: a list mid-layout drops
     * nodes for a frame or two, and flashing the highlight off and on for that
     * is worse than waiting.
     */
    data class TargetTemporarilyLost(val lastRegion: SemanticTargetRegion) : TrackerState

    /** The page is being replaced. Everything positional comes down now. */
    data object Transitioning : TrackerState

    /** The person paused the guide. */
    data object GuidePaused : TrackerState
}

/**
 * Follows one target for the life of a step.
 *
 * Once a target has been settled on, the tracker stops asking "what scores
 * highest on this screen" and starts asking "where has *that* gone". Those are
 * different questions, and answering the first one on every scan is what made
 * the outline hop between a setting's title, its description and its slider —
 * all of which match the same words.
 *
 * Reacquisition is by [TargetIdentity], so a scroll moves the outline and
 * nothing else does.
 *
 * Plain Kotlin over [ScreenSnapshot], so all of this is unit tested.
 */
class TargetTracker(
    private val boundsTolerancePx: Int = DEFAULT_BOUNDS_TOLERANCE_PX,
    private val requiredAgreeingScans: Int = DEFAULT_AGREEING_SCANS,
    /** How much must be on screen before a target is shown at all. */
    private val visibilityThreshold: Float = DEFAULT_VISIBILITY_THRESHOLD,
    /**
     * How little may remain on screen before a shown target is given up.
     *
     * Lower than [visibilityThreshold] on purpose. With one threshold, a target
     * resting near it would flip between highlight and scroll cue on every
     * scan; the gap between the two means it has to genuinely leave before the
     * cue comes back.
     */
    private val visibilityReleaseThreshold: Float = DEFAULT_VISIBILITY_RELEASE,
) {
    var state: TrackerState = TrackerState.Searching
        private set

    /** The target being followed, once one has been settled on. */
    var trackedIdentity: TargetIdentity? = null
        private set

    /** Increments per newly settled target; keys Pebble's entrance. */
    var generation: Int = 0
        private set

    private var settledRegion: SemanticTargetRegion? = null

    // Acquisition, before anything is being tracked.
    private var candidateIdentity: TargetIdentity? = null
    private var candidateBounds: Bounds? = null
    private var agreeingScans: Int = 0

    /** Consecutive scans in which a tracked target could not be found. */
    private var missesSinceSeen: Int = 0

    /** Everything forgotten: a new step, a new page, or a fresh start. */
    fun reset() {
        state = TrackerState.Searching
        trackedIdentity = null
        settledRegion = null
        candidateIdentity = null
        candidateBounds = null
        agreeingScans = 0
        missesSinceSeen = 0
    }

    /** The page is changing. Positional overlays must not survive it. */
    fun beginTransition() {
        reset()
        state = TrackerState.Transitioning
    }

    fun pause() {
        state = TrackerState.GuidePaused
    }

    /**
     * Takes one look at the screen.
     *
     * @param viewport the area a highlight may usefully occupy — the app
     *   window less the system bars.
     * @param direction which way the step says the target lies, or null when
     *   the step has not said and no cue should be offered.
     */
    fun onScreen(
        screen: ScreenSnapshot,
        spec: TargetSpec,
        viewport: Bounds,
        direction: ScrollDirection?,
    ): TrackerState {
        if (state is TrackerState.GuidePaused) return state

        val tracked = trackedIdentity
        val region = if (tracked != null) {
            reacquire(screen, spec, tracked) ?: acquireFresh(screen, spec)
        } else {
            acquireFresh(screen, spec)
        }

        state = when {
            // A candidate has been seen but not yet confirmed. That is still
            // looking, not absence — reporting it as absence would put a
            // "scroll down" cue on screen with the target sitting in plain
            // view, waiting for a confirmation that only another scan can give.
            region == null && candidateIdentity != null -> TrackerState.Searching

            region == null -> onNothingFound(screen, direction)

            region.visibleFractionWithin(viewport) < thresholdFor(region) ->
                // It exists but is mostly off the edge. Outlining a sliver is
                // no use, so keep asking for the scroll that will bring it in.
                onNotUsablyVisible(screen, direction, region)

            else -> {
                missesSinceSeen = 0
                // A region that has barely moved is the same region. Node
                // bounds drift by a pixel or two as a page settles, and
                // redrawing for that would make a still screen shimmer. Real
                // movement — a scroll — is far beyond this and comes straight
                // through.
                val settled = settledRegion
                val stable = if (
                    settled != null &&
                    settled.identity == region.identity &&
                    region.boundsInScreen.isEffectivelySameAs(
                        settled.boundsInScreen,
                        boundsTolerancePx,
                    )
                ) {
                    settled
                } else {
                    region
                }
                settledRegion = stable
                TrackerState.TargetVisible(stable)
            }
        }
        return state
    }

    /**
     * Which visibility bar applies right now.
     *
     * Getting a target on screen takes more than keeping it there, so a
     * highlight that is already up survives being scrolled partly out of view
     * instead of flickering against the cue.
     */
    private fun thresholdFor(region: SemanticTargetRegion): Float {
        val shown = state is TrackerState.TargetVisible &&
            settledRegion?.identity?.isSameControlAs(region.identity) == true
        return if (shown) visibilityReleaseThreshold else visibilityThreshold
    }

    /**
     * Re-points at the same control after the page was rebuilt underneath it.
     *
     * The identity carries a window id, which a font-size change replaces. This
     * adopts the new one without treating it as a different target, so nothing
     * is torn down and nothing pops back in.
     */
    fun rebindTo(region: SemanticTargetRegion) {
        trackedIdentity = region.identity
        settledRegion = region
        missesSinceSeen = 0
        candidateIdentity = null
        candidateBounds = null
        agreeingScans = 0
        state = TrackerState.TargetVisible(region)
    }

    /** Finds the step's target by what it is, ignoring which window holds it. */
    fun findSameControl(
        screen: ScreenSnapshot,
        spec: TargetSpec,
        control: TargetIdentity,
    ): SemanticTargetRegion? {
        val matches = TargetMatcher.findAll(screen, spec)
        val same = matches.firstOrNull { it.identity?.isSameControlAs(control) == true }
            ?: return null
        return regionFor(screen, spec, same)
    }

    // ------------------------------------------------------- reacquisition

    /**
     * Finds the target being followed, by identity rather than by score.
     *
     * This is what keeps the outline on the same setting while a page scrolls
     * under it.
     */
    private fun reacquire(
        screen: ScreenSnapshot,
        spec: TargetSpec,
        identity: TargetIdentity,
    ): SemanticTargetRegion? {
        val matches = TargetMatcher.findAll(screen, spec)
        val sameTarget = matches.firstOrNull { it.identity == identity } ?: return null
        return regionFor(screen, spec, sameTarget)
    }

    /** No target yet: the best candidate has to win twice before it is shown. */
    private fun acquireFresh(screen: ScreenSnapshot, spec: TargetSpec): SemanticTargetRegion? {
        val best = TargetMatcher.findBest(screen, spec) ?: return null
        val identity = best.identity ?: return null

        val previousBounds = candidateBounds
        if (candidateIdentity == identity &&
            previousBounds != null &&
            best.bounds.isEffectivelySameAs(previousBounds, boundsTolerancePx)
        ) {
            agreeingScans++
        } else {
            candidateIdentity = identity
            candidateBounds = best.bounds
            agreeingScans = 1
        }

        if (agreeingScans < requiredAgreeingScans) return null

        val region = regionFor(screen, spec, best) ?: return null
        trackedIdentity = identity
        generation++
        candidateIdentity = null
        candidateBounds = null
        agreeingScans = 0
        return region
    }

    private fun regionFor(
        screen: ScreenSnapshot,
        spec: TargetSpec,
        match: com.aaravsekhri.dot.guide.matching.TargetMatch,
    ): SemanticTargetRegion? {
        val anchor = screen.nodeById(match.labelNodeId) ?: return null
        val highlight = screen.nodeById(match.highlightNodeId) ?: anchor
        val identity = match.identity ?: return null
        return SemanticRegionResolver.resolve(
            screen = screen,
            anchor = anchor,
            highlightNode = highlight,
            spec = spec,
            identity = identity,
            label = match.matchedLabel,
        )
    }

    // ------------------------------------------------------------ absences

    private fun onNothingFound(
        screen: ScreenSnapshot,
        direction: ScrollDirection?,
    ): TrackerState {
        val last = settledRegion
        if (last != null) {
            missesSinceSeen++
            if (missesSinceSeen <= MISSES_BEFORE_GIVING_UP) {
                return TrackerState.TargetTemporarilyLost(last)
            }
        }

        // Genuinely gone. Stop following it and decide what to say.
        trackedIdentity = null
        settledRegion = null
        missesSinceSeen = 0
        return scrollOrSearch(screen, direction)
    }

    private fun onNotUsablyVisible(
        screen: ScreenSnapshot,
        direction: ScrollDirection?,
        region: SemanticTargetRegion,
    ): TrackerState {
        // Still tracked — it is on the page, just not far enough onto it.
        settledRegion = region
        missesSinceSeen = 0

        // The target's own position says which way it lies, and it is right
        // even when the step's hint is not. A step carries one direction for
        // the whole of its life, so a target that has been scrolled *past*
        // was being pointed at with an arrow aimed the other way.
        val viewport = screen.viewport(com.aaravsekhri.dot.guide.model.Insets.NONE)
        val observed = when {
            region.boundsInScreen.centerY < viewport.centerY -> ScrollDirection.Up
            else -> ScrollDirection.Down
        }
        return scrollOrSearch(screen, observed)
    }

    private fun scrollOrSearch(
        screen: ScreenSnapshot,
        direction: ScrollDirection?,
    ): TrackerState = if (direction != null && screen.hasScrollableContent) {
        TrackerState.ScrollNeeded(direction)
    } else {
        TrackerState.Searching
    }

    companion object {
        const val DEFAULT_BOUNDS_TOLERANCE_PX = 3
        const val DEFAULT_AGREEING_SCANS = 2

        /**
         * How much of a setting has to be on screen before outlining it is
         * worth doing. Below this the cue keeps asking for the scroll.
         *
         * This was four fifths, which meant a row could never be seen partly
         * outlined: the engine switched to the cue while the row was still
         * almost entirely visible, and the clipping behaviour further down —
         * full-size rectangle, pixels cut by the canvas — was unreachable in
         * practice. Just under half is enough of a control to recognise and to
         * reach, and it lets a row slide off the edge with its outline still
         * on it, which is what someone scrolling towards it expects to see.
         */
        const val DEFAULT_VISIBILITY_THRESHOLD = 0.35f

        /**
         * How little may remain before a shown target is released.
         *
         * Lower than the threshold above, so a row resting near the boundary
         * keeps whichever state it already had instead of alternating.
         */
        const val DEFAULT_VISIBILITY_RELEASE = 0.25f

        /** Scans a tracked target may be missing before it is let go. */
        const val MISSES_BEFORE_GIVING_UP = 3
    }
}
