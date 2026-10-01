package com.aaravsekhri.dot.guide.positional

import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry
import com.aaravsekhri.dot.guide.motion.OutlineVisibilityPolicy
import com.aaravsekhri.dot.guide.motion.TargetPose
import com.aaravsekhri.dot.guide.motion.reconstructFrom
import com.aaravsekhri.dot.guide.overlay.PebbleCorner
import com.aaravsekhri.dot.guide.overlay.VisiblePebbleAnchorResolver

/** One reading of the screen, offered to the controller. */
data class ScanCandidate(
    val identity: TargetIdentity,
    /** As accessibility reported it, which may be clipped to the visible part. */
    val reportedBounds: Bounds,
    /** Text inside the target that Pebble should not sit on. */
    val avoid: List<Bounds> = emptyList(),
    /** True only when an exact approved title was found inside the region. */
    val titleVerified: Boolean,
    /**
     * The caller can show these are the control's whole bounds, not a
     * clipped part: they lie strictly inside the physical screen and inside
     * the list that holds them, where accessibility would otherwise have cut
     * them off. Needed because the viewport excludes the system bars and
     * apps drawn edge to edge — a launcher's last row — reach under them.
     * Never set on a guess; without it a clipped-looking target is refused
     * until its real size is known, as before.
     */
    val knownWhole: Boolean = false,
)

/** Everything about the screen the controller needs to judge a candidate. */
data class ScreenContext(
    val viewport: Bounds,
    val occluders: List<Bounds>,
    val pebbleSizePx: Int,
    val pebbleStraddlePx: Int,
    /**
     * The scrolling container the target lives in, where there is one.
     *
     * This is what actually clips a row's reported bounds, and it is not the
     * viewport: a list sits below a toolbar, so a row scrolling up is cut off
     * well before it reaches the top of the screen. Measuring "is this reading
     * the whole control?" against the display instead of against the list is
     * why a row's canonical height quietly shrank as it scrolled away.
     */
    val contentBounds: Bounds? = null,
)

/**
 * The single owner of everything Dot draws over another app.
 *
 * There is one of these, it holds one state, and nothing else may show or hide
 * a positional overlay. Every input arrives as a method call, every output
 * comes from [output], and work started in one state cannot land in another
 * because each result carries the [generation] it began under.
 *
 * The product rule it exists to enforce is simple to say and was hard to
 * arrive at: **an outline is only ever shown over a page that is standing
 * still.** The moment a page starts moving the outline goes, and while it moves
 * the person gets words instead — because a rectangle drawn from a position
 * that is already out of date is not an imprecise highlight, it is a confident
 * instruction to tap the wrong row. Three attempts at following a scroll went
 * before this: rescanning the tree, translating by scroll deltas, and reading
 * the control sixty times a second. The last of those works, and the outline it
 * produces is still five to twenty pixels behind at ordinary scrolling speeds,
 * still competing with the app for its own main thread, and still capable of
 * lagging visibly when either is busy. Not moving it at all is both calmer to
 * look at and honest.
 *
 * The rest follows from that:
 *
 * What the words say comes from a reading of the target, never from the
 * direction the page moved. Which way a finger went says nothing about where a
 * control is; the person may be scrolling towards it, past it, or back to it.
 *
 * When nothing can place the target, the card says so rather than picking a
 * direction.
 *
 * Plain Kotlin with injected time, so all of this is tested as rules rather
 * than inferred from watching a device.
 */
