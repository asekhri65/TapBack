package com.aaravsekhri.dot.guide.overlay

import com.aaravsekhri.dot.guide.model.Bounds

/**
 * How much of the card is drawn.
 *
 * Both show the whole instruction at the same text size. The compact one
 * gives up decoration and spacing — the small heading, the gesture drawing,
 * generous padding — so it fits where the full one does not. It never gives
 * up words, the step's own button, Move or Pause.
 */
enum class CardDensity {
    Full,
    Compact,

    /**
     * One short band for the tightest spaces — between a box being typed in
     * and the keyboard, above a dialog. Tried before the card is ever put
     * over what the step needs; see [StripStepCard].
     */
    Strip,
}

/**
 * Which edge a card is held against, so it stays against it when the space
 * or its own height changes — a card at the bottom rises with the keyboard,
 * and a taller card at the bottom grows upwards rather than off the screen.
 */
enum class CardEdge { Top, Bottom, Between }

/**
 * Who asked for part of the screen to be kept clear of the card. Each keeps
 * its own regions, so one never overwrites another; [essential] ones must
 * stay usable, the others are kept in view when that costs nothing.
 */
enum class ClearSource(val essential: Boolean) {
    /** The control the step is about, outlined or not. */
    Target(true),

    /** A gesture's path, with room for the finger that follows it. */
    Gesture(true),

    /** Controls the step's words name, on a card that points at nothing. */
    Words(true),

    /** What helps: a box's label, the form's Next, the app's message. */
    Helpful(false),
}

/**
 * The current step's regions, by who asked for them. Each source replaces
 * only its own; an empty list clears only its own; a new step clears all.
 * Pure, so the rules are tested rather than trusted.
 */
class KeepClear {
    private val sets = linkedMapOf<ClearSource, List<Bounds>>()

    /** @return true when anything changed. */
    fun set(source: ClearSource, regions: List<Bounds>): Boolean {
        val clean = regions.filter { !it.isEmpty }
        if (sets[source].orEmpty() == clean) return false
        if (clean.isEmpty()) sets.remove(source) else sets[source] = clean
        return true
    }

    fun clear() = sets.clear()

    /** Everything that must stay usable, merged. */
    val essential: List<Bounds> get() = sets.filterKeys { it.essential }.values.flatten()

    /** What would help to see, merged. */
    val helpful: List<Bounds> get() = sets.filterKeys { !it.essential }.values.flatten()
}

/** Where the card's top edge goes, in screen pixels, how much of it is drawn, and what it is held against. */
data class CardSpot(val density: CardDensity, val top: Int, val edge: CardEdge = CardEdge.Between)

/**
 * One decision about where the card goes.
 *
 * @property clear false when there was nowhere the card could go without
 *   covering part of what the step needs. The card then says so, with
 *   something concrete to do about it; it never pretends it fitted.
 */
data class CardPlacementResult(
    val spot: CardSpot,
    val clear: Boolean,
    val reason: String,
)

/**
 * Where the instruction card goes: the one policy every card uses.
 *
 * Pure, so it can be tested without a device. Everything is in one coordinate
 * system — raw screen pixels, the same as accessibility bounds — and the card
 * is described by the heights it actually measured at, at its real width and
 * text size, not by a band assumed to be big enough.
 *
 * What must stay usable ([avoid]) is never covered while anywhere else will
 * do: the control the step is about, a gesture's path with room for the
 * finger. What would help to see ([prefer]) — the box's label, the form's
 * Next, the app's message — is kept clear when that costs nothing more.
 *
 * The rules, in order:
 * 1. Where the person moved the card, while that covers nothing that must
 *    stay usable. A Move never wins over the thing the step needs.
 * 2. Where it already is, while that is still clear — whichever density it
 *    is at. It does not hop because something else would also do, and it
 *    does not swap between full and compact while either is fine. A small
 *    [hysteresis] keeps a target that moved a few pixels from moving it; the
 *    control itself is never covered for it.
 * 3. The full card at an edge, the one away from what it must not cover
 *    first.
 * 4. The compact card, then the strip, at an edge.
 * 5. Only then a gap between controls, full, compact and then the strip.
 * 6. If nothing is clear, the spot that covers least of what must stay
 *    usable, marked not clear, so the card can ask for a concrete change
 *    rather than sit over the button silently.
 *
 * The same inputs always give the same answer, and the heights it is given
 * do not depend on which density is showing (both are measured every time),
 * so a placement cannot feed back into itself and oscillate.
 */
