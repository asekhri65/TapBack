package com.aaravsekhri.dot.guide.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Proof that the visual tracker is doing something, drawn in blue.
 *
 * Deliberately not Dot's rust: this rectangle is evidence for a developer, not
 * guidance for a person, and the two must never be confused on screen. It has
 * no Pebble and no entrance animation — it moves purely on visual-motion
 * estimates between semantic reconciliations, which is the whole thing being
 * measured. If it lags the content, that is the answer.
 *
 * Drawn on the same full-screen, non-touchable surface as the production
 * highlight, so it is touch-through for exactly the same reason.
 */
@Composable
fun VisualTrackingDebugOverlay(
    trackedRect: Rect?,
    confidence: Float,
    modifier: Modifier = Modifier,
) {
    if (trackedRect == null) return

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val corner = CornerRadius(8.dp.toPx())
            val topLeft = Offset(trackedRect.left, trackedRect.top)
            val size = Size(trackedRect.width, trackedRect.height)

            // Brightness follows confidence, so a struggling tracker looks like
            // one rather than like a confident wrong answer.
            val alpha = (0.35f + 0.65f * confidence).coerceIn(0f, 1f)

            drawRoundRect(
                color = DebugBlue.copy(alpha = alpha * 0.12f),
                topLeft = topLeft,
                size = size,
                cornerRadius = corner,
            )
            drawRoundRect(
                color = DebugBlue.copy(alpha = alpha),
                topLeft = topLeft,
                size = size,
                cornerRadius = corner,
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}

/** Unmistakably not the production rust. */
private val DebugBlue = Color(0xFF1E6FFF)

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 390, heightDp = 200)
@Composable
private fun PreviewDebugOverlay() {
    VisualTrackingDebugOverlay(
        trackedRect = Rect(40f, 40f, 1000f, 380f),
        confidence = 0.9f,
    )
}
