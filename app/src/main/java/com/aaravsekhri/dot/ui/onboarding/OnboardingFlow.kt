package com.aaravsekhri.dot.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.guide.service.AccessibilityServiceStatus
import com.aaravsekhri.dot.guide.service.GuidePreferences
import com.aaravsekhri.dot.onboarding.OnboardingStep
import com.aaravsekhri.dot.onboarding.OnboardingStore
import com.aaravsekhri.dot.ui.design.Stage
import com.aaravsekhri.dot.ui.design.StageBack
import com.aaravsekhri.dot.ui.design.StageCounter
import com.aaravsekhri.dot.ui.design.StageEyebrow
import com.aaravsekhri.dot.ui.design.StageGap
import com.aaravsekhri.dot.ui.design.StageHeadline
import com.aaravsekhri.dot.ui.design.StageIcon
import com.aaravsekhri.dot.ui.design.StageIcons
import com.aaravsekhri.dot.ui.design.StagePreferred
import com.aaravsekhri.dot.ui.design.StagePrimary
import com.aaravsekhri.dot.ui.design.StageRich
import com.aaravsekhri.dot.ui.design.StageScaffold
import com.aaravsekhri.dot.ui.design.StageSecondary
import com.aaravsekhri.dot.ui.design.StageSuccessChip
import com.aaravsekhri.dot.ui.design.StageTile
import com.aaravsekhri.dot.ui.pebble.StagePebble
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/**
 * TapBack's first-run introduction, as the approved boards draw it: meet
 * Pebble, a practice tap, guidance access, ready. Four screens, each moved on
 * by its own button with Back on every screen after the first; no timers and
 * no swipes to discover.
 *
 * Access is Android's to grant: the app opens Android's Accessibility
 * settings and reads the real status whenever it comes back, so returning is
 * "Guidance is on." only when it is. Where they are is kept across a trip to
 * Settings, a rotation, or the process going. Nothing here sells anything.
 */
