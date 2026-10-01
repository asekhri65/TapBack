package com.aaravsekhri.dot.ui.plus

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.account.Allowance
import com.aaravsekhri.dot.account.OfferState
import com.aaravsekhri.dot.account.PlusActions
import com.aaravsekhri.dot.account.PlusPeriodText
import com.aaravsekhri.dot.account.PlusRepository
import com.aaravsekhri.dot.account.PlusUiState
import com.aaravsekhri.dot.account.PurchaseState
import com.aaravsekhri.dot.courses.CourseCatalog
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.components.UnderlinedTextAction
import com.aaravsekhri.dot.ui.components.dotClickable
import com.aaravsekhri.dot.ui.design.Stage
import com.aaravsekhri.dot.ui.design.StageClose
import com.aaravsekhri.dot.ui.design.StageEyebrow
import com.aaravsekhri.dot.ui.design.StageGap
import com.aaravsekhri.dot.ui.design.StageHeadline
import com.aaravsekhri.dot.ui.design.StageIcon
import com.aaravsekhri.dot.ui.design.StageIcons
import com.aaravsekhri.dot.ui.design.StageLink
import com.aaravsekhri.dot.ui.design.StagePreferred
import com.aaravsekhri.dot.ui.design.StagePrimary
import com.aaravsekhri.dot.ui.design.StageRich
import com.aaravsekhri.dot.ui.design.StageScaffold
import com.aaravsekhri.dot.ui.design.StageSecondary
import com.aaravsekhri.dot.ui.design.StageTag
import com.aaravsekhri.dot.ui.design.StageTile
import com.aaravsekhri.dot.ui.design.StageBullet
import com.aaravsekhri.dot.ui.legal.LegalDocument
import com.aaravsekhri.dot.ui.legal.LegalScreen
import com.aaravsekhri.dot.ui.pebble.LanePebble
import com.aaravsekhri.dot.ui.pebble.StagePebble
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotType
import com.aaravsekhri.dot.ui.util.rememberReducedMotion
import com.revenuecat.purchases.models.Period
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TapBack Plus, as the approved boards draw it: what it gives, the price and
 * period exactly as the store says them, and a plain way to carry on free.
 *
 * Nothing here grants anything. A purchase counts once TapBack's service has
 * confirmed it with RevenueCat; until then the screen says what is really
 * happening — waiting, cancelled, pending, not confirmed yet. With nothing
 * to sell (no key in this build, no offering, no connection) it says so.
 */
@Composable
fun PlusScreen(
    reason: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val plus = PlusRepository.get(context)
    val state by plus.state.collectAsState()
    LaunchedEffect(Unit) {
        plus.clearPurchaseMessage()
        plus.loadOffer()
        plus.refreshAllowance()
    }
    PlusFlow(
        reason = reason,
        state = state,
        billingAvailable = plus.billingAvailable,
        testStore = plus.testStoreBuild,
        actions = plus,
        onClose = onClose,
    )
}

