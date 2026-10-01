@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.aaravsekhri.dot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aaravsekhri.dot.R

/**
 * Schibsted Grotesk, the typeface the boards are set in. The bundled file is
 * the variable original, so each weight is pinned through its wght axis rather
 * than left to the platform's synthetic bolding.
 */
private fun schibsted(weight: Int) = Font(
    resId = R.font.schibsted_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val SchibstedGrotesk = FontFamily(
    schibsted(400),
    schibsted(500),
    schibsted(600),
    schibsted(700),
)

/**
 * Text styles named after the role they play on the boards, so screens read
 * as the design does. Sizes are in sp and therefore honour font scaling.
 */
object DotType {
    val SettingsLabel = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W500,
        fontSize = 16.sp, lineHeight = 22.sp,
    )

    /** "Tap Pebble to talk" */
    val Prompt = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W500,
        fontSize = 19.sp, lineHeight = 27.sp, textAlign = TextAlign.Center,
    )

    /** "Type instead" / "Speak instead" */
    val TextAction = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W500,
        fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
    )

    /** "Tap Pebble to stop" */
    val Hint = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W400,
        fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
    )

    /** "Listening…" */
    val Status = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center,
    )

    /** "YOU SAID" */
    val Eyebrow = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 0.11.em,
    )

    /** "START HERE" / "MORE PATHS" */
    val SectionLabel = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.12.em,
    )

    /** The recognised request, shown large. */
    val Request = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W400,
        fontSize = 24.sp, lineHeight = 34.8.sp, letterSpacing = (-0.012).em,
    )

    /** The request inside the text box. */
    val RequestBox = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W400,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em,
    )

    val PrimaryButton = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 18.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
    )

    val SecondaryButton = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W500,
        fontSize = 18.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
    )

    val EditButton = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W500,
        fontSize = 17.sp, lineHeight = 22.sp,
    )

    val LessonButton = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 17.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
    )

    /** "Learn" */
    val ScreenTitle = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 31.sp, lineHeight = 39.sp, letterSpacing = (-0.021).em,
    )

    /** "Get started with ChatGPT" */
    val CardTitle = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.015).em,
    )

    val CardBody = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W400,
        fontSize = 16.sp, lineHeight = 23.2.sp,
    )

    val CardMeta = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W400,
        fontSize = 14.5.sp, lineHeight = 20.sp,
    )

    /** "Share photos with family" */
    val PathTitle = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 19.sp, lineHeight = 23.75.sp, letterSpacing = (-0.012).em,
    )

    val PathMeta = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W400,
        fontSize = 15.sp, lineHeight = 21.sp,
    )

    val NavLabelSelected = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 18.sp,
    )

    val NavLabel = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W400,
        fontSize = 14.sp, lineHeight = 18.sp,
    )
}

/** Supplied to MaterialTheme so any stray Material text still uses the brand face. */
internal val DotMaterialTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = SchibstedGrotesk),
        displayMedium = base.displayMedium.copy(fontFamily = SchibstedGrotesk),
        displaySmall = base.displaySmall.copy(fontFamily = SchibstedGrotesk),
        headlineLarge = base.headlineLarge.copy(fontFamily = SchibstedGrotesk),
        headlineMedium = base.headlineMedium.copy(fontFamily = SchibstedGrotesk),
        headlineSmall = base.headlineSmall.copy(fontFamily = SchibstedGrotesk),
        titleLarge = base.titleLarge.copy(fontFamily = SchibstedGrotesk),
        titleMedium = base.titleMedium.copy(fontFamily = SchibstedGrotesk),
        titleSmall = base.titleSmall.copy(fontFamily = SchibstedGrotesk),
        bodyLarge = base.bodyLarge.copy(fontFamily = SchibstedGrotesk),
        bodyMedium = base.bodyMedium.copy(fontFamily = SchibstedGrotesk),
        bodySmall = base.bodySmall.copy(fontFamily = SchibstedGrotesk),
        labelLarge = base.labelLarge.copy(fontFamily = SchibstedGrotesk),
        labelMedium = base.labelMedium.copy(fontFamily = SchibstedGrotesk),
        labelSmall = base.labelSmall.copy(fontFamily = SchibstedGrotesk),
    )
}
