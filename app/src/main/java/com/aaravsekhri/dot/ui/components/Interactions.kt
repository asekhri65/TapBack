package com.aaravsekhri.dot.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import com.aaravsekhri.dot.ui.theme.DotDimens

/**
 * A tap target with press feedback clipped to the control's own shape.
 *
 * Every interactive surface in Dot goes through this so nothing ends up
 * smaller than [DotDimens.MinTouchTarget]; the audience includes people with
 * less steady hands, and a 48dp floor is the least we can do.
 */
fun Modifier.dotClickable(
    onClick: () -> Unit,
    role: Role? = null,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    onClickLabel: String? = null,
): Modifier = this
    .defaultMinSize(minWidth = DotDimens.MinTouchTarget, minHeight = DotDimens.MinTouchTarget)
    .clip(shape)
    .clickable(
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )
