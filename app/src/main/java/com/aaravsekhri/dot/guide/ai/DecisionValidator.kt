package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.matching.TextNormalizer
import com.aaravsekhri.dot.guide.model.Bounds

/**
 * A control the guide is willing to point at.
 *
 * Reaching this type is the only way a model's answer becomes something on
 * somebody's screen, and it can only be built by [DecisionValidator] from a
 * reading taken after the answer came back.
 */
data class ValidatedTarget(
    val bounds: Bounds,
    val naming: String,
    val observationId: String,
    val role: String = "",
    val packageName: String = "",
    val windowId: Int = -1,
    /** Information to read — opening hours, an address — not a control to tap. */
    val reading: Boolean = false,
)

/**
 * Checks a decision against the screen as it is now, not as it was.
 *
 * A planning request takes the better part of a second. In that time the
 * person may have scrolled, tapped something, or left the app entirely, and an
 * instruction that was right when it was written is then an instruction to
 * touch whatever has arrived in that place. So nothing is drawn from the
 * observation that prompted the decision: the decision is re-grounded against
 * a fresh reading, and dropped if it cannot be.
 *
 * Matching is semantic. Node ids are positions in a traversal and mean nothing
 * across readings; what carries is what the control says and what kind of
 * thing it is. Two equally good matches are refused outright — a guide that
 * picks one of two identical rows is guessing, and guessing is what puts an
 * outline on the wrong thing.
 */
object DecisionValidator {

    sealed interface Result {
        data class Ready(val target: ValidatedTarget) : Result

        /** The answer was about a screen that has since changed. */
        data object Stale : Result

        /** The control is not on this screen. */
        data object NotFound : Result

        /** More than one control fits. Refused rather than picked between. */
        data object Ambiguous : Result

        /** Found, but not usable: covered, disabled, or off the edge. */
        data class NotActionable(val why: String) : Result

        /**
         * Found, and usable, but with no boundary worth drawing — a card
         * holding its own buttons, a region the size of the screen. Said in
         * words, on purpose.
         */
        data class WordsOnly(val why: String) : Result
    }

    /** A highlight: the control it names, grounded against [live]. */
    fun ground(
        decision: AiDecision.Highlight,
        decidedFrom: Observation,
        live: Observation,
    ): Result = ground(decision.observationId, decision.candidateId, decidedFrom, live)

    /**
     * Any decision that names a control.
     *
     * @param observationId the reading the decision says it was made from.
     * @param candidateId the control it names, in that reading's ids.
     * @param decidedFrom the reading it was given.
     * @param live a reading taken now.
     * @param mustBeEditable true for a typing step: a field that has stopped
     *   being editable — a search box that became a results header — is not
     *   somewhere to tell anybody to type.
     */
    fun ground(
        observationId: String,
        candidateId: String,
        decidedFrom: Observation,
        live: Observation,
        mustBeEditable: Boolean = false,
        /** Information to read: found among the page's readable words as well as its controls. */
        reading: Boolean = false,
    ): Result {
        // Answering about a different reading than the one we sent is either a
        // confused model or a reply that outlived its request.
        if (observationId != decidedFrom.observationId) return Result.Stale

        // A different app, or a different window, is a different screen. Not
        // something to re-ground onto; something to observe again.
        if (live.packageName != decidedFrom.packageName) return Result.Stale
        if (live.windowId != decidedFrom.windowId) return Result.Stale
        if (live.screenGeneration != decidedFrom.screenGeneration) return Result.Stale

        val wanted = decidedFrom.candidate(candidateId) ?: return Result.NotFound
        if (mustBeEditable && !wanted.editable) return Result.NotActionable("it is not somewhere to type")
        val naming = TextNormalizer.normalize(wanted.naming)
        if (naming.isBlank()) return Result.NotFound

        val pool = if (reading) live.candidates + live.readable else live.candidates
        val matches = pool.filter {
            TextNormalizer.normalize(it.naming) == naming && it.role == wanted.role
        }
        if (matches.isEmpty()) return Result.NotFound

        // Two controls with the same words — the plus beside Font size and
        // the plus beside Display size — are told apart only when the page
        // has not changed at all since the decision: every control, every
        // position. Then the one in the same place is the same control. Any
        // movement at all and the refusal stands, because after a scroll
        // "the same place" is a different row.
        val match = matches.singleOrNull()
            ?: matches.takeIf { live.layoutKey == decidedFrom.layoutKey }
                ?.singleOrNull { it.bounds == wanted.bounds }
            ?: return Result.Ambiguous

        if (!match.enabled) return Result.NotActionable("it is greyed out")
        if (mustBeEditable && !match.editable) return Result.NotActionable("it is not somewhere to type")
        if (match.bounds.isEmpty) return Result.NotActionable("it has no size")
        if (match.bounds.right < match.bounds.left || match.bounds.bottom < match.bounds.top) {
            return Result.NotActionable("it is off the screen")
        }
        val onScreen = match.bounds.left < live.screenWidth && match.bounds.top < live.screenHeight &&
            match.bounds.right > 0 && match.bounds.bottom > 0
        if (!onScreen) return Result.NotActionable("it is off the screen")

        return ready(match, live, reading)
    }

    /**
     * Finds an already-validated target again, on the same page, after the
     * page has moved.
     *
     * The same rules as [ground] — one match by wording and kind, in the same
     * app and window, enabled and on screen — without asking anybody anything.
     * A scroll that leaves the target on the page does not need a new
     * decision; it needs the target found where it now is. Anything this
     * cannot find unambiguously goes back to the model.
     */
    fun relocate(target: ValidatedTarget, live: Observation): Result {
        if (live.packageName != target.packageName || live.windowId != target.windowId) {
            return Result.Stale
        }
        val naming = TextNormalizer.normalize(target.naming)
        if (naming.isBlank()) return Result.NotFound
        val pool = if (target.reading) live.candidates + live.readable else live.candidates
        val matches = pool.filter {
            TextNormalizer.normalize(it.naming) == naming && it.role == target.role
        }
        if (matches.isEmpty()) return Result.NotFound
        if (matches.size > 1) return Result.Ambiguous
        val match = matches.single()
        if (!match.enabled) return Result.NotActionable("it is greyed out")
        val onScreen = !match.bounds.isEmpty && match.bounds.top < live.screenHeight && match.bounds.bottom > 0
        if (!onScreen) return Result.NotActionable("it is off the screen")
        return ready(match, live, target.reading)
    }

    private fun ready(match: CandidateControl, live: Observation, reading: Boolean = false): Result {
        // Information is outlined round its own words, never round a panel.
        if (!reading) match.wordsOnly?.let { return Result.WordsOnly(it) }
        return Result.Ready(
            ValidatedTarget(
                bounds = match.drawBounds,
                naming = match.naming,
                observationId = live.observationId,
                role = match.role,
                packageName = live.packageName,
                windowId = live.windowId,
                reading = reading,
            ),
        )
    }
}
