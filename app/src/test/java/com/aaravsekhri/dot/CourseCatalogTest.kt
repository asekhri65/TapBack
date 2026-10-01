package com.aaravsekhri.dot

import com.aaravsekhri.dot.courses.CourseCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The course catalogue: ten courses, real lessons, stable ids, sourced facts. */
class CourseCatalogTest {

    @Test
    fun `there are the thirty-one courses, the first twenty-eight kept as they were`() {
        assertEquals(
            listOf(
                "chatgpt", "whatsapp", "facebook", "photos", "maps", "gmail", "youtube", "calendar", "chrome", "messages",
                "camera", "clock", "keep", "files", "meet", "translate", "contacts", "phone", "spotify", "lens", "drive",
                "playstore", "notifications", "connect", "reading",
                "claude", "gemini", "perplexity",
                "lightroom", "canva", "homescreen",
            ),
            CourseCatalog.courses.map { it.id },
        )
    }

    @Test
    fun `the AI courses are grouped together, practise safely and remind the learner to check answers`() {
        val ai = CourseCatalog.courses.filter { it.category == com.aaravsekhri.dot.courses.CourseCategory.AiTools }.map { it.id }
        assertEquals(listOf("chatgpt", "claude", "gemini", "perplexity"), ai)
        listOf("claude", "gemini", "perplexity").map { CourseCatalog.course(it)!! }.forEach { course ->
            val points = course.lessons.flatMap { it.practice?.checkpoints.orEmpty() }
            assertTrue("${course.id} reminds to check answers", points.any { it.contains("can be wrong") })
            assertTrue("${course.id} warns about private details", points.any { it.contains("private details") })
            course.lessons.forEach { l ->
                assertTrue("${l.id} starts with its purpose", l.purpose.startsWith("By the end of this lesson"))
                assertTrue("${l.id} explains its result", l.result.isNotBlank())
                assertTrue("${l.id} says what to do when it cannot be done", l.fallback.isNotBlank())
            }
        }
        // The draft is never sent.
        CourseCatalog.course("claude")!!.lessons.drop(1).forEach { l ->
            assertTrue(l.id, (l.rules + l.objective).any { it.contains("Do not send", true) })
        }
    }

    @Test
    fun `every heading has courses, and every course one heading`() {
        val byCategory = CourseCatalog.courses.groupBy { it.category }
        assertEquals(com.aaravsekhri.dot.courses.CourseCategory.entries.toSet(), byCategory.keys)
        byCategory.values.forEach { assertTrue(it.size >= 3) }
    }

    @Test
    fun `every lesson can be launched, by the adaptive guide or by a guide Dot ships`() {
        CourseCatalog.courses.flatMap { it.lessons }.forEach { l ->
            l.guideId?.let { assertTrue("${l.id} names a real guide", com.aaravsekhri.dot.guide.catalog.GuideCatalog.byId(it) != null) }
                ?: assertTrue("${l.id} has an objective for the adaptive guide", l.objective.isNotBlank())
            assertTrue(CourseCatalog.lesson(CourseCatalog.courses.first { c -> l in c.lessons }.id, l.id) != null)
        }
    }

    @Test
    fun `the text-size walkthrough is taught once, inside Read comfortably`() {
        val withGuide = CourseCatalog.courses.flatMap { it.lessons }.filter { it.guideId != null }
        assertEquals(listOf("reading.text-size"), withGuide.map { it.id })
    }

    @Test
    fun `every lesson has an objective, an explanation, a way to know it is done, and sources`() {
        CourseCatalog.courses.flatMap { it.lessons }.forEach { l ->
            assertTrue(l.id, l.title.isNotBlank() && l.objective.isNotBlank() && l.doneWhen.isNotBlank())
            assertTrue("${l.id} explains briefly", l.explanation.isNotBlank() && l.explanation.length <= 200)
            assertTrue("${l.id} cites official help", l.sources.isNotEmpty() && l.sources.all { it.url.startsWith("https://") })
        }
    }

    @Test
    fun `ids are unique and say which course they belong to`() {
        val ids = CourseCatalog.courses.flatMap { c -> c.lessons.map { c.id to it.id } }
        assertEquals(ids.size, ids.map { it.second }.toSet().size)
        ids.forEach { (course, lesson) -> assertTrue(lesson, lesson.startsWith("$course.")) }
    }

    @Test
    fun `lessons are small and courses are short`() {
        CourseCatalog.courses.forEach { assertTrue(it.id, it.coreLessons.size in 3..5) }
        CourseCatalog.courses.forEach { assertTrue(it.id, it.lessons.size <= 5) }
        assertEquals("the first ten are unchanged", 43, CourseCatalog.courses.take(10).sumOf { it.lessons.size })
    }

    @Test
    fun `nothing is a coordinate`() {
        val digits = Regex("""\b\d{2,4}\s*,\s*\d{2,4}\b""")
        CourseCatalog.courses.flatMap { it.lessons }.forEach { l ->
            assertFalse(l.id, digits.containsMatchIn(l.objective + l.doneWhen))
        }
    }

