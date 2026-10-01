package com.aaravsekhri.dot.guide.engine

import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.matching.TargetMatcher
import com.aaravsekhri.dot.guide.tracking.TargetTracker
import com.aaravsekhri.dot.guide.tracking.TrackerState
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.Insets
import com.aaravsekhri.dot.guide.model.SemanticTargetRegion
import com.aaravsekhri.dot.guide.model.FailureReason
import com.aaravsekhri.dot.guide.model.GuideDefinition
import com.aaravsekhri.dot.guide.model.GuideSessionState
import com.aaravsekhri.dot.guide.model.ClickDescriptor
import com.aaravsekhri.dot.guide.model.GuideStep
import com.aaravsekhri.dot.guide.model.StepCompletionRule
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.RecoveryReason
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.model.StepAction

/**
 * Runs one guide session.
 *
 * The engine only ever moves forward on evidence: either the person activated
 * the control Dot was pointing at, or the screen the next step expects has
 * appeared with its target on it. Time passing is never a reason to advance —
 * someone reading slowly is not someone who has finished.
 *
 * Plain Kotlin over [ScreenSnapshot], so progression, refusal to progress, and
 * recovery are all unit tested without an emulator.
 */
class GuideEngine(
    val definition: GuideDefinition,
    val tracker: TargetTracker = TargetTracker(),
) {
    var state: GuideSessionState = GuideSessionState.Inactive
        private set

    /** Consecutive passes where the current step's target could not be found. */
    private var searchMisses = 0

    /** The page the settled target belongs to, so navigation can be noticed. */
    private var currentPageSignature: String? = null

    /** The step currently being worked on, or null when nothing is running. */
    val currentStep: GuideStep?
        get() = stepIndex?.let(definition.steps::getOrNull)

    /**
     * Which step the engine is on.
     *
     * Every state that belongs to a step has to be listed. Leaving
     * [GuideSessionState.AwaitingDestination] out of it meant the engine
     * considered itself stepless the moment a tap landed, so the page that
     * arrived next was never examined and the guide sat on step one forever.
     */
    private val stepIndex: Int?
        get() = when (val current = state) {
            is GuideSessionState.Searching -> current.stepIndex
            is GuideSessionState.ScrollNeeded -> current.stepIndex
            is GuideSessionState.TargetFound -> current.stepIndex
            is GuideSessionState.WaitingForUser -> current.stepIndex
            is GuideSessionState.AwaitingDestination -> current.stepIndex
            is GuideSessionState.Paused -> current.stepIndex
            is GuideSessionState.Recovery -> current.stepIndex
            else -> null
        }

    // ------------------------------------------------------------ lifecycle

    /** Asked for from the app, before Settings has been opened. */
    fun start() {
        searchMisses = 0
        state = GuideSessionState.Starting(definition.id)
    }

    /** Settings is on screen; begin looking for the first step's target. */
    fun beginSearching() {
        startFreshSearch(stepIndex ?: 0)
    }

    fun pause() {
        val index = stepIndex ?: return
        tracker.pause()
        transitionTo(GuideSessionState.Paused(definition.id, index))
    }

    fun resume() {
        startFreshSearch(stepIndex ?: return)
    }

    fun cancel() {
        state = GuideSessionState.Cancelled(definition.id)
    }

    fun fail(reason: FailureReason) {
        state = GuideSessionState.Failed(definition.id, reason)
    }

    /**
     * Restored after the process was recreated. Deliberately lands in Paused:
     * the screen may have moved on while Dot was gone, and a stale highlight
     * pointing at the wrong control is worse than asking before continuing.
     */
    fun restoreConservatively(stepIndex: Int) {
        val safeIndex = stepIndex.coerceIn(0, definition.stepCount - 1)
        searchMisses = 0
        tracker.reset()
        state = GuideSessionState.Paused(definition.id, safeIndex)
    }

    // ------------------------------------------------------- user reactions

    /** "I don't see that" on the instruction card. */
    fun requestHelp() {
        val index = stepIndex ?: return
        state = GuideSessionState.Recovery(definition.id, index, RecoveryReason.UserAskedForHelp)
    }

    /** "Try again" from the recovery card: look afresh with no assumptions. */
    fun retry() {
        startFreshSearch(stepIndex ?: return)
    }

    /** "Go back a step" from the recovery card. */
    fun goBackAStep() {
        startFreshSearch((stepIndex ?: return) - 1)
    }

    /**
     * The page was rebuilt, or a control's value changed, but the person has
     * not gone anywhere.
     *
     * The region is refreshed and the target re-adopted in place. Nothing is
     * cleared and the generation does not move, so the outline stays exactly
     * where it is and Pebble does not play its entrance again — which is what a
     * single notch on a slider should feel like.
     */
    fun onSameScreenUpdate(region: SemanticTargetRegion) {
        val index = stepIndex ?: return
        val current = state
        val generation = when (current) {
            is GuideSessionState.WaitingForUser -> current.targetGeneration
            is GuideSessionState.TargetFound -> current.targetGeneration
            else -> tracker.generation
        }
        searchMisses = 0
        tracker.rebindTo(region)
        transitionTo(
            GuideSessionState.WaitingForUser(
                definition.id,
                index,
                region,
                region.label,
                generation,
                (current as? GuideSessionState.WaitingForUser)?.adjustmentSeen == true,
            ),
        )
    }

    /**
     * The page has changed underneath the guide.
     *
     * Everything Dot was pointing at belonged to the page that has gone, so it
     * is dropped at once rather than lingering until the next scan happens to
     * notice. The screen goes quiet and the search starts over.
     */
    fun onPageChanged() {
        val index = stepIndex ?: return
        currentPageSignature = null
        tracker.beginTransition()
        searchMisses = 0
        transitionTo(GuideSessionState.Searching(definition.id, index))
    }

    /**
     * The person scrolled.
     *
     * The cue comes down immediately — they are already doing the thing it
     * asked for, and leaving it up would be nagging.
     */
    /**
     * The person scrolled.
     *
     * Nothing about the guide changes here. The cue stays up until the target
     * is actually far enough onto the screen to be worth outlining, which the
     * tracker decides on the next look — dismissing it the moment a finger
     * moves would take it away while they are still on their way to the thing.
     */
    fun onUserScrolled() = Unit

    /** Clears anything settled and goes back to looking, on [index]. */
    private fun startFreshSearch(index: Int) {
        val safeIndex = index.coerceIn(0, definition.stepCount - 1)
        searchMisses = 0
        tracker.reset()
        transitionTo(GuideSessionState.Searching(definition.id, safeIndex))
    }

    /** One place every state change goes through, so tracing is complete. */
    private fun transitionTo(next: GuideSessionState) {
        val previous = state
        state = next
        if (previous != next) {
            GuideLog.d("state") { "${previous.javaClass.simpleName} -> ${next.javaClass.simpleName} $next" }
        }
    }

    /**
     * The person said they are happy with an adjustment. Only meaningful on an
     * [StepAction.Adjust] step, where there is no tap for Dot to observe.
     */
    fun confirmStepDone() {
        val index = stepIndex ?: return
        if (definition.steps.getOrNull(index)?.action != StepAction.Adjust) return
        advanceFrom(index, "the person said the adjustment is done")
    }

    // ------------------------------------------------------ screen reactions

    /**
     * A fresh look at whatever is in front.
     *
     * @param screen the visible hierarchy, already filtered to the active window.
     */
    fun onScreenSnapshot(
        screen: ScreenSnapshot,
        insets: Insets = Insets.NONE,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val index = stepIndex ?: return
        if (state is GuideSessionState.Paused) return

        val step = definition.steps.getOrNull(index) ?: return

        // Waiting for the page a tap should have produced. Nothing else is
        // worth doing until it arrives or is given up on.
        val awaiting = state as? GuideSessionState.AwaitingDestination
        if (awaiting != null) {
            if (!isExpectedPackage(screen.packageName, step)) return
            onScreenWhileAwaiting(awaiting, screen, nowMs)
            return
        }

        // The person went back, or arrived at an earlier page some other way.
        // The step follows the screen rather than the other way round.
        val screenStep = stepForScreen(screen)
        if (screenStep != null && screenStep < index) {
            GuideLog.d("progress") {
                "screen belongs to step $screenStep; returning from step $index"
            }
            startFreshSearch(screenStep)
            return
        }

        // A tap whose click event never arrived, confirmed by the page itself.
        if (advanceOnDestinationWithoutClick(index, screen)) return

        // Leaving the app comes first, and overrides everything below. Dot must
        // not sit on top of an unrelated app holding a card about a Settings
        // screen that is no longer in front.
        if (!isExpectedPackage(screen.packageName, step)) {
            state = GuideSessionState.Recovery(
                definition.id,
                index,
                RecoveryReason.LeftExpectedApp,
            )
            return
        }

        // Someone still in Settings who has said they cannot see the control
        // keeps the recovery card until they choose what to do with it.
        // Quietly replacing it with the instruction they just told us was not
        // working would be a strange thing to do to them.
        val current = state
        if (current is GuideSessionState.Recovery &&
            current.reason == RecoveryReason.UserAskedForHelp
        ) {
            return
        }

        // Navigation the engine has not been told about yet. Noticing it here
        // as well as from window events means a stale outline never survives a
        // page change, whichever signal arrives first.
        // Window identity is deliberately not consulted here. A rebuilt page
        // carries a new window id without the person having moved, and the
        // service's continuity classifier is what decides whether a change is
        // navigation. This only records what was last seen.
        currentPageSignature = screen.pageSignature

        // Someone may have tapped the control while Dot was not looking, or
        // navigated ahead by themselves. If the *next* step's target is on
        // screen, that step is where they actually are.
        val nextIndex = index + 1
        val nextStep = definition.steps.getOrNull(nextIndex)
        if (nextStep != null && isExpectedPackage(screen.packageName, nextStep)) {
            val nextMatch = TargetMatcher.findBest(screen, nextStep.target)
            if (nextMatch != null) {
                val currentMatch = TargetMatcher.findBest(screen, step.target)
                // Only jump when the next target is genuinely the better read
                // of this screen, so a shared word cannot skip a step.
                if (currentMatch == null || nextMatch.score > currentMatch.score) {
                    tracker.reset()
                    searchMisses = 0
                    transitionTo(GuideSessionState.Searching(definition.id, nextIndex))
                    return
                }
            }
        }

        val viewport = screen.viewport(insets)
        val trackerState = tracker.onScreen(screen, step.target, viewport, step.expectedDirection)

        GuideLog.d("tracker") {
            "gen=${tracker.generation} identity=${tracker.trackedIdentity} state=$trackerState"
        }

        applyTrackerState(index, trackerState)
    }

    /**
     * Turns what the tracker sees into what the guide is doing.
     *
     * The tracker answers "where is the target"; this decides what that means
     * for the person. Keeping them apart is what stops target acquisition and
     * guide progress interfering with each other.
     */
    private fun applyTrackerState(index: Int, trackerState: TrackerState) {
        when (trackerState) {
            is TrackerState.TargetVisible -> {
                searchMisses = 0
                val region = trackerState.region
                val current = state
                val alreadyShowing = current is GuideSessionState.WaitingForUser &&
                    current.targetGeneration == tracker.generation

                transitionTo(
                    if (alreadyShowing) {
                        // Same target, new position: follow it, and leave the
                        // entrance animation alone.
                        current.copy(region = region, matchedLabel = region.label)
                    } else {
                        GuideSessionState.TargetFound(
                            definition.id,
                            index,
                            region,
                            region.label,
                            tracker.generation,
                        )
                    },
                )
            }

            is TrackerState.TargetTemporarilyLost -> {
                // Hold what is on screen. A list mid-layout drops nodes for a
                // frame or two and flashing the outline off for that is worse
                // than a moment of staleness.
                searchMisses = 0
            }

            is TrackerState.ScrollNeeded -> {
                searchMisses = 0
                transitionTo(
                    GuideSessionState.ScrollNeeded(definition.id, index, trackerState.direction),
                )
            }

            TrackerState.Searching -> {
                searchMisses++
                transitionTo(
                    if (searchMisses >= MISSES_BEFORE_RECOVERY) {
                        GuideSessionState.Recovery(
                            definition.id,
                            index,
                            RecoveryReason.TargetNotFound,
                        )
                    } else {
                        GuideSessionState.Searching(definition.id, index)
                    },
                )
            }

            TrackerState.Transitioning ->
                transitionTo(GuideSessionState.Searching(definition.id, index))

            TrackerState.GuidePaused -> Unit
        }
    }

    /** Called once the highlight for a region is actually on screen. */
    fun onHighlightPlaced(region: SemanticTargetRegion) {
        val current = state as? GuideSessionState.TargetFound ?: return
        transitionTo(
            GuideSessionState.WaitingForUser(
                definition.id,
                current.stepIndex,
                region,
                current.matchedLabel,
                current.targetGeneration,
            ),
        )
    }

    /**
     * The person tapped something, described at the moment they did.
     *
     * Deliberately takes a [ClickDescriptor] rather than a screen and a node.
     * The old signature could not work: it needed the tapped control and the
     * step's target to both still exist in a screen read *after* the tap, and
     * a tap on a settings row navigates, so neither did. The lookup failed
     * every time and the guide never left its first step.
     *
     * A matching tap does not advance on its own. It moves to
     * [GuideSessionState.AwaitingDestination], and the page that arrives
     * decides whether the step is really done.
     */
    fun onUserActivated(click: ClickDescriptor, nowMs: Long) {
        val index = stepIndex ?: return
        if (state is GuideSessionState.Paused) return
        val step = definition.steps.getOrNull(index) ?: return
        if (step.action != StepAction.Tap) return
        if (!isExpectedPackage(click.packageName, step)) {
            GuideLog.d("progress") { "click ignored: ${click.packageName} is not this guide's app" }
            return
        }

        if (!click.matches(step.target)) {
            GuideLog.d("progress") { "click rejected: $click is not step $index's control" }
            return
        }

        GuideLog.d("progress") {
            "click accepted on step $index; awaiting ${step.expectedDestination?.id ?: "no destination"}"
        }
        tracker.reset()
        currentPageSignature = null

        if (step.expectedDestination == null) {
            // Nowhere to go: the tap itself is the whole of this step.
            advanceFrom(index, "tapped, no destination expected")
            return
        }
        transitionTo(GuideSessionState.AwaitingDestination(definition.id, index, nowMs))
    }

    /**
     * Whether this screen is the page the current step leads to.
     *
     * Asked before any continuity reasoning, because the two can disagree and
     * this one is right. Tapping Display & touch opens a page whose toolbar
     * title is also "Display & touch", so the matcher goes on finding the
     * step's label and the screen looks unchanged — which is how the guide
     * came to sit on step one while the person was two pages further on.
     */
    fun hasArrivedAtDestination(screen: ScreenSnapshot): Boolean {
        val index = stepIndex ?: return false
        val step = definition.steps.getOrNull(index) ?: return false
        if (!isExpectedPackage(screen.packageName, step)) return false
        val destination = step.expectedDestination ?: return false
        if (step.screen?.matches(screen) == true) return false
        return destination.matches(screen)
    }

    /**
     * Whether [screen] satisfies this step's completion rule.
     *
     * Only destination-shaped rules can be answered from a screen; a rule that
     * waits for a tap is answered by [onUserActivated], and one that waits for
     * the person is answered by [confirmStepDone].
     */
    private fun destinationReached(rule: StepCompletionRule, screen: ScreenSnapshot): Boolean =
        when (rule) {
            is StepCompletionRule.DestinationAppeared -> rule.signature.matches(screen)
            is StepCompletionRule.Either -> rule.rules.any { destinationReached(it, screen) }
            StepCompletionRule.ExactTargetClicked, StepCompletionRule.UserConfirms -> false
        }

    /**
     * A page arrived while waiting for one.
     *
     * @return true when this screen completed the step.
     */
    private fun onScreenWhileAwaiting(
        current: GuideSessionState.AwaitingDestination,
        screen: ScreenSnapshot,
        nowMs: Long,
    ): Boolean {
        val step = definition.steps.getOrNull(current.stepIndex) ?: return false
        val destination = step.expectedDestination

        if (destination != null && destination.matches(screen)) {
            advanceFrom(current.stepIndex, "destination ${destination.id} appeared")
            return true
        }

        // The tap went nowhere. Rather than wait indefinitely — or skip ahead
        // on a page nobody asked for — the step is resumed and its control
        // found again.
        if (nowMs - current.since >= DESTINATION_TIMEOUT_MS) {
            GuideLog.d("progress") {
                "destination ${destination?.id} did not appear in ${DESTINATION_TIMEOUT_MS}ms; " +
                    "resuming step ${current.stepIndex}"
            }
            startFreshSearch(current.stepIndex)
        }
        return false
    }

    /**
     * Progression without a click event.
     *
     * Some screens do not emit a usable `TYPE_VIEW_CLICKED`, and Dot cannot
     * require one it may never receive. The bar is deliberately high: the
     * step's own page must have been replaced by the page it leads to. A
     * target merely going missing is not evidence of anything — that is what
     * scrolling looks like — so absence alone can never advance a step.
     */
    private fun advanceOnDestinationWithoutClick(index: Int, screen: ScreenSnapshot): Boolean {
        val step = definition.steps.getOrNull(index) ?: return false
        val destination = step.expectedDestination ?: return false
        if (!destination.matches(screen)) return false
        // The page this step is performed on must be gone. Without this, a
        // destination that shares labels with its origin would advance the
        // guide the moment it started.
        if (step.screen?.matches(screen) == true) return false

        advanceFrom(index, "destination ${destination.id} present without a click event")
        return true
    }

    /**
     * The person went back.
     *
     * Recognised by the page rather than by a gesture: a screen that belongs
     * to an earlier step means they are on that step again, whatever route
     * took them there.
     */
    private fun stepForScreen(screen: ScreenSnapshot): Int? =
        definition.steps.indexOfFirst { it.screen?.matches(screen) == true }
            .takeIf { it >= 0 }

    /**
     * A control was dragged or stepped by the person. Notes it on an adjust
     * step so the card can offer to finish, but never advances on its own —
     * only the person decides when the text is big enough.
     */
    fun onUserAdjusted(screen: ScreenSnapshot) {
        val current = state as? GuideSessionState.WaitingForUser ?: return
        val step = definition.steps.getOrNull(current.stepIndex) ?: return
        if (step.action != StepAction.Adjust) return
        if (!isExpectedPackage(screen.packageName, step)) return
        if (current.adjustmentSeen) return

        state = current.copy(adjustmentSeen = true)
    }

    // ------------------------------------------------------------- internals

    private fun advanceFrom(index: Int, reason: String) {
        GuideLog.d("progress") { "advancing from step $index: $reason" }
        val nextIndex = index + 1
        searchMisses = 0
        // A finished step's outline belongs to the screen being left behind.
        tracker.reset()
        currentPageSignature = null
        transitionTo(
            if (nextIndex >= definition.stepCount) {
                GuideSessionState.Completed(definition.id)
            } else {
                GuideSessionState.Searching(definition.id, nextIndex)
            },
        )
    }

    private fun isExpectedPackage(packageName: String?, step: GuideStep): Boolean =
        packageName != null && packageName in step.expectedPackages

    private fun isDescendantOf(
        screen: ScreenSnapshot,
        node: NodeSnapshot,
        ancestorId: Int,
    ): Boolean {
        var current: NodeSnapshot? = node
        var hops = 0
        while (current != null && hops < MAX_DESCENDANT_HOPS) {
            if (current.id == ancestorId) return true
            current = current.parentId?.let { screen.nodeById(it) }
            hops++
        }
        return false
    }

    companion object {
        /**
         * How long a tap is given to produce the page it should.
         *
         * Long enough for a slow activity launch, short enough that a tap
         * which did nothing does not leave the guide hanging. After it, the
         * step resumes rather than skipping forward.
         */
        const val DESTINATION_TIMEOUT_MS = 4_000L

        /**
         * One empty look is usually a screen mid-transition. Asking for help
         * after a few in a row avoids a recovery card flashing up during
         * every ordinary navigation.
         */
        const val MISSES_BEFORE_RECOVERY = 3
        const val MAX_DESCENDANT_HOPS = 8
    }
}
