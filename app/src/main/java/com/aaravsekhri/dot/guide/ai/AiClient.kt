package com.aaravsekhri.dot.guide.ai

import android.util.Base64
import com.aaravsekhri.dot.BuildConfig
import com.aaravsekhri.dot.guide.GuideLog
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What a planning attempt came back as. */
sealed interface PlanningOutcome {
    data class Decided(val decision: AiDecision, val latencyMs: Long) : PlanningOutcome

    /**
     * The service, or the model behind it, could not be reached in time.
     *
     * [retryAfterMs] is the service's own word on when to try again, when it
     * gave one. [correlationId] ties this attempt to the service's log line
     * for it; it identifies nothing else.
     */
    data class Unreachable(
        val why: String,
        val kind: PlanningFailure = PlanningFailure.Offline,
        val retryAfterMs: Long? = null,
        val correlationId: String = "",
    ) : PlanningOutcome

    /** The service answered, but not with anything that can be acted on. */
    data class Refused(
        val why: String,
        val kind: PlanningFailure = PlanningFailure.ServiceProblem,
        val retryable: Boolean = false,
        val retryAfterMs: Long? = null,
        val correlationId: String = "",
    ) : PlanningOutcome
}

/**
 * Why no decision came back.
 *
 * Distinct because each calls for something different. A moment's throttling
 * is worth waiting out, briefly; an exhausted quota or a spending cap will
 * fail the same way until somebody changes a setting, and asking again only
 * makes a storm of refusals; a phone with no connection is the person's to
 * fix. [transient] says which kind of problem waiting can solve.
 */
enum class PlanningFailure(val transient: Boolean) {
    /** The phone itself has no usable connection: the person's to fix. */
    Offline(true),

    /**
     * The phone is online, but Dot's own service did not answer: refused the
     * connection, or could not be found or reached. Not the person's Wi-Fi —
     * saying so sent people to check a connection that was fine.
     */
    ServiceDown(true),

    /** Dot's own service limit, which exists to stop a loop running up a bill. */
    LocalLimit(true),

    /** The model's provider asked for a short pause, and usually said how long. */
    Throttled(true),

    /** The provider said "too many" without saying which limit. Treated cautiously. */
    ProviderLimited(true),

    /** The provider is overloaded or could not be reached from the service. */
    Capacity(true),

    SlowAnswer(true),
    UnusableAnswer(true),

    /** Anything else: tried again, but only a little. */
    Unknown(true),

    /**
     * Something went wrong inside TapBack itself — an answer it could not
     * read, a bug — not the network and not the service. Never reported as
     * an outage; trying again by hand is offered, not repeated by itself.
     */
    InternalError(false),

    /** A quota, a spending cap or billing: nothing will work until it is changed. */
    QuotaExhausted(false),

    /** The service or its provider is set up wrongly: a key, a model name. */
    ServiceProblem(false),

    /**
     * The service refused this request's shape (too large, a field out of
     * range). Asking again with the same screen gets the same refusal, so it
     * is not retried; a different screen may well work.
     */
    RequestRejected(false),

    /** This task has no admission: its course needs Dot Plus, or there are no guided tasks left. */
    NotAdmitted(false),

    /** This task, or today, has used a great deal of help: stopped as a guard against a loop. */
    UsageLimit(false),
    NotAllowed(false),
    NotConfigured(false),
}

/**
 * Where the next step comes from.
 *
 * An interface so the session loop can be driven without a network: the
 * interesting failures in that loop are about timing — an answer arriving
 * late, a page moving underneath — and those cannot be produced by hand.
 */
interface PlanningService {
    val configured: Boolean
    suspend fun nextStep(request: PlanningRequest): PlanningOutcome
}

/**
 * Asks Dot's planning service for the next step.
 *
 * Deliberately small and dependency-free: one endpoint, `HttpURLConnection`
 * and the framework's own JSON. Adding a networking stack for a single POST
 * would be more code to audit for an app whose whole claim is that it reads
 * screens carefully.
 *
 * The provider key is never here. The phone holds a token for Dot's own
 * service; the service holds the key. A decompiled APK yields a token that is
 * useless without the server, and in a release build it yields neither.
 *
 * Cancellation is honoured, and is not a failure. The connection is blocking
 * I/O that coroutine cancellation cannot interrupt, so cancelling disconnects
 * it — the request stops rather than completing unobserved — and the
 * cancellation propagates rather than being reported as "could not reach the
 * internet", which is what it used to be.
 */