    @Test
    fun `packages and publishers are the verified ones`() {
        val expected = mapOf(
            "chatgpt" to ("com.openai.chatgpt" to "OpenAI"),
            "whatsapp" to ("com.whatsapp" to "WhatsApp LLC"),
            "facebook" to ("com.facebook.katana" to "Meta Platforms, Inc."),
            "photos" to ("com.google.android.apps.photos" to "Google LLC"),
            "maps" to ("com.google.android.apps.maps" to "Google LLC"),
            "gmail" to ("com.google.android.gm" to "Google LLC"),
            "youtube" to ("com.google.android.youtube" to "Google LLC"),
            "calendar" to ("com.google.android.calendar" to "Google LLC"),
            "chrome" to ("com.android.chrome" to "Google LLC"),
            "messages" to ("com.google.android.apps.messaging" to "Google LLC"),
            "camera" to ("" to "Your phone's maker"),
            "clock" to ("com.google.android.deskclock" to "Google LLC"),
            "keep" to ("com.google.android.keep" to "Google LLC"),
            "files" to ("com.google.android.apps.nbu.files" to "Google LLC"),
            "meet" to ("com.google.android.apps.tachyon" to "Google LLC"),
            "translate" to ("com.google.android.apps.translate" to "Google LLC"),
            "contacts" to ("com.google.android.contacts" to "Google LLC"),
            "phone" to ("com.google.android.dialer" to "Google LLC"),
            "spotify" to ("com.spotify.music" to "Spotify AB"),
            "lens" to ("com.google.android.googlequicksearchbox" to "Google LLC"),
            "drive" to ("com.google.android.apps.docs" to "Google LLC"),
            "playstore" to ("com.android.vending" to "Google LLC"),
            "notifications" to ("com.android.settings" to "Your phone's maker"),
            "connect" to ("com.android.settings" to "Your phone's maker"),
            "reading" to ("com.android.settings" to "Your phone's maker"),
            "claude" to ("com.anthropic.claude" to "Anthropic PBC"),
            "gemini" to ("com.google.android.apps.bard" to "Google LLC"),
            "perplexity" to ("ai.perplexity.app.android" to "PerplexityAI"),
            "lightroom" to ("com.adobe.lrmobile" to "Adobe"),
            "canva" to ("com.canva.editor" to "Canva"),
            "homescreen" to ("" to "Your phone's maker"),
        )
        CourseCatalog.courses.forEach { c ->
            assertEquals(c.id, expected[c.id], c.app.packageName to c.app.publisher)
        }
    }

    @Test
    fun `lessons that send, post or call say the learner decides`() {
        val risky = Regex("(?i)\\b(send|post|call)\\b")
        // Lessons that reach other people: messages, posts, emails, shares, calls.
        val toPeople = setOf("whatsapp", "facebook", "gmail", "messages", "photos", "meet", "contacts", "phone", "drive")
        CourseCatalog.courses.filter { it.id in toPeople }.flatMap { it.lessons }
            .filter { risky.containsMatchIn(it.objective) && !it.objective.contains("Do not", ignoreCase = true) }
            .forEach { l ->
                val guarded = (l.rules + l.objective).any {
                    it.contains("when they are ready", true) || it.contains("only if", true) ||
                        it.contains("ask before", true) || it.contains("explicitly", true) || it.contains("Only guide", true) || it.startsWith("Do not")
                }
                assertTrue("${l.id} must leave the final step to the learner", guarded)
            }
    }

    @Test
    fun `the creative courses stay free, private and unpublished, and say when a phone can't`() {
        val photo = com.aaravsekhri.dot.courses.CourseCategory.PhotosAndCreativity
        assertTrue(CourseCatalog.course("lightroom")!!.category == photo)
        assertTrue(CourseCatalog.course("canva")!!.category == photo)
        assertEquals(com.aaravsekhri.dot.courses.CourseCategory.MakeItYours, CourseCatalog.course("homescreen")!!.category)
        // Photo editing is never filed under AI.
        assertFalse(CourseCatalog.courses.filter { it.category == com.aaravsekhri.dot.courses.CourseCategory.AiTools }.any { it.id == "lightroom" || it.id == "photos" })

        listOf("lightroom", "canva").map { CourseCatalog.course(it)!! }.forEach { c ->
            assertTrue("${c.id} needs its own account", c.lessons.first().needsAccount)
            c.lessons.filterNot { it.id.endsWith(".compare") || it.id.endsWith(".edit") }.forEach { l ->
                assertTrue("${l.id} never pays or trials", l.rules.any { it.contains("Never start a trial") })
            }
        }
        val canvaLast = CourseCatalog.course("canva")!!.lessons.last()
        assertTrue(canvaLast.rules.any { it.startsWith("Download only") })
        assertTrue(canvaLast.practice!!.stopAt.contains("nothing is sent"))

        val home = CourseCatalog.course("homescreen")!!
        assertTrue(home.lessons.any { l -> l.rules.any { it.contains("Never install a launcher or an icon pack") } })
        home.lessons.forEach { assertTrue("${it.id} says what to do when a phone differs", it.fallback.isNotBlank()) }
        assertEquals("homescreen.undo", home.lessons.last().id)
    }
}
