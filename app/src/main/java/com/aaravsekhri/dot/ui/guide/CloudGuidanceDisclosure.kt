package com.aaravsekhri.dot.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.ui.components.PrimaryButton
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotTheme
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * The second thing Dot asks for, and the bigger one.
 *
 * The first disclosure covers Dot reading the screen on the phone. This covers
 * relevant parts of that screen — and sometimes a picture of it — leaving the
 * device so a service can work out what to suggest next. Those are materially
 * different and are asked separately, in that order, because somebody who
 * agreed to the first has not agreed to the second.
 *
 * It says plainly what is sent, what is not, and what happens on a screen that
 * looks private. Declining leaves the fixed guides working.
 */
@Composable
fun CloudGuidanceDisclosureScreen(
    goal: String,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DotColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = stringResource(R.string.cloud_disclosure_title),
            style = DotType.ScreenTitle,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.cloud_disclosure_body, goal),
            style = DotType.CardBody,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.cloud_disclosure_not_sent),
            style = DotType.CardBody,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton(
            text = stringResource(R.string.cloud_disclosure_allow),
            onClick = onAllow,
            modifier = Modifier,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            text = stringResource(R.string.disclosure_decline),
            onClick = onDecline,
            modifier = Modifier,
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewCloudDisclosure() {
    DotTheme {
        CloudGuidanceDisclosureScreen(
            goal = "help me set an alarm",
            onAllow = {},
            onDecline = {},
        )
    }
}