/** The paywall and everything it can lead to, from a [state] and the [actions] that change it. */
@Composable
internal fun PlusFlow(
    reason: String,
    state: PlusUiState,
    billingAvailable: Boolean,
    testStore: Boolean,
    actions: PlusActions,
    onClose: () -> Unit,
    initialSheet: LocalSheet? = null,
) {
    com.aaravsekhri.dot.ui.design.DismissKeyboardOnShow()
    val activity = LocalContext.current.findActivity()
    var document by rememberSaveable { mutableStateOf<LegalDocument?>(null) }
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    val close = {
        actions.clearPurchaseMessage()
        onClose()
    }
    val allowance = state.allowance
    val perWeek = allowance?.tasksPerPeriod ?: 10
    val offer = state.offer

    document?.let {
        LegalScreen(it) { document = null }
        return
    }

    when {
        state.purchase == PurchaseState.Success -> {
            androidx.activity.compose.BackHandler(onBack = close)
            PlusSettled(
                celebrate = true,
                title = stringResource(R.string.pw_success_title),
                body = pluralStringResource(R.plurals.pw_success_body, perWeek, perWeek),
                note = when {
                    testStore -> stringResource(R.string.pw_success_note_test)
                    offer is OfferState.Ready -> stringResource(R.string.pw_success_note, adverb(offer.period), offer.price)
                    else -> null
                },
                onDone = close,
            )
            return
        }
        allowance?.plusActive == true && state.purchase == PurchaseState.Idle && sheet == null -> {
            // Already on Plus: nothing to sell, only where things stand.
            androidx.activity.compose.BackHandler(onBack = close)
            val renews = allowance.plusExpiresAtMs?.let(::longDay)
            if (reason == "no_allowance") {
                PlusSettled(
                    celebrate = false,
                    title = stringResource(R.string.pw_plus_used_title),
                    body = if (renews != null && allowance.plusWillRenew) stringResource(R.string.pw_plus_used_body, renews)
                    else stringResource(R.string.pw_plus_used_body_plain),
                    note = null,
                    onDone = close,
                )
            } else {
                PlusSettled(
                    celebrate = false,
                    title = stringResource(R.string.pw_success_title),
                    body = pluralStringResource(R.plurals.pw_success_body, perWeek, perWeek),
                    note = when {
                        // A preview or a promotional grant is said as what it is, never as a purchase.
                        (allowance.plusPreview || allowance.plusPromotional) && renews != null ->
                            stringResource(R.string.plus_status_preview, renews)
                        state.testStore -> stringResource(R.string.pw_success_note_test)
                        renews == null -> null
                        allowance.plusWillRenew -> stringResource(R.string.pw_active_renews, renews)
                        else -> stringResource(R.string.pw_active_ends, renews)
                    },
                    onDone = close,
                )
            }
            return
        }
    }

    androidx.activity.compose.BackHandler(onBack = close)
    val exhausted = reason == "no_allowance" ||
        (allowance != null && !allowance.canStart && !allowance.plusActive)
    Box(Modifier.fillMaxSize()) {
        Paywall(
            reason = reason,
            exhausted = exhausted,
            state = state,
            testStore = testStore,
            onClose = close,
            onGet = {
                when {
                    !billingAvailable -> sheet = LocalSheet.NotConfigured
                    offer is OfferState.Ready -> activity?.let(actions::purchase)
                    offer is OfferState.Unavailable && offer.why == OfferState.Unavailable.Why.NotConfigured -> sheet = LocalSheet.NotConfigured
                    offer is OfferState.Unavailable && offer.why == OfferState.Unavailable.Why.NoOffering -> sheet = LocalSheet.NoOffering
                    offer is OfferState.Unavailable -> sheet = LocalSheet.Unavailable
                    else -> Unit
                }
            },
            onRestore = { if (billingAvailable) actions.restore() else sheet = LocalSheet.NotConfigured },
            onDocument = { document = it },
            onDismissNotice = actions::clearPurchaseMessage,
        )

        when (state.purchase) {
            PurchaseState.Working -> Waiting(
                stringResource(if (testStore) R.string.pw_waiting_test else R.string.pw_waiting),
                stringResource(R.string.pw_waiting_body),
            )
            PurchaseState.Restoring -> Waiting(
                stringResource(if (testStore) R.string.pw_restoring_test else R.string.pw_restoring),
                stringResource(R.string.pw_restoring_body),
            )
            PurchaseState.Restored -> Sheet(
                mood = SheetMood.Celebrate,
                title = stringResource(R.string.pw_restored_title),
                body = stringResource(R.string.pw_restored_body),
                primary = stringResource(R.string.pw_done),
                onPrimary = close,
                onDismiss = close,
            )
            PurchaseState.NothingToRestore -> Sheet(
                mood = SheetMood.Curious,
                title = stringResource(R.string.pw_none_title),
                body = stringResource(if (testStore) R.string.pw_none_body_test else R.string.pw_none_body),
                primary = stringResource(R.string.pw_ok),
                onPrimary = actions::clearPurchaseMessage,
                onDismiss = actions::clearPurchaseMessage,
            )
            PurchaseState.Unconfirmed -> Sheet(
                mood = SheetMood.Curious,
                title = stringResource(R.string.pw_unconfirmed_title),
                body = stringResource(R.string.pw_unconfirmed_body),
                primary = stringResource(R.string.pw_check_again),
                onPrimary = actions::recheck,
                secondary = stringResource(R.string.pw_not_now),
                onSecondary = close,
                onDismiss = actions::clearPurchaseMessage,
            )
            else -> Unit
        }

        when (sheet) {
            LocalSheet.Unavailable -> Sheet(
                mood = SheetMood.Curious,
                title = stringResource(R.string.pw_unavailable_title),
                body = stringResource(R.string.pw_unavailable_body),
                primary = stringResource(R.string.pw_try_again),
                onPrimary = {
                    sheet = null
                    activity?.let(actions::retry)
                },
                secondary = stringResource(R.string.pw_not_now),
                onSecondary = { sheet = null },
                onDismiss = { sheet = null },
            )
            LocalSheet.NoOffering -> Sheet(
                mood = SheetMood.Curious,
                title = stringResource(R.string.pw_unavailable_title),
                body = stringResource(R.string.pw_unavailable_offering),
                primary = stringResource(R.string.pw_ok),
                onPrimary = { sheet = null },
                onDismiss = { sheet = null },
            )
            LocalSheet.NotConfigured -> Sheet(
                mood = SheetMood.Curious,
                title = stringResource(R.string.pw_not_configured_title),
                body = stringResource(R.string.pw_not_configured_body),
                primary = stringResource(R.string.pw_ok),
                onPrimary = { sheet = null },
                onDismiss = { sheet = null },
            )
            null -> Unit
        }
    }
}

