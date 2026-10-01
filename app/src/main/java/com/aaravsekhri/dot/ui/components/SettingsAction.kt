package com.aaravsekhri.dot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens
import com.aaravsekhri.dot.ui.theme.DotType

/**
 * The Settings control that sits in the top-right corner of every screen.
 * A labelled sun-like glyph rather than a bare icon, so it is readable at a
 * glance and reachable at 48dp.
 */
@Composable
fun SettingsAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(end = DotDimens.SettingsEndPadding - 6.dp)
            .defaultMinSize(minHeight = DotDimens.MinTouchTarget)
            .height(DotDimens.MinTouchTarget)
            .dotClickable(
                onClick = onClick,
                role = Role.Button,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DotDimens.SettingsGap),
    ) {
        SettingsGlyph()
        Text(
            text = stringResource(R.string.settings),
            style = DotType.SettingsLabel,
            color = DotColors.Muted,
        )
    }
}

/** A small centred circle with eight rays — drawn to match the reference exactly. */
@Composable
private fun SettingsGlyph() {
    Canvas(Modifier.size(DotDimens.SettingsIcon)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(width = 1.8f * unit, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)

        drawCircle(
            color = DotColors.Muted,
            radius = 3.1f * unit,
            center = p(12f, 12f),
            style = stroke,
        )
        // Four cardinal rays, then four diagonals.
        listOf(
            p(12f, 2.8f) to p(12f, 5.7f),
            p(12f, 18.3f) to p(12f, 21.2f),
            p(2.8f, 12f) to p(5.7f, 12f),
            p(18.3f, 12f) to p(21.2f, 12f),
            p(5.5f, 5.5f) to p(7.55f, 7.55f),
            p(16.45f, 16.45f) to p(18.5f, 18.5f),
            p(18.5f, 5.5f) to p(16.45f, 7.55f),
            p(7.55f, 16.45f) to p(5.5f, 18.5f),
        ).forEach { (start, end) ->
            drawLine(
                color = DotColors.Muted,
                start = start,
                end = end,
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBFBFC)
@Composable
private fun PreviewSettingsAction() {
    SettingsAction(onClick = {})
}
