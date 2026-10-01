package com.aaravsekhri.dot.ui.util

import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * True when the person has turned animations off system-wide
 * (Settings > Accessibility > Remove animations, or Developer options).
 *
 * Dot's audience includes people who find movement distracting or
 * disorienting, so every decorative animation checks this first.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    if (LocalMotionOff.current) return true
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * True inside a copy of the interface that is only being measured, never
 * shown — the overlay card measures itself at two densities and draws one.
 * Nothing in the unseen copy animates.
 */
val LocalMotionOff = staticCompositionLocalOf { false }

/** Motion curves taken from the approved boards. */
object DotEasing {
    /** The calm breath of the listening rings. */
    val Ring: Easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

    /** Pebble's pop in place when it arrives beside something. */
    val Pop: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
}

/** Durations, in milliseconds, from the approved boards. */
object DotDuration {
    const val RING_PULSE = 2600
    const val POP_IN_PLACE = 210
    const val FADE_IN = 200

    /** Pebble settles into place before its halo is drawn around the target. */
    const val POP_DELAY = 240
    const val HALO_DELAY = 410
}
