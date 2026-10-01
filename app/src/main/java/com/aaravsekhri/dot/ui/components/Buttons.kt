package com.aaravsekhri.dot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * The one action that matters on a screen: solid ink, full width, generous
 * height. Never more than one of these is shown at a time.
 *
 * Height is a floor rather than a fixed value so the label still fits when the
 * system font scale is turned up.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = DotDimens.PrimaryButtonHeight,
    radius: Dp = DotDimens.PrimaryButtonRadius,
    style: TextStyle = DotType.PrimaryButton,
    /** False while there is nothing yet to act on: shown, faded, and not tappable. */
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .dotClickable(onClick = onClick, role = Role.Button, shape = shape, enabled = enabled)
            .background(if (enabled) DotColors.Ink else DotColors.Ink.copy(alpha = 0.28f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style, color = DotColors.Surface)
    }
}

/** The quieter alternative beside a primary action, e.g. "Try again". */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(DotDimens.SecondaryButtonRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DotDimens.SecondaryButtonHeight)
            .dotClickable(onClick = onClick, role = Role.Button, shape = shape)
            .background(DotColors.Surface, shape)
            .border(1.dp, DotColors.Border, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = DotType.SecondaryButton, color = DotColors.Ink)
    }
}

/** The small bordered "Edit" control beside the recognised request. */
@Composable
fun EditButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(DotDimens.EditButtonRadius)
    Box(
        modifier = modifier
            .heightIn(min = DotDimens.EditButtonHeight)
            .dotClickable(onClick = onClick, role = Role.Button, shape = shape)
            .background(DotColors.Surface, shape)
            .border(1.dp, DotColors.Border, shape)
            .padding(horizontal = DotDimens.EditButtonPadding, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = DotType.EditButton, color = DotColors.Ink)
    }
}

/**
 * A plain underlined word-action — "Type instead", "Speak instead".
 * Quiet enough not to compete with the primary action, but still a full-width
 * 48dp target rather than a small run of text.
 */
@Composable
fun UnderlinedTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DotDimens.MinTouchTarget)
            .dotClickable(onClick = onClick, role = Role.Button, shape = RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DotType.TextAction,
            color = DotColors.Muted,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBFBFC, widthDp = 342)
@Composable
private fun PreviewButtons() {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
    ) {
        PrimaryButton(text = "Show me how", onClick = {})
        SecondaryButton(text = "Try again", onClick = {})
        EditButton(text = "Edit", onClick = {})
        UnderlinedTextAction(text = "Type instead", onClick = {})
    }
}
