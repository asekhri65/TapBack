package com.aaravsekhri.dot

import com.aaravsekhri.dot.ui.legal.Block
import com.aaravsekhri.dot.ui.legal.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegalDocumentTest {

    private fun document(name: String): String =
        listOf("src/main/res/raw/$name", "app/src/main/res/raw/$name").map(::File).first { it.exists() }.readText()

    @Test
    fun `a document is a date line, then headed sections of paragraphs and lists`() {
        val blocks = parse(
            """
            Last updated: today

            Opening words.

            ## First
            - one
            - two
            After the list.
            ## Second
            Closing words.
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.Dated("Last updated: today"),
                Block.Paragraph("Opening words."),
                Block.Heading("First"),
                Block.Items(listOf("one", "two")),
                Block.Paragraph("After the list."),
                Block.Heading("Second"),
                Block.Paragraph("Closing words."),
            ),
            blocks,
        )
    }

    @Test
    fun `both shipped documents read as documents, with no placeholders or invented addresses`() {
        for (name in listOf("privacy_policy.txt", "terms_of_service.txt")) {
            val text = document(name)
            val blocks = parse(text)
            assertTrue(name, blocks.first() is Block.Dated)
            assertTrue(name, blocks.count { it is Block.Heading } >= 5)
            assertFalse(name, Regex("""https?://|www\.|@[a-z]|\[|\bTODO\b|lorem""", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
    }

    @Test
    fun `the privacy policy says what leaves the phone and who handles it`() {
        val text = document("privacy_policy.txt")
        listOf("Gemini", "RevenueCat", "Google Play", "password", "does not keep it").forEach {
            assertTrue(it, text.contains(it))
        }
    }

    @Test
    fun `the terms state the same allowance the service enforces`() {
        val text = document("terms_of_service.txt")
        assertTrue(text.contains("2 guided tasks to try TapBack"))
        assertTrue(text.contains("don’t come back each week"))
        assertTrue(text.contains("10 guided tasks each subscription week"))
        assertTrue(text.contains("Cancel anytime in Google Play") || text.contains("cancel anytime in Google Play"))
    }
}