class AiClient(
    private val baseUrl: String = BuildConfig.DOT_BACKEND_URL,
    private val token: String = BuildConfig.DOT_BACKEND_TOKEN,
    /**
     * Whether the phone has a working connection right now, when it can say.
     * Tells "your phone is offline" from "Dot's service is not answering",
     * which the exception alone cannot: both surface as a failed connect.
     */
    private val networkUp: () -> Boolean? = { null },
    /** Who is asking: this install's id and secret, when it has them. */
    private val identity: () -> Pair<String, String>? = { null },
) : PlanningService {

    /**
     * False when this build was not given somewhere to ask. A developer's
     * build has a local address and the shared token; a release build has an
     * HTTPS address and no token, and proves itself by its install identity.
     */
    override val configured: Boolean get() = serviceConfigured(baseUrl, token)

    override suspend fun nextStep(request: PlanningRequest): PlanningOutcome {
        if (!configured) {
            return PlanningOutcome.Refused(
                "this build has no planning service configured",
                PlanningFailure.NotConfigured,
            )
        }
        val payload = encode(request).toString().toByteArray()
        return withContext(Dispatchers.IO) {
            val connection = (URL("$baseUrl/v1/next-step").openConnection() as HttpURLConnection)
            val onCancel = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) connection.disconnect()
            }
            val phase = Phase()
            try {
                exchange(connection, payload, phase, request.context.taskId).also { outcome ->
                    ensureActive()
                    ServiceTrace.record("/v1/next-step", phase, outcome)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: java.io.IOException) {
                // Only the network, or the service not answering, gets here.
                // A disconnect caused by cancellation surfaces as an I/O error
                // too; if that is what happened, say so rather than blaming
                // the network.
                ensureActive()
                val kind = transportFailure(io, phase.sent, networkUp())
                ServiceTrace.failed("/v1/next-step", phase, io, kind)
                PlanningOutcome.Unreachable("the service could not be reached", kind)
            } catch (bug: Exception) {
                // A mistake on the phone — reading an answer, building a
                // request — is not the service being down, and is not
                // retried as though it were.
                ensureActive()
                ServiceTrace.failed("/v1/next-step", phase, bug, PlanningFailure.InternalError)
                PlanningOutcome.Refused("TapBack could not handle this request", PlanningFailure.InternalError)
            } finally {
                onCancel?.dispose()
                connection.disconnect()
            }
        }
    }

    /** How far a request got: whether the service had the whole of it, and when. */
    internal class Phase {
        val startedAt: Long = android.os.SystemClock.elapsedRealtime()
        @Volatile var sent = false
        @Volatile var status: Int = 0
        @Volatile var correlationId: String = ""

        /** The step a failure happened in, as a fixed word. */
        val name: String get() = when {
            status != 0 -> "answered"
            sent -> "awaiting"
            else -> "connecting"
        }
        val elapsedMs: Long get() = android.os.SystemClock.elapsedRealtime() - startedAt
    }

    private fun exchange(connection: HttpURLConnection, payload: ByteArray, phase: Phase, taskId: String): PlanningOutcome {
        val started = System.currentTimeMillis()
        connection.apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            identity()?.let { (user, key) ->
                setRequestProperty("X-Dot-User", user)
                setRequestProperty("X-Dot-Key", key)
            }
            taskId.takeIf { it.isNotBlank() }?.let { setRequestProperty("X-Dot-Task", it) }
        }
        connection.outputStream.use { it.write(payload) }
        phase.sent = true

        val code = connection.responseCode
        phase.status = code
        phase.correlationId = connection.getHeaderField("X-Dot-Correlation").orEmpty()
        if (code !in 200..299) {
            // The body names a category, whether to retry and when, and never
            // contains screen content or the provider's own words.
            val detail = try {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                    ?.let { JSONObject(it).optJSONObject("detail") }
            } catch (_: Exception) {
                null
            }
            val category = detail?.optString("category").orEmpty()
            val retryAfterMs = retryAfterMs(detail, connection.getHeaderField("Retry-After"))
            val correlation = detail?.optString("correlation_id").orEmpty()
                .ifBlank { connection.getHeaderField("X-Dot-Correlation").orEmpty() }
            GuideLog.d("ai") {
                "planning service said $code category=${category.ifBlank { "-" }} " +
                    "reason=${detail?.optString("reason").orEmpty().ifBlank { "-" }} " +
                    "retryAfterMs=$retryAfterMs cid=${correlation.ifBlank { "-" }}"
            }
            return failureFor(code, category, retryAfterMs, correlation)
        }

        val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
        // A body that is not the agreed shape is an unusable answer, not "no
        // internet": it came back, it just cannot be acted on.
        val decision = runCatching { parseDecision(JSONObject(body).getJSONObject("decision")) }.getOrNull()
            ?: return PlanningOutcome.Refused(
                "the answer was not a usable decision",
                PlanningFailure.UnusableAnswer,
                retryable = true,
                correlationId = connection.getHeaderField("X-Dot-Correlation").orEmpty(),
            )
        return PlanningOutcome.Decided(decision, System.currentTimeMillis() - started)
    }

    private fun encode(request: PlanningRequest): JSONObject {
        val o = request.observation
        val candidates = JSONArray()
        for (c in o.candidates) {
            candidates.put(
                JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("description", c.description)
                    .put("role", c.role)
                    .put(
                        "bounds",
                        JSONArray()
                            .put(c.bounds.left).put(c.bounds.top)
                            .put(c.bounds.right).put(c.bounds.bottom),
                    )
                    .put("clickable", c.clickable)
                    .put("editable", c.editable)
                    .put("enabled", c.enabled)
                    .put("scrollable", c.scrollable)
                    .put("horizontal", c.horizontal)
                    .put("checked", c.checked ?: JSONObject.NULL)
                    .put("selected", c.selected)
                    .put("focused", c.focused)
                    .put("value", c.value)
                    .put("hint", c.hint)
                    .put("readiness", c.readiness.wire)
                    .put("error", c.error)
                    .put("parent", c.parentId ?: JSONObject.NULL),
            )
        }

        val observation = JSONObject()
            .put("observation_id", o.observationId)
            .put("taken_at_ms", o.takenAtMs)
            .put("package_name", o.packageName)
            .put("window_title", o.windowTitle)
            .put("screen_width", o.screenWidth)
            .put("screen_height", o.screenHeight)
            .put("candidates", candidates)
            .put(
                "information",
                JSONArray().apply {
                    o.readable.forEach { r ->
                        put(
                            JSONObject().put("id", r.id).put("text", r.label.take(120)).put(
                                "bounds",
                                JSONArray().put(r.bounds.left).put(r.bounds.top).put(r.bounds.right).put(r.bounds.bottom),
                            ),
                        )
                    }
                },
            )
            .put("scroll_containers", JSONArray(o.scrollContainerIds))
            .put("truncated", o.truncated)
            .put("keyboard_visible", o.keyboardVisible)
            .put("home_screen", o.homeScreen)
            .put("screenshot_note", o.screenshotNote)
            .put("notices", JSONArray(o.notices))

        val answers = JSONArray()
        // The service requires both halves; an answer to a question whose
        // words were not kept is still an answer.
        request.answers.filter { it.answer.isNotBlank() }.takeLast(8).forEach {
            answers.put(
                JSONObject()
                    .put("question", it.question.trim().ifBlank { "TapBack asked a question" }.take(200))
                    .put("answer", it.answer.trim().take(200)),
            )
        }

        return JSONObject()
            .put("session_id", o.sessionId)
            .put("goal", request.goal.trim().take(MAX_GOAL))
            .put("observation", observation)
            .put("answers", answers)
            .put("progress", JSONArray(request.progress.takeLast(10).map { it.take(160) }))
            .put("subgoal", request.subgoal.take(160))
            .put("task_details", JSONObject(request.taskDetails.filterKeys { it in TaskDetails.KEYS } as Map<*, *>))
            .put("failed_approaches", JSONArray(request.failedApproaches.takeLast(6).map { it.take(160) }))
            .put("recent_apps", JSONArray(request.recentApps.takeLast(6)))
            .put("last_instruction", request.lastInstruction.take(300))
            .put("last_expected_outcome", request.lastExpectedOutcome.take(300))
            .put("last_kind", request.lastKind.take(20))
            .put("reported_problem", request.reportedProblem)
            .put(
                "device",
                JSONObject()
                    .put("navigation", request.device.navigation.wire)
                    .put("sdk", request.device.sdk)
                    .put("font_scale", request.device.fontScale.toDouble()),
            )
            .put("attempt", request.attempt)
            .put("trigger", request.trigger.filter { it in 'a'..'z' || it == '_' }.take(30))
            .apply {
                request.context.app?.let { app ->
                    put(
                        "target_app",
                        JSONObject()
                            .put("name", app.name.take(60))
                            .put("package_name", app.packageName.take(200))
                            .put("publisher", app.publisher.take(80))
                            .put("label", app.label.take(60))
                            .put("install_state", app.installState.wire),
                    )
                }
                request.context.lesson?.let { lesson ->
                    put(
                        "lesson",
                        JSONObject()
                            .put("course", lesson.courseTitle.take(80))
                            .put("title", lesson.lessonTitle.take(80))
                            .put("objective", lesson.objective.take(300))
                            .put("done_when", lesson.doneWhen.take(300))
                            .put("rules", JSONArray(lesson.rules.take(8).map { it.take(200) }))
                            .put("needs_account", lesson.needsAccount)
                            .put("learner_confirms", lesson.learnerConfirms)
                            .put("explained", JSONArray(request.explained.take(8)))
                            .apply {
                                lesson.practice?.let { p ->
                                    put(
                                        "practice",
                                        JSONObject()
                                            .put("intro", p.intro.take(160))
                                            .put("example", p.example.take(160))
                                            .put("type_text", p.typeText.take(160))
                                            .put("checkpoints", JSONArray(p.checkpoints.take(6).map { it.take(160) }))
                                            .put("own_choice", p.ownChoice.take(160))
                                            .put("stop_at", p.stopAt.take(160)),
                                    )
                                }
                            },
                    )
                }
                o.screenshotJpeg?.let {
                    put("screenshot_jpeg_b64", Base64.encodeToString(it, Base64.NO_WRAP))
                }
            }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8_000

        /** Somewhere to ask, and a way to be let in: the shared token, or HTTPS with install identities. */
        fun serviceConfigured(baseUrl: String, token: String): Boolean =
            baseUrl.isNotBlank() && (token.isNotBlank() || baseUrl.startsWith("https://"))

        /** The longest goal the service accepts; Ask's box stops there too. */
        const val MAX_GOAL = 500

        /** A little past the server's own provider timeout, so the server answers first. */
        const val READ_TIMEOUT_MS = 30_000

        /**
         * What a failed exchange means, from how it failed and how far it got.
         *
         * Once the whole request was sent, a timeout is a slow answer: the
         * service has it and is working. Before that, the service could not
         * be reached — and whether that is the phone being offline or Dot's
         * service being down is told by the phone's own connection, not by
         * the exception, which reads the same for both.
         */
        internal fun transportFailure(error: Throwable, sent: Boolean, networkUp: Boolean?): PlanningFailure = when {
            error is javax.net.ssl.SSLException -> PlanningFailure.ServiceProblem
            sent && error is java.net.SocketTimeoutException -> PlanningFailure.SlowAnswer
            networkUp == false -> PlanningFailure.Offline
            error is java.net.UnknownHostException && networkUp == null -> PlanningFailure.Offline
            else -> PlanningFailure.ServiceDown
        }

        /** The service's own delay, from its body or its Retry-After header, within reason. */
        internal fun retryAfterMs(detail: JSONObject?, header: String?): Long? {
            val seconds = detail?.optDouble("retry_after_s")?.takeIf { !it.isNaN() }
                ?: header?.trim()?.toDoubleOrNull()
            return seconds?.takeIf { it > 0 }?.let { (it * 1000).toLong().coerceAtMost(MAX_HONOURED_DELAY_MS) }
        }

        internal fun failureFor(
            code: Int,
            category: String,
            retryAfterMs: Long? = null,
            correlationId: String = "",
        ): PlanningOutcome {
            fun unreachable(why: String, kind: PlanningFailure) =
                PlanningOutcome.Unreachable(why, kind, retryAfterMs, correlationId)
            fun refused(why: String, kind: PlanningFailure) =
                PlanningOutcome.Refused(why, kind, kind.transient, retryAfterMs, correlationId)
            return when {
                category == "no_allowance" || category == "not_admitted" || category == "premium_course" ->
                    refused("this task was not admitted: $category", PlanningFailure.NotAdmitted)
                category == "task_limit" || category == "daily_limit" ->
                    refused("a usage guard was reached: $category", PlanningFailure.UsageLimit)
                code == 401 || code == 403 -> refused("this phone is not allowed to ask", PlanningFailure.NotAllowed)
                category == "local_rate_limit" || (code == 429 && category.isBlank()) ->
                    unreachable("TapBack's own limit was reached", PlanningFailure.LocalLimit)
                category == "provider_throttled" -> unreachable("the provider asked for a pause", PlanningFailure.Throttled)
                // "rate_limited" is what the service said before it could tell limits apart.
                category == "provider_limited" || category == "rate_limited" ->
                    unreachable("the provider said too many, without saying which limit", PlanningFailure.ProviderLimited)
                category == "quota_exhausted" -> refused("the provider's quota or spending cap is used up", PlanningFailure.QuotaExhausted)
                category == "provider_capacity" || category == "network" ->
                    unreachable("the provider is not answering just now", PlanningFailure.Capacity)
                code == 504 || category == "timeout" -> unreachable("the answer took too long", PlanningFailure.SlowAnswer)
                category == "not_configured" -> refused("no model is configured on the server", PlanningFailure.NotConfigured)
                category == "auth" || category == "model_not_found" ->
                    refused("the service is misconfigured: $category", PlanningFailure.ServiceProblem)
                // The provider refused what the service built from this screen,
                // or the service refused what the phone sent: the same request
                // gets the same answer.
                category == "bad_request" || category == "request_rejected" || code == 400 || code == 413 || code == 422 ->
                    refused("the request was refused: ${category.ifBlank { code.toString() }}", PlanningFailure.RequestRejected)
                category == "unusable_answer" -> refused("no usable answer", PlanningFailure.UnusableAnswer)
                // Nothing at that address speaks Dot's protocol: a wrong URL
                // or a service from an older version.
                code == 404 || code == 405 -> refused("the service does not know this request", PlanningFailure.ServiceProblem)
                else -> unreachable("the service answered $code", PlanningFailure.Unknown)
            }
        }

        /** The longest server-requested wait worth honouring; anything longer is "not now". */
        const val MAX_HONOURED_DELAY_MS = 60_000L

        /** Unknown kinds, and anything missing what its kind needs, are refused rather than guessed at. */
        internal fun parseDecision(json: JSONObject): AiDecision? {
            val observationId = json.optString("observation_id").ifBlank { return null }
            val meta = DecisionMeta(
                assessment = when (json.optString("assessment")) {
                    "succeeded" -> Assessment.Succeeded
                    "failed" -> Assessment.Failed
                    "unclear" -> Assessment.Unclear
                    else -> Assessment.NotApplicable
                },
                progressNote = json.optString("progress_note").take(160),
                subgoal = json.optString("subgoal").take(160),
                practiceChoice = json.optString("practice_choice").take(com.aaravsekhri.dot.courses.CourseProgressStore.MAX_EXAMPLE),
                taskDetails = json.optJSONObject("task_details")?.let { details ->
                    TaskDetails.KEYS.associateWith { details.optString(it).trim().take(TaskDetails.MAX_VALUE) }
                        .filterKeys { details.has(it) }
                }.orEmpty(),
            )
            fun text(key: String) = json.optString(key).takeUnless { json.isNull(key) }.orEmpty()
            return when (json.optString("kind")) {
                "highlight" -> AiDecision.Highlight(
                    observationId = observationId,
                    candidateId = text("candidate_id").ifBlank { return null },
                    instruction = text("instruction").ifBlank { return null },
                    expectedOutcome = text("expected_outcome"),
                    meta = meta,
                )

                "type_text" -> AiDecision.TypeText(
                    observationId = observationId,
                    candidateId = text("candidate_id").ifBlank { return null },
                    // May be empty: the phone words a typing step itself.
                    instruction = text("instruction"),
                    text = text("text"),
                    expectedOutcome = text("expected_outcome"),
                    meta = meta,
                )

                "submit" -> {
                    val viaKeyboard = when (text("via")) {
                        "keyboard" -> true
                        "control" -> false
                        else -> return null
                    }
                    val candidate = text("candidate_id").ifBlank { null }
                    if (!viaKeyboard && candidate == null) return null
                    AiDecision.Submit(
                        observationId = observationId,
                        viaKeyboard = viaKeyboard,
                        candidateId = if (viaKeyboard) null else candidate,
                        // May be empty: the phone words this step itself.
                        instruction = text("instruction"),
                        expectedOutcome = text("expected_outcome"),
                        meta = meta,
                    )
                }

                "scroll" -> AiDecision.Scroll(
                    observationId = observationId,
                    reveal = when (text("reveal")) {
                        "below" -> Reveal.Below
                        "above" -> Reveal.Above
                        "right" -> Reveal.Right
                        "left" -> Reveal.Left
                        else -> return null
                    },
                    containerId = text("container_id").ifBlank { null },
                    lookingFor = text("looking_for"),
                    expectedOutcome = text("expected_outcome"),
                    meta = meta,
                )

                "navigate" -> AiDecision.Navigate(
                    observationId = observationId,
                    destination = when (text("to")) {
                        "home" -> Destination.Home
                        "back" -> Destination.Back
                        else -> return null
                    },
                    reason = text("reason"),
                    expectedOutcome = text("expected_outcome"),
                    meta = meta,
                )

                "ask" -> AiDecision.Ask(
                    observationId = observationId,
                    question = text("question").ifBlank { return null },
                    options = json.optJSONArray("options")?.let { array ->
                        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
                    }.orEmpty().take(3),
                    meta = meta,
                    freeText = json.optBoolean("free_text", false),
                    personal = json.optBoolean("personal", false),
                )

                "wait" -> AiDecision.Wait(
                    observationId = observationId,
                    reason = text("reason"),
                    recheckAfterMs = json.optLong("recheck_after_ms", 2_000L).coerceIn(500L, 15_000L),
                    maxRechecks = json.optInt("max_rechecks", 4).coerceIn(1, 10),
                    meta = meta,
                )

                "manual" -> AiDecision.Manual(
                    observationId = observationId,
                    instruction = text("instruction").ifBlank { return null },
                    reason = text("reason"),
                    meta = meta,
                    candidateId = text("candidate_id").ifBlank { null },
                )

                "explain" -> AiDecision.Explain(
                    observationId = observationId,
                    checkpointId = text("checkpoint_id").ifBlank { return null }.take(8),
                    candidateId = text("candidate_id").takeIf(String::isNotBlank),
                    missing = json.optBoolean("missing", false),
                    instruction = text("instruction"),
                    meta = meta,
                )

                "complete" -> AiDecision.Complete(
                    observationId = observationId,
                    summary = text("summary").ifBlank { return null },
                    evidence = text("evidence"),
                    meta = meta,
                )

                "blocked" -> AiDecision.Blocked(
                    observationId = observationId,
                    explanation = text("explanation").ifBlank { return null },
                    suggestion = text("suggestion"),
                    meta = meta,
                    unavailable = json.optBoolean("unavailable", false),
                    appError = json.optBoolean("app_error", false),
                )

                else -> null
            }
        }
    }
}

