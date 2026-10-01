package com.aaravsekhri.dot.account

import android.content.Context
import androidx.core.content.edit
import com.aaravsekhri.dot.BuildConfig
import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.ai.AiClient
import com.aaravsekhri.dot.guide.ai.DotServiceHttp
import com.aaravsekhri.dot.guide.ai.PlanningFailure
import com.aaravsekhri.dot.guide.ai.PlanningOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Who this install is to Dot's service: an id, and a secret nobody else has. */
data class Identity(val userId: String, val key: String)

/**
 * What the person may start now, as Dot's service worked it out — from its
 * own ledger and from RevenueCat, never from anything the phone said.
 */
data class Allowance(
    val canStart: Boolean,
    val nextSource: String?,
    val welcomeLeft: Int,
    val freeWeeklyAvailable: Boolean,
    val plusLeft: Int?,
    /** When the next free task arrives, if none is available now. */
    val nextFreeAtMs: Long?,
    val plusActive: Boolean,
    val plusExpiresAtMs: Long?,
    val plusWillRenew: Boolean,
    /** "verified", "cached", "unverified" or "unconfigured". */
    val plusStatus: String,
    val tasksPerPeriod: Int,
    val freeCourses: Set<String>,
    val billingConfigured: Boolean,
    /** The service takes preview codes right now (a test set-up); never true in a public release's service by default. */
    val previewAvailable: Boolean = false,
    /** RevenueCat's own promotional grant (a preview made there), never a purchase. */
    val plusPromotional: Boolean = false,
    /** The store product behind Plus, as RevenueCat told the service; blank for a preview. */
    val plusProduct: String = "",
    /** How soon the service will ask RevenueCat afresh again; a forced refresh sooner gets its cached answer. */
    val refreshMinMs: Long = DEFAULT_REFRESH_MIN_MS,
) {
    fun isCourseFree(courseId: String): Boolean = courseId in freeCourses

    /**
     * The service has just asked RevenueCat and RevenueCat says [product] was
     * bought: active, verified (not a cached or unreachable answer), not a
     * promotional grant, not a preview. Nothing else confirms a purchase.
     */
    fun confirmsPurchase(product: String): Boolean =
        plusActive && plusStatus == "verified" && !plusPromotional &&
            plusProduct.substringBefore(':') == product

    /** Plus is a temporary preview the service granted — never a purchase, never renewing. */
    val plusPreview: Boolean get() = plusActive && plusStatus == "preview"

    companion object {
        fun parse(o: JSONObject): Allowance {
            val plus = o.optJSONObject("plus") ?: JSONObject()
            val courses = o.optJSONArray("free_courses")
            return Allowance(
                canStart = o.optBoolean("can_start"),
                nextSource = o.optString("next_source").takeUnless { o.isNull("next_source") || it.isBlank() },
                welcomeLeft = o.optInt("welcome_left"),
                freeWeeklyAvailable = o.optBoolean("free_weekly_available"),
                plusLeft = if (o.isNull("plus_left") || !o.has("plus_left")) null else o.optInt("plus_left"),
                nextFreeAtMs = if (o.isNull("next_free_at") || !o.has("next_free_at")) null else (o.optDouble("next_free_at") * 1000).toLong(),
                plusActive = plus.optBoolean("active"),
                plusExpiresAtMs = if (plus.isNull("expires_at") || !plus.has("expires_at")) null else (plus.optDouble("expires_at") * 1000).toLong(),
                plusWillRenew = plus.optBoolean("will_renew"),
                plusStatus = plus.optString("status"),
                tasksPerPeriod = plus.optInt("tasks_per_period", 10),
                freeCourses = buildSet { if (courses != null) for (i in 0 until courses.length()) add(courses.optString(i)) },
                billingConfigured = o.optBoolean("billing_configured"),
                previewAvailable = o.optBoolean("preview_available"),
                plusPromotional = plus.optBoolean("promotional"),
                plusProduct = plus.optString("product"),
                refreshMinMs = if (o.has("refresh_min_s")) (o.optDouble("refresh_min_s") * 1000).toLong() else DEFAULT_REFRESH_MIN_MS,
            )
        }

        /** The service's own minimum (backend/app/billing.py MIN_REFRESH_S), if it does not say. */
        const val DEFAULT_REFRESH_MIN_MS = 8_000L

        /** Before the service has said anything: the one free course, nothing assumed about Plus. */
        val UNKNOWN = Allowance(
            canStart = true, nextSource = null, welcomeLeft = 0, freeWeeklyAvailable = false, plusLeft = null,
            nextFreeAtMs = null, plusActive = false, plusExpiresAtMs = null, plusWillRenew = false,
            plusStatus = "unverified", tasksPerPeriod = 10, freeCourses = setOf("maps"), billingConfigured = false,
        )
    }
}

