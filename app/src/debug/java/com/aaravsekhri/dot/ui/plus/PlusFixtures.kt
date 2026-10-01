package com.aaravsekhri.dot.ui.plus

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.aaravsekhri.dot.account.Allowance
import com.aaravsekhri.dot.account.OfferState
import com.aaravsekhri.dot.account.PlusActions
import com.aaravsekhri.dot.account.PlusPeriodText
import com.aaravsekhri.dot.account.PlusUiState
import com.aaravsekhri.dot.account.PurchaseState
import com.revenuecat.purchases.models.Period
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only: each TapBack Plus state drawn from fixture data, to
 * look at and compare with the approved boards. Nothing here reaches a
 * store or TapBack's service, and nothing here is a purchase; the price is
 * the boards' example, not the store's.
 */
enum class PlusFixture(val label: String) {
    Paywall("Paywall"),
    TestStore("Paywall, Test Store build"),
    Course("Locked course"),
    UsedUp("Free help used up"),
    Cancelled("Purchase cancelled"),
    Pending("Payment pending"),
    Processing("Waiting for the store"),
    Unavailable("Store unreachable"),
    NotConfigured("No store in this build"),
    RestoreNone("Restore: nothing found"),
    Restored("Restore: found"),
    Unconfirmed("Paid, not confirmed yet"),
    Success("Success"),
    Invitation("After the first task"),
}

@Composable
fun PlusFixtureScreen(fixture: PlusFixture, onClose: () -> Unit) {
    if (fixture == PlusFixture.Invitation) {
        PlusInvitationScreen(summary = "Your text is bigger now.", perWeek = 10, onExplore = onClose, onKeepFree = onClose)
        return
    }
    val scope = rememberCoroutineScope()
    var state by remember(fixture) { mutableStateOf(initial(fixture)) }
    val actions = remember(fixture) {
        object : PlusActions {
            override fun purchase(activity: Activity) {
                state = state.copy(purchase = PurchaseState.Working)
                scope.launch {
                    delay(2_400)
                    state = state.copy(purchase = PurchaseState.Success)
                }
            }

            override fun retry(activity: Activity) {
                state = state.copy(offer = READY)
                purchase(activity)
            }

            override fun restore() {
                state = state.copy(purchase = PurchaseState.Restoring)
                scope.launch {
                    delay(1_800)
                    state = state.copy(purchase = if (fixture == PlusFixture.Restored) PurchaseState.Restored else PurchaseState.NothingToRestore)
                }
            }

            override fun recheck() {
                state = state.copy(purchase = PurchaseState.Success)
            }

            override fun clearPurchaseMessage() {
                state = state.copy(purchase = PurchaseState.Idle)
            }
        }
    }
    // Back waits while a store works, as it does for real: the fixture's
    // store answers on its own after a few seconds.
    androidx.compose.runtime.LaunchedEffect(fixture) {
        if (fixture == PlusFixture.Processing) {
            delay(6_000)
            state = state.copy(purchase = PurchaseState.Success)
        }
    }
    PlusFlow(
        reason = when (fixture) {
            PlusFixture.Course -> "premium_course"
            PlusFixture.UsedUp -> "no_allowance"
            else -> "browse"
        },
        state = state,
        billingAvailable = fixture != PlusFixture.NotConfigured,
        testStore = fixture == PlusFixture.TestStore,
        actions = actions,
        onClose = onClose,
        initialSheet = when (fixture) {
            PlusFixture.Unavailable -> LocalSheet.Unavailable
            PlusFixture.NotConfigured -> LocalSheet.NotConfigured
            else -> null
        },
    )
}

private val READY = OfferState.Ready("$0.99", PlusPeriodText(Period.Unit.WEEK, 1), pkg = null)

private fun initial(fixture: PlusFixture): PlusUiState {
    val day = 24 * 60 * 60 * 1000L
    val free = Allowance.UNKNOWN.copy(canStart = true, welcomeLeft = 1, plusStatus = "verified", billingConfigured = true)
    val usedUp = free.copy(canStart = false, welcomeLeft = 0, nextFreeAtMs = System.currentTimeMillis() + 5 * day)
    return PlusUiState(
        allowance = if (fixture == PlusFixture.UsedUp) usedUp else free,
        offer = when (fixture) {
            PlusFixture.Unavailable -> OfferState.Unavailable(OfferState.Unavailable.Why.Offline)
            PlusFixture.NotConfigured -> OfferState.Unavailable(OfferState.Unavailable.Why.NotConfigured)
            else -> READY
        },
        purchase = when (fixture) {
            PlusFixture.Cancelled -> PurchaseState.Cancelled
            PlusFixture.Pending -> PurchaseState.Pending
            PlusFixture.Processing -> PurchaseState.Working
            PlusFixture.RestoreNone -> PurchaseState.NothingToRestore
            PlusFixture.Restored -> PurchaseState.Restored
            PlusFixture.Unconfirmed -> PurchaseState.Unconfirmed
            PlusFixture.Success -> PurchaseState.Success
            else -> PurchaseState.Idle
        },
    )
}