object CardPlacement {

    /**
     * @param area where the card may go: the screen less the system bars, the
     *   keyboard, and a small gap from each edge.
     * @param heights the card's measured height at each density.
     * @param avoid what must stay usable, already widened for a finger.
     * @param current where it is now, if anywhere.
     * @param pinned where the person put it with Move, for this step.
     * @param minReachable the least of an oversized control that must stay
     *   uncovered — its middle, at least this tall — for it to count as
     *   reachable. A control taller than the space beside any card (a drawing
     *   pad, a map) cannot be kept wholly clear; its middle can.
     * @param prefer what would help to see, kept clear when possible.
     * @param hysteresis how far into [avoid]'s finger margin a card already
     *   in place may reach before it moves. Never more than that margin.
     */
    fun choose(
        area: Bounds,
        heights: Map<CardDensity, Int>,
        avoid: List<Bounds>,
        current: CardSpot?,
        pinned: CardSpot? = null,
        minReachable: Int = 0,
        prefer: List<Bounds> = emptyList(),
        hysteresis: Int = 0,
    ): CardPlacementResult {
        val regions = avoid.filter { !it.isEmpty && it.intersects(area) }
        val helpful = prefer.filter { !it.isEmpty && it.intersects(area) }
        fun h(density: CardDensity) = heights[density]?.takeIf { it > 0 }
        // Whether a region is too tall to keep wholly clear is decided once,
        // from the smallest card: otherwise a smaller card was held to a
        // stricter rule than a bigger one, "fitted" nowhere, and placement
        // swung between them on every measure.
        val smallest = heights.values.filter { it > 0 }.minOrNull() ?: 0
        val largeAbove = area.height - smallest
        fun rule(density: CardDensity, slack: Int = 0) = h(density)?.let { Rule(area, it, regions.map { r -> r.deflate(slack) }, minReachable, largeAbove) }

        // 1. Where the person put it, if that leaves the step usable.
        var movedAside = false
        if (pinned != null) {
            val density = if (h(pinned.density) != null) pinned.density else CardDensity.entries.first { h(it) != null }
            val spot = resolve(pinned.copy(density = density), area, h(density)!!)
            if (rule(density)!!.fits(spot.top)) return CardPlacementResult(spot, true, "moved by the person")
            movedAside = true
        }
        val note = if (movedAside) " (not where it was moved: that covered what the step needs)" else ""

        // 2. Where it is, while that still works.
        if (current != null && !movedAside) {
            h(current.density)?.let { height ->
                val spot = resolve(current, area, height)
                val r = rule(current.density, hysteresis)!!
                if (r.fits(spot.top)) return CardPlacementResult(spot, true, "still clear${edge(r, spot.top)}")
            }
        }

        // 3–5. Edges at full size, edges compact, then gaps — each group
        // trying first the places that also leave the helpful things in view.
        val preferTop = preferTop(area, regions.ifEmpty { helpful })
        val groups = buildList {
            for (density in CardDensity.entries) {
                val height = h(density) ?: continue
                add(density to edges(density, area, height, preferTop))
            }
            for (density in CardDensity.entries) {
                val height = h(density) ?: continue
                add(density to gaps(density, area, height, regions + helpful, if (preferTop) area.top else area.bottom - height))
            }
        }
        for ((density, spots) in groups) {
            val height = h(density)!!
            val r = rule(density)!!
            val fitting = spots.filter { r.fits(it.top) }
            val best = fitting.firstOrNull { spot -> helpful.none { it.intersects(card(area, spot.top, height)) } }
                ?: fitting.firstOrNull()
                ?: continue
            val why = when (best.edge) {
                CardEdge.Top -> "top edge"
                CardEdge.Bottom -> "bottom edge"
                CardEdge.Between -> "between controls"
            }
            return CardPlacementResult(best, true, "$why, ${density.name.lowercase()}${edge(r, best.top)}$note")
        }

        // 6. Nothing is clear. The compact card, where it covers the least,
        // and said plainly.
        val density = CardDensity.entries.lastOrNull { h(it) != null } ?: CardDensity.Full
        val height = h(density) ?: 0
        val options = edges(density, area, height, preferTop) + gaps(density, area, height, regions, area.top)
        val least = options.minByOrNull { covered(it.top, height, area, regions) } ?: CardSpot(density, area.top, CardEdge.Top)
        return CardPlacementResult(least, false, "no clear spot$note")
    }

