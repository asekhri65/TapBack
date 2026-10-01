package com.aaravsekhri.dot.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.ui.components.PrimaryButton
import com.aaravsekhri.dot.ui.components.SecondaryButton
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotTheme
import com.aaravsekhri.dot.ui.theme.DotType

/** A plain statement with one way out. */
@Composable
fun SimpleNoticeScreen(title: String, body: String, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(DotColors.Surface)
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(title, style = DotType.ScreenTitle, color = DotColors.Ink)
        Spacer(Modifier.height(16.dp))
        Text(body, style = DotType.CardBody, color = DotColors.Ink)
        Spacer(Modifier.height(32.dp))
        PrimaryButton(text = stringResource(R.string.guide_exit), onClick = onClose)
    }
}

/**
 * An unfinished request, offered rather than resumed.
 *
 * Carrying on takes a fresh look at whatever is on screen now and plans again
 * from there. What it never does is replay the last instruction: that was
 * about a screen from another day, and following it would be pointing at a
 * place rather than a thing.
 */
@Composable
fun ResumeSessionScreen(goal: String, onContinue: () -> Unit, onDiscard: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(DotColors.Surface)
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            stringResource(R.string.resume_offer_title),
            style = DotType.ScreenTitle,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.resume_offer_body, goal),
            style = DotType.CardBody,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton(text = stringResource(R.string.resume_offer_continue), onClick = onContinue)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(text = stringResource(R.string.resume_offer_discard), onClick = onDiscard)
    }
}

/**
 * Starting one guide while another is running. Nothing is lost either way:
 * the one that stops keeps its saved place. A plain choice, with neither
 * answer recommended.
 */
@Composable
fun ReplaceGuideScreen(current: String, next: String, onStart: () -> Unit, onKeep: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(DotColors.Surface)
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            stringResource(R.string.replace_guide_title),
            style = DotType.ScreenTitle,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.replace_guide_body, current, next),
            style = DotType.CardBody,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton(text = stringResource(R.string.replace_guide_start, next), onClick = onStart)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(text = stringResource(R.string.replace_guide_keep), onClick = onKeep)
    }
}

/**
 * The one question Dot is allowed to ask, answered where there is a keyboard.
 *
 * Asked here rather than on the overlay on purpose: typing into a field
 * floating over somebody else's app is unpleasant, and the Ask tab already
 * knows how to take a sentence.
 */
@Composable
fun ClarificationScreen(
    question: String,
    onAnswer: (String) -> Unit,
    onClose: () -> Unit,
    options: List<String> = emptyList(),
) {
    var answer by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(DotColors.Surface)
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            stringResource(R.string.question_title),
            style = DotType.ScreenTitle,
            color = DotColors.Ink,
        )
        Spacer(Modifier.height(16.dp))
        Text(question, style = DotType.CardBody, color = DotColors.Ink)
        Spacer(Modifier.height(20.dp))
        // A choice is answered with one tap. Typing is still there for an
        // answer the choices do not cover.
        options.forEach { option ->
            SecondaryButton(text = option, onClick = { onAnswer(option) })
            Spacer(Modifier.height(12.dp))
        }
        if (options.isNotEmpty()) Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = DotType.CardBody,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (answer.isNotBlank()) onAnswer(answer) }),
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = stringResource(R.string.question_send),
            onClick = { if (answer.isNotBlank()) onAnswer(answer) },
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton(text = stringResource(R.string.guide_exit), onClick = onClose)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewClarification() {
    DotTheme {
        ClarificationScreen(
            question = "Do you mean the screen brightness, or the text size?",
            onAnswer = {},
            onClose = {},
        )
    }
}