/** A question Dot asked and the answer it got, kept together. */
data class QuestionAnswer(val question: String, val answer: String)

/** How this phone gets to its Home screen, as far as it can be known. */
enum class NavigationStyle(val wire: String) {
    Gestures("gestures"),
    ThreeButton("three_button"),
    TwoButton("two_button"),
    Unknown("unknown"),
}

data class DeviceContext(
    val navigation: NavigationStyle = NavigationStyle.Unknown,
    val sdk: Int = 0,
    val fontScale: Float = 1f,
)

/** Everything the service needs to choose the next step. */
data class PlanningRequest(
    val goal: String,
    val observation: Observation,
    val answers: List<QuestionAnswer> = emptyList(),
    val progress: List<String> = emptyList(),
    val subgoal: String = "",
    val failedApproaches: List<String> = emptyList(),
    val recentApps: List<String> = emptyList(),
    val lastInstruction: String = "",
    val lastExpectedOutcome: String = "",
    val lastKind: String = "",
    val reportedProblem: Boolean = false,
    val device: DeviceContext = DeviceContext(),
    val attempt: Int = 0,
    /** Why this request is being made — a short fixed word, for measuring cost. */
    val trigger: String = "",
    /** Which app and which lesson; alongside the goal, never instead of it. */
    val context: TaskContext = TaskContext(),
    /** What the person has settled about the task; see [DecisionMeta.taskDetails]. */
    val taskDetails: Map<String, String> = emptyMap(),
    /** In a lesson: the teaching points already shown and acknowledged, by id. */
    val explained: List<String> = emptyList(),
)