@Composable
fun OnboardingFlow(
    store: OnboardingStore,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { GuidePreferences(context) }
    var step by rememberSaveable { mutableStateOf(store.step) }
    var practiced by rememberSaveable { mutableStateOf(store.practiced) }
    var wentToSettings by rememberSaveable { mutableStateOf(store.wentToSettings) }
    var settingsMissing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(step) { store.step = step }

    var serviceOn by remember { mutableStateOf(AccessibilityServiceStatus.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceOn = AccessibilityServiceStatus.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.activity.compose.BackHandler(enabled = step.previous != null) { step.previous?.let { step = it } }

    val back = stringResource(R.string.onb_back)
    when (step) {
        OnboardingStep.MeetPebble -> StageScaffold(
            header = { Spacer(Modifier.size(1.dp)); StageCounter(1, 4) },
            footer = { StagePrimary(stringResource(R.string.onb_meet_action), onClick = { step = OnboardingStep.Practice }) },
        ) {
            Spacer(Modifier.weight(1f))
            StagePebble(size = 184.dp, modifier = Modifier.align(Alignment.CenterHorizontally), key = "meet", contentDescription = "Pebble, your guide")
            Spacer(Modifier.weight(1f))
            StageHeadline(
                stringResource(R.string.onb_meet_title),
                style = Stage.Welcome,
                align = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 320.dp),
            )
            StageGap(14.dp)
            Text(
                stringResource(R.string.onb_meet_body),
                style = Stage.BodyLarge,
                color = Stage.Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 300.dp),
            )
            StageGap(12.dp)
        }

        OnboardingStep.Practice -> Practice(
            practiced = practiced,
            back = back,
            onPracticed = { practiced = true; store.practiced = true },
            onBack = { step = OnboardingStep.MeetPebble },
            onNext = { step = OnboardingStep.EnableGuidance },
        )

        OnboardingStep.EnableGuidance -> when {
            serviceOn -> Result(
                back = back,
                onBack = { step = OnboardingStep.Practice },
                celebrate = true,
                title = stringResource(R.string.onb_on_title),
                body = stringResource(R.string.onb_on_body),
                chip = stringResource(R.string.onb_on_chip),
                footer = { StagePrimary(stringResource(R.string.onb_continue), onClick = { step = OnboardingStep.Ready }) },
            )
            wentToSettings || settingsMissing -> Result(
                back = back,
                onBack = { step = OnboardingStep.Practice },
                celebrate = false,
                title = stringResource(R.string.onb_off_title),
                body = stringResource(if (settingsMissing) R.string.onb_no_settings else R.string.onb_off_body),
                chip = null,
                footer = {
                    OpenSettingsButton {
                        settingsMissing = !AccessibilityServiceStatus.openSettings(context)
                    }
                    StageGap(10.dp)
                    StageSecondary(stringResource(R.string.onb_explore_first), onClick = { step = OnboardingStep.Ready })
                },
            )
            else -> Enable(
                back = back,
                onBack = { step = OnboardingStep.Practice },
                onOpenSettings = {
                    // Consent, given here, to what this screen explains: the
                    // screen read on the phone during a guide, and sent to
                    // TapBack's service and Gemini to plan each step. The
                    // same record the guide itself checks.
                    preferences.disclosureAccepted = true
                    preferences.cloudGuidanceAccepted = true
                    wentToSettings = true
                    store.wentToSettings = true
                    settingsMissing = !AccessibilityServiceStatus.openSettings(context)
                },
                onExplore = { step = OnboardingStep.Ready },
            )
        }

        OnboardingStep.Ready -> StageScaffold(
            header = { StageBack(back) { step = OnboardingStep.EnableGuidance }; StageCounter(4, 4) },
            footer = {
                StagePrimary(stringResource(R.string.onb_get_started), onClick = {
                    store.finish()
                    onFinished()
                })
                StageGap(10.dp)
                Text(
                    stringResource(R.string.onb_ready_note),
                    style = Stage.Note,
                    color = Stage.Secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            Spacer(Modifier.weight(1f))
            StagePebble(size = 184.dp, celebrate = true, key = "ready", modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.weight(1f))
            StageHeadline(
                stringResource(R.string.onb_ready_title),
                style = Stage.Welcome,
                align = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (!serviceOn) {
                StageGap(12.dp)
                Text(
                    stringResource(R.string.onb_ready_later),
                    style = Stage.Body,
                    color = Stage.Secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.weight(0.55f))
        }
    }
}

/**
 * The practice tap: the same outline and Pebble TapBack draws over other
 * apps, on a real button. Continue appears only after the tap, in space kept
 * for it so nothing jumps.
 */
@Composable
private fun Practice(practiced: Boolean, back: String, onPracticed: () -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val still = rememberReducedMotion()
    val rise = remember(practiced) { Animatable(if (practiced && !still) 0f else 1f) }
    LaunchedEffect(practiced) { if (practiced && !still) rise.animateTo(1f, tween(280, easing = Stage.Arrive)) }
    val fade = remember(practiced) { Animatable(if (practiced && !still) 0f else if (practiced) 1f else 0f) }
    LaunchedEffect(practiced) { if (practiced && !still) fade.animateTo(1f, tween(220)) }
    StageScaffold(
        header = { StageBack(back, onBack); StageCounter(2, 4) },
        footer = {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                if (practiced) {
                    StagePrimary(
                        stringResource(R.string.onb_continue),
                        onClick = onNext,
                        modifier = Modifier.graphicsLayer {
                            alpha = rise.value
                            translationY = (1f - rise.value) * 24.dp.toPx()
                        },
                    )
                } else {
                    Text(stringResource(R.string.onb_try_hint), style = Stage.Small, color = Stage.Secondary, textAlign = TextAlign.Center)
                }
            }
        },
    ) {
        StageGap(8.dp)
        StageHeadline(stringResource(R.string.onb_try_title), style = Stage.PracticeHeadline)
        StageGap(24.dp)
        val flow = com.aaravsekhri.dot.ui.design.LocalStageFlow.current
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (flow) Modifier.heightIn(min = 300.dp) else Modifier.weight(1f).heightIn(min = 250.dp))
                .background(Stage.PaleFill, RoundedCornerShape(24.dp))
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        ) {
            StageEyebrow(stringResource(R.string.onb_practice))
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().padding(top = 34.dp)) {
                if (!practiced) {
                    StagePreferred(pebbleOnStart = true, pebbleSize = 40.dp) {
                        StagePrimary(stringResource(R.string.onb_try_button), onClick = onPracticed)
                    }
                } else {
                    Box(Modifier.padding(top = 8.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp)
                                .background(Stage.SuccessFill, RoundedCornerShape(14.dp))
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StageIcon(StageIcons.Check, Stage.SuccessText, 20.dp, stroke = 2.4f)
                            Text(stringResource(R.string.onb_try_done), style = Stage.Primary, color = Stage.SuccessText)
                        }
                        StagePebble(
                            size = 40.dp,
                            shadow = false,
                            bob = false,
                            celebrate = true,
                            key = "practice",
                            modifier = Modifier.align(Alignment.TopStart).graphicsLayer {
                                translationY = -(40.dp.toPx() * 0.72f)
                                translationX = -4.dp.toPx()
                            },
                        )
                    }
                }
            }
            // Kept whether or not it shows, so the panel does not move.
            StageGap(22.dp)
            Text(
                stringResource(R.string.onb_try_after),
                style = Stage.Body,
                color = Stage.Charcoal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = fade.value },
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Enable(back: String, onBack: () -> Unit, onOpenSettings: () -> Unit, onExplore: () -> Unit) {
    StageScaffold(
        header = { StageBack(back, onBack); StageCounter(3, 4) },
        footer = {
            OpenSettingsButton(onOpenSettings)
            StageGap(10.dp)
            StageSecondary(stringResource(R.string.onb_explore_first), onClick = onExplore)
        },
    ) {
        StageGap(8.dp)
        StageHeadline(stringResource(R.string.onb_enable_title))
        StageGap(12.dp)
        Text(stringResource(R.string.onb_enable_body), style = Stage.Body, color = Stage.Secondary)
        StageGap(20.dp)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Explained(StageIcons.Hand, stringResource(R.string.onb_enable_tap_title), stringResource(R.string.onb_enable_tap_body))
            Explained(StageIcons.Screen, stringResource(R.string.onb_enable_screen_title), stringResource(R.string.onb_enable_screen_body))
            Explained(StageIcons.Lock, stringResource(R.string.onb_enable_private_title), stringResource(R.string.onb_enable_private_body))
        }
        StageGap(20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Stage.PaleFill, RoundedCornerShape(20.dp))
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StageEyebrow(stringResource(R.string.onb_choose), modifier = Modifier.weight(1f).padding(end = 8.dp))
                com.aaravsekhri.dot.ui.design.StageTag(stringResource(R.string.onb_choose_tag))
            }
            Choice(1, stringResource(R.string.onb_choose_1))
            Choice(2, stringResource(R.string.onb_choose_2))
            Choice(3, stringResource(R.string.onb_choose_3), switch = true)
            StageRich(stringResource(R.string.onb_choose_note), style = Stage.Note, color = Stage.Secondary)
        }
    }
}

