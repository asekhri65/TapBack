package com.aaravsekhri.dot.guide.overlay

import androidx.compose.ui.geometry.Rect
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry
import com.aaravsekhri.dot.guide.overlay.PebbleCorner
import com.aaravsekhri.dot.guide.motion.TargetPose

/**
 * Exactly what should be on screen, as plain data.
 *
 * Every overlay layer appears here or it is not shown. Building this from the
 * guide state, comparing it with what is already up, and applying the
 * difference is what replaced a dozen scattered `show()` and `hide()` calls —
 * which is why overlays used to appear and vanish at slightly different moments
 * depending on which branch of the renderer ran.
 *
 * Being a data class matters: two identical scenes compare equal, so a render
 * that changes nothing touches no windows at all.
 */
internal data class OverlayScene(
    val highlight: HighlightLayer? = null,
    val guidance: GuidanceLayer? = null,
    val card: CardLayer? = null,
)

/**
 * The outline and its Pebble, as a fixed shape and a position.
 *
 * Only [pose] may change while the same control is being pointed at. The
 * rectangles are derived, so there is no way for a scroll, a clamp or a
 * reconciliation to alter the target's width or height — which is what used to
 * make a row sliding off the top appear to shrink, and a row near the card
 * appear to compress.
 */
internal data class HighlightLayer(
    val canonical: CanonicalTargetGeometry,
    val pose: TargetPose,
    /** Resolved by the anchor resolver, so Pebble is always somewhere visible. */
    val pebble: Bounds,
    val corner: PebbleCorner,
    /** Changes only for a genuinely new target, keying Pebble's entrance. */
    val appearanceKey: Int,
    /**
     * The scrolling area whose edge cuts the outline off.
     *
     * A list sits below a toolbar, so a row leaving the top of the list is
     * gone long before it reaches the top of the screen. Without this the
     * outline carried on over the toolbar, which reads as the highlight
     * escaping its list.
     */
    val clip: Bounds? = null,
) {
    /**
     * The target's true rectangle.
     *
     * Always the full canonical size. Never its intersection with the
     * viewport: the canvas clips the pixels that fall outside, and the
     * rectangle itself keeps the dimensions it was measured with.
     */
    val outline: Rect get() = canonical.fullRect(pose)

    val pebbleRect: Rect get() = Rect(
        pebble.left.toFloat(),
        pebble.top.toFloat(),
        pebble.right.toFloat(),
        pebble.bottom.toFloat(),
    )

    /** The union of everything Dot draws here, for masking visual matching. */
    fun coveredBounds(): Bounds {
        val o = outline
        return Bounds(
            left = minOf(o.left.toInt(), pebble.left),
            top = minOf(o.top.toInt(), pebble.top),
            right = maxOf(o.right.toInt(), pebble.right),
            bottom = maxOf(o.bottom.toInt(), pebble.bottom),
        )
    }
}

/** The centred "scroll this way" cue. */
internal data class GuidanceLayer(
    val message: com.aaravsekhri.dot.guide.positional.ScrollGuidance,
    /** True only while the page is moving. See the output's own note. */
    val animate: Boolean,
    /** Which edge to sit on — always the one away from the target. */
    val atBottom: Boolean,
)

/**
 * The instruction card: only what it says.
 *
 * Where it goes is not part of the scene. It is decided by one policy
 * ([CardPlacement]) from the card's measured size, the screen, the keyboard
 * and what it must stay clear of — the same for every kind of card.
 */
internal data class CardLayer(
    val content: CardContent,
)

/** What the card says. Data only, so scenes can be compared. */
internal sealed interface CardContent {

