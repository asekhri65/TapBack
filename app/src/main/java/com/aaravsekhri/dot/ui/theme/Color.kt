package com.aaravsekhri.dot.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours lifted verbatim from the approved design boards
 * (Idle / Listening / Ready to send / Typing / Typed, ready / Ask / Learn).
 *
 * Dot is a light-only surface by design: there is no approved dark variant,
 * so these values are used directly rather than via a Material colour scheme.
 */
object DotColors {
    /** Page background — the exact near-white from every board. */
    val Background = Color(0xFFFBFBFC)

    /** Primary text. */
    val Ink = Color(0xFF16181B)

    /** Secondary text and icon strokes. */
    val Muted = Color(0xFF6B7076)

    /** Tertiary labels ("YOU SAID", "MORE PATHS", step counts). */
    val Faint = Color(0xFF7A8087)

    /** Pebble's body. */
    val Rust = Color(0xFFC2663F)

    /** Deeper rust used for text ("Listening…", "START HERE"). */
    val RustDeep = Color(0xFFA9512C)

    /** Stroke of the highlight halo drawn around the action Pebble points at. */
    val RustOutline = Color(0xFFB85B33)

    /** Wash inside the highlight halo — rgba(194,102,63,0.07). */
    val HaloFill = Color(0xFFC2663F).copy(alpha = 0.07f)

    /** Listening rings — rgba(194,102,63,0.30) / 0.45. */
    val RingStill = Color(0xFFC2663F).copy(alpha = 0.30f)
    val RingPulse = Color(0xFFC2663F).copy(alpha = 0.45f)

    /** Pebble's eye whites — a warm off-white, never pure white. */
    val EyeWhite = Color(0xFFF8F5F0)

    val Surface = Color(0xFFFFFFFF)
    val Border = Color(0xFFD6DADF)
    val Underline = Color(0xFFB9BEC3)

    /** Selected pill behind the active bottom-navigation item. */
    val NavPill = Color(0xFFECEEF1)

    /** Featured "Start here" card on Learn. */
    val FeaturedFill = Color(0xFFFCF5F1)
    val FeaturedBorder = Color(0xFFEBD3C5)

    /** "Share photos with family" path card. */
    val PhotoFill = Color(0xFFEFF3F6)
    val PhotoBorder = Color(0xFFDFE8EE)

    /** "Get comfortable with social media" path card. */
    val SocialFill = Color(0xFFEFF4F0)
    val SocialBorder = Color(0xFFDEEAE1)

    /** "Make the text bigger" path card. */
    val TextSizeFill = Color(0xFFF4F1F7)
    val TextSizeBorder = Color(0xFFE5DFEC)

    // Two more soft course colours, in the same family as the three above.
    val SandFill = Color(0xFFF7F4EC)
    val SandBorder = Color(0xFFEAE3D2)
    val RoseFill = Color(0xFFF8F1F1)
    val RoseBorder = Color(0xFFEDDCDC)

    /** The unfilled part of a lesson progress bar. */
    val ProgressTrack = Color(0xFFE6E8EB)

    val Chevron = Color(0xFF9AA0A6)

    /** Book mark carried by the Learn tab icon when it is not selected. */
    val BookGrey = Color(0xFF5F656B)

    // Illustration palette (Learn path cards).
    val PhotoFrameStroke = Color(0xFFCBD5DD)
    val PhotoSky = Color(0xFFDCE7EF)
    val PhotoSun = Color(0xFFEFC08A)
    val PhotoHills = Color(0xFF9FB3C2)
    val BubbleStroke = Color(0xFFC6D6C9)
    val BubbleLines = Color(0xFFB9CCBD)
}