/** Sheets the paywall opens itself, before the store is asked anything. */
internal enum class LocalSheet { Unavailable, NoOffering, NotConfigured }

@Composable
private fun Paywall(
    reason: String,
    exhausted: Boolean,
    state: PlusUiState,
    testStore: Boolean,
    onClose: () -> Unit,
    onGet: () -> Unit,
    onRestore: () -> Unit,
    onDocument: (LegalDocument) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val allowance = state.allowance
    val perWeek = allowance?.tasksPerPeriod ?: 10
    val offer = state.offer
    var open by rememberSaveable { mutableStateOf(false) }

    StageScaffold(
        header = {
            StageClose(stringResource(R.string.pw_close), onClose)
            Spacer(Modifier)
        },
        footer = {
            notice(state.purchase)?.let { words ->
                Notice(words, onDismissNotice)
                StageGap(14.dp)
            }
            Column(Modifier.padding(end = 64.dp)) {
                when (offer) {
                    is OfferState.Ready -> {
                        Text(
                            stringResource(R.string.pw_price, offer.price, unit(offer.period)),
                            style = Stage.Price,
                            color = Stage.Charcoal,
                        )
                        Text(
                            stringResource(R.string.pw_renews, adverb(offer.period)),
                            style = Stage.Small,
                            color = Stage.Secondary,
                        )
                        if (testStore) {
                            StageGap(6.dp)
                            StageTag(stringResource(R.string.pw_test_tag))
                        }
                    }
                    OfferState.Loading -> Text(stringResource(R.string.pw_price_loading), style = Stage.Small, color = Stage.Secondary)
                    is OfferState.Unavailable -> Text(stringResource(R.string.pw_price_unavailable), style = Stage.Small, color = Stage.Secondary)
                }
            }
            StageGap(14.dp)
            StagePreferred(Modifier.fillMaxWidth()) {
                StagePrimary(
                    stringResource(R.string.pw_get),
                    onClick = onGet,
                    enabled = offer !is OfferState.Loading,
                )
            }
            if (exhausted) {
                allowance?.nextFreeAtMs?.let { at ->
                    StageGap(12.dp)
                    StageRich(
                        stringResource(R.string.pw_next_free, longDay(at)),
                        style = Stage.Small,
                        color = Stage.Secondary,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                StageGap(10.dp)
                StageSecondary(stringResource(R.string.pw_not_now), onClose)
            } else {
                StageGap(10.dp)
                StageSecondary(stringResource(R.string.pw_continue_free), onClose)
            }
            StageGap(4.dp)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            ) {
                StageLink(stringResource(R.string.pw_restore), onRestore)
                StageLink(stringResource(R.string.pw_terms), { onDocument(LegalDocument.Terms) })
                StageLink(stringResource(R.string.pw_privacy), { onDocument(LegalDocument.Privacy) })
            }
        },
    ) {
        StageGap(4.dp)
        StagePebble(size = 76.dp, bob = false, blinkMs = 7_000, key = "paywall", contentDescription = "Pebble")
        StageGap(20.dp)
        when {
            reason == "premium_course" -> {
                StageEyebrow(stringResource(R.string.pw_eyebrow_course), color = Stage.RustText)
                StageGap(10.dp)
            }
            exhausted -> {
                StageEyebrow(stringResource(R.string.pw_eyebrow_used), color = Stage.RustText)
                StageGap(10.dp)
            }
        }
        StageHeadline(stringResource(R.string.pw_title))
        StageGap(20.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Benefit(StageIcons.Calendar, pluralStringResource(R.plurals.pw_benefit_tasks, perWeek, perWeek))
            Benefit(StageIcons.Book, stringResource(R.string.pw_benefit_courses))
        }
        StageGap(12.dp)
        Text(stringResource(R.string.pw_task_note), style = Stage.Small, color = Stage.Secondary)
        StageGap(16.dp)
        Included(open = open, onToggle = { open = !open }, perWeek = perWeek, allowance = allowance, testStore = testStore)
    }
}

@Composable
private fun Benefit(icon: String, text: String) {
    Row(
        Modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StageTile(icon, fill = Stage.RustTint, tint = DotColors.Rust)
        Text(text, style = Stage.Benefit, color = Stage.Charcoal)
    }
}

/** "What's included?": free and Plus side by side in words, and who takes the payment. */
@Composable
private fun Included(open: Boolean, onToggle: () -> Unit, perWeek: Int, allowance: Allowance?, testStore: Boolean) {
    val still = rememberReducedMotion()
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(if (still) 0 else 200), label = "chevron")
    val expanded = stringResource(R.string.pw_included)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Stage.Hairline),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .dotClickable(onClick = onToggle, role = Role.Button, shape = RoundedCornerShape(10.dp))
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(expanded, style = Stage.Link, color = Stage.Charcoal, modifier = Modifier.weight(1f))
            StageIcon(StageIcons.ChevronDown, Stage.Charcoal, 22.dp, stroke = 2f, rotation = turn)
        }
        AnimatedVisibility(
            visible = open,
            enter = if (still) fadeIn(tween(0)) else expandVertically() + fadeIn(),
            exit = if (still) fadeOut(tween(0)) else shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StageEyebrow(stringResource(R.string.pw_free))
                    StageBullet(stringResource(R.string.pw_free_tasks), Stage.Supporting)
                    freeCourseNames(allowance).forEach { StageBullet(stringResource(R.string.pw_free_course, it), Stage.Supporting) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StageEyebrow(stringResource(R.string.pw_plus), color = Stage.RustText)
                    StageBullet(pluralStringResource(R.plurals.pw_benefit_tasks, perWeek, perWeek), Stage.Supporting)
                    StageBullet(stringResource(R.string.pw_benefit_courses), Stage.Supporting)
                }
                Text(
                    stringResource(if (testStore) R.string.pw_payment_note_test else R.string.pw_payment_note),
                    style = Stage.Small,
                    color = Stage.Secondary,
                )
            }
        }
    }
}