    /** A spot's top, for the card as tall as it is now and the space as it is now. */
    fun resolve(spot: CardSpot, area: Bounds, height: Int): CardSpot {
        val top = when (spot.edge) {
            CardEdge.Top -> area.top
            CardEdge.Bottom -> area.bottom - height
            CardEdge.Between -> spot.top
        }.coerceIn(area.top, (area.bottom - height).coerceAtLeast(area.top))
        return spot.copy(top = top)
    }

    /** Whether a card at [spot] of [height] covers any of [regions]: for checking where it really ended up. */
    fun covers(area: Bounds, spot: Bounds, regions: List<Bounds>, minReachable: Int = 0): Boolean {
        val r = Rule(Bounds(area.left, minOf(area.top, spot.top), area.right, maxOf(area.bottom, spot.bottom)), spot.height, regions, minReachable)
        return !r.fits(spot.top)
    }

    private fun edge(rule: Rule, top: Int) = if (rule.coversAnEdge(top)) " (the edge of a large control)" else ""

    /**
     * Whether a card of height [h] at a given top leaves everything reachable.
     *
     * An ordinary control must be wholly clear. One too tall to be — taller
     * than the room beside any card this size — may lose an end to the card,
     * but never its middle, and never so much that less than
     * [minReachable] of it is left to touch.
     */
    private class Rule(val area: Bounds, val h: Int, val regions: List<Bounds>, val minReachable: Int, largeAbove: Int? = null) {
        private val room = largeAbove ?: (area.height - h)

        fun fits(top: Int): Boolean =
            top >= area.top && top + h <= area.bottom && regions.all { acceptable(it, top) }

        fun coversAnEdge(top: Int): Boolean = regions.any { overlaps(it, top) }

        private fun overlaps(r: Bounds, top: Int) =
            r.left < area.right && area.left < r.right && r.top < top + h && top < r.bottom

        private fun acceptable(r: Bounds, top: Int): Boolean {
            if (!overlaps(r, top)) return true
            if (r.height <= room) return false
            val middle = (r.top + r.bottom) / 2
            if (middle in top until top + h) return false
            val left = maxOf(top - r.top, r.bottom - (top + h))
            return left >= minReachable
        }
    }

    /**
     * Away from what it must not cover: a control in the lower half sends the
     * card to the top, one in the upper half to the bottom. With nothing to
     * avoid, the bottom — where cards have always started.
     */
    private fun preferTop(area: Bounds, regions: List<Bounds>): Boolean =
        regions.isNotEmpty() && regions.map { (it.top + it.bottom) / 2 }.average() > (area.top + area.bottom) / 2.0

    private fun edges(density: CardDensity, area: Bounds, h: Int, preferTop: Boolean): List<CardSpot> {
        val top = CardSpot(density, area.top, CardEdge.Top)
        val bottom = CardSpot(density, (area.bottom - h).coerceAtLeast(area.top), CardEdge.Bottom)
        return if (preferTop) listOf(top, bottom) else listOf(bottom, top)
    }

    /** Just below and just above each thing, nearest the preferred edge first. */
    private fun gaps(density: CardDensity, area: Bounds, h: Int, regions: List<Bounds>, preferredTop: Int): List<CardSpot> =
        regions.flatMap { listOf(it.bottom, it.top - h) }
            .filter { it >= area.top && it + h <= area.bottom }
            .distinct()
            .sortedBy { kotlin.math.abs(it - preferredTop) }
            .map { CardSpot(density, it, CardEdge.Between) }

    private fun card(area: Bounds, top: Int, h: Int) = Bounds(area.left, top, area.right, top + h)

    /** How much of what must stay visible a card at [top] would cover. */
    private fun covered(top: Int, h: Int, area: Bounds, regions: List<Bounds>): Long {
        val card = card(area, top, h)
        return regions.sumOf { r ->
            val w = (minOf(r.right, card.right) - maxOf(r.left, card.left)).coerceAtLeast(0)
            val v = (minOf(r.bottom, card.bottom) - maxOf(r.top, card.top)).coerceAtLeast(0)
            w.toLong() * v
        }
    }

    private fun Bounds.deflate(by: Int): Bounds =
        if (by <= 0 || width <= 2 * by || height <= 2 * by) this else Bounds(left + by, top + by, right - by, bottom - by)
}
