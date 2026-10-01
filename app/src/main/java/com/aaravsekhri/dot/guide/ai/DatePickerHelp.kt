package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.matching.TextNormalizer
import com.aaravsekhri.dot.guide.model.Bounds

/**
 * A wheel date picker in front — Android's spinner "Set date" dialog, or an
 * app's own drawn the same way — read on the phone, and helped with one
 * action at a time: each wheel, then Set.
 *
 * What a wheel shows is read only to say which wheel is which ("the month
 * wheel"); it is never kept, logged or sent. Nothing about a picker goes to
 * the planner: it is a small, well-known control, and the date on it is
 * somebody's birthday.
 */
object DatePickerHelp {

    enum class WheelKind(val words: String) {
        Month("month"), Day("day"), Year("year"), Unknown("next"),
    }

    data class Wheel(val bounds: Bounds, val kind: WheelKind)

    data class Picker(
        /** Left to right, as they are drawn. */
        val wheels: List<Wheel>,
        val set: CandidateControl?,
        val cancel: CandidateControl?,
        /** Everything the picker occupies: the card stays off all of it. */
        val area: Bounds,
    )

    /** The picker on [o], when one is in front: two or more wheels side by side. */
    fun find(o: Observation): Picker? {
        val wheels = o.candidates.filter { it.role.contains("NumberPicker") || it.role.endsWith("WheelView") }
            .sortedBy { it.bounds.left }
        if (wheels.size < 2) return null
        val shown = wheels.map { w ->
            // The wheel's current value: the box in its middle.
            val value = o.candidates.firstOrNull { c ->
                c.parentId == w.id && c.editable
            }?.label.orEmpty()
            Wheel(w.bounds, kindOf(value))
        }
        val buttons = o.candidates.filter { it.clickable && !it.editable && o.candidates.none { w -> w.id == it.parentId && w in wheels } }
        fun named(vararg words: String) = buttons.firstOrNull { TextNormalizer.normalize(it.naming) in words }
        val set = named("set", "ok", "done", "confirm")
        val cancel = named("cancel")
        val all = wheels.map { it.bounds } + listOfNotNull(set?.bounds, cancel?.bounds)
        val area = Bounds(all.minOf { it.left }, all.minOf { it.top }, all.maxOf { it.right }, all.maxOf { it.bottom })
        return Picker(distinctKinds(shown), set, cancel, area)
    }

    /** Which wheel shows what, from its current value alone. */
    internal fun kindOf(value: String): WheelKind {
        val v = value.trim()
        val n = v.toIntOrNull()
        return when {
            v.isEmpty() -> WheelKind.Unknown
            n == null -> WheelKind.Month
            n > 31 -> WheelKind.Year
            else -> WheelKind.Day
        }
    }

    /** A month shown as a number ("09") reads as a day: the left of two small numbers is taken as the month when no name is shown. */
    private fun distinctKinds(wheels: List<Wheel>): List<Wheel> {
        if (wheels.any { it.kind == WheelKind.Month }) return wheels
        val days = wheels.withIndex().filter { it.value.kind == WheelKind.Day }
        if (days.size < 2) return wheels
        return wheels.mapIndexed { i, w -> if (i == days.first().index) w.copy(kind = WheelKind.Month) else w }
    }

    /** One wheel's step: what to do with it, in one sentence. */
    fun wheelWords(wheel: Wheel, index: Int, count: Int): String {
        val which = when (wheel.kind) {
            WheelKind.Unknown -> when (index) {
                0 -> "the left wheel"
                count - 1 -> "the right wheel"
                else -> "the middle wheel"
            }
            else -> "the ${wheel.kind.words} wheel"
        }
        val until = when (wheel.kind) {
            WheelKind.Month -> "your birth month"
            WheelKind.Day -> "the day you were born"
            WheelKind.Year -> "your birth year"
            WheelKind.Unknown -> "the right value"
        }
        // The card's Done button follows: the clause names this one wheel.
        return if (index == 0) {
            "Swipe $which up or down until it shows $until, then tell me once it does."
        } else {
            "Now swipe $which until it shows $until, then tell me once it does."
        }
    }

    const val WHEEL_DONE = "Done"
}
