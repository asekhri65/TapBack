package com.aaravsekhri.dot.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Measurements taken from the approved 390 x 844 boards.
 *
 * Fixed values reproduce the design's local spacing; the large empty runs
 * between groups are expressed as weights in the screens themselves so the
 * layout breathes on taller or shorter phones instead of clipping.
 */
object DotDimens {
    /** Board width the design was drawn against. */
    val DesignWidth = 390.dp

    /** Side gutter used by every board. */
    val Gutter = 24.dp

    /** Minimum comfortable touch target for the intended audience. */
    val MinTouchTarget = 48.dp

    // Settings control (top 44, right 18, height 44 on every board).
    val SettingsEndPadding = 18.dp
    val SettingsHeight = 44.dp
    val SettingsIcon = 18.dp
    val SettingsGap = 7.dp

    // Pebble sizes.
    val PebbleLarge = 176.dp
    val PebbleListening = 132.dp
    val PebbleHighlight = 34.dp
    val PebbleNav = 26.dp
    val PebbleInline = 22.dp

    // Listening rings.
    val ListeningRing = 196.dp
    val ListeningRingStroke = 1.5.dp

    // Primary / secondary actions.
    val PrimaryButtonHeight = 60.dp
    val PrimaryButtonRadius = 14.dp
    val SecondaryButtonHeight = 56.dp
    val SecondaryButtonRadius = 14.dp
    val LessonButtonHeight = 52.dp
    val LessonButtonRadius = 12.dp

    /** The halo sits 8dp outside the target it highlights. */
    val HaloInset = 8.dp
    val HaloRadius = 22.dp
    val HaloLessonRadius = 20.dp
    val HaloStroke = 2.dp

    // Request text box (Typing / Typed, ready).
    val RequestBoxHeight = 120.dp
    val RequestBoxRadius = 14.dp
    val RequestBoxPadding = 18.dp

    // Edit button (Ready to send).
    val EditButtonHeight = 44.dp
    val EditButtonRadius = 12.dp
    val EditButtonPadding = 18.dp

    // Bottom navigation: 68dp of content, then the system navigation inset.
    val NavHeight = 68.dp
    val NavHorizontalPadding = 12.dp
    val NavPillWidth = 132.dp
    val NavPillHeight = 64.dp
    val NavPillRadius = 18.dp
    val NavPillGap = 5.dp

    // Learn.
    val FeaturedRadius = 20.dp
    val FeaturedPadding = 20.dp
    val PathCardHeight = 104.dp
    val PathCardRadius = 18.dp
    val PathIllustration = 72.dp
}