/** The free courses by their short names — "Google Maps" — from the service's list. */
private fun freeCourseNames(allowance: Allowance?): List<String> =
    (allowance?.freeCourses ?: Allowance.UNKNOWN.freeCourses)
        .mapNotNull { id -> CourseCatalog.course(id)?.title?.substringBefore(':')?.trim() }
        .sorted()

/** What the footer notice says, for the outcomes that leave the person on the paywall. */
@Composable
private fun notice(purchase: PurchaseState): String? = when (purchase) {
    PurchaseState.Cancelled -> stringResource(R.string.pw_cancelled)
    PurchaseState.Pending -> stringResource(R.string.pw_pending)
    is PurchaseState.Failed -> stringResource(if (purchase.simulated) R.string.pw_failed_simulated else R.string.pw_failed)
    else -> null
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Stage.PaleFill, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StageIcon(StageIcons.Info, Stage.Secondary, 22.dp, stroke = 1.8f)
        Text(text, style = Stage.Small, color = Stage.Charcoal, modifier = Modifier.weight(1f))
        StageClose(stringResource(R.string.pw_dismiss), onDismiss)
    }
}

/**
 * The store is working: a card over the paywall with Pebble hopping across
 * its lane. Nothing underneath takes a tap meanwhile, and Back waits too —
 * the store's own sheet is the way out.
 */
