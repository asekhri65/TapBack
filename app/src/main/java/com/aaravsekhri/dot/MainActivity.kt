package com.aaravsekhri.dot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.aaravsekhri.dot.ui.app.DotApp
import com.aaravsekhri.dot.ui.theme.DotTheme

/**
 * Dot's single entry point. Everything the app draws lives under
 * [com.aaravsekhri.dot.ui]; this class only sets up the window.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.aaravsekhri.dot.dev.LocalNetworkAccess.requestIfNeeded(this)

        // Dot's surface is near-white in every state, so the system bars are
        // transparent with dark icons over it. Drawing edge to edge also means
        // the keyboard's insets reach Compose, which the typing state relies on.
        val transparent = Color.Transparent.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )

        setContent {
            DotTheme {
                // "Show me how" and every lesson are wired through DotApp's
                // launch controller; Settings opens within it.
                DotApp()
            }
        }
    }
}
