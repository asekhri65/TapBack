package com.aaravsekhri.dot.ui.ask

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.ui.components.HighlightedAction
import com.aaravsekhri.dot.ui.components.PrimaryButton
import com.aaravsekhri.dot.ui.components.SettingsAction
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * The Ask tab: one question, one box to answer it in, one button.
 *
 * Built so the button is never somewhere to go looking for. The words scroll
 * in the space above; "Show me how" sits beneath them, and the whole screen is
 * padded for the keyboard, so while the keyboard is open the button is right
 * above it. Putting the keyboard away only puts the keyboard away — the words
 * stay as they are, and nothing is sent until the button is pressed.
 *
 * Pebble perches on the box, as it always has on the thing to do next. It is
 * not a microphone and tapping it does nothing: V1 is typing only.
 *
 * @param autoFocus true when the screen may put the cursor in the box by
 *   itself. Only once per visit, and never over a dialog, so coming back from
 *   Settings or a guide does not throw the keyboard up again.
 */
@Composable
fun AskScreen(
    holder: AskStateHolder,
    onOpenSettings: () -> Unit,
    onShowMeHow: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inPreview = LocalInspectionMode.current

    // The caret starts after any words already there, so a returning draft
    // is carried on rather than typed over.
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(holder.draft, TextRange(holder.draft.length)))
    }

    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoFocus) {
        if (autoFocus && !focusedOnce && !inPreview) {
            focusedOnce = true
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    fun submit() {
        val request = holder.submit(SystemClock.uptimeMillis()) ?: return
        focusManager.clearFocus()
        keyboard?.hide()
        onShowMeHow(request)
    }

    val fieldDescription = stringResource(R.string.request_field_description)
    val field: @Composable (Modifier, Boolean) -> Unit = { fieldModifier, compact ->
        val shape = RoundedCornerShape(DotDimens.RequestBoxRadius)
        BasicTextField(
            value = fieldValue,
            onValueChange = {
                // As long as the service will read, and no longer.
                val kept = if (it.text.length > com.aaravsekhri.dot.guide.ai.AiClient.MAX_GOAL) {
                    it.copy(text = it.text.take(com.aaravsekhri.dot.guide.ai.AiClient.MAX_GOAL))
                } else {
                    it
                }
                fieldValue = kept
                holder.updateDraft(kept.text)
            },
            textStyle = DotType.RequestBox.copy(color = DotColors.Ink),
            cursorBrush = SolidColor(DotColors.Rust),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Go,
            ),
            // A shortcut beside the button, not instead of it.
            keyboardActions = KeyboardActions(onGo = { submit() }),
            decorationBox = { inner ->
                Box {
                    if (fieldValue.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ask_placeholder),
                            style = DotType.RequestBox,
                            color = DotColors.Faint,
                        )
                    }
                    inner()
                }
            },
            modifier = fieldModifier
                .background(DotColors.Surface, shape)
                .border(1.dp, DotColors.Border, shape)
                .padding(if (compact) 10.dp else DotDimens.RequestBoxPadding)
                .focusRequester(focusRequester)
                .semantics { contentDescription = fieldDescription },
        )
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        if (maxHeight < SHORT_WINDOW) {
            // Landscape with the keyboard up leaves a strip a few lines high.
            // The words and the button share it side by side; the heading and
            // Settings come back when the keyboard goes.
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = DotDimens.Gutter, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                field(Modifier.weight(1f).fillMaxHeight(), true)
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    text = stringResource(R.string.show_me_how),
                    onClick = ::submit,
                    enabled = holder.canSubmit,
                    height = DotDimens.MinTouchTarget,
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
            }
            return@BoxWithConstraints
        }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth()) {
                SettingsAction(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd))
            }

            // Everything above the button scrolls together, so a long request
            // or a large text size never pushes the button away.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DotDimens.Gutter),
            ) {
                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.ask_heading),
                    style = DotType.ScreenTitle,
                    color = DotColors.Ink,
                    modifier = Modifier.semantics { heading() },
                )

                Spacer(Modifier.height(28.dp))

                HighlightedAction(modifier = Modifier.fillMaxWidth()) {
                    // Grows with the words, then scrolls inside itself.
                    field(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = DotDimens.RequestBoxHeight, max = MAX_BOX_HEIGHT),
                        false,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.ask_hint),
                    style = DotType.Hint.copy(textAlign = TextAlign.Start),
                    color = DotColors.Muted,
                )

                Spacer(Modifier.height(24.dp))
            }

            PrimaryButton(
                text = stringResource(R.string.show_me_how),
                onClick = ::submit,
                enabled = holder.canSubmit,
                modifier = Modifier.padding(horizontal = DotDimens.Gutter, vertical = 12.dp),
            )
        }
    }
}

/** Below this the screen is a strip above the keyboard, not a page. */
private val SHORT_WINDOW = 240.dp

/** Past this the box scrolls inside itself rather than growing. */
private val MAX_BOX_HEIGHT = 220.dp