/** A preview grant: when it ends, and whether RevenueCat holds it too. */
data class PreviewGrant(val expiresAtMs: Long, val atRevenueCat: Boolean, val new: Boolean)

/** A task's admission: whether it may start, and where its credit came from. */
data class Admission(val admitted: Boolean, val source: String?, val reason: String, val allowance: Allowance)

/** What an account call came back as. */
sealed interface AccountResult<out T> {
    data class Ok<T>(val value: T) : AccountResult<T>

    /** Nothing was decided: the service could not be asked, or refused. [kind] says which, for honest words. */
    data class Failed(val kind: PlanningFailure, val category: String = "") : AccountResult<Nothing>
}

/**
 * Talks to Dot's service about the person: who they are, what they may start,
 * and tasks starting, being released and finishing.
 *
 * The identity is created once, by the service, and kept here: the id is
 * also the person's RevenueCat app user id, and the secret proves the id is
 * theirs. It is never logged. It follows the phone's backup, so a restored
 * phone is the same person.
 */
class AccountClient(
    context: Context,
    private val baseUrl: String = BuildConfig.DOT_BACKEND_URL,
    private val token: String = BuildConfig.DOT_BACKEND_TOKEN,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val configured: Boolean get() = AiClient.serviceConfigured(baseUrl, token)

    /** The identity already held, without asking the service. */
    fun cachedIdentity(): Identity? {
        val id = prefs.getString(KEY_USER, null) ?: return null
        val key = prefs.getString(KEY_SECRET, null) ?: return null
        return Identity(id, key)
    }

    /** The last allowance the service gave, for showing while offline. Never used to admit anything. */
    fun cachedAllowance(): Allowance? = prefs.getString(KEY_ALLOWANCE, null)?.let {
        runCatching { Allowance.parse(JSONObject(it)) }.getOrNull()
    }

    suspend fun identity(): AccountResult<Identity> = registering.withLock {
        cachedIdentity()?.let { return@withLock AccountResult.Ok(it) }
        when (val r = call("POST", "/v1/identity", JSONObject(), identity = null)) {
            is AccountResult.Failed -> r
            is AccountResult.Ok -> {
                val id = r.value.optString("user_id")
                val key = r.value.optString("key")
                if (id.isBlank() || key.isBlank()) return@withLock AccountResult.Failed(PlanningFailure.UnusableAnswer)
                prefs.edit { putString(KEY_USER, id).putString(KEY_SECRET, key) }
                GuideLog.d("account") { "identity created" }
                AccountResult.Ok(Identity(id, key))
            }
        }
    }

    suspend fun allowance(refresh: Boolean = false): AccountResult<Allowance> =
        withIdentity { who ->
            call(if (refresh) "POST" else "GET", if (refresh) "/v1/entitlement/refresh" else "/v1/allowance", null, who)
        }.map { json -> Allowance.parse(json).also { remember(json) } }

    suspend fun admit(taskId: String, kind: String, courseId: String = ""): AccountResult<Admission> =
        withIdentity { who ->
            call("POST", "/v1/tasks", JSONObject().put("task_id", taskId).put("kind", kind).put("course_id", courseId), who)
        }.map { json ->
            val allowanceJson = json.optJSONObject("allowance") ?: JSONObject()
            remember(allowanceJson)
            Admission(
                admitted = json.optBoolean("admitted"),
                source = json.optString("source").takeUnless { json.isNull("source") },
                reason = json.optString("reason"),
                allowance = Allowance.parse(allowanceJson),
            )
        }

    /** Give the credit back, if Dot never helped with the task. The service decides; this only asks. */
    suspend fun release(taskId: String): AccountResult<Boolean> =
        withIdentity { who -> call("POST", "/v1/tasks/${encode(taskId)}/release", JSONObject(), who) }
            .map { json -> json.optBoolean("released").also { json.optJSONObject("allowance")?.let(::remember) } }

    /**
     * A preview code, checked by the service for this install's own
     * identity. The code is sent once and kept nowhere on the phone.
     */
    suspend fun redeemPreview(code: String): AccountResult<PreviewGrant> =
        withIdentity { who -> call("POST", "/v1/preview/redeem", JSONObject().put("code", code.trim()), who) }
            .map { json ->
                json.optJSONObject("allowance")?.let(::remember)
                val p = json.optJSONObject("preview") ?: JSONObject()
                PreviewGrant(
                    expiresAtMs = (p.optDouble("expires_at", 0.0) * 1000).toLong(),
                    atRevenueCat = p.optBoolean("revenuecat"),
                    new = p.optBoolean("new"),
                )
            }

    /** Ends this install's preview now. */
    suspend fun revokePreview(): AccountResult<Boolean> =
        withIdentity { who -> call("POST", "/v1/preview/revoke", JSONObject(), who) }
            .map { json -> json.optBoolean("revoked").also { json.optJSONObject("allowance")?.let(::remember) } }

    suspend fun finish(taskId: String): AccountResult<Unit> =
        withIdentity { who -> call("POST", "/v1/tasks/${encode(taskId)}/finish", JSONObject(), who) }.map { }

    // ------------------------------------------------------------ internals

    private val registering = Mutex()

    private fun remember(allowance: JSONObject) = prefs.edit { putString(KEY_ALLOWANCE, allowance.toString()) }

    private suspend fun withIdentity(block: suspend (Identity) -> AccountResult<JSONObject>): AccountResult<JSONObject> {
        val who = when (val r = identity()) {
            is AccountResult.Failed -> return r
            is AccountResult.Ok -> r.value
        }
        val first = block(who)
        // The service no longer knows this identity (its ledger was reset):
        // a new one, once, rather than failing forever.
        if (first is AccountResult.Failed && first.category == "unknown_identity") {
            prefs.edit { remove(KEY_USER).remove(KEY_SECRET) }
            val again = identity()
            if (again is AccountResult.Ok) return block(again.value)
        }
        return first
    }

    private suspend fun call(method: String, path: String, body: JSONObject?, identity: Identity?): AccountResult<JSONObject> {
        if (!configured) return AccountResult.Failed(PlanningFailure.NotConfigured)
        return withContext(Dispatchers.IO) {
            val headers = buildMap {
                if (token.isNotBlank()) put("Authorization", "Bearer $token")
                identity?.let {
                    put("X-Dot-User", it.userId)
                    put("X-Dot-Key", it.key)
                }
            }
            when (val r = DotServiceHttp.exchange(baseUrl + path, method, headers, body?.toString(), READ_TIMEOUT_MS)) {
                is DotServiceHttp.Result.Failed -> {
                    val kind = if (r.transport) AiClient.transportFailure(r.error, r.sent, null) else PlanningFailure.InternalError
                    GuideLog.d("account") { "$path failed: ${r.error.javaClass.simpleName} kind=$kind" }
                    AccountResult.Failed(kind)
                }
                is DotServiceHttp.Result.Answered -> {
                    if (r.code !in 200..299) {
                        val category = runCatching { JSONObject(r.body).optJSONObject("detail")?.optString("category") }.getOrNull().orEmpty()
                        GuideLog.d("account") { "$path answered ${r.code} category=${category.ifBlank { "-" }}" }
                        val kind = when (val outcome = AiClient.failureFor(r.code, category)) {
                            is PlanningOutcome.Unreachable -> outcome.kind
                            is PlanningOutcome.Refused -> outcome.kind
                            else -> PlanningFailure.Unknown
                        }
                        AccountResult.Failed(kind, category)
                    } else {
                        runCatching { AccountResult.Ok(JSONObject(r.body)) }.getOrElse { AccountResult.Failed(PlanningFailure.UnusableAnswer) }
                    }
                }
            }
        }
    }

    private fun encode(taskId: String) = java.net.URLEncoder.encode(taskId, "UTF-8")

    companion object {
        private const val PREFS = "dot_account"
        private const val KEY_USER = "user_id"
        private const val KEY_SECRET = "install_key"
        private const val KEY_ALLOWANCE = "allowance"
        private const val READ_TIMEOUT_MS = 12_000

        /** A new task's id: random, and the same one for every retry of that task's admission. */
        fun newTaskId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
    }
}

internal inline fun <T, R> AccountResult<T>.map(f: (T) -> R): AccountResult<R> = when (this) {
    is AccountResult.Ok -> AccountResult.Ok(f(value))
    is AccountResult.Failed -> this
}
