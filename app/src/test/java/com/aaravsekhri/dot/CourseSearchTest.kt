package com.aaravsekhri.dot

import com.aaravsekhri.dot.courses.CourseCatalog
import com.aaravsekhri.dot.courses.CourseSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseSearchTest {

    private fun ids(query: String) = CourseSearch.search(CourseCatalog.courses, query).map { it.course.id }

    @Test
    fun `everyday words find the courses that use other words`() {
        assertEquals("photos", ids("pictures").first())
        assertTrue(ids("pictures").containsAll(listOf("photos", "camera")))
        assertEquals("gmail", ids("email").first())
        assertEquals("clock", ids("alarm").first())
        assertEquals("spotify", ids("music").first())
        assertTrue(ids("texts").contains("messages"))
    }

    @Test
    fun `a course is found by its app, its title or one of its lessons`() {
        assertEquals("maps", ids("maps").first())
        assertEquals("whatsapp", ids("WhatsApp").first())
        assertTrue(ids("dark theme").contains("reading"))
    }

    @Test
    fun `every word has to match, filler words aside, and nothing matches nothing`() {
        assertTrue(ids("how do I use the photos app").first() == "photos")
        assertTrue(ids("zzzqqq").isEmpty())
        assertTrue(ids("   ").isEmpty())
        assertTrue(ids("photos zzzqqq").isEmpty())
    }

    @Test
    fun `plurals and case meet in the middle`() {
        assertEquals(ids("Photo"), ids("photos"))
        assertEquals("messag", CourseSearch.stem("messages").take(6))
    }
}
