package com.aaravsekhri.dot.guide.positional

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry
import com.aaravsekhri.dot.guide.motion.TargetPose
import com.aaravsekhri.dot.guide.overlay.PebbleCorner

/**
 * What the positional overlay is doing, as one value.
 *
 * Everything drawn on top of another app comes from this. Previously the
 * decision was spread across accessibility callbacks, delayed coroutines,
 * animation completions and scan results, each free to call show or hide, and
 * the visible result was whichever of them ran last. That is what the
 * flickering was: not an unstable target, but four owners disagreeing about a
 * target that was perfectly stable.
 */
sealed interface PositionalGuideState {

    /**
     * Looking for the target.
     *
     * No outline, and a directional cue when the guide knows which way the
     * target lies. A person who cannot see the thing they are meant to tap
     * needs to be told where it is, and "nothing on screen" is not that.
     */
    data class Searching(val direction: ScrollDirection? = null) : PositionalGuideState

    /** Settled, verified, and drawn. The only state that shows an outline. */
    data class TargetStable(
        val identity: TargetIdentity,
        val geometry: CanonicalTargetGeometry,
        val pose: TargetPose,
        val corner: PebbleCorner,
    ) : PositionalGuideState

    /**
     * The page is moving and the target is below the part of the list in use.
     *
     * Proven by a reading of the control, never by which way the page went.
     */
    /**
     * The identity is null until a target has been settled on for the first
     * time. Somebody scrolling down a long list towards a row Dot has not
     * outlined yet still deserves to be told when they have reached it, and the
     * step's own wording is enough to find it.
     */
    data class UserScrollingTargetBelow(
        val expectedIdentity: TargetIdentity?,
    ) : PositionalGuideState

    /** The page is moving and the target is above the part of the list in use. */
    data class UserScrollingTargetAbove(
        val expectedIdentity: TargetIdentity?,
    ) : PositionalGuideState

    /**
     * The page is moving and the target is right there.
     *
     * No outline: it would be drawn from a position already out of date, and a
     * rectangle a row out of place is not an imprecise highlight but a clear
     * instruction to tap the wrong thing. The card says to stop, and the
     * outline returns the moment the page does.
     */
    data class UserScrollingStopHere(
        val expectedIdentity: TargetIdentity?,
    ) : PositionalGuideState

    /**
     * The page is moving and the target cannot be placed.
     *
     * The honest state. An arrow here would be a guess, and a guess sends
     * somebody the wrong way down a list they are already lost in.
     */
    data class UserScrollingUncertain(
        val expectedIdentity: TargetIdentity?,
    ) : PositionalGuideState

    /**
     * The page has stopped; the target is being checked.
     *
     * Whatever the card was saying, it keeps saying. Taking it down the instant
     * a finger lifts and putting the outline up a moment later is two changes
     * where the person should see one, and the gap between them reads as the
     * guide losing its place.
     */
    data class WaitingForStability(
        /**
         * Null while the guide is still on its way to a target it has never
         * outlined. Then any verified candidate for the step will do; once a
         * target has been settled on, only that one will.
         */
        val expectedIdentity: TargetIdentity?,
        /** The message to hold on screen meanwhile. */
        val holding: ScrollGuidance?,
        val confirmations: Int = 0,
        val lastSeen: Bounds? = null,
        /** How many readings have been asked for and not settled anything. */
        val attempts: Int = 0,
    ) : PositionalGuideState

    /** Found, verified, and behind Dot's own card. Identity is kept; nothing is drawn. */
    data class TargetOccluded(
        val expectedIdentity: TargetIdentity,
    ) : PositionalGuideState

    /** The screen is being replaced. Everything positional is down. */
    data object Transitioning : PositionalGuideState

    /** The person paused the guide. */
    data object Paused : PositionalGuideState
}

/** Exactly what should be on screen, derived from one state. */
data class PositionalOutput(
    val outline: OutlineOutput?,
    /** The compact card shown while the page is moving, or none. */
    val guidance: GuidanceOutput?,
    /** Rises whenever the outline is a genuinely new target, keying the pop-in. */
    val appearanceKey: Int,
    /**
     * Rises once per haptic the renderer should fire.
     *
     * A count rather than a flag, so a pulse cannot be missed by a renderer
     * that happened not to read the output on the frame it was requested, and
     * cannot be fired twice by one that read it on two.
     */
    val hapticPulses: Int = 0,
)

/** A drawable outline: a fixed shape, a position, and a visible Pebble. */
data class OutlineOutput(
    val geometry: CanonicalTargetGeometry,
    val pose: TargetPose,
    val pebble: Bounds,
    val corner: PebbleCorner,
    /**
     * The scrolling area the outline's pixels are cut to.
     *
     * Not a smaller target: the rectangle keeps the size the control actually
     * is and the canvas stops drawing it where the list stops. That is the
     * difference between a row sliding under a toolbar and a row that appears
     * to shrink as it goes.
     */
    val clip: Bounds? = null,
)

/** The compact guidance card, and which edge it should sit on. */
data class GuidanceOutput(
    val message: ScrollGuidance,
    /**
     * Whether the card's arrow should be moving.
     *
     * Only while the page is. A drifting arrow reinforces the direction of a
     * gesture somebody is making; over a page standing still it is decoration
     * that redraws a full-screen layer sixty times a second for ever, which
     * measured at two thirds of a processor core on a screen nobody was
     * touching.
     */
    val animate: Boolean = false,
    /**
     * True to put the card along the bottom.
     *
     * Chosen opposite the target wherever the target's position is known, so
     * the thing being talked about is never underneath the thing talking.
     */
    val atBottom: Boolean,
)
