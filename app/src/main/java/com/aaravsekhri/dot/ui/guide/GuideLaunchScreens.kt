package com.aaravsekhri.dot.ui.guide

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import com.aaravsekhri.dot.ui.pebble.CardPebble
import com.aaravsekhri.dot.ui.pebble.PebbleMotion
import com.aaravsekhri.dot.ui.util.rememberReducedMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.ui.components.PrimaryButton
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.pebble.Pebble
import com.aaravsekhri.dot.ui.pebble.PebbleSize
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.theme.DotTheme
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * The screens between "Show me how" and a running guide.
 *
 * All of them are ordinary full screens in Dot's own voice, not system
 * dialogs. The disclosure in particular has to be somewhere a person will
 * actually read it, which means its own screen with two plain choices — not a
 * line inside a settings page or a privacy policy.
 */

/** Shared frame so every one of these reads as the same kind of moment. */
@Composable
internal fun LaunchScreenScaffold(
    title: String,
    body: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DotColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DotDimens.Gutter),
        ) {
            Spacer(Modifier.height(56.dp))

            Pebble(size = PebbleSize.Medium, contentDescription = null)

            Spacer(Modifier.height(28.dp))

            Text(
                text = title,
                style = DotType.ScreenTitle,
                color = DotColors.Ink,
                modifier = Modifier.semantics { heading() },
            )

            Spacer(Modifier.height(16.dp))

            body()

            Spacer(Modifier.height(40.dp))

            actions()

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * "Getting your guide ready…": shown while TapBack asks its service to start the
 * task, before any guidance exists — so it says only that, and offers the one
 * thing that is true at this point: ending it.
 *
 * Pebble is centred a little above the middle of the screen at a modest size,
 * breathing gently in a square that never changes; the words and the indicator
 * beneath it have fixed room, so nothing moves while it waits. With animations
 * off Pebble is still and the indicator does not pulse.
 */
@Composable
internal fun GuideLoadingScreen(message: String, endLabel: String, onEnd: () -> Unit) {
    // The request was typed a moment ago: the keyboard that is still up would
    // sit over the one button this screen has.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        focus.clearFocus()
        keyboard?.hide()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(DotColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = DotDimens.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Weighted 4 to 5: the group's middle sits just above the screen's.
            Spacer(Modifier.weight(0.8f))
            CardPebble(PebbleMotion.Bob, size = 88.dp)
            Spacer(Modifier.height(28.dp))
            Text(
                text = message,
                style = DotType.CardTitle,
                color = DotColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Spacer(Modifier.height(20.dp))
            LoadingDots()
            Spacer(Modifier.weight(1f))
            SecondaryButton(text = endLabel, onClick = onEnd)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Three small dots taking turns; all the same, and still, with animations off. */
@Composable
private fun LoadingDots() {
    val still = rememberReducedMotion()
    val transition = if (still) null else rememberInfiniteTransition(label = "loading-dots")
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(12.dp)
            .clearAndSetSemantics { },
    ) {
        repeat(3) { i ->
            val alpha = if (transition == null) {
                0.5f
            } else {
                val a by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(
                        keyframes {
                            durationMillis = 1_350
                            0.25f at (i * 150)
                            0.85f at (i * 150 + 350) using FastOutSlowInEasing
                            0.25f at (i * 150 + 700) using FastOutSlowInEasing
                        },
                    ),
                    label = "dot-$i",
                )
                a
            }
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(DotColors.Rust, CircleShape),
            )
        }
    }
}

@Composable
internal fun BodyText(text: String) {
    Text(text = text, style = DotType.CardBody, color = DotColors.Muted)
}

/**
 * The prominent disclosure.
 *
 * Shown before Android's Accessibility settings is ever opened, and only
 * "Allow and continue" leads there. Declining is a real option that leaves the
 * rest of Dot working.
 */
@Composable
fun DisclosureScreen(
    onAllow: () -> Unit,
    onDecline: () -> Unit,
) {
    LaunchScreenScaffold(
        title = stringResource(R.string.disclosure_title),
        body = { BodyText(stringResource(R.string.disclosure_body)) },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.disclosure_allow),
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = stringResource(R.string.disclosure_decline),
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** What to do once in Android's Accessibility settings. */
@Composable
fun EnableServiceScreen(
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
) {
    LaunchScreenScaffold(
        title = stringResource(R.string.enable_title),
        body = { BodyText(stringResource(R.string.enable_body)) },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.enable_open_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = stringResource(R.string.enable_not_now),
                    onClick = onNotNow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** Offered on returning to Dot with access switched on. */
@Composable
fun ResumeGuideScreen(
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    goal: String? = null,
) {
    LaunchScreenScaffold(
        title = stringResource(R.string.resume_title),
        body = {
            BodyText(
                // The person's own words for a general request; the tutorial's
                // own sentence only for the tutorial it describes.
                if (goal != null) stringResource(R.string.resume_body_goal, goal) else stringResource(R.string.resume_body),
            )
        },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.resume_continue),
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = stringResource(R.string.resume_cancel),
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/**
 * What Dot can still do without accessibility access: say the steps, and let
 * the person follow them at their own pace.
 */
@Composable
fun LimitedModeScreen(
    steps: List<String>,
    onClose: () -> Unit,
) {
    LaunchScreenScaffold(
        title = stringResource(R.string.limited_title),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BodyText(stringResource(R.string.limited_body))
                steps.forEachIndexed { index, step ->
                    Row2(number = index + 1, text = step)
                }
            }
        },
        actions = {
            SecondaryButton(
                text = stringResource(R.string.limited_close),
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** A numbered step in limited mode. */
@Composable
private fun Row2(number: Int, text: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$number.",
            style = DotType.CardBody,
            color = DotColors.RustDeep,
        )
        Text(text = text, style = DotType.CardBody, color = DotColors.Ink)
    }
}

/** Shown when the request is not one of the guides Dot ships. */
@Composable
fun UnsupportedRequestScreen(onClose: () -> Unit) {
    LaunchScreenScaffold(
        title = stringResource(R.string.unsupported_title),
        body = { BodyText(stringResource(R.string.unsupported_body)) },
        actions = {
            SecondaryButton(
                text = stringResource(R.string.limited_close),
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Preview(name = "Disclosure", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDisclosure() {
    DotTheme { DisclosureScreen(onAllow = {}, onDecline = {}) }
}

@Preview(name = "Enable service", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewEnableService() {
    DotTheme { EnableServiceScreen(onOpenSettings = {}, onNotNow = {}) }
}

@Preview(name = "Resume guide", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewResume() {
    DotTheme { ResumeGuideScreen(onContinue = {}, onCancel = {}) }
}

@Preview(name = "Limited mode", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewLimited() {
    DotTheme {
        LimitedModeScreen(
            steps = listOf(
                "Open Settings and tap Display.",
                "Tap Display size and text.",
                "Drag Font size to the right.",
            ),
            onClose = {},
        )
    }
}