@Composable
private fun BoxScope.Waiting(title: String, body: String) {
    androidx.activity.compose.BackHandler {}
    val still = rememberReducedMotion()
    val shown = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(200)) }
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = shown.value }
            .background(Stage.Scrim.copy(alpha = 0.32f))
            .blockTouches(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .background(Stage.Surface, RoundedCornerShape(24.dp))
                .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 20.dp)
                .semantics(mergeDescendants = true) {
                    paneTitle = title
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = Stage.Benefit, color = Stage.Charcoal, textAlign = TextAlign.Center)
            StageGap(6.dp)
            Text(body, style = Stage.Small, color = Stage.Secondary, textAlign = TextAlign.Center)
            StageGap(14.dp)
            LanePebble(Modifier.fillMaxWidth())
        }
    }
}

internal enum class SheetMood { Curious, Celebrate }

/** A sheet rising from the bottom: Pebble, a title, a line, and one or two answers. */
@Composable
private fun BoxScope.Sheet(
    mood: SheetMood,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val still = rememberReducedMotion()
    val rise = remember(title) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(title) { rise.animateTo(1f, tween(280, easing = Stage.Arrive)) }
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = rise.value }
            .background(Stage.Scrim.copy(alpha = 0.28f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .clearAndSetSemantics { },
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rise.value
                translationY = (1f - rise.value) * 24.dp.toPx()
            }
            .background(Stage.Surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .blockTouches()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 24.dp)
            .semantics { paneTitle = title },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            StagePebble(
                size = 48.dp,
                bob = false,
                shadow = false,
                celebrate = mood == SheetMood.Celebrate,
                curious = mood == SheetMood.Curious,
                key = title,
            )
            StageHeadline(title, style = Stage.SheetTitle, modifier = Modifier.padding(top = 4.dp))
        }
        StageGap(12.dp)
        Text(body, style = Stage.Supporting, color = Stage.Secondary)
        StageGap(22.dp)
        StagePrimary(primary, onPrimary)
        if (secondary != null && onSecondary != null) {
            StageGap(10.dp)
            StageSecondary(secondary, onSecondary)
        }
    }
}

/**
 * Where things stand once Plus is on: "You're on TapBack Plus." with
 * Pebble's celebration after a purchase, or plainly when it already was.
 */
@Composable
private fun PlusSettled(celebrate: Boolean, title: String, body: String, note: String?, onDone: () -> Unit) {
    StageScaffold(
        header = {},
        footer = { StagePrimary(stringResource(R.string.pw_done), onDone) },
    ) {
        Spacer(Modifier.weight(1f))
        StagePebble(
            size = 136.dp,
            celebrate = celebrate,
            bob = !celebrate,
            key = title,
            contentDescription = if (celebrate) "Pebble, celebrating" else "Pebble",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.weight(1f))
        StageHeadline(title, align = TextAlign.Center, modifier = Modifier.align(Alignment.CenterHorizontally))
        StageGap(12.dp)
        Text(
            body,
            style = Stage.Body,
            color = Stage.Secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 320.dp),
        )
        if (note != null) {
            StageGap(14.dp)
            Text(
                note,
                style = Stage.Note,
                color = Stage.Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 300.dp),
            )
        }
        StageGap(12.dp)
    }
}

