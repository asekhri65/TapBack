package com.aaravsekhri.dot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.ui.app.DotTab
import com.aaravsekhri.dot.ui.pebble.PebbleSize
import com.aaravsekhri.dot.ui.pebble.drawPebbleMark
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * Dot's two-destination bottom navigation.
 *
 * The selected item is marked three ways over — a filled pill, a heavier
 * label, and a full-strength icon — so it never depends on colour alone. It is
 * also announced as selected to accessibility services.
 */
@Composable
fun DotBottomNav(
    selected: DotTab,
    onSelect: (DotTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DotDimens.NavHeight)
            .padding(horizontal = DotDimens.NavHorizontalPadding),
        verticalAlignment = Alignment.Top,
    ) {
        DotTab.entries.forEach { tab ->
            NavItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NavItem(
    tab: DotTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(tab.labelRes)
    Box(
        modifier = modifier
            .heightIn(min = DotDimens.NavHeight),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = DotDimens.NavPillWidth)
                .fillMaxWidth()
                .heightIn(min = DotDimens.NavPillHeight)
                .dotClickable(
                    onClick = onClick,
                    role = Role.Tab,
                    shape = RoundedCornerShape(DotDimens.NavPillRadius),
                )
                // Announced as "Ask, selected" rather than relying on the pill alone.
                .semantics { this.selected = selected; this.role = Role.Tab }
                .background(
                    color = if (selected) DotColors.NavPill else Color.Transparent,
                    shape = RoundedCornerShape(DotDimens.NavPillRadius),
                )
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DotDimens.NavPillGap, Alignment.CenterVertically),
        ) {
            Box(Modifier.alpha(if (selected) 1f else 0.45f)) {
                when (tab) {
                    DotTab.Ask -> AskTabIcon()
                    DotTab.Learn -> LearnTabIcon(selected = selected)
                }
            }
            Text(
                text = label,
                style = if (selected) DotType.NavLabelSelected else DotType.NavLabel,
                color = if (selected) DotColors.Ink else DotColors.Faint,
            )
        }
    }
}

/** Ask is simply Pebble, facing forward. */
@Composable
private fun AskTabIcon() {
    Canvas(Modifier.size(DotDimens.PebbleNav)) {
        drawPebbleMark(
            topLeft = Offset.Zero,
            diameter = size.minDimension,
            pupilRadius = PebbleSize.Navigation.pupilRadius,
            catchlightRadius = PebbleSize.Navigation.catchlightRadius,
        )
    }
}

/**
 * Learn is Pebble drawn smaller, with a book tucked behind its shoulder.
 * The book darkens to ink when the tab is selected.
 */
@Composable
private fun LearnTabIcon(selected: Boolean) {
    val bookColor = if (selected) DotColors.Ink else DotColors.BookGrey
    Canvas(Modifier.size(DotDimens.PebbleNav)) {
        val unit = size.minDimension / 100f
        // Pebble at 0.66 scale, nudged down 4 units, as approved.
        drawPebbleMark(
            topLeft = Offset(0f, 4f * unit),
            diameter = size.minDimension * 0.66f,
            pupilRadius = 6.4f,
            catchlightRadius = 2.4f,
        )
        drawBook(unit = unit, color = bookColor)
    }
}

/** The small tilted book: a rounded slab with a spine down its middle. */
private fun DrawScope.drawBook(unit: Float, color: Color) {
    rotate(degrees = -7f, pivot = Offset(70f * unit, 74f * unit)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(44f * unit, 54f * unit),
            size = Size(52f * unit, 38f * unit),
            cornerRadius = CornerRadius(7f * unit),
        )
        drawLine(
            color = DotColors.Background,
            start = Offset(70f * unit, 56f * unit),
            end = Offset(70f * unit, 90f * unit),
            strokeWidth = 3.4f * unit,
            cap = StrokeCap.Round,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBFBFC, widthDp = 390)
@Composable
private fun PreviewNavAskSelected() {
    DotBottomNav(selected = DotTab.Ask, onSelect = {})
}

@Preview(showBackground = true, backgroundColor = 0xFFFBFBFC, widthDp = 390)
@Composable
private fun PreviewNavLearnSelected() {
    DotBottomNav(selected = DotTab.Learn, onSelect = {})
}
