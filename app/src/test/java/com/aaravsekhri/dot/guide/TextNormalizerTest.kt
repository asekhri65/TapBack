package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.matching.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Label text has to survive the wording drift between Android builds. */
class TextNormalizerTest {

    @Test
    fun `case and surrounding whitespace do not matter`() {
        assertEquals("display", TextNormalizer.normalize("  Display  "))
        assertEquals("display", TextNormalizer.normalize("DISPLAY"))
    }

    @Test
    fun `ampersand reads as the word and`() {
        assertEquals(
            TextNormalizer.normalize("Display and touch"),
            TextNormalizer.normalize("Display & touch"),
        )
    }

    @Test
    fun `typographic hyphens match plain ones`() {
        assertEquals("wi fi", TextNormalizer.normalize("Wi‑Fi"))
        assertEquals("auto rotate", TextNormalizer.normalize("Auto-rotate"))
    }

    @Test
    fun `non breaking spaces collapse`() {
        assertEquals("font size", TextNormalizer.normalize("Font size"))
    }

    @Test
    fun `trailing punctuation is ignored`() {
        assertEquals("opening settings", TextNormalizer.normalize("Opening Settings…"))
        assertEquals("display", TextNormalizer.normalize("Display."))
    }

    @Test
    fun `accents are folded`() {
        assertEquals("affichage", TextNormalizer.normalize("Affichage"))
        assertEquals("taille", TextNormalizer.normalize("taillé"))
    }

    @Test
    fun `runs of whitespace collapse to one space`() {
        assertEquals("display size and text", TextNormalizer.normalize("Display   size  and\ttext"))
    }

    @Test
    fun `blank input normalises to empty`() {
        assertEquals("", TextNormalizer.normalize(null))
        assertEquals("", TextNormalizer.normalize("   "))
    }

    @Test
    fun `token overlap measures how much of the expected wording is present`() {
        assertEquals(1f, TextNormalizer.tokenOverlap("font size", "Font size"), 0.001f)
        assertEquals(1f, TextNormalizer.tokenOverlap("font size", "Font size, bold text"), 0.001f)
        assertEquals(0.5f, TextNormalizer.tokenOverlap("font size", "Font weight"), 0.001f)
        assertEquals(0f, TextNormalizer.tokenOverlap("font size", "Dark theme"), 0.001f)
    }

    @Test
    fun `token overlap is not confused by repeated words`() {
        assertTrue(TextNormalizer.tokenOverlap("size size", "size") < 1f)
    }
}