/**
 * Right after the first guided task: "All done.", and — a moment later,
 * never over the task itself — one invitation to TapBack Plus. Keeping to
 * free puts it away for good.
 */
@Composable
fun PlusInvitationScreen(summary: String?, perWeek: Int, onExplore: () -> Unit, onKeepFree: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onKeepFree)
    com.aaravsekhri.dot.ui.design.DismissKeyboardOnShow()
    val still = rememberReducedMotion()
    val rise = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        delay(1_500)
        rise.animateTo(1f, tween(280, easing = Stage.Arrive))
    }
    val shown = rise.value > 0.5f
    StageScaffold(
        header = {},
        footer = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = rise.value
                        translationY = (1f - rise.value) * 24.dp.toPx()
                    }
                    .then(if (shown) Modifier else Modifier.blockTouches().clearAndSetSemantics { })
                    .background(Stage.PaleFill, RoundedCornerShape(22.dp))
                    .padding(20.dp),
            ) {
                Text(stringResource(R.string.inv_card_title), style = Stage.CardTitle, color = Stage.Charcoal)
                StageGap(4.dp)
                Text(pluralStringResource(R.plurals.inv_card_body, perWeek, perWeek), style = Stage.Small, color = Stage.Secondary)
                StageGap(16.dp)
                StagePrimary(stringResource(R.string.inv_explore), onExplore)
                StageGap(10.dp)
                StageSecondary(stringResource(R.string.inv_keep_free), onKeepFree)
            }
        },
    ) {
        Spacer(Modifier.weight(1f))
        StagePebble(
            size = 136.dp,
            celebrate = true,
            key = "first-task",
            contentDescription = "Pebble, celebrating",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.weight(1f))
        StageEyebrow(stringResource(R.string.inv_eyebrow), modifier = Modifier.align(Alignment.CenterHorizontally))
        StageGap(10.dp)
        StageHeadline(stringResource(R.string.inv_title), align = TextAlign.Center, modifier = Modifier.align(Alignment.CenterHorizontally))
        StageGap(10.dp)
        Text(
            summary ?: stringResource(R.string.inv_generic),
            style = Stage.BodyLarge,
            color = Stage.Secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        StageGap(12.dp)
    }
}

/**
 * The invitation on a later visit: a small card on Ask, once. Either answer
 * puts it away for good.
 */
@Composable
fun PlusInvitation(perWeek: Int, onSee: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Stage.PaleFill, RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StagePebble(size = 40.dp, bob = false, shadow = false, blinkMs = 7_000)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.inv_card_title), style = Stage.CardTitle, color = Stage.Charcoal)
                Text(pluralStringResource(R.plurals.inv_card_body, perWeek, perWeek), style = Stage.Small, color = Stage.Secondary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            StageLink(stringResource(R.string.inv_keep_free), onDismiss)
            StageLink(stringResource(R.string.inv_explore), onSee)
        }
    }
}

/** Swallows every touch, so nothing behind a card or a sheet is tapped by accident. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ------------------------------------------------------------ words for time

/** "Monday, October 6", in the phone's language. */
internal fun longDay(ms: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(ms))

internal fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/** "week", "2 weeks": the store's period as a unit. */
@Composable
private fun unit(period: PlusPeriodText): String {
    val n = period.value.coerceAtLeast(1)
    return pluralStringResource(
        when (period.unit) {
            Period.Unit.DAY -> R.plurals.pw_unit_day
            Period.Unit.MONTH -> R.plurals.pw_unit_month
            Period.Unit.YEAR -> R.plurals.pw_unit_year
            else -> R.plurals.pw_unit_week
        },
        n,
        n,
    )
}

/** "weekly", "every 2 weeks": the store's period as how often it renews. */
@Composable
private fun adverb(period: PlusPeriodText): String {
    if (period.value > 1) return stringResource(R.string.pw_adverb_every, unit(period))
    return stringResource(
        when (period.unit) {
            Period.Unit.DAY -> R.string.pw_adverb_day
            Period.Unit.MONTH -> R.string.pw_adverb_month
            Period.Unit.YEAR -> R.string.pw_adverb_year
            else -> R.string.pw_adverb_week
        },
    )
}

