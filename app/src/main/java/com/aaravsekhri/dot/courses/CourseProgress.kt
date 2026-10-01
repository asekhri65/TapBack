package com.aaravsekhri.dot.courses

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Where one lesson stands for this learner. */
enum class LessonStatus {
    NotStarted,
    InProgress,
    Completed,

    /** The learner chose to move past it. Not done, and not counted as done. */
    Skipped,

    /** What it teaches was not available on this phone or account. Not done either. */
    Unavailable,
}

/** How a completion was known. */
enum class Evidence {
    /** The screen showed the lesson's outcome. */
    Observed,

    /** The learner said so, for an outcome no screen can show. */
    LearnerConfirmed,
}

data class LessonRecord(
    val status: LessonStatus,
    /** The lesson content version this was recorded against. */
    val version: Int,
    val updatedAtMs: Long,
    val evidence: Evidence? = null,
    /** For an unavailable lesson: what was missing, briefly. */
    val note: String = "",
)

data class CourseRecord(
    val currentLessonId: String? = null,
    val lessons: Map<String, LessonRecord> = emptyMap(),
    val updatedAtMs: Long = 0L,
    /**
     * Practice examples the learner chose instead of the course's own, by
     * [Practice.exampleKey]: "a place of my own". Kept only while the course
     * is under way, so the next lesson and a resume use the same one; gone
     * once every core lesson is done.
     */
    val examples: Map<String, String> = emptyMap(),
    /**
     * Practising the whole course again from its first lesson. Earned
     * completions stay; the course's current lesson walks forward in order,
     * finished lessons included, until the last one.
     */
    val replay: Boolean = false,
)

/** What Learn shows for a course: counts of lessons, never of taps. */
data class CourseSummary(
    val course: Course,
    val completed: Int,
    val total: Int,
    val notAvailable: Int,
    val current: Lesson?,
    val action: CourseAction,
    val statuses: Map<String, LessonStatus>,
) {
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
    val started: Boolean get() = action != CourseAction.Start
}

enum class CourseAction { Start, Resume, Review }

/**
 * Durable, on-device course progress.
 *
 * Kept apart from any guide's checkpoint: this says which lessons are done,
 * not how far through a screen somebody was, so a new request typed in Ask
 * — or any guide at all — cannot erase it. Only ids, statuses, versions and
 * times are stored, and — only while a course is under way — a practice
 * example the learner chose to use instead of the course's own. Nothing from
 * a screen.
 *
 * A lesson is completed only by [complete], which the guide calls with
 * evidence. Starting one, stopping to try again later, an error, or moving to
 * the next never completes anything.
 *
 * Content versions, deliberately:
 * - A lesson completed on an older version stays completed. The learner did
 *   it; a rewrite of the explanation does not undo that.
 * - A lesson in progress on an older version stays in progress, and its guide
 *   checkpoint (stamped with the version) is not resumed: it starts afresh
 *   against the new objective.
 * - A lesson marked unavailable on an older version is offered again: the
 *   new content may work where the old did not.
 * - Records for lessons no longer in the catalog are ignored.
 */