class PositionalGuideController(
    private val config: Config = Config(),
    private val visibility: OutlineVisibilityPolicy = OutlineVisibilityPolicy(),
    private val locator: TargetLocator = TargetLocator(visibility),
) {

    data class Config(
        /**
         * How long without a scroll event before the page counts as still.
         *
         * Measured, and then measured again when the first value proved too
         * short. Settings delivers scroll events about every 121ms during an
         * ordinary drag, but the gaps are much wider at the extremes: 368ms
         * during a very slow drag, where the content barely moves between
         * frames, and 356ms between the two events a hard fling produces. A
         * threshold under those ends the session in the middle of a gesture and
         * puts an outline back over a page that is still moving, which is the
         * one thing this design exists to prevent.
         *
         * Six hundred milliseconds clears the worst observed gap by a wide
         * margin and is still short enough that letting go and seeing the
         * outline reads as one action rather than two.
         */
        val idleAfterScrollNanos: Long = 600_000_000L,
        /** Agreeing readings required, at least a frame apart, before drawing. */
        val confirmationsRequired: Int = 2,
        /**
         * Readings to ask for after a scroll before concluding the control is
         * not on this page. About a second at the rate the caller asks.
         */
        val verificationAttempts: Int = 10,
        /** How far two readings may disagree and still be the same sighting. */
        val stabilityTolerancePx: Int = 12,
        /** Of the target must be visible before the outline appears. */
        val restoreVisibleFraction: Float = 0.45f,
        /** And this much must remain for it to stay. */
        val minimumVisibleFraction: Float = 0.35f,
    )

    var state: PositionalGuideState = PositionalGuideState.Searching()
        private set

    /** Rises on every change that invalidates work already in flight. */
    var generation: Long = 0L
        private set

    private var appearanceKey: Int = 0
    private var hapticPulses: Int = 0

    /** The target as last drawn, so returning to it is not a new appearance. */
    private var lastDrawnIdentity: TargetIdentity? = null

    /** The message last put on screen, which is what haptics key off. */
    private var lastGuidanceShown: ScrollGuidance? = null

    /** The shape of the target as first measured, kept across clipped readings. */
    private var knownGeometry: CanonicalTargetGeometry? = null
    private var knownIdentity: TargetIdentity? = null

    /** Zero means no scroll has been seen. Never a sentinel that can overflow. */
    private var lastScrollNanos: Long = 0L

    /** The screen as the last full reading described it. */
    private var lastContext: ScreenContext? = null

    private var lastPebbleSize: Int = 0

    // ------------------------------------------------------------- inputs

    /**
     * The person is moving the page.
     *
     * Called only for movement the person caused. A control re-laying itself
     * out — which Android also reports as a scroll — is filtered out before it
     * reaches here, because treating a font-size change as a scroll took the
     * outline down and played Pebble's entrance again on every notch.
     *
     * The outline comes off in this one transition, before anything is known
     * about where the target has got to. That ordering is the point: there is
     * never a frame in which a stale rectangle is still on screen over a page
     * that has started moving.
     */
    fun onScrollActivity(atNanos: Long) {
        lastScrollNanos = atNanos
        val current = state
        when (current) {
            is PositionalGuideState.Paused, is PositionalGuideState.Transitioning -> return
            else -> Unit
        }
        if (isScrolling(current)) return

        // The first card of a scroll is whatever the guide already believed:
        // the last reading if there has been one, or the step's own hint about
        // where its target lies. Both are things Dot knew before the person
        // touched the screen. What is never used is the direction they moved.
        val seed = when {
            lastKnownLocation != TargetLocation.Unknown -> lastKnownLocation
            current is PositionalGuideState.Searching -> when (current.direction) {
                ScrollDirection.Down -> TargetLocation.Below
                ScrollDirection.Up -> TargetLocation.Above
                null -> TargetLocation.Unknown
            }
            else -> TargetLocation.Unknown
        }
        transitionTo(stateFor(seed, expectedIdentity()))
    }

    /**
     * A fresh reading of the target, taken while the page is moving.
     *
     * @param reported the control's rectangle as accessibility gave it, or null
     *   when it could not be read at all.
     */
    fun onScrollEvidence(reported: Bounds?, atNanos: Long) {
        val current = state
        // Also while the page has stopped and the target is being checked: the
        // card is still on screen then, and a card left saying "stop here" over
        // a list the person has scrolled away from is worse than no card. The
        // message goes on tracking the evidence until the outline replaces it.
        if (!isScrolling(current) && current !is PositionalGuideState.WaitingForStability) return
        val context = lastContext ?: return
        val clip = context.contentBounds ?: context.viewport

        val location = if (reported == null) {
            // The control could not be read. That is not the same as not
            // knowing where it is: a row that has been *seen* to leave through
            // the bottom is still below until something says otherwise, and
            // saying "stop for a moment" instead would be throwing away a fact
            // in favour of a shrug. Only a target last seen in view leaves
            // genuinely no idea which way it went.
            lastProvenEdge ?: TargetLocation.Unknown
        } else {
            locator.classify(
                reported = reported,
                geometry = knownGeometry,
                clip = clip,
                occluders = context.occluders,
                holdingVisible = current is PositionalGuideState.UserScrollingStopHere,
            )
        }

        GuideLog.d("classify") {
            val full = reported?.let { r ->
                knownGeometry?.let {
                    val pose = it.reconstructFrom(r, clip)
                    Bounds(
                        pose.leftPx.toInt(),
                        pose.topPx.toInt(),
                        (pose.leftPx + it.widthPx).toInt(),
                        (pose.topPx + it.heightPx).toInt(),
                    )
                } ?: r
            }
            "state=${current::class.simpleName} identity=${expectedIdentity() != null} " +
                "node=${reported != null} reported=$reported full=$full clip=$clip " +
                "occluders=${context.occluders} " +
                "visible=${full?.let { visibility.visibleFraction(it, clip, context.occluders) }} " +
                "centreCovered=${full?.let { visibility.actionableCentreCovered(it, context.occluders) }} " +
                "canonical=${knownGeometry?.let { "${it.widthPx.toInt()}x${it.heightPx.toInt()}" }} " +
                "wasProven=$lastKnownLocation -> now=$location gen=$generation"
        }
        lastKnownLocation = location
        when (location) {
            TargetLocation.Above, TargetLocation.Below -> lastProvenEdge = location
            // In view: no edge has been crossed, so there is no edge to keep.
            TargetLocation.Visible -> lastProvenEdge = null
            TargetLocation.Unknown -> Unit
        }

        // Keep track of where it was last seen, so the card can stay at the
        // other end of the screen as the target travels.
        if (location == TargetLocation.Visible && reported != null) {
            lastSeenBounds = knownGeometry?.let {
                val pose = it.reconstructFrom(reported, clip)
                Bounds(
                    left = pose.leftPx.toInt(),
                    top = pose.topPx.toInt(),
                    right = (pose.leftPx + it.widthPx).toInt(),
                    bottom = (pose.topPx + it.heightPx).toInt(),
                )
            } ?: reported
        }

        if (current is PositionalGuideState.WaitingForStability) {
            val message = messageFor(location)
            if (current.holding != message) {
                state = current.copy(holding = message)
                lastGuidanceShown = message
            }
            return
        }

        val next = stateFor(location, expectedIdentity())
        if (next != current) transitionTo(next)
    }

    private fun messageFor(location: TargetLocation): ScrollGuidance = when (location) {
        TargetLocation.Visible -> ScrollGuidance.StopHere
        TargetLocation.Above -> ScrollGuidance.ScrollUp
        TargetLocation.Below -> ScrollGuidance.KeepScrollingDown
        TargetLocation.Unknown -> ScrollGuidance.StopForAMoment
    }

    /**
     * Time passed.
     *
     * The only thing decided on a clock: whether the page has stopped. Where
     * the target is is never decided on a clock.
     */
    fun onTick(nowNanos: Long) {
        val current = state
        if (!isScrolling(current)) return
        if (lastScrollNanos == 0L) return
        if (nowNanos - lastScrollNanos < config.idleAfterScrollNanos) return

        // Stopped. The card keeps saying whatever it was saying until the
        // target has been checked, so the person sees one change rather than
        // two.
        transitionTo(
            PositionalGuideState.WaitingForStability(
                expectedIdentity = expectedIdentityOf(current),
                holding = lastGuidanceShown,
            ),
        )
    }

    /**
     * A reading of the screen came back.
     *
     * @param scanGeneration the generation the scan started under. A scan begun
     *   before the page moved describes a page that has since changed, and is
     *   discarded rather than applied late.
     */
    fun onScanResult(
        candidate: ScanCandidate?,
        context: ScreenContext,
        scanGeneration: Long,
        nowNanos: Long,
    ) {
        if (scanGeneration < generation) {
            GuideLog.d("positional") { "reading from generation $scanGeneration discarded" }
            return
        }
        lastContext = context
        when (val current = state) {
            is PositionalGuideState.Paused, is PositionalGuideState.Transitioning -> Unit

            // The page is moving. A full reading of the tree is far too old to
            // draw from by the time it arrives, and it is not what decides the
            // message either — that comes from the targeted reading.
            is PositionalGuideState.UserScrollingTargetAbove,
            is PositionalGuideState.UserScrollingTargetBelow,
            is PositionalGuideState.UserScrollingStopHere,
            is PositionalGuideState.UserScrollingUncertain,
            -> Unit

            is PositionalGuideState.WaitingForStability -> confirm(current, candidate, context)

            is PositionalGuideState.Searching -> acceptFirstSighting(candidate, context)

            is PositionalGuideState.TargetOccluded ->
                reviewSettled(candidate, context, current.expectedIdentity)

            is PositionalGuideState.TargetStable ->
                reviewSettled(candidate, context, current.identity)
        }
    }

    /**
     * A control changed value without the page moving — a font-size notch.
     *
     * Explicitly not a scroll and not navigation. Stated as a method so the
     * distinction is visible in the code rather than implied by the absence of
     * a call.
     */
    fun onSameScreenControlChange() = Unit

    /** The card moved, or something else Dot draws did. */
    fun onOccludersChanged(occluders: List<Bounds>) {
        lastContext = lastContext?.copy(occluders = occluders)
    }

    /** The screen is being replaced. */
    fun onNavigation() {
        knownGeometry = null
        knownIdentity = null
        lastDrawnIdentity = null
        lastGuidanceShown = null
        lastKnownLocation = TargetLocation.Unknown
        lastProvenEdge = null
        lastScrollNanos = 0L
        transitionTo(PositionalGuideState.Transitioning)
    }

    /**
     * The guide is looking for its target, and may know which way it lies.
     *
     * The direction here comes from the guide's own reading of the page or from
     * the step's authored hint — not from anything the person just did.
     */
    fun onSearching(direction: ScrollDirection? = null) {
        val current = state
        if (current is PositionalGuideState.Searching && current.direction == direction) return

        // Not while the page is moving, and not while the target is being
        // checked after it stopped. The guide's own tracker reports "searching"
        // for a reading or two after any scroll — it has to re-find the row —
        // and letting that through threw away the checks in progress and the
        // card being held, so the outline never arrived and the screen went
        // blank instead.
        if (isScrolling(current) || current is PositionalGuideState.WaitingForStability) return
        transitionTo(PositionalGuideState.Searching(direction))
    }

    /**
     * The target is withdrawn, but the page is the same one.
     *
     * The person touched the control — a switch, a slider's plus — and the
     * guide is working out what comes next. Unlike [onNavigation], the control
     * last drawn is remembered: if the next step points at it again, it is the
     * same control on the same page, and Pebble does not replay its entrance.
     */
    /**
     * A fresh, whole measurement of the target is on its way: forget the
     * shape remembered from earlier readings.
     *
     * The remembered shape exists to carry a target through a scroll, where
     * later readings are clipped by the list's edge. It must not outlive the
     * layout it was measured in. A search field that wrapped its hint to two
     * lines was remembered at that height; once focused it was one line, and
     * sat flush with the bottom of its list above the keyboard — which read
     * as "clipped" — so the old, taller shape was drawn from its top, and the
     * outline ran down past the field towards the keyboard.
     */
    fun forgetShape() {
        knownGeometry = null
    }

    fun onTargetWithdrawn() {
        knownGeometry = null
        knownIdentity = null
        lastGuidanceShown = null
        lastKnownLocation = TargetLocation.Unknown
        transitionTo(PositionalGuideState.Searching())
    }

    fun onPaused() = transitionTo(PositionalGuideState.Paused)

    fun onResumed() = transitionTo(PositionalGuideState.Searching())

    // ------------------------------------------------------------ output

    /** Exactly what should be on screen. The only source of visible truth. */
    fun output(): PositionalOutput = when (val current = state) {
        is PositionalGuideState.TargetStable -> PositionalOutput(
            outline = buildOutline(current),
            guidance = null,
            appearanceKey = appearanceKey,
            hapticPulses = hapticPulses,
        )

        is PositionalGuideState.UserScrollingTargetBelow ->
            card(ScrollGuidance.KeepScrollingDown)

        is PositionalGuideState.UserScrollingTargetAbove ->
            card(ScrollGuidance.ScrollUp)

        is PositionalGuideState.UserScrollingStopHere ->
            card(ScrollGuidance.StopHere)

        is PositionalGuideState.UserScrollingUncertain ->
            card(ScrollGuidance.StopForAMoment)

        // The page has stopped and the target is being checked. Whatever was
        // being said keeps being said: this is the middle of one change, not a
        // state worth showing anybody.
        // The page has stopped and the target is being checked. Whatever was
        // being said keeps being said — and if nothing was, something is, so
        // that a recognised scroll never ends on a frame with neither an
        // outline nor a card.
        is PositionalGuideState.WaitingForStability ->
            card(current.holding ?: ScrollGuidance.StopForAMoment)

        // Looking for the target. The guide's own hint comes first, and when
        // it has none, whatever the last reading proved. Leaving somebody with
        // an empty screen after a fling that overshot — no outline and nothing
        // to read — is the one outcome worse than an imperfect instruction.
        is PositionalGuideState.Searching -> when (current.direction) {
            ScrollDirection.Down -> card(ScrollGuidance.KeepScrollingDown)
            ScrollDirection.Up -> card(ScrollGuidance.ScrollUp)
            null -> when (lastProvenEdge) {
                TargetLocation.Above -> card(ScrollGuidance.ScrollUp)
                TargetLocation.Below -> card(ScrollGuidance.KeepScrollingDown)
                else -> blank()
            }
        }

        // Behind Dot's own card: the target is not somewhere else, it is right
        // here and covered, so there is nothing to draw and nothing to say.
        is PositionalGuideState.TargetOccluded -> blank()

        is PositionalGuideState.Transitioning, is PositionalGuideState.Paused -> blank()
    }

    private fun blank() = PositionalOutput(
        outline = null,
        guidance = null,
        appearanceKey = appearanceKey,
        hapticPulses = hapticPulses,
    )

    /**
     * The card, on the edge away from the target.
     *
     * A card sitting on top of the thing it is telling you to look at is worse
     * than no card. Where the target is known, the card goes to the other end
     * of the screen; where it is not, it stays where it was rather than moving
     * for no reason.
     */
    private fun card(message: ScrollGuidance) = PositionalOutput(
        outline = null,
        guidance = GuidanceOutput(
            message = message,
            atBottom = cardAtBottomFor(message),
            animate = isScrolling(state),
        ),
        appearanceKey = appearanceKey,
        hapticPulses = hapticPulses,
    )

    private fun cardAtBottomFor(message: ScrollGuidance): Boolean = when (message) {
        // Below the fold: the card goes up top.
        ScrollGuidance.KeepScrollingDown -> false
        // Above: the card goes down.
        ScrollGuidance.ScrollUp -> true
        // Right here: away from wherever "here" is.
        ScrollGuidance.StopHere -> !targetInLowerHalf()
        ScrollGuidance.StopForAMoment -> lastCardAtBottom
    }.also { lastCardAtBottom = it }

    private var lastCardAtBottom: Boolean = false

    private fun targetInLowerHalf(): Boolean {
        val context = lastContext ?: return false
        val clip = context.contentBounds ?: context.viewport
        val target = lastSeenBounds ?: return false
        return target.centerY > clip.centerY
    }

    private fun buildOutline(stable: PositionalGuideState.TargetStable): OutlineOutput? {
        val pebble = stable.geometry.pebbleAnchorOffsets[stable.corner] ?: return null
        return OutlineOutput(
            clip = lastContext?.let { it.contentBounds ?: it.viewport },
            geometry = stable.geometry,
            pose = stable.pose,
            pebble = Bounds(
                left = (stable.pose.leftPx + pebble.x).toInt(),
                top = (stable.pose.topPx + pebble.y).toInt(),
                right = (stable.pose.leftPx + pebble.x).toInt() + lastPebbleSize,
                bottom = (stable.pose.topPx + pebble.y).toInt() + lastPebbleSize,
            ),
            corner = stable.corner,
        )
    }

    // ------------------------------------------------------------- rules

    /** The last place a reading put the target, for the first card of a scroll. */
    private var lastKnownLocation: TargetLocation = TargetLocation.Unknown

    /** Where the target was last seen, for deciding which edge the card takes. */
    private var lastSeenBounds: Bounds? = null

    /**
     * The last edge the target was proven to be past, if any.
     *
     * Kept so that a control which has scrolled out of the tree altogether —
     * which is what happens on a long list — goes on being described truthfully
     * instead of dissolving into "stop for a moment" the moment it can no
     * longer be read.
     */
    private var lastProvenEdge: TargetLocation? = null

    private fun stateFor(
        location: TargetLocation,
        expected: TargetIdentity?,
    ): PositionalGuideState = when (location) {
        TargetLocation.Visible -> PositionalGuideState.UserScrollingStopHere(expected)
        TargetLocation.Above -> PositionalGuideState.UserScrollingTargetAbove(expected)
        TargetLocation.Below -> PositionalGuideState.UserScrollingTargetBelow(expected)
        TargetLocation.Unknown -> PositionalGuideState.UserScrollingUncertain(expected)
    }

    private fun isScrolling(state: PositionalGuideState): Boolean = when (state) {
        is PositionalGuideState.UserScrollingTargetAbove,
        is PositionalGuideState.UserScrollingTargetBelow,
        is PositionalGuideState.UserScrollingStopHere,
        is PositionalGuideState.UserScrollingUncertain,
        -> true

        else -> false
    }

    private fun acceptFirstSighting(candidate: ScanCandidate?, context: ScreenContext) {
        val settled = settle(candidate, context) ?: return
        adopt(settled)
    }

    /**
     * The page has stopped. Agree twice, then draw.
     *
     * Two readings rather than one because a list that has just come to rest is
     * still settling for a frame or two, and a rectangle taken mid-settle puts
     * the outline a few pixels out and then corrects it, which reads as a
     * twitch. The caller leaves at least a rendered frame between them.
     */
    private fun confirm(
        current: PositionalGuideState.WaitingForStability,
        candidate: ScanCandidate?,
        context: ScreenContext,
    ) {
        if (candidate == null) {
            giveUpWaiting(current)
            return
        }
        val expected = current.expectedIdentity
        if (expected != null && !candidate.identity.isSameControlAs(expected)) {
            // A different control, in roughly the place the target used to be.
            // Ignored outright, and the count is not reset by it either: the
            // answer to "that is not the row I wanted" is to keep waiting for
            // the row that was, never to settle on this one.
            GuideLog.d("positional") { "a different control was offered; still waiting" }
            giveUpWaiting(current)
            return
        }

        val previous = current.lastSeen
        val agrees = previous != null &&
            previous.isEffectivelySameAs(candidate.reportedBounds, config.stabilityTolerancePx)
        val confirmations = if (agrees) current.confirmations + 1 else 1
        if (confirmations < config.confirmationsRequired) {
            state = current.copy(
                confirmations = confirmations,
                lastSeen = candidate.reportedBounds,
                attempts = current.attempts + 1,
            )
            return
        }

        val settled = settle(candidate, context) ?: run {
            state = current.copy(
                confirmations = confirmations,
                lastSeen = candidate.reportedBounds,
                attempts = current.attempts + 1,
            )
            return
        }
        adopt(settled)
    }

    /**
     * Stops waiting for a target that is not coming back.
     *
     * Checking cannot go on for ever. When the page has stopped and the control
     * simply is not on it — the person scrolled past it and let go — waiting
     * pins the last message on screen, truthful or not, and keeps the whole
     * tree being read several times a second to ask a question already answered.
     * After a bounded number of tries the guide goes back to looking properly,
     * which is both honest and much cheaper.
     */
    private fun giveUpWaiting(current: PositionalGuideState.WaitingForStability) {
        val attempts = current.attempts + 1
        if (attempts < config.verificationAttempts) {
            state = current.copy(attempts = attempts)
            return
        }
        GuideLog.d("positional") { "the target did not come back; looking again" }
        transitionTo(PositionalGuideState.Searching(null))
    }

    /** An already-settled target, re-read. Correct it in place; never re-pop. */
    private fun reviewSettled(
        candidate: ScanCandidate?,
        context: ScreenContext,
        expected: TargetIdentity,
    ) {
        if (candidate == null || !candidate.identity.isSameControlAs(expected)) return
        val settled = settle(candidate, context)
        if (settled == null) {
            if (state !is PositionalGuideState.TargetOccluded) {
                transitionTo(PositionalGuideState.TargetOccluded(expected))
            }
            return
        }
        state = settled
    }

    /**
     * Puts a settled target on screen.
     *
     * The entrance plays for a genuinely different control and for nothing
     * else. Keying it on the control rather than on which transition happened
     * to arrive here is what stops a font-size notch, a settling scroll or a
     * rebuilt window from making Pebble pop again.
     */
    private fun adopt(settled: PositionalGuideState.TargetStable) {
        val isNewTarget = lastDrawnIdentity?.isSameControlAs(settled.identity) != true
        if (isNewTarget) appearanceKey++
        lastDrawnIdentity = settled.identity
        lastGuidanceShown = null
        transitionTo(settled)
    }

    /**
     * Turns a verified candidate into a drawable state, or refuses.
     *
     * Every reason to refuse is a reason the outline would otherwise have been
     * wrong or unexplained: an unverified title, a target behind the card, too
     * little of it on screen, or nowhere visible to put Pebble.
     */
    private fun settle(
        candidate: ScanCandidate?,
        context: ScreenContext,
    ): PositionalGuideState.TargetStable? {
        if (candidate == null) return null

        // Fail closed. Checked here, immediately before anything can be drawn,
        // and not merely wherever the candidate was first chosen.
        if (!candidate.titleVerified) return null

        lastPebbleSize = context.pebbleSizePx
        val clip = context.contentBounds ?: context.viewport

        // A first sighting of a row partly off the edge reports only the
        // visible part, and taking that as the target's size bakes the clipping
        // into the shape for good. The size is only ever learned from a reading
        // that shows the whole control.
        val geometry = geometryFor(candidate, context) ?: run {
            GuideLog.d("positional") {
                "refused: ${candidate.reportedBounds} is clipped and the target's " +
                    "size is not known yet"
            }
            return null
        }
        val pose = geometry.reconstructFrom(candidate.reportedBounds, clip)
        val full = Bounds(
            left = pose.leftPx.toInt(),
            top = pose.topPx.toInt(),
            right = (pose.leftPx + geometry.widthPx).toInt(),
            bottom = (pose.topPx + geometry.heightPx).toInt(),
        )

        val currentlyHidden = state !is PositionalGuideState.TargetStable
        val threshold =
            if (currentlyHidden) config.restoreVisibleFraction else config.minimumVisibleFraction
        val fraction = visibility.visibleFraction(full, clip, context.occluders)
        if (fraction < threshold) {
            GuideLog.d("positional") {
                "refused: only ${(fraction * 100).toInt()}% visible full=$full clip=$clip"
            }
            return null
        }
        if (visibility.actionableCentreCovered(full, context.occluders)) {
            GuideLog.d("positional") { "refused: something covers the middle of $full" }
            return null
        }

        val anchor = VisiblePebbleAnchorResolver.resolve(
            target = full,
            viewport = clip,
            avoid = candidate.avoid,
            occluders = context.occluders,
            size = context.pebbleSizePx,
            straddle = context.pebbleStraddlePx,
        ) ?: run {
            GuideLog.d("positional") { "refused: nowhere visible for Pebble on $full" }
            return null
        }

        // Pebble goes where the resolver put it, not merely on the corner it
        // named. For a full-width row the resolver pulls Pebble back inside
        // the screen; keeping only the corner and drawing at the raw corner
        // offset put three-quarters of Pebble off the edge, which is exactly
        // the sliver the pull-in exists to prevent.
        val placed = geometry.copy(
            pebbleAnchorOffsets = geometry.pebbleAnchorOffsets + (
                anchor.corner to androidx.compose.ui.geometry.Offset(
                    (anchor.bounds.left - full.left).toFloat(),
                    (anchor.bounds.top - full.top).toFloat(),
                )
            ),
        )

        knownGeometry = placed
        knownIdentity = candidate.identity
        lastSeenBounds = full
        lastKnownLocation = TargetLocation.Visible
        lastProvenEdge = null

        return PositionalGuideState.TargetStable(
            identity = candidate.identity,
            geometry = placed,
            pose = pose,
            corner = anchor.corner,
        )
    }

    /**
     * The target's shape, preferring what was measured when it was whole.
     *
     * A clipped reading is never allowed to become the canonical size — that is
     * precisely how the outline used to shrink as a row left the screen.
     */
    private fun geometryFor(
        candidate: ScanCandidate,
        context: ScreenContext,
    ): CanonicalTargetGeometry? {
        val known = knownGeometry
        val sameControl = knownIdentity?.isSameControlAs(candidate.identity) == true

        val clipFrame = context.contentBounds ?: context.viewport
        val unclipped = candidate.reportedBounds.top > clipFrame.top &&
            candidate.reportedBounds.bottom < clipFrame.bottom
        if (unclipped || candidate.knownWhole) {
            // Whole and on screen: this is the measurement to trust, and it
            // replaces whatever was known — a font-size change really does make
            // the control taller.
            return CanonicalTargetGeometry.of(
                candidate.reportedBounds,
                anchorsFor(candidate.reportedBounds, context),
            )
        }

        // Clipped, but this control's real size is already known: keep it.
        if (known != null && sameControl && known.matches(candidate.reportedBounds)) return known

        // Clipped, and nothing known to fall back on.
        return null
    }

    private fun anchorsFor(
        bounds: Bounds,
        context: ScreenContext,
    ): Map<PebbleCorner, androidx.compose.ui.geometry.Offset> {
        val size = context.pebbleSizePx
        val straddle = context.pebbleStraddlePx
        fun offset(left: Int, top: Int) = androidx.compose.ui.geometry.Offset(
            (left - bounds.left).toFloat(),
            (top - bounds.top).toFloat(),
        )
        return mapOf(
            PebbleCorner.TopEnd to offset(bounds.right - size + straddle, bounds.top - straddle),
            PebbleCorner.TopStart to offset(bounds.left - straddle, bounds.top - straddle),
            PebbleCorner.BottomEnd to
                offset(bounds.right - size + straddle, bounds.bottom - size + straddle),
            PebbleCorner.BottomStart to
                offset(bounds.left - straddle, bounds.bottom - size + straddle),
        )
    }

    private fun expectedIdentity(): TargetIdentity? =
        expectedIdentityOf(state) ?: knownIdentity

    private fun expectedIdentityOf(state: PositionalGuideState): TargetIdentity? = when (state) {
        is PositionalGuideState.TargetStable -> state.identity
        is PositionalGuideState.UserScrollingTargetAbove -> state.expectedIdentity
        is PositionalGuideState.UserScrollingTargetBelow -> state.expectedIdentity
        is PositionalGuideState.UserScrollingStopHere -> state.expectedIdentity
        is PositionalGuideState.UserScrollingUncertain -> state.expectedIdentity
        is PositionalGuideState.WaitingForStability -> state.expectedIdentity
        is PositionalGuideState.TargetOccluded -> state.expectedIdentity
        else -> null
    }

    /**
     * The single place the state changes, so the things that must happen on
     * every change happen on every change.
     *
     * The haptic is one of them. It fires when the message becomes "stop here"
     * having been something else, which is the moment worth feeling: the person
     * has arrived. Keying it on the message rather than on the state means
     * holding "stop here" through the checks after a scroll does not buzz
     * again, and neither does a run of readings that all say the same thing.
     */
    private fun transitionTo(next: PositionalGuideState) {
        if (next == state) return
        state = next
        generation++

        val message = output().guidance?.message
        if (message == ScrollGuidance.StopHere && lastGuidanceShown != ScrollGuidance.StopHere) {
            hapticPulses++
        }
        if (message != null) lastGuidanceShown = message
        GuideLog.d("positional") {
            "-> ${next::class.simpleName}${message?.let { " ($it)" } ?: ""} gen=$generation"
        }
    }
}