@Composable
private fun Explained(icon: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StageTile(icon, Stage.PaleFill, Stage.Charcoal)
        StageRich("**$title** $body", style = Stage.Supporting, color = Stage.Secondary, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Choice(n: Int, label: String, switch: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(Stage.Charcoal, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = Stage.Chip, color = Stage.Surface)
        }
        Text(label, style = Stage.Supporting, color = Stage.Charcoal, modifier = Modifier.weight(1f))
        if (switch) {
            // A drawing of the switch they will turn on: on, dark, never a control.
            Box(
                Modifier
                    .size(44.dp, 26.dp)
                    .background(Stage.Charcoal, RoundedCornerShape(13.dp))
                    .padding(3.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Box(Modifier.size(20.dp).background(Stage.Surface, CircleShape)) }
        }
    }
}

@Composable
private fun OpenSettingsButton(onClick: () -> Unit) {
    StagePrimary(stringResource(R.string.onb_open_settings), onClick = onClick, trailing = {
        StageIcon(StageIcons.External, Stage.Surface, 20.dp, stroke = 2.1f)
    })
}

/** The access step's result, told truthfully from Android's own record. */
@Composable
private fun Result(
    back: String,
    onBack: () -> Unit,
    celebrate: Boolean,
    title: String,
    body: String,
    chip: String?,
    footer: @Composable ColumnScope.() -> Unit,
) {
    StageScaffold(
        header = { StageBack(back, onBack); StageCounter(3, 4) },
        footer = footer,
    ) {
        Spacer(Modifier.weight(1f))
        StagePebble(
            size = 128.dp,
            celebrate = celebrate,
            curious = !celebrate,
            bob = false,
            key = title,
            contentDescription = if (celebrate) "Pebble, happy" else "Pebble, curious",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.weight(1f))
        StageHeadline(title, align = TextAlign.Center, modifier = Modifier.align(Alignment.CenterHorizontally))
        StageGap(12.dp)
        StageRich(
            body,
            style = Stage.Body,
            color = Stage.Secondary,
            align = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 330.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (chip != null) {
            StageGap(20.dp)
            StageSuccessChip(chip, Modifier.align(Alignment.CenterHorizontally))
        }
        StageGap(12.dp)
    }
}
