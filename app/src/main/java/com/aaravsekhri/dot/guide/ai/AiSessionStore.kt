package com.aaravsekhri.dot.guide.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where an unfinished request is remembered.
 *
 * An interface so the loop can be driven in a test without Android's
 * preferences, and so that what is stored stays one small, auditable shape.
 */
interface SessionCheckpoints {
    fun save(checkpoint: AiSessionStore.Checkpoint)
    fun load(): AiSessionStore.Checkpoint?
    fun discard()
}

/**
 * The little that survives the app being closed.
 *
 * Enough to carry on where somebody left off: the goal in their own words,
 * each question Dot asked with the answer it got, what has been verified so
 * far, what was being worked towards, what did not work, and what was last
 * suggested.
 *
 * What is deliberately absent: node references, which mean nothing once the
 * screen has changed; coordinates, which would let a resumed session point at
 * a place rather than a thing; and screen captures, which are somebody's
 * private screen and have no business outliving the request they were taken
 * for. Resuming takes a fresh reading and plans again from it.
 *
 * Its own preferences file, excluded from cloud backup and device transfer in
 * the backup rules, because a half-finished request is a sentence somebody
 * typed about their own phone.
 */
class AiSessionStore(
    context: Context,
    /**
     * Which checkpoint: the one Ask request ([SLOT_ASK]), or one per course
     * ([courseSlot]). Kept apart so a new request in Ask never erases a
     * lesson in progress, and a lesson never erases an unfinished request.
     */
    private val slot: String = SLOT_ASK,
) : SessionCheckpoints {

    private val key: String = keyFor(slot)

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Checkpoint(
        val sessionId: String,
        val goal: String,
        val answers: List<QuestionAnswer>,
        val progress: List<String>,
        val subgoal: String,
        val failedApproaches: List<String>,
        val lastInstruction: String,
        val lastExpectedOutcome: String,
        val lastKind: String,
        val status: String,
        val updatedAtMs: Long,
        /** The lesson this belongs to, when it is a lesson's; empty for Ask. */
        val lessonId: String = "",
        /** The version of the lesson's content it was made with. */
        val contentVersion: Int = 0,
        /** A question asked and not yet answered, to put back up on resuming. */
        val pendingQuestion: String = "",
        val pendingOptions: List<String> = emptyList(),
        val pendingFreeText: Boolean = false,
        val pendingPersonal: Boolean = false,
        /** What the person settled about the task: see [TaskDetails]. Gone with the task. */
        val taskDetails: Map<String, String> = emptyMap(),
        /** A lesson's teaching card that was up: its stage (see [TeachStage.wire]), words and point. */
        val teachStage: String = "",
        val teachWords: String = "",
        val teachCheckpoint: String = "",
        /** The words of the information the card pointed at, to look for again. */
        val teachTarget: String = "",
        /** Its kind — text, or a control — so it is found again as the same kind of thing. */
        val teachTargetRole: String = "",
        /** Teaching points already acknowledged in this attempt. */
        val explained: List<String> = emptyList(),
        val resultShown: Boolean = false,
        /** The task admitted for this session, so resuming it never costs a second. */
        val taskId: String = "",
    ) {
        /** Answers alone, for the few places that only need what was said. */
        val clarifications: List<String> get() = answers.map { it.answer }
    }

    override fun save(checkpoint: Checkpoint) {
        preferences.edit().putString(key, encode(checkpoint).toString()).apply()
    }

    /** The unfinished session, if there is one worth offering to resume. */
    override fun load(): Checkpoint? {
        val raw = preferences.getString(key, null) ?: return null
        return decode(raw)?.takeIf { it.status != STATUS_COMPLETED && it.goal.isNotBlank() }
    }

    override fun discard() {
        preferences.edit().remove(key).apply()
    }

    /**
     * A goal typed in Ask, held across the trip to consent and Accessibility
     * settings — which can outlast the activity, and on a low-memory phone the
     * process. Kept here rather than in the app's general preferences so it is
     * covered by the same backup exclusion as the session itself.
     */
    var pendingGoal: String?
        get() = preferences.getString(KEY_PENDING_GOAL, null)?.takeIf(String::isNotBlank)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PENDING_GOAL) else putString(KEY_PENDING_GOAL, value)
            }.apply()
        }

    companion object {
        const val FILE = "dot_ai_session"

        const val STATUS_COMPLETED = "completed"

        /** "Try again later": stopped by choice, everything kept, nothing scheduled. */
        const val STATUS_LATER = "later"

        /** What a lesson teaches was not available on this phone or account. */
        const val STATUS_UNAVAILABLE = "unavailable"

        const val SLOT_ASK = "ask"

        fun courseSlot(courseId: String) = "course:$courseId"

        /** Ask keeps the key it always had, so an unfinished request survives an update. */
        internal fun keyFor(slot: String): String = if (slot == SLOT_ASK) KEY else "$KEY:$slot"

        private const val KEY = "checkpoint"
        private const val KEY_PENDING_GOAL = "pending_goal"

        internal fun encode(checkpoint: Checkpoint): JSONObject {
            val answers = JSONArray()
            checkpoint.answers.forEach {
                answers.put(JSONObject().put("q", it.question).put("a", it.answer))
            }
            return JSONObject()
                .put("session_id", checkpoint.sessionId)
                .put("goal", checkpoint.goal)
                .put("answers", answers)
                .put("progress", JSONArray(checkpoint.progress))
                .put("subgoal", checkpoint.subgoal)
                .put("failed", JSONArray(checkpoint.failedApproaches))
                .put("last_instruction", checkpoint.lastInstruction)
                .put("last_expected_outcome", checkpoint.lastExpectedOutcome)
                .put("last_kind", checkpoint.lastKind)
                .put("status", checkpoint.status)
                .put("updated_at", checkpoint.updatedAtMs)
                .put("lesson_id", checkpoint.lessonId)
                .put("content_version", checkpoint.contentVersion)
                .put("pending_question", checkpoint.pendingQuestion)
                .put("pending_options", JSONArray(checkpoint.pendingOptions))
                .put("pending_free_text", checkpoint.pendingFreeText)
                .put("pending_personal", checkpoint.pendingPersonal)
                .put("task_details", JSONObject(checkpoint.taskDetails as Map<*, *>))
                .put("teach_stage", checkpoint.teachStage)
                .put("teach_words", checkpoint.teachWords)
                .put("teach_checkpoint", checkpoint.teachCheckpoint)
                .put("teach_target", checkpoint.teachTarget)
                .put("teach_target_role", checkpoint.teachTargetRole)
                .put("explained", JSONArray(checkpoint.explained))
                .put("result_shown", checkpoint.resultShown)
                .put("task_id", checkpoint.taskId)
        }

        internal fun decode(raw: String): Checkpoint? = try {
            val json = JSONObject(raw)
            val answers = json.optJSONArray("answers")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { QuestionAnswer(it.optString("q"), it.optString("a")) }
                        ?.takeIf { it.answer.isNotBlank() }
                }
            } ?: json.optJSONArray("clarifications").strings().map { QuestionAnswer("", it) }
            Checkpoint(
                sessionId = json.optString("session_id"),
                goal = json.optString("goal"),
                answers = answers,
                // Older checkpoints held progress as one sentence.
                progress = json.optJSONArray("progress")?.strings()
                    ?: listOfNotNull(json.optString("progress").takeIf(String::isNotBlank)),
                subgoal = json.optString("subgoal"),
                failedApproaches = json.optJSONArray("failed").strings(),
                lastInstruction = json.optString("last_instruction"),
                lastExpectedOutcome = json.optString("last_expected_outcome"),
                lastKind = json.optString("last_kind"),
                status = json.optString("status"),
                updatedAtMs = json.optLong("updated_at"),
                lessonId = json.optString("lesson_id"),
                contentVersion = json.optInt("content_version"),
                pendingQuestion = json.optString("pending_question"),
                pendingOptions = json.optJSONArray("pending_options").strings(),
                pendingFreeText = json.optBoolean("pending_free_text"),
                pendingPersonal = json.optBoolean("pending_personal"),
                taskDetails = json.optJSONObject("task_details")?.let { d ->
                    TaskDetails.KEYS.filter { d.has(it) }.associateWith { d.optString(it) }.filterValues(String::isNotBlank)
                }.orEmpty(),
                teachStage = json.optString("teach_stage"),
                teachWords = json.optString("teach_words"),
                teachCheckpoint = json.optString("teach_checkpoint"),
                teachTarget = json.optString("teach_target"),
                teachTargetRole = json.optString("teach_target_role"),
                explained = json.optJSONArray("explained").strings(),
                resultShown = json.optBoolean("result_shown"),
                taskId = json.optString("task_id"),
            )
        } catch (_: Exception) {
            null
        }

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
        }
    }
}
