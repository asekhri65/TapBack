package com.aaravsekhri.dot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aaravsekhri.dot.BuildConfig
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.guide.service.AccessibilityServiceStatus
import com.aaravsekhri.dot.guide.service.GuidePreferences
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.components.UnderlinedTextAction
import com.aaravsekhri.dot.ui.pebble.CardPebble
import com.aaravsekhri.dot.ui.pebble.PebbleMotion
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * Dot's settings: what the guide can do on this phone and how to change it,
 * the plan, the introduction again, and plainly what happens to the screen.
 *
 * Access is read afresh whenever Dot comes back to the front — the switch is
 * in Android's settings, and Dot never assumes it is where it was.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onReplayIntroduction: () -> Unit,
    /** The plan section: status, Dot Plus, restore and management. */
    plan: @Composable ColumnScope.() -> Unit,
    onOpenDocument: (com.aaravsekhri.dot.ui.legal.LegalDocument) -> Unit = {},
    /** Debug builds: a TapBack Plus state from fixture data, to look at. */
    onPreviewPlus: (com.aaravsekhri.dot.ui.plus.PlusFixture) -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember(context) { GuidePreferences(context) }
    var guidanceOn by remember { mutableStateOf(AccessibilityServiceStatus.isEnabled(context)) }
    var cloudAllowed by remember { mutableStateOf(preferences.cloudGuidanceAccepted) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                guidanceOn = AccessibilityServiceStatus.isEnabled(context)
                cloudAllowed = preferences.cloudGuidanceAccepted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    com.aaravsekhri.dot.ui.design.DismissKeyboardOnShow()

    Column(
        Modifier
            .fillMaxSize()
            .background(DotColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DotDimens.Gutter),
    ) {
        Spacer(Modifier.height(8.dp))
        UnderlinedTextAction(text = stringResource(R.string.settings_back), onClick = onBack, modifier = Modifier.width(96.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_title),
                style = DotType.ScreenTitle,
                color = DotColors.Ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            CardPebble(PebbleMotion.Bob, size = 48.dp)
        }
        Spacer(Modifier.height(20.dp))

        Section(stringResource(R.string.settings_guidance_heading)) {
            Body(stringResource(if (guidanceOn) R.string.settings_guidance_on else R.string.settings_guidance_off))
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                text = stringResource(R.string.settings_guidance_open),
                onClick = { AccessibilityServiceStatus.openSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (guidanceOn) {
                Spacer(Modifier.height(8.dp))
                Meta(stringResource(R.string.settings_guidance_turn_off_help))
            }
        }

        Section(stringResource(R.string.settings_cloud_heading)) {
            Body(stringResource(if (cloudAllowed) R.string.settings_cloud_on else R.string.settings_cloud_off))
            if (cloudAllowed) {
                UnderlinedTextAction(
                    text = stringResource(R.string.settings_cloud_withdraw),
                    onClick = {
                        // Nothing more is sent from now on; the next guide asks again.
                        preferences.cloudGuidanceAccepted = false
                        cloudAllowed = false
                    },
                )
            }
        }

        Section(stringResource(R.string.settings_plan_heading), content = plan)

        Section(stringResource(R.string.settings_intro_heading)) {
            SecondaryButton(
                text = stringResource(R.string.settings_intro_replay),
                onClick = onReplayIntroduction,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section(stringResource(R.string.settings_privacy_heading)) {
            Body(stringResource(R.string.settings_privacy_body))
            Spacer(Modifier.height(4.dp))
            UnderlinedTextAction(
                text = stringResource(R.string.legal_privacy_title),
                onClick = { onOpenDocument(com.aaravsekhri.dot.ui.legal.LegalDocument.Privacy) },
            )
            UnderlinedTextAction(
                text = stringResource(R.string.legal_terms_title),
                onClick = { onOpenDocument(com.aaravsekhri.dot.ui.legal.LegalDocument.Terms) },
            )
        }

        if (com.aaravsekhri.dot.BuildConfig.DEBUG) {
            Section("Lesson preview (test build)") { PreviewAccessSection() }
            Section("Developer (debug build)") { DeveloperSection(onPreviewPlus) }
        }

        AppIdentity()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        title.uppercase(),
        style = DotType.SectionLabel,
        color = DotColors.Faint,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(DotColors.Surface, shape)
            .border(1.dp, DotColors.Border, shape)
            .padding(16.dp),
        content = content,
    )
    Spacer(Modifier.height(22.dp))
}

@Composable
internal fun Body(text: String) = Text(text, style = DotType.CardBody, color = DotColors.Ink)

@Composable
internal fun Meta(text: String) = Text(text, style = DotType.CardMeta, color = DotColors.Muted)

/** The app as the launcher shows it: the approved icon, its name and version. */
@Composable
private fun AppIdentity() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The adaptive icon's own layers, masked as a launcher would: the
        // visible 72 of its 108 units, on its near-white background.
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.res.colorResource(R.color.ic_launcher_background))
                .border(1.dp, DotColors.Border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(66.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.app_name), style = DotType.CardBody, color = DotColors.Ink)
            Meta(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
        }
    }
}
