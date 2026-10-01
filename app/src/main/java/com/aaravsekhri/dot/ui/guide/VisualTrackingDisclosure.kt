package com.aaravsekhri.dot.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.ui.components.PrimaryButton
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.theme.DotTheme

/**
 * Dot's own explanation, shown before Android's capture dialog.
 *
 * The system dialog says what Android requires it to say; this says what Dot is
 * actually doing and why, in the words someone would use themselves. It appears
 * only once a guide is running and the experiment has been switched on —
 * never at launch, and never before there is something to track.
 *
 * Declining is a first-class answer, which is why it says what happens next
 * rather than just "no".
 */
@Composable
fun VisualTrackingDisclosureScreen(
    onAllow: () -> Unit,
    onDecline: () -> Unit,
) {
    LaunchScreenScaffold(
        title = stringResource(R.string.visual_disclosure_title),
        body = { BodyText(stringResource(R.string.visual_disclosure_body)) },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.visual_disclosure_allow),
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = stringResource(R.string.visual_disclosure_decline),
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Preview(name = "Visual tracking disclosure", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewVisualDisclosure() {
    DotTheme { VisualTrackingDisclosureScreen(onAllow = {}, onDecline = {}) }
}
