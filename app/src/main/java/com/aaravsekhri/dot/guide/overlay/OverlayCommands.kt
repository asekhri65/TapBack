package com.aaravsekhri.dot.guide.overlay

/**
 * What the buttons on the overlay cards ask the service to do.
 *
 * The overlay never touches the engine itself; it reports intent and the
 * service decides, which keeps all session logic in one place.
 */
interface OverlayCommands {
    fun onPause()
    fun onResume()
    fun onExit()
    fun onRetry()
    fun onCannotSeeTarget()
    fun onGoBackAStep()
    fun onConfirmDone()
    fun onReopenSettings()
    fun onFinish()

    /** A general session: the private part is done and Dot may look again. */
    fun onContinueAfterHandoff()

    /** A general session: ask for the next step again. */
    fun onRetryPlanning()

    /** A general session: open Dot so the question can be answered. */
    fun onAnswerInApp()

    /** A lesson's teaching card was continued from: "Let's begin", "Show me how", "Next". */
    fun onTeachingNext(step: Long = 0) = Unit

    /** A general session: "I'm done typing", from the card of [step]. */
    fun onDoneTyping(step: Long = 0) = Unit

    /** A general session: "Try another way". */
    fun onTryAnotherWay() = Unit

    /** A general session: "Keep waiting". */
    fun onKeepWaiting() = Unit

    /** The card was moved to the other end of the screen. */
    fun onCardMoved() = Unit

    /** One of a card's choices, by position, for the question it was offered for. */
    fun onChoice(index: Int, questionId: Int? = null) = Unit

    /** Words typed on the card, for the question they answer. */
    fun onAnswerText(text: String, questionId: Int) = Unit

    /** "Change answer": take the last answer back. */
    fun onChangeAnswer() = Unit

    /** "Try again later": save everything, stop, and take the overlay down. */
    fun onTryLater() = Unit

    /** "Keep trying": a fresh look and a different approach. */
    fun onKeepTrying() = Unit

    /** "Continue" after a lesson: the next lesson in the course. */
    fun onNextLesson() = Unit

    /** Open the course in Dot to choose another lesson. */
    fun onOpenCourse() = Unit

    /** "Check again": look at a screen that could not be read. */
    fun onCheckAgain() = Unit
}
