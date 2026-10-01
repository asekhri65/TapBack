package com.aaravsekhri.dot.account

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.aaravsekhri.dot.BuildConfig
import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.ai.PlanningFailure
import com.revenuecat.purchases.CacheFetchPolicy
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.models.Period
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Dot Plus product, as the store describes it: its own price and period, never ours. */
sealed interface OfferState {
    data object Loading : OfferState

    /**
     * Ready to buy: [price] and [period] exactly as the store formats them.
     * [pkg] is null only in a debug build's fixture preview, which cannot buy.
     */
    data class Ready(val price: String, val period: PlusPeriodText, internal val pkg: Package?) : OfferState

    /** Nothing to buy, and why, so the screen can say it honestly. */
    data class Unavailable(val why: Why) : OfferState {
        enum class Why { NotConfigured, NoOffering, Offline, Error }
    }
}

/** A billing period in words the screen can build its own sentence from. */
data class PlusPeriodText(val unit: Period.Unit?, val value: Int)

sealed interface PurchaseState {
    data object Idle : PurchaseState

    /** The store's purchase flow is open, or its answer is being confirmed. */
    data object Working : PurchaseState

    /** Looking for an earlier purchase to restore. */
    data object Restoring : PurchaseState

    /** TapBack's service confirmed a new purchase with RevenueCat. */
    data object Success : PurchaseState

    /** TapBack's service confirmed an earlier purchase, restored to this install. */
    data object Restored : PurchaseState

    /** The store took the payment, but Dot's service could not confirm it yet. */
    data object Unconfirmed : PurchaseState
    data object Cancelled : PurchaseState
    data object Pending : PurchaseState

    /** A restore that found nothing to restore. */
    data object NothingToRestore : PurchaseState
    data class Failed(val simulated: Boolean) : PurchaseState
}

data class PlusUiState(
    /** What Dot's service says the person may start; null until it has said anything. */
    val allowance: Allowance? = null,
    /** Why the allowance could not be fetched just now, if it could not. */
    val allowanceProblem: PlanningFailure? = null,
    val offer: OfferState = OfferState.Loading,
    val purchase: PurchaseState = PurchaseState.Idle,
    /** Where to manage the subscription, for a store that has a page for it. */
    val managementUrl: String? = null,
    /** Plus was bought through RevenueCat's Test Store: a demonstration, not a charge. */
    val testStore: Boolean = false,
)

/** What the paywall's buttons do; the repository in the app, a fixture in a debug preview. */
interface PlusActions {
    fun purchase(activity: Activity)
    fun retry(activity: Activity)
    fun restore()
    fun recheck()
    fun clearPurchaseMessage()
}

/** The one invitation, after the first guided task: right after it, or on a later visit. */
sealed interface Invitation {
    /** The task finished moments ago; [summary] is its own closing line, if still in memory. */
    data class Fresh(val summary: String?) : Invitation
    data object Later : Invitation
}

/**
 * Dot Plus: the offer, buying, restoring, and what the person may start.
 *
 * RevenueCat's SDK does the buying; what it says on the phone is only ever
 * used to show things. Whether someone may start a task comes from Dot's
 * service, which asks RevenueCat itself. So nothing here grants anything —
 * and without a key for this build, nothing here pretends to sell anything.
 */
class PlusRepository private constructor(context: Context) : PlusActions {

    private val app = context.applicationContext
    val accounts = AccountClient(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlusUiState(allowance = accounts.cachedAllowance()))
    val state: StateFlow<PlusUiState> = _state.asStateFlow()

    /** A public RevenueCat key for this build, of the kind this build may carry. */
    val billingAvailable: Boolean get() = BuildConfig.REVENUECAT_API_KEY.isNotBlank()

    /** This build sells through RevenueCat's Test Store: nothing is ever charged, and the screens say so. */
    val testStoreBuild: Boolean get() = BuildConfig.REVENUECAT_API_KEY.startsWith("test_")

    private val configuring = Mutex()
    private var lastRefreshAt = 0L

    /** When the service was last asked to check with RevenueCat afresh. */
    @Volatile
    private var lastVerifyAt = 0L

