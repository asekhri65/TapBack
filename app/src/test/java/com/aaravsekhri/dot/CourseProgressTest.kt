package com.aaravsekhri.dot

import com.aaravsekhri.dot.courses.CourseAction
import com.aaravsekhri.dot.courses.CourseCatalog
import com.aaravsekhri.dot.courses.CourseProgressStore
import com.aaravsekhri.dot.courses.CourseRecord
import com.aaravsekhri.dot.courses.Evidence
import com.aaravsekhri.dot.courses.LessonRecord
import com.aaravsekhri.dot.courses.LessonStatus
import com.aaravsekhri.dot.guide.ai.AiSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved course progress: counted in lessons, completed only with evidence, and durable. */
class CourseProgressTest {

    /** A file that outlives any one store, as preferences outlive a process. */
    private class Disk : CourseProgressStore.Storage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) {
            this.value = value
        }
    }

    private val course = CourseCatalog.whatsApp
    private fun lesson(i: Int) = course.lessons[i]

    @Test
    fun `a new course is not started and offers Start`() {
        val summary = CourseProgressStore(Disk()).summary(course)
        assertEquals(CourseAction.Start, summary.action)
        assertEquals(0, summary.completed)
        assertEquals(course.lessons.first(), summary.current)
    }

    @Test
    fun `starting a lesson, or stopping, never completes it`() {
        val store = CourseProgressStore(Disk())
        store.started(course.id, lesson(0).id, lesson(0).version)
        val summary = store.summary(course)
        assertEquals(LessonStatus.InProgress, summary.statuses[lesson(0).id])
        assertEquals(0, summary.completed)
        assertEquals(CourseAction.Resume, summary.action)
        assertEquals(lesson(0), summary.current)
    }

    @Test
    fun `progress is counted in lessons completed, and the next lesson becomes current`() {
        val store = CourseProgressStore(Disk())
        store.started(course.id, lesson(0).id, lesson(0).version)
        store.complete(course.id, lesson(0).id, lesson(0).version, Evidence.Observed)
        store.started(course.id, lesson(1).id, lesson(1).version)
        store.complete(course.id, lesson(1).id, lesson(1).version, Evidence.Observed)
        val summary = store.summary(course)
        assertEquals(2, summary.completed)
        assertEquals(course.lessons.size, summary.total)
        assertEquals(lesson(2), summary.current)
        assertEquals(2f / course.lessons.size, summary.fraction, 0.001f)
    }

    @Test
    fun `skipped and unavailable lessons are kept apart from completed ones`() {
        val store = CourseProgressStore(Disk())
        store.skip(course.id, lesson(0).id, lesson(0).version)
        store.unavailable(course.id, lesson(1).id, lesson(1).version, "No calls on this tablet")
        val summary = store.summary(course)
        assertEquals(0, summary.completed)
        assertEquals(2, summary.notAvailable)
        assertEquals(LessonStatus.Skipped, summary.statuses[lesson(0).id])
        assertEquals(LessonStatus.Unavailable, summary.statuses[lesson(1).id])
        assertEquals(lesson(2), summary.current)
    }

    @Test
    fun `any lesson can be opened out of order`() {
        val store = CourseProgressStore(Disk())
        store.started(course.id, lesson(3).id, lesson(3).version)
        assertEquals(lesson(3), store.summary(course).current)
    }

    @Test
    fun `reviewing a finished lesson does not undo it`() {
        val store = CourseProgressStore(Disk())
        store.complete(course.id, lesson(0).id, lesson(0).version, Evidence.LearnerConfirmed)
        store.started(course.id, lesson(0).id, lesson(0).version)
        assertEquals(LessonStatus.Completed, store.summary(course).statuses[lesson(0).id])
    }

    @Test
    fun `a course with every lesson done or ruled out offers Review`() {
        val store = CourseProgressStore(Disk())
        course.lessons.forEachIndexed { i, l ->
            if (i == 2) store.unavailable(course.id, l.id, l.version, "") else store.complete(course.id, l.id, l.version, Evidence.Observed)
        }
        val summary = store.summary(course)
        assertEquals(CourseAction.Review, summary.action)
        assertNull(summary.current)
        assertEquals(course.lessons.size - 1, summary.completed)
    }

    @Test
    fun `progress survives the process going and coming back`() {
        val disk = Disk()
        CourseProgressStore(disk).apply {
            started(course.id, lesson(0).id, lesson(0).version)
            complete(course.id, lesson(0).id, lesson(0).version, Evidence.Observed)
            started(course.id, lesson(1).id, lesson(1).version)
        }
        // A new process: a new store over the same file.
        val again = CourseProgressStore(disk).summary(course)
        assertEquals(1, again.completed)
        assertEquals(LessonStatus.InProgress, again.statuses[lesson(1).id])
        assertEquals(lesson(1), again.current)
    }

    @Test
    fun `a damaged file is an empty start, not a crash`() {
        val disk = Disk().apply { value = "{not json" }
        assertEquals(CourseAction.Start, CourseProgressStore(disk).summary(course).action)
    }

    @Test
    fun `content version changes are handled deliberately`() {
        val l = lesson(0).copy(version = 2)
        assertEquals(
            "done stays done",
            LessonStatus.Completed,
            CourseProgressStore.statusOf(l, LessonRecord(LessonStatus.Completed, 1, 0)),
        )
        assertEquals(
            "unavailable is offered again with new content",
            LessonStatus.NotStarted,
            CourseProgressStore.statusOf(l, LessonRecord(LessonStatus.Unavailable, 1, 0)),
        )
        assertEquals(
            "in progress stays in progress (its old checkpoint is not resumed)",
            LessonStatus.InProgress,
            CourseProgressStore.statusOf(l, LessonRecord(LessonStatus.InProgress, 1, 0)),
        )
    }

    @Test
    fun `records for lessons no longer in the course are ignored`() {
        val summary = CourseProgressStore.summarize(
            course,
            CourseRecord(lessons = mapOf("whatsapp.retired" to LessonRecord(LessonStatus.Completed, 1, 0))),
        )
        assertEquals(0, summary.completed)
    }

    @Test
    fun `the most recently worked-on unfinished course is the one to continue`() {
        var now = 0L
        val store = CourseProgressStore(Disk(), now = { now })
        now = 10; store.started("maps", "maps.search", 1)
        now = 20; store.started("gmail", "gmail.inbox", 1)
        assertEquals("gmail", store.active()?.id)
    }

    @Test
    fun `an Ask request and a lesson keep their checkpoints in different places`() {
        val ask = AiSessionStore.keyFor(AiSessionStore.SLOT_ASK)
        val course = AiSessionStore.keyFor(AiSessionStore.courseSlot("whatsapp"))
        val other = AiSessionStore.keyFor(AiSessionStore.courseSlot("maps"))
        assertEquals("Ask keeps the key earlier versions wrote", "checkpoint", ask)
        assertNotEquals(ask, course)
        assertNotEquals(course, other)
    }

    @Test
    fun `a lesson checkpoint remembers which lesson and version it belongs to`() {
        val checkpoint = AiSessionStore.Checkpoint(
            sessionId = "s", goal = "Share a photo", answers = emptyList(), progress = emptyList(),
            subgoal = "", failedApproaches = emptyList(), lastInstruction = "", lastExpectedOutcome = "",
            lastKind = "", status = AiSessionStore.STATUS_LATER, updatedAtMs = 1,
            lessonId = "whatsapp.share-photo", contentVersion = 2,
        )
        val back = AiSessionStore.decode(AiSessionStore.encode(checkpoint).toString())!!
        assertEquals("whatsapp.share-photo", back.lessonId)
        assertEquals(2, back.contentVersion)
        assertEquals(AiSessionStore.STATUS_LATER, back.status)
        assertFalse("try again later is not completion", back.status == AiSessionStore.STATUS_COMPLETED)
        assertTrue(back.goal.isNotBlank())
    }

    @Test
    fun `progress saved by the ten-course version survives the expansion`() {
        // Written by the previous version, before the fifteen new courses.
        val before = """{"format":1,"courses":{"maps":{"current":"maps.details","at":10,"lessons":{"maps.search":{"status":"Completed","version":1,"at":5,"evidence":"Observed","note":""},"maps.details":{"status":"InProgress","version":1,"at":10,"evidence":"","note":""}}},"chatgpt":{"current":"chatgpt.question","at":8,"lessons":{"chatgpt.setup":{"status":"Unavailable","version":1,"at":8,"evidence":"","note":"No Play Store"}}}}}"""
        val store = CourseProgressStore(Disk().apply { value = before })
        val maps = store.summary(CourseCatalog.course("maps")!!)
        assertEquals(1, maps.completed)
        assertEquals(4, maps.total)
        assertEquals("maps.details", maps.current?.id)
        assertEquals(LessonStatus.Unavailable, store.summary(CourseCatalog.course("chatgpt")!!).statuses["chatgpt.setup"])
        // Every new course starts fresh.
        CourseCatalog.courses.drop(10).forEach { assertEquals(it.id, CourseAction.Start, store.summary(it).action) }
    }

    @Test
    fun `optional extensions are extra, never missing`() {
        val camera = CourseCatalog.course("camera")!!
        val store = CourseProgressStore(Disk())
        camera.coreLessons.forEach { store.complete(camera.id, it.id, it.version, Evidence.Observed) }
        val done = store.summary(camera)
        assertEquals(camera.coreLessons.size, done.total)
        assertEquals(done.total, done.completed)
        assertEquals("all core lessons done is a finished course", CourseAction.Review, done.action)
        assertEquals(LessonStatus.NotStarted, done.statuses["camera.selfie"])
    }

    @Test
    fun `completed, skipped, unavailable and partly done lessons all show as what they are`() {
        val keep = CourseCatalog.course("keep")!!
        val store = CourseProgressStore(Disk())
        store.complete(keep.id, "keep.note", keep.lessons.first { it.id == "keep.note" }.version, Evidence.Observed)
        store.skip(keep.id, "keep.list", keep.lessons.first { it.id == "keep.list" }.version)
        store.unavailable(keep.id, "keep.tick", keep.lessons.first { it.id == "keep.tick" }.version, "")
        store.started(keep.id, "keep.find", keep.lessons.first { it.id == "keep.find" }.version)
        val s = store.summary(keep)
        assertEquals(
            mapOf(
                "keep.note" to LessonStatus.Completed,
                "keep.list" to LessonStatus.Skipped,
                "keep.tick" to LessonStatus.Unavailable,
                "keep.find" to LessonStatus.InProgress,
            ),
            s.statuses,
        )
        assertEquals(1, s.completed)
        assertEquals(2, s.notAvailable)
        assertEquals(CourseAction.Resume, s.action)
        assertEquals("keep.find", s.current?.id)
    }

    @Test
    fun `the learner's own practice example is kept for the course, across lessons and a restart, and gone once the course is done`() {
        val maps = CourseCatalog.course("maps")!!
        val practice = maps.lessons.first().practice!!
        val disk = Disk()
        val store = CourseProgressStore(disk)
        assertEquals("the course's own, by default", CourseCatalog.MAPS_PLACE, store.exampleFor("maps", practice))
        store.rememberExample("maps", practice.exampleKey, "  Joe's Diner, Springfield  ")
        val again = CourseProgressStore(disk)
        assertEquals("Joe's Diner, Springfield", again.exampleFor("maps", maps.lessons[1].practice!!))
        assertEquals("the next lesson types it", "Joe's Diner, Springfield", practice.textFor(again.exampleFor("maps", practice)))
        maps.coreLessons.forEach { again.complete("maps", it.id, it.version, Evidence.Observed) }
        assertEquals("not kept once the course is done", CourseCatalog.MAPS_PLACE, again.exampleFor("maps", practice))
    }

    @Test
    fun `nothing left to do is not the same as everything done`() {
        val maps = CourseCatalog.course("maps")!!
        val store = CourseProgressStore(Disk())
        maps.coreLessons.forEachIndexed { i, l ->
            if (i == 1) store.skip("maps", l.id, l.version) else store.complete("maps", l.id, l.version, Evidence.Observed)
        }
        val s = store.summary(maps)
        assertEquals(CourseAction.Review, s.action)
        assertEquals(maps.coreLessons.size - 1, s.completed)
        assertTrue("so no course celebration may be claimed", s.completed < s.total)
    }

    @Test
    fun `every lesson practises on something, or needs nothing made up`() {
        val maps = CourseCatalog.course("maps")!!
        maps.lessons.forEach { assertTrue(it.id, it.practice != null) }
        assertTrue(maps.lessons.first().objective.contains("practice", ignoreCase = true) || maps.lessons.first().objective.contains("practise", ignoreCase = true))
        assertFalse("never a place the learner must already have in mind", maps.lessons.first().objective.contains("learner chooses"))
    }

    @Test
    fun `restarting a finished course practises every lesson again in order, and keeps what was earned`() {
        val maps = CourseCatalog.course("maps")!!
        val store = CourseProgressStore(Disk())
        store.rememberExample("maps", "maps.place", "Joe's Diner")
        maps.coreLessons.dropLast(1).forEach { store.complete("maps", it.id, it.version, Evidence.Observed) }
        store.complete("maps", maps.coreLessons.last().id, maps.coreLessons.last().version, Evidence.Observed)
        assertEquals(CourseAction.Review, store.summary(maps).action)

        store.restartCourse("maps")
        val restarted = store.summary(maps)
        assertEquals(CourseAction.Resume, restarted.action)
        assertEquals(maps.coreLessons.first(), restarted.current)
        assertEquals("earned history kept", maps.coreLessons.size, restarted.completed)
        assertEquals("the course's own example again", CourseCatalog.MAPS_PLACE, store.exampleFor("maps", maps.lessons.first().practice!!))

        store.complete("maps", maps.coreLessons[0].id, maps.coreLessons[0].version, Evidence.Observed)
        assertEquals("on to the next in order, though finished", maps.coreLessons[1], store.summary(maps).current)
        maps.coreLessons.drop(1).forEach { store.complete("maps", it.id, it.version, Evidence.Observed) }
        assertEquals("the replay is over", CourseAction.Review, store.summary(maps).action)
    }

    @Test
    fun `restarting one course leaves the others alone, and a restart survives the process`() {
        val disk = Disk()
        val store = CourseProgressStore(disk)
        val keep = CourseCatalog.course("keep")!!
        store.complete("keep", keep.coreLessons[0].id, keep.coreLessons[0].version, Evidence.Observed)
        store.started("maps", "maps.details", CourseCatalog.lesson("maps", "maps.details")!!.version)
        store.restartCourse("maps")
        val again = CourseProgressStore(disk)
        assertEquals(1, again.summary(keep).completed)
        assertEquals("maps.search", again.summary(CourseCatalog.course("maps")!!).current?.id)
    }

    @Test
    fun `every lesson says what it is for and what its result means`() {
        CourseCatalog.courses.flatMap { it.lessons }.forEach { l ->
            assertTrue(l.id, l.purpose.startsWith("By the end of this lesson"))
            assertTrue(l.id, l.result.isNotBlank())
            assertTrue("${l.id}: one short sentence", l.purpose.length <= 120)
            l.practice?.checkpoints?.forEach { assertTrue("${l.id}: short point", it.length <= 160) }
        }
    }
}