class CourseProgressStore(
    private val storage: Storage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Where the record lives. An interface so tests need no Android. */
    interface Storage {
        fun read(): String?
        fun write(value: String)
    }

    private val state = MutableStateFlow(decode(storage.read()))

    /** Every course's record, by course id. */
    val records: StateFlow<Map<String, CourseRecord>> = state.asStateFlow()

    /** A lesson was opened. Never downgrades a completed one: reviewing is not undoing. */
    fun started(courseId: String, lessonId: String, version: Int) = update(courseId) { record ->
        val existing = record.lessons[lessonId]
        val next = when (existing?.status) {
            LessonStatus.Completed -> existing
            else -> LessonRecord(LessonStatus.InProgress, version, now())
        }
        record.copy(currentLessonId = lessonId, lessons = record.lessons + (lessonId to next))
    }

    /** Finished, with how that is known. The current lesson moves to the next one not done. */
    fun complete(courseId: String, lessonId: String, version: Int, evidence: Evidence) = update(courseId) { record ->
        val lessons = record.lessons + (lessonId to LessonRecord(LessonStatus.Completed, version, now(), evidence))
        val course = CourseCatalog.course(courseId)
        // Every core lesson done: the learner's own examples are no longer needed.
        val allDone = course != null && course.coreLessons.all { statusOf(it, lessons[it.id]) == LessonStatus.Completed }
        // Practising again: on to the next lesson in order, finished or not,
        // until the last; then the replay is over.
        val order = course?.coreLessons.orEmpty()
        val replayNext = if (record.replay) order.getOrNull(order.indexOfFirst { it.id == lessonId } + 1) else null
        record.copy(
            currentLessonId = replayNext?.id ?: nextOpen(courseId, lessonId, lessons),
            lessons = lessons,
            examples = if (allDone && replayNext == null) emptyMap() else record.examples,
            replay = replayNext != null,
        )
    }

    /**
     * "Restart course": practise it again from the first lesson. Nothing
     * earned is taken back; the learner's own practice examples are let go,
     * so the course's examples are used afresh.
     */
    fun restartCourse(courseId: String) {
        val course = CourseCatalog.course(courseId) ?: return
        val first = (course.coreLessons.ifEmpty { course.lessons }).first()
        update(courseId) { it.copy(currentLessonId = first.id, examples = emptyMap(), replay = true) }
    }

    /** The learner chose their own [value] to practise with, for [key], for the rest of the course. */
    fun rememberExample(courseId: String, key: String, value: String) {
        val clean = value.trim().take(MAX_EXAMPLE)
        if (key.isBlank() || clean.isBlank()) return
        update(courseId) { it.copy(examples = it.examples + (key to clean)) }
    }

    /** The example in use for [practice] in [courseId]: the learner's own if they chose one, else the course's. */
    fun exampleFor(courseId: String, practice: Practice): String =
        practice.exampleKey.takeIf(String::isNotBlank)?.let { records.value[courseId]?.examples?.get(it) } ?: practice.example

    /** What it teaches is not here. Recorded as such, never as done. */
    fun unavailable(courseId: String, lessonId: String, version: Int, note: String) = update(courseId) { record ->
        val lessons = record.lessons + (lessonId to LessonRecord(LessonStatus.Unavailable, version, now(), note = note.take(160)))
        record.copy(currentLessonId = nextOpen(courseId, lessonId, lessons), lessons = lessons)
    }

    /** The learner moved past it. Not done. */
    fun skip(courseId: String, lessonId: String, version: Int) = update(courseId) { record ->
        val lessons = record.lessons + (lessonId to LessonRecord(LessonStatus.Skipped, version, now()))
        record.copy(currentLessonId = nextOpen(courseId, lessonId, lessons), lessons = lessons)
    }

    /** What Learn shows for [course]. */
    fun summary(course: Course): CourseSummary = summarize(course, records.value[course.id])

    /** The course most recently worked on and not finished, for "Continue learning". */
    fun active(): Course? = records.value
        .filter { (id, _) -> CourseCatalog.course(id)?.let { summary(it).action == CourseAction.Resume } == true }
        .maxByOrNull { it.value.updatedAtMs }
        ?.let { CourseCatalog.course(it.key) }

    private fun update(courseId: String, change: (CourseRecord) -> CourseRecord) {
        val all = state.value
        val next = change(all[courseId] ?: CourseRecord()).copy(updatedAtMs = now())
        val updated = all + (courseId to next)
        storage.write(encode(updated))
        state.value = updated
    }

    /** The first lesson after [from], wrapping round, that is not done or ruled out. */
    private fun nextOpen(courseId: String, from: String, lessons: Map<String, LessonRecord>): String? {
        val course = CourseCatalog.course(courseId) ?: return null
        val order = course.coreLessons.ifEmpty { course.lessons }
        val start = order.indexOfFirst { it.id == from }.coerceAtLeast(0)
        return (1..order.size).map { order[(start + it) % order.size] }
            .firstOrNull { lesson -> statusOf(lesson, lessons[lesson.id]) !in DONE_OR_OUT }
            ?.id
    }

    companion object {
        private val DONE_OR_OUT = setOf(LessonStatus.Completed, LessonStatus.Unavailable, LessonStatus.Skipped)

        /** A chosen example is a place's or a phrase's name, never a paragraph. */
        const val MAX_EXAMPLE = 120

        /** The status a record means for the lesson as it is now. See the class notes on versions. */
        fun statusOf(lesson: Lesson, record: LessonRecord?): LessonStatus {
            if (record == null) return LessonStatus.NotStarted
            if (record.version == lesson.version) return record.status
            return when (record.status) {
                LessonStatus.Completed, LessonStatus.InProgress, LessonStatus.Skipped -> record.status
                LessonStatus.Unavailable, LessonStatus.NotStarted -> LessonStatus.NotStarted
            }
        }

        fun summarize(course: Course, record: CourseRecord?): CourseSummary {
            val statuses = course.lessons.associate { it.id to statusOf(it, record?.lessons?.get(it.id)) }
            // Counted over the core lessons: an optional extension is extra,
            // never something the course is "missing".
            val core = course.coreLessons
            val completed = core.count { statuses[it.id] == LessonStatus.Completed }
            val out = core.count { statuses[it.id] == LessonStatus.Unavailable || statuses[it.id] == LessonStatus.Skipped }
            val anything = statuses.values.any { it != LessonStatus.NotStarted }
            val current = record?.currentLessonId
                ?.let { id -> course.lessons.firstOrNull { it.id == id } }
                ?.takeIf { record.replay || statuses[it.id] !in DONE_OR_OUT }
                ?: core.firstOrNull { statuses[it.id] == LessonStatus.InProgress }
                ?: core.firstOrNull { statuses[it.id] == LessonStatus.NotStarted }
            val action = when {
                !anything -> CourseAction.Start
                current == null -> CourseAction.Review
                else -> CourseAction.Resume
            }
            return CourseSummary(
                course = course,
                completed = completed,
                total = core.size,
                notAvailable = out,
                current = current ?: course.lessons.first().takeIf { !anything },
                action = action,
                statuses = statuses,
            )
        }

        internal fun encode(records: Map<String, CourseRecord>): String {
            val root = JSONObject().put("format", 1)
            val courses = JSONObject()
            records.forEach { (courseId, record) ->
                val lessons = JSONObject()
                record.lessons.forEach { (lessonId, lesson) ->
                    lessons.put(
                        lessonId,
                        JSONObject()
                            .put("status", lesson.status.name)
                            .put("version", lesson.version)
                            .put("at", lesson.updatedAtMs)
                            .put("evidence", lesson.evidence?.name ?: "")
                            .put("note", lesson.note),
                    )
                }
                courses.put(
                    courseId,
                    JSONObject()
                        .put("current", record.currentLessonId ?: "")
                        .put("at", record.updatedAtMs)
                        .put("lessons", lessons)
                        .put("examples", JSONObject(record.examples as Map<*, *>))
                        .put("replay", record.replay),
                )
            }
            return root.put("courses", courses).toString()
        }

        internal fun decode(raw: String?): Map<String, CourseRecord> {
            if (raw.isNullOrBlank()) return emptyMap()
            return try {
                val courses = JSONObject(raw).optJSONObject("courses") ?: return emptyMap()
                courses.keys().asSequence().associateWith { courseId ->
                    val c = courses.getJSONObject(courseId)
                    val lessons = c.optJSONObject("lessons")
                    val examples = c.optJSONObject("examples")
                    CourseRecord(
                        currentLessonId = c.optString("current").takeIf(String::isNotBlank),
                        updatedAtMs = c.optLong("at"),
                        examples = examples?.keys()?.asSequence()?.associateWith { examples.optString(it) }.orEmpty(),
                        replay = c.optBoolean("replay", false),
                        lessons = lessons?.keys()?.asSequence()?.mapNotNull { lessonId ->
                            val l = lessons.getJSONObject(lessonId)
                            val status = runCatching { LessonStatus.valueOf(l.optString("status")) }.getOrNull()
                                ?: return@mapNotNull null
                            lessonId to LessonRecord(
                                status = status,
                                version = l.optInt("version", 1),
                                updatedAtMs = l.optLong("at"),
                                evidence = runCatching { Evidence.valueOf(l.optString("evidence")) }.getOrNull(),
                                note = l.optString("note"),
                            )
                        }?.toMap().orEmpty(),
                    )
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}

/** The one store for the app and the guide service, which share a process. */
object CourseProgress {
    private const val FILE = "dot_course_progress"
    private const val KEY = "progress"

    @Volatile
    private var instance: CourseProgressStore? = null

    fun get(context: Context): CourseProgressStore = instance ?: synchronized(this) {
        instance ?: run {
            val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            CourseProgressStore(
                object : CourseProgressStore.Storage {
                    override fun read(): String? = prefs.getString(KEY, null)

                    // Committed, not applied: a lesson finished just before
                    // the process goes must still be finished afterwards.
                    @android.annotation.SuppressLint("ApplySharedPref")
                    override fun write(value: String) {
                        prefs.edit(commit = true) { putString(KEY, value) }
                    }
                },
            ).also { instance = it }
        }
    }
}