    data class Step(
        val stepLabel: String,
        val instruction: String,
        val doneLabel: String?,
        /**
         * The quiet link's wording. Null keeps the tutorial's own ("I don't
         * see that"); a general session says "That didn't work", which is
         * the thing it needs to hear.
         */
        val cannotSeeLabel: String? = null,
        val showCannotSee: Boolean = true,
        /** What the wide button does, when it is not the tutorial's "done". */
        val doneAction: StatusAction? = null,
        /** The movement to demonstrate beside the words, if any. */
        val gesture: com.aaravsekhri.dot.guide.ai.Gesture? = null,
        /** Offer "Move": the card is over somebody else's app and may be in the way. */
        val movable: Boolean = false,
        /**
         * Set while Dot is waiting on something rather than instructing:
         * Pebble animates in the card, quietly, in the way that fits what it
         * is waiting for. Null for an instruction.
         */
        val waiting: WaitingKind? = null,
        /** The done action as a small link rather than the wide button: a fallback, not the way forward. */
        val quietDone: Boolean = false,
        /** One more small link — "Change answer", "Try again" — and what it does. */
        val linkLabel: String? = null,
        val linkAction: StatusAction? = null,
        /** Where Pebble and the eye go on this card. */
        val emphasis: CardEmphasis = CardEmphasis.Card,
        /**
         * Which step of a general session this card belongs to. Rises with
         * every new step, including one worded exactly like the last, so an
         * action from a card that has gone is recognised, and what the card
         * keeps clear of or where it was moved belongs to this step only.
         */
        val step: Long = 0,
    ) : CardContent

    data class Status(
        val message: String,
        val actionLabel: String?,
        val action: StatusAction?,
        val secondaryLabel: String?,
        val secondaryAction: StatusAction?,
        /** The primary action is the one Dot recommends: Pebble perches on it. */
        val preferred: Boolean = false,
        /** A pause, a failure, a way back: Pebble is calm and reassuring. */
        val reassuring: Boolean = false,
    ) : CardContent

    /** A question with its answers as buttons, chosen on the card. */
    data class Choices(
        val question: String,
        val options: List<String>,
        /** Which question this is: an answer is only ever taken for its own question. */
        val questionId: Int = 0,
    ) : CardContent

    /**
     * A question answered in the person's own words, typed on the card. The
     * card's window takes the keyboard only while this is up.
     */
    data class TextQuestion(
        val question: String,
        val questionId: Int,
        /** Answers that need no typing — "I'm not sure" — as buttons under the box. */
        val quickAnswers: List<String> = emptyList(),
        /** Said above the box when the answer is personal: where it goes, and the other way. */
        val note: String? = null,
    ) : CardContent

    data class Recovery(
        val title: String,
        val body: String,
    ) : CardContent

    /**
     * Something finished: a task, a lesson, a whole course. Pebble, pleased,
     * with what was learned, honest progress, one clear way on and a quiet
     * second. [eventId] names the completion, so it is celebrated once.
     */
    data class Celebration(
        val headline: String,
        val detail: String,
        val progress: String?,
        /** 0..1 for the small progress bar; null for none. */
        val fraction: Float?,
        val primaryLabel: String,
        val primaryAction: StatusAction,
        val secondaryLabel: String?,
        val secondaryAction: StatusAction?,
        val fuller: Boolean,
        val eventId: Long,
        /** False once this completion has been celebrated: the still pose from then on. */
        val animate: Boolean,
    ) : CardContent
}

/** Which command a status card's buttons invoke. */
internal enum class StatusAction {
    Pause,
    Resume,
    Exit,
    Finish,
    ReopenSettings,

    /** A general session: the person has finished the private part themselves. */
    ContinueAfterHandoff,

    /** A general session: the planning request failed and may be worth another go. */
    RetryPlanning,

    /** A general session: Dot needs an answer, which is typed in the app. */
    AnswerInApp,

    /** A general session: the person has finished typing. */
    DoneTyping,
    TeachingNext,

    /** A general session: stuck or blocked, and asking for a different approach. */
    TryAnotherWay,

    /** A general session: a bounded wait ran out, and the person wants more. */
    KeepWaiting,

    /** A general session: take back the last answer and ask again. */
    ChangeAnswer,

    /** Stop here, keep everything, and carry on another time. */
    TryLater,

    /** Look again and try something different — not the same thing again. */
    KeepTrying,

    /** A lesson finished: go on to the next one. */
    NextLesson,

    /** Open the course in Dot, to pick another lesson. */
    OpenCourse,

    /** Look at a screen that could not be read, again. */
    CheckAgain,
}

/**
 * What Dot is waiting for, which decides how Pebble waits.
 *
 * Distinct because they are different situations to be in: Dot working
 * something out, Dot reading the screen, something outside Dot taking its
 * time, and the page not holding still long enough to read.
 */
enum class WaitingKind {
    /** Reading the screen. */
    Looking,

    /** A planning request is out. */
    Thinking,

    /** An app installing, a page loading: not Dot's doing. */
    External,

    /** The page keeps moving; Dot asks for a pause. Pebble keeps still too. */
    HoldStill,
}
