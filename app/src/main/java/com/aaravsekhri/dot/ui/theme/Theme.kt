package com.aaravsekhri.dot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Dot's theme. The design is deliberately light-only and does not lean on
 * Material's defaults, so the scheme simply mirrors [DotColors] for the few
 * Material components that read from it. Screens state their own text styles
 * from [DotType] rather than inheriting Material's.
 */
private val DotColorScheme = lightColorScheme(
    primary = DotColors.Ink,
    onPrimary = DotColors.Surface,
    secondary = DotColors.Rust,
    onSecondary = DotColors.Surface,
    background = DotColors.Background,
    onBackground = DotColors.Ink,
    surface = DotColors.Surface,
    onSurface = DotColors.Ink,
    outline = DotColors.Border,
)

@Composable
fun DotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DotColorScheme,
        typography = DotMaterialTypography,
        shapes = MaterialTheme.shapes.copy(
            small = RoundedCornerShape(DotDimens.EditButtonRadius),
            medium = RoundedCornerShape(DotDimens.PrimaryButtonRadius),
            large = RoundedCornerShape(DotDimens.FeaturedRadius),
        ),
        content = content,
    )
}
