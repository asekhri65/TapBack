package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.matching.TextNormalizer

/**
 * Whether a field now holds the words a typing step asked for.
 *
 * The evidence a search step is done, so nobody has to tell Dot. Deliberately
 * strict about partial input — "Face" is not "Facebook", and a pause after
 * four letters must not move anything on — and forgiving about the things a
 * keyboard does by itself: capitals, a trailing space or full stop, an extra
 * word the person chose to add.
 */
object TypedText {

    fun matches(fieldText: String, expected: String): Boolean {
        val want = TextNormalizer.normalize(expected)
        if (want.isBlank()) return false
        val have = TextNormalizer.normalize(fieldText)
        if (have == want) return true
        // A field read cut off at the reading's limit: the words so far are
        // all of the requested ones that could be seen.
        if (fieldText.length >= ObservationBuilder.MAX_FIELD_TEXT && want.startsWith(have.substringBeforeLast(' '))) return true
        // Their own extra words around the requested ones are theirs to add.
        return " $have ".contains(" $want ")
    }
}
