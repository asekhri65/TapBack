package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Dot ships one guide in this phase and should say so rather than guess. */
class GuideCatalogTest {

    @Test
    fun `the flagship request maps to the text size guide`() {
        assertEquals(
            GuideCatalog.textSize,
            GuideCatalog.forRequest("Make the text bigger on my phone"),
        )
    }

    @Test
    fun `wording variations still find it`() {
        listOf(
            "make the font larger",
            "The writing is too small, can you make it bigger",
            "increase text size",
            "I want bigger letters",
        ).forEach { request ->
            assertEquals(request, GuideCatalog.textSize, GuideCatalog.forRequest(request))
        }
    }

    @Test
    fun `an unrelated request matches nothing`() {
        assertNull(GuideCatalog.forRequest("Turn on bluetooth"))
        assertNull(GuideCatalog.forRequest("Call my daughter"))
        assertNull(GuideCatalog.forRequest(""))
    }

    @Test
    fun `the guide is three steps and the last one is adjusted, not tapped`() {
        assertEquals(3, GuideCatalog.textSize.stepCount)
        assertEquals(
            com.aaravsekhri.dot.guide.model.StepAction.Adjust,
            GuideCatalog.textSize.steps.last().action,
        )
    }
}