// ------------------------------------------------------------ in Settings

/** Where the free allowance stands, said simply. */
@Composable
internal fun freeLine(allowance: Allowance?): String? {
    allowance ?: return null
    return when {
        allowance.welcomeLeft > 0 -> pluralStringResource(R.plurals.plus_free_left, allowance.welcomeLeft, allowance.welcomeLeft)
        allowance.freeWeeklyAvailable -> stringResource(R.string.plus_free_weekly_ready)
        allowance.nextFreeAtMs != null -> stringResource(R.string.plus_next_free, longDay(allowance.nextFreeAtMs))
        else -> stringResource(R.string.plus_free_used)
    }
}

/**
 * The plan, in Settings: what the person has, what is left, when more comes,
 * and the ways to buy, restore and manage.
 */
@Composable
fun ColumnScope.PlanSection(onSeePlus: () -> Unit) {
    val context = LocalContext.current
    val plus = PlusRepository.get(context)
    val state by plus.state.collectAsState()
    LaunchedEffect(Unit) { plus.refreshAllowance() }
    val allowance = state.allowance
    val status = when {
        allowance == null -> stringResource(R.string.plus_status_unknown)
        // A preview is said as a preview, with its end: never as a subscription.
        allowance.plusPreview -> stringResource(R.string.plus_status_preview, allowance.plusExpiresAtMs?.let(::longDay) ?: "")
        allowance.plusActive -> pluralStringResource(
            if (allowance.plusWillRenew) R.plurals.plus_active_until else R.plurals.plus_active_ends,
            allowance.plusLeft ?: 0,
            allowance.plusLeft ?: 0,
            allowance.tasksPerPeriod,
            allowance.plusExpiresAtMs?.let(::date) ?: "",
        )
        else -> stringResource(R.string.plus_status_free) + (freeLine(allowance)?.let { " $it" } ?: "")
    }
    Text(status, style = DotType.CardBody, color = DotColors.Ink)
    Spacer(Modifier.size(12.dp))
    if (allowance?.plusActive != true) {
        SecondaryButton(text = stringResource(R.string.plus_see), onClick = onSeePlus)
    }
    if (plus.billingAvailable) {
        UnderlinedTextAction(text = stringResource(R.string.pw_restore), onClick = plus::restore)
    }
    settingsWords(state.purchase, plus.testStoreBuild)?.let {
        Spacer(Modifier.size(10.dp))
        Text(
            it,
            style = DotType.CardBody,
            color = DotColors.Ink,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (allowance?.plusActive == true && !allowance.plusPreview && !state.testStore) {
        // The store's own page for the subscription; Google Play's list of them otherwise.
        val url = state.managementUrl ?: "https://play.google.com/store/account/subscriptions?package=${context.packageName}"
        UnderlinedTextAction(
            text = stringResource(R.string.plus_manage),
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
    }
    if (state.testStore) {
        Text(stringResource(R.string.plus_test_store), style = DotType.CardMeta, color = DotColors.RustDeep)
    }
}

/** A restore from Settings, said in a line: the same outcomes the paywall shows as sheets. */
@Composable
private fun settingsWords(purchase: PurchaseState, testStore: Boolean): String? = when (purchase) {
    PurchaseState.Idle -> null
    PurchaseState.Working -> stringResource(if (testStore) R.string.pw_waiting_test else R.string.pw_waiting)
    PurchaseState.Restoring -> stringResource(if (testStore) R.string.pw_restoring_test else R.string.pw_restoring)
    PurchaseState.Restored -> "${stringResource(R.string.pw_restored_title)}. ${stringResource(R.string.pw_restored_body)}"
    PurchaseState.Success -> stringResource(R.string.pw_success_title)
    PurchaseState.Unconfirmed -> stringResource(R.string.pw_unconfirmed_body)
    PurchaseState.NothingToRestore -> stringResource(if (testStore) R.string.pw_none_body_test else R.string.pw_none_body)
    else -> notice(purchase)
}
