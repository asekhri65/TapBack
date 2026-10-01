package com.aaravsekhri.dot.guide.ai

/**
 * The physical movement an instruction asks for, as data.
 *
 * Every animated card is drawn from one of these, and every one of these is
 * built from the action contract — a scroll's `reveal`, a navigation's
 * destination, the phone's navigation setting — never from the wording.
 * That is what keeps the picture, the sentence and the gesture the person
 * actually needs in agreement.
 */
sealed interface Gesture {

    /**
     * Drag the page with one finger.
     *
     * [finger] is the way the finger travels, and the page travels with it:
     * to see more below, the finger (and the page) go up.
     */
    data class Slide(val finger: FingerDirection) : Gesture

    /** Go to the Home screen, the way this phone does it. */
    data class Home(val navigation: NavigationStyle) : Gesture

    /** Go back one screen, the way this phone does it. */
    data class Back(val navigation: NavigationStyle) : Gesture

    /** Show recent apps, the way this phone does it: with gestures, up from the bottom edge and hold. */
    data class Recents(val navigation: NavigationStyle) : Gesture
}

enum class FingerDirection { Up, Down, Left, Right }

/** Revealing content in one direction takes the finger the other way. */
fun Reveal.finger(): FingerDirection = when (this) {
    Reveal.Below -> FingerDirection.Up
    Reveal.Above -> FingerDirection.Down
    Reveal.Right -> FingerDirection.Left
    Reveal.Left -> FingerDirection.Right
}

/**
 * One frame of a repeating demonstration.
 *
 * @param progress how far along the movement, 0 at the start, 1 at the end.
 * @param alpha how visible the fingertip is.
 * @param holding true while the finger stays down at the end, for a gesture
 *   that needs a hold before it is let go.
 */
data class GestureFrame(val progress: Float, val alpha: Float, val holding: Boolean = false)

/**
 * The timing of every gesture demonstration.
 *
 * Appear, travel the way the gesture goes, fade out where it ended, reset
 * while invisible, pause, repeat. It never travels backwards while it can be
 * seen: a visible return stroke would demonstrate the opposite gesture.
 */
object GestureMotion {

    /** One whole cycle, including the pause. Calm rather than urgent. */
    const val CYCLE_MS = 2_400

    private const val APPEAR_END = 0.08f
    private const val MOVE_END = 0.58f
    private const val FADE_END = 0.76f

    /** A hold longer than this is not demonstrated as one; nobody waits that long. */
    private const val MAX_HOLD_MS = 1_500L

    /** The whole cycle for a gesture held for [holdMs] at its end: the hold is added, nothing is rushed. */
    fun cycleMs(holdMs: Long): Int = CYCLE_MS + holdMs.coerceIn(0, MAX_HOLD_MS).toInt()

    /**
     * @param t position in the cycle, 0..1.
     * @param holdMs how long the finger stays down at the end before letting
     *   go. The travel keeps its own pace; the hold is extra time, spent at
     *   the end with the fingertip still showing, before it fades.
     */
    fun frame(t: Float, holdMs: Long = 0): GestureFrame {
        val c = t.coerceIn(0f, 1f)
        val hold = holdMs.coerceIn(0, MAX_HOLD_MS)
        if (hold > 0) {
            // In the longer cycle's own time: the same appear and travel, then
            // the hold, then the same fade, then the same unseen pause.
            val total = cycleMs(hold).toFloat()
            val ms = c * total
            val appear = APPEAR_END * CYCLE_MS
            val move = MOVE_END * CYCLE_MS
            val held = move + hold
            val fade = held + (FADE_END - MOVE_END) * CYCLE_MS
            return when {
                ms < appear -> GestureFrame(0f, ms / appear)
                ms < move -> GestureFrame(ease((ms - appear) / (move - appear)), 1f)
                ms < held -> GestureFrame(1f, 1f, holding = true)
                ms < fade -> GestureFrame(1f, 1f - (ms - held) / (fade - held))
                else -> GestureFrame(0f, 0f)
            }
        }
        return when {
            c < APPEAR_END -> GestureFrame(0f, c / APPEAR_END)
            c < MOVE_END -> GestureFrame(ease((c - APPEAR_END) / (MOVE_END - APPEAR_END)), 1f)
            c < FADE_END -> GestureFrame(1f, 1f - (c - MOVE_END) / (FADE_END - MOVE_END))
            // Invisible: back at the start, where it will reappear.
            else -> GestureFrame(0f, 0f)
        }
    }

    /** Gentle in and out; no overshoot, so nothing bounces. */
    private fun ease(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }
}
