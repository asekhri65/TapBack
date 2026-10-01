package com.aaravsekhri.dot.guide.matching

import java.text.Normalizer
import java.util.Locale

/**
 * Puts label text into a shape that can be compared across Android versions
 * and manufacturers.
 *
 * Settings wording drifts constantly — "Display & touch" on one phone,
 * "Display and brightness" on another, "Wi‑Fi" with a non-breaking hyphen,
 * trailing ellipses, stray double spaces. Comparing raw strings would make the
 * guide brittle for exactly the people least able to work around it.
 */
object TextNormalizer {

    /** Characters that should all collapse to a plain hyphen. */
    private val HYPHENS = charArrayOf('‐', '‑', '‒', '–', '—', '−')

    /** Characters that should all collapse to a plain space. */
    private val SPACES = charArrayOf(' ', ' ', ' ', '　')

    /**
     * Lower-cases, strips accents and punctuation, spells out "&", and
     * collapses whitespace. An empty or blank input normalises to "".
     */
    fun normalize(raw: CharSequence?): String {
        if (raw.isNullOrBlank()) return ""

        val builder = StringBuilder(raw.length)
        for (character in raw) {
            when {
                character in HYPHENS -> builder.append('-')
                character in SPACES -> builder.append(' ')
                character == '&' -> builder.append(" and ")
                else -> builder.append(character)
            }
        }

        // Split accents off their base letters, then drop the accents.
        val decomposed = Normalizer.normalize(builder, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

        val cleaned = decomposed
            .lowercase(Locale.ROOT)
            // Keep letters, digits and spaces; hyphens become spaces so
            // "auto-rotate" and "auto rotate" match.
            .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }
            .joinToString("")

        return cleaned.trim().replace(Regex("\\s+"), " ")
    }

    /** The normalised words of [raw], in order, with duplicates kept. */
    fun tokens(raw: CharSequence?): List<String> {
        val normalized = normalize(raw)
        return if (normalized.isEmpty()) emptyList() else normalized.split(' ')
    }

    /**
     * How much of [candidate]'s wording is accounted for by [expected], from
     * 0f to 1f. Used so "Display size and text" still scores well against a
     * label reading "Display size & text (font)".
     */
    fun tokenOverlap(expected: CharSequence?, candidate: CharSequence?): Float {
        val expectedTokens = tokens(expected)
        val candidateTokens = tokens(candidate)
        if (expectedTokens.isEmpty() || candidateTokens.isEmpty()) return 0f

        val remaining = candidateTokens.toMutableList()
        var matched = 0
        for (token in expectedTokens) {
            if (remaining.remove(token)) matched++
        }
        return matched.toFloat() / expectedTokens.size
    }
}