/**
 * The few facts about a task worth keeping as facts rather than as chat:
 * where to, what to search for, who to contact. Personal, so kept only with
 * the unfinished task's checkpoint, sent only to plan the task, and never
 * logged.
 */
object TaskDetails {
    val KEYS = listOf("destination", "search_text", "contact")
    const val MAX_VALUE = 120
}

/**
 * The one other exchange with Dot's service: small JSON about the person —
 * who they are, what they may start — for [com.aaravsekhri.dot.account.AccountClient].
 *
 * Here, beside [AiClient], so every connection this app opens is in one file
 * to audit. Nothing from the screen is ever sent through it.
 */
internal object DotServiceHttp {

    /** What came back: a status and its body, or how the exchange failed. */
    sealed interface Result {
        data class Answered(val code: Int, val body: String) : Result

        /** [transport] true for the network or the service; false for a mistake on the phone. */
        data class Failed(val error: Exception, val sent: Boolean, val transport: Boolean = true) : Result
    }

    fun exchange(url: String, method: String, headers: Map<String, String>, body: String?, readTimeoutMs: Int): Result {
        val path = runCatching { URL(url).path }.getOrDefault("?")
        val phase = AiClient.Phase()
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            ServiceTrace.failed(path, phase, e, if (e is java.io.IOException) PlanningFailure.ServiceProblem else PlanningFailure.InternalError)
            return Result.Failed(e, sent = false, transport = e is java.io.IOException)
        }
        var sent = false
        return try {
            connection.requestMethod = method
            connection.connectTimeout = AiClient.CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            sent = true
            phase.sent = true
            val code = connection.responseCode
            phase.status = code
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val category = if (code in 200..299) "" else ServiceTrace.categoryOf(text)
            ServiceTrace.answered(path, phase, code, category, connection.getHeaderField("X-Dot-Correlation").orEmpty())
            Result.Answered(code, text)
        } catch (e: java.io.IOException) {
            ServiceTrace.failed(path, phase, e, AiClient.transportFailure(e, sent, null))
            Result.Failed(e, sent, transport = true)
        } catch (e: Exception) {
            ServiceTrace.failed(path, phase, e, PlanningFailure.InternalError)
            Result.Failed(e, sent, transport = false)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * What happened to recent requests to TapBack's service, for developers:
 * which endpoint, how far it got, how long it took, the status, the failure's
 * category and its correlation id. Never a URL's query, a header, a body, a
 * screen, a key or anything typed. Logged, and kept in memory for the
 * debug build's diagnostics.
 */
object ServiceTrace {

    data class Entry(
        val atMs: Long,
        val path: String,
        val phase: String,
        val elapsedMs: Long,
        val status: Int,
        val category: String,
        val error: String,
        val correlationId: String,
    ) {
        fun line(): String =
            "$path phase=$phase ${elapsedMs}ms http=${if (status == 0) "-" else status} " +
                "category=${category.ifBlank { "-" }} error=${error.ifBlank { "-" }} cid=${correlationId.ifBlank { "-" }}"
    }

    private const val KEEP = 20
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun recent(): List<Entry> = entries.toList().asReversed()

    @Synchronized
    private fun add(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > KEEP) entries.removeFirst()
        GuideLog.d("trace") { entry.line() }
    }

    internal fun answered(path: String, phase: AiClient.Phase, status: Int, category: String, cid: String) =
        add(Entry(System.currentTimeMillis(), path, "answered", phase.elapsedMs, status, category, "", cid))

    internal fun failed(path: String, phase: AiClient.Phase, error: Throwable, kind: PlanningFailure) =
        add(Entry(System.currentTimeMillis(), path, phase.name, phase.elapsedMs, phase.status, kind.name, error.javaClass.simpleName, ""))

    internal fun record(path: String, phase: AiClient.Phase, outcome: PlanningOutcome) {
        when (outcome) {
            is PlanningOutcome.Decided -> answered(path, phase, phase.status, "", phase.correlationId)
            is PlanningOutcome.Refused -> add(
                Entry(System.currentTimeMillis(), path, phase.name, phase.elapsedMs, phase.status, outcome.kind.name, "", outcome.correlationId),
            )
            is PlanningOutcome.Unreachable -> add(
                Entry(System.currentTimeMillis(), path, phase.name, phase.elapsedMs, phase.status, outcome.kind.name, "", outcome.correlationId),
            )
        }
    }

    /** The category an error body names; only the fixed word, never anything else in it. */
    internal fun categoryOf(body: String): String =
        runCatching { JSONObject(body).optJSONObject("detail")?.optString("category").orEmpty() }.getOrDefault("")
            .filter { it.isLetterOrDigit() || it == '_' }.take(40)
}