    /** On every return to Dot: what may be started, and what the store says, if it has been a while. */
    fun onForeground(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshAt < FOREGROUND_REFRESH_MS) return
        lastRefreshAt = now
        scope.launch { refreshAllowance() }
        scope.launch { loadOffer() }
    }

    suspend fun refreshAllowance(verify: Boolean = false): Allowance? {
        if (verify) lastVerifyAt = SystemClock.elapsedRealtime()
        return when (val r = accounts.allowance(refresh = verify)) {
            is AccountResult.Ok -> {
                _state.update { it.copy(allowance = r.value, allowanceProblem = null) }
                r.value
            }
            is AccountResult.Failed -> {
                _state.update { it.copy(allowanceProblem = r.kind) }
                null
            }
        }
    }

    /** An allowance the service has just given with another answer (an admission, a release). */
    fun refreshAllowanceFrom(allowance: Allowance) = _state.update { it.copy(allowance = allowance, allowanceProblem = null) }

    /** Why RevenueCat could not be used just now; see [purchases]. */
    private enum class Setup { Ready, NoBilling, NoIdentity, LoginFailed }

    @Volatile
    private var lastSetup = Setup.Ready

    /**
     * RevenueCat, set up with this person's TapBack id, so a purchase belongs
     * to the person TapBack's service will ask about. Null — never a guess —
     * when that cannot be made true: no billing in this build, no TapBack
     * identity yet, or RevenueCat still logged in as somebody else because
     * logging in failed. Buying then would put the subscription on the wrong
     * customer, and the service would never find it.
     */
    private suspend fun purchases(): Purchases? = configuring.withLock {
        if (!billingAvailable) return@withLock null.also { lastSetup = Setup.NoBilling }
        val who = (accounts.identity() as? AccountResult.Ok)?.value
            ?: return@withLock null.also { lastSetup = Setup.NoIdentity }
        if (!Purchases.isConfigured) {
            Purchases.configure(PurchasesConfiguration.Builder(app, BuildConfig.REVENUECAT_API_KEY).appUserID(who.userId).build())
        } else if (Purchases.sharedInstance.appUserID != who.userId) {
            try {
                Purchases.sharedInstance.awaitLogIn(who.userId)
            } catch (e: PurchasesException) {
                GuideLog.d("plus") { "RevenueCat log-in failed code=${e.code}" }
            }
        }
        if (Purchases.sharedInstance.appUserID != who.userId) {
            GuideLog.d("plus") { "RevenueCat is not on this person's id; not buying or reading" }
            return@withLock null.also { lastSetup = Setup.LoginFailed }
        }
        lastSetup = Setup.Ready
        Purchases.sharedInstance
    }

    /** What to say when [purchases] gave nothing. */
    private fun unavailableWhy(): OfferState.Unavailable.Why = when (lastSetup) {
        Setup.NoBilling -> OfferState.Unavailable.Why.NotConfigured
        Setup.NoIdentity -> OfferState.Unavailable.Why.Offline
        Setup.LoginFailed, Setup.Ready -> OfferState.Unavailable.Why.Error
    }

    suspend fun loadOffer() {
        if (!billingAvailable) {
            _state.update { it.copy(offer = OfferState.Unavailable(OfferState.Unavailable.Why.NotConfigured)) }
            return
        }
        val purchases = purchases()
        if (purchases == null) {
            _state.update { it.copy(offer = OfferState.Unavailable(unavailableWhy())) }
            return
        }
        _state.update { if (it.offer is OfferState.Ready) it else it.copy(offer = OfferState.Loading) }
        try {
            val offerings = purchases.awaitOfferings()
            val offering = offerings[OFFERING] ?: offerings.current
            // Only the weekly Plus product is sold here; any other package in
            // the offering is a set-up mistake, and the screen says so.
            val pkg = offering?.weekly?.takeIf { it.product.id.substringBefore(':') == PRODUCT }
                ?: offering?.availablePackages?.firstOrNull { it.product.id.substringBefore(':') == PRODUCT }
            if (pkg == null) GuideLog.d("plus") { "offering has no $PRODUCT package" }
            _state.update {
                it.copy(
                    offer = if (pkg == null) {
                        OfferState.Unavailable(OfferState.Unavailable.Why.NoOffering)
                    } else {
                        OfferState.Ready(
                            price = pkg.product.price.formatted,
                            period = PlusPeriodText(pkg.product.period?.unit, pkg.product.period?.value ?: 0),
                            pkg = pkg,
                        )
                    },
                )
            }
            readCustomer(purchases, CacheFetchPolicy.CACHED_OR_FETCHED)
        } catch (e: PurchasesException) {
            GuideLog.d("plus") { "offerings unavailable code=${e.code}" }
            val why = if (e.code == PurchasesErrorCode.NetworkError) OfferState.Unavailable.Why.Offline else OfferState.Unavailable.Why.Error
            _state.update { it.copy(offer = OfferState.Unavailable(why)) }
        }
    }

    /** What the store says about the subscription, for showing only: where to manage it, which store. */
    private suspend fun readCustomer(purchases: Purchases, policy: CacheFetchPolicy) {
        runCatching { purchases.awaitCustomerInfo(policy) }.getOrNull()?.let { info ->
            val entitlement = info.entitlements[ENTITLEMENT]
            _state.update {
                it.copy(
                    managementUrl = info.managementURL?.toString(),
                    testStore = entitlement?.store == Store.TEST_STORE,
                )
            }
        }
    }

    override fun purchase(activity: Activity) {
        val offer = _state.value.offer as? OfferState.Ready ?: return
        val pkg = offer.pkg ?: return
        if (_state.value.purchase == PurchaseState.Working || _state.value.purchase == PurchaseState.Restoring) return
        _state.update { it.copy(purchase = PurchaseState.Working) }
        scope.launch {
            val purchases = purchases() ?: return@launch _state.update { it.copy(purchase = PurchaseState.Failed(false)) }
            val result = try {
                purchases.awaitPurchase(PurchaseParams.Builder(activity, pkg).build())
                null
            } catch (e: PurchasesTransactionException) {
                when {
                    e.userCancelled || e.code == PurchasesErrorCode.PurchaseCancelledError -> PurchaseState.Cancelled
                    e.code == PurchasesErrorCode.PaymentPendingError -> PurchaseState.Pending
                    e.code == PurchasesErrorCode.ProductAlreadyPurchasedError -> null
                    else -> PurchaseState.Failed(simulated = e.code == PurchasesErrorCode.TestStoreSimulatedPurchaseError)
                }
            } catch (e: PurchasesException) {
                PurchaseState.Failed(false)
            }
            GuideLog.d("plus") { "purchase finished: ${result?.javaClass?.simpleName ?: "bought"}" }
            if (result != null) {
                _state.update { it.copy(purchase = result) }
                return@launch
            }
            confirmWithService(purchases, restored = false)
        }
    }

    /** "Try again" after the store could not be reached: the offer afresh, then the purchase if there is one. */
    override fun retry(activity: Activity) {
        _state.update { it.copy(purchase = PurchaseState.Idle) }
        scope.launch {
            loadOffer()
            if (_state.value.offer is OfferState.Ready) purchase(activity)
        }
    }

    override fun restore() {
        if (_state.value.purchase == PurchaseState.Working || _state.value.purchase == PurchaseState.Restoring) return
        _state.update { it.copy(purchase = PurchaseState.Restoring) }
        scope.launch {
            val purchases = purchases() ?: return@launch _state.update { it.copy(purchase = PurchaseState.Failed(false)) }
            val restored = runCatching { purchases.awaitRestore() }.getOrNull()
            if (restored == null) {
                _state.update { it.copy(purchase = PurchaseState.Failed(false)) }
                return@launch
            }
            if (restored.entitlements[ENTITLEMENT]?.isActive != true) {
                refreshAllowance(verify = true)
                _state.update { it.copy(purchase = PurchaseState.NothingToRestore) }
                return@launch
            }
            confirmWithService(purchases, restored = true)
        }
    }

    /**
     * The allowance, after the service has asked RevenueCat afresh. Waits out
     * the service's own refresh limit first: asked sooner, it answers from its
     * cache, which is exactly the answer from before the purchase.
     */
    private suspend fun verifiedAllowance(): Allowance? {
        val minimum = _state.value.allowance?.refreshMinMs ?: Allowance.DEFAULT_REFRESH_MIN_MS
        val wait = lastVerifyAt + minimum + REFRESH_MARGIN_MS - SystemClock.elapsedRealtime()
        if (lastVerifyAt > 0L && wait > 0) kotlinx.coroutines.delay(wait)
        return refreshAllowance(verify = true)
    }

    /**
     * Plus counts once TapBack's service has asked RevenueCat itself and been
     * told of a purchase of [PRODUCT] — never on the phone's word, and never
     * because of a preview or a promotional grant.
     */
    private suspend fun confirmWithService(purchases: Purchases, restored: Boolean) {
        readCustomer(purchases, CacheFetchPolicy.FETCH_CURRENT)
        var confirmed = false
        // RevenueCat can take a moment to have it: a few tries, each one far
        // enough after the last that the service really asks again.
        for (attempt in 1..CONFIRM_ATTEMPTS) {
            confirmed = verifiedAllowance()?.confirmsPurchase(PRODUCT) == true
            GuideLog.d("plus") { "confirm attempt $attempt: ${if (confirmed) "confirmed" else "not yet"}" }
            if (confirmed) break
        }
        val done = if (restored) PurchaseState.Restored else PurchaseState.Success
        _state.update { it.copy(purchase = if (confirmed) done else PurchaseState.Unconfirmed) }
    }

    /** Paid, but not confirmed yet: ask TapBack's service again. Never assumed. */
    override fun recheck() {
        if (_state.value.purchase == PurchaseState.Working) return
        _state.update { it.copy(purchase = PurchaseState.Working) }
        scope.launch {
            val confirmed = verifiedAllowance()?.confirmsPurchase(PRODUCT) == true
            _state.update { it.copy(purchase = if (confirmed) PurchaseState.Success else PurchaseState.Unconfirmed) }
        }
    }

    override fun clearPurchaseMessage() = _state.update { it.copy(purchase = PurchaseState.Idle) }

    // ------------------------------------------------ preview access (test builds)

    /**
     * A preview code, for recording lessons: checked and granted by TapBack's
     * service for this install, then both entitlement states are read afresh
     * — the service's, and RevenueCat's own when this build has a key.
     */
    suspend fun redeemPreview(code: String): AccountResult<PreviewGrant> {
        val r = accounts.redeemPreview(code)
        if (r is AccountResult.Ok) refreshBoth()
        return r
    }

    suspend fun revokePreview(): AccountResult<Boolean> {
        val r = accounts.revokePreview()
        refreshBoth()
        return r
    }

    private suspend fun refreshBoth() {
        refreshAllowance(verify = true)
        purchases()?.let { readCustomer(it, CacheFetchPolicy.FETCH_CURRENT) }
    }

    // ------------------------------------------------ the invitation, once

    /** The first task's closing line, in memory only: shown with the invitation, never stored. */
    @Volatile
    private var firstSummary: String? = null

    /** The first guided task finished: the one invitation may be shown, next time TapBack is open. */
    fun onTaskCompleted(summary: String = "") {
        if (prefs.getBoolean(KEY_FIRST_DONE, false)) return
        firstSummary = summary.trim().takeIf { it.isNotEmpty() && it.length <= MAX_SUMMARY }
        prefs.edit {
            putBoolean(KEY_FIRST_DONE, true)
            putLong(KEY_FIRST_DONE_AT, System.currentTimeMillis())
        }
    }

    val shouldInvite: Boolean
        get() = prefs.getBoolean(KEY_FIRST_DONE, false) && !prefs.getBoolean(KEY_INVITE_DISMISSED, false) &&
            _state.value.allowance?.plusActive != true && billingAvailable

    /**
     * The invitation to show now, if any: the "All done." screen when the
     * first task finished minutes ago, a small card on a later visit.
     */
    fun invitation(now: Long = System.currentTimeMillis()): Invitation? {
        if (!shouldInvite) return null
        val at = prefs.getLong(KEY_FIRST_DONE_AT, 0L)
        return if (at > 0L && now - at in 0..FRESH_INVITATION_MS) Invitation.Fresh(firstSummary) else Invitation.Later
    }

    fun dismissInvitation() = prefs.edit { putBoolean(KEY_INVITE_DISMISSED, true) }

    companion object {
        const val ENTITLEMENT = "dot_plus"
        const val OFFERING = "default"
        const val PRODUCT = "dot_plus_weekly"
        private const val PREFS = "dot_plus"
        private const val KEY_FIRST_DONE = "first_task_completed"
        private const val KEY_INVITE_DISMISSED = "invitation_dismissed"
        private const val KEY_FIRST_DONE_AT = "first_task_completed_at"
        private const val FRESH_INVITATION_MS = 30 * 60_000L
        private const val MAX_SUMMARY = 160
        private const val FOREGROUND_REFRESH_MS = 60_000L
        /** Confirmation tries after a purchase, each past the service's refresh limit: about half a minute in all. */
        private const val CONFIRM_ATTEMPTS = 4
        private const val REFRESH_MARGIN_MS = 750L

        // Holds the application context only, which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: PlusRepository? = null

        fun get(context: Context): PlusRepository =
            instance ?: synchronized(this) { instance ?: PlusRepository(context).also { instance = it } }
    }
}
