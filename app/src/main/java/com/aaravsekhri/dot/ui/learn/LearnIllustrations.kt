package com.aaravsekhri.dot.ui.learn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import com.aaravsekhri.dot.ui.pebble.drawPebbleMark
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotDimens

/**
 * The Learn path illustrations, drawn natively in a 72-unit square.
 *
 * Both are decorative — the card's own title and step count carry the meaning —
 * so each clears its semantics rather than adding a second thing to announce.
 * Pebble appears small in the lower-left of each, the same way it does on the
 * boards, so the paths read as things you do together.
 */

private const val BOX = 72f

/** A tilted photo — sky, sun, hills — with Pebble looking on. */
@Composable
fun SharePhotosIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier.size(DotDimens.PathIllustration).clearAndSetSemantics { }) {
        val u = size.minDimension / BOX
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        rotate(degrees = -8f, pivot = p(44f, 30f)) {
            val frame = RoundRect(p(26f, 10f), Size(40f * u, 34f * u), 5f * u)
            drawRoundRect(DotColors.Surface, frame.topLeft, frame.size, CornerRadius(frame.radius))
            drawRoundRect(
                DotColors.PhotoFrameStroke, frame.topLeft, frame.size,
                CornerRadius(frame.radius), style = Stroke(1.4f * u),
            )
            drawRoundRect(
                DotColors.PhotoSky, p(30f, 14f), Size(32f * u, 21f * u), CornerRadius(3f * u),
            )
            drawCircle(DotColors.PhotoSun, radius = 3.2f * u, center = p(39f, 21f))

            // The hill line, as drawn in the reference.
            val hills = Path().apply {
                moveTo(31f * u, 34.5f * u)
                lineTo(39f * u, 26f * u)
                lineTo(44.5f * u, 31.5f * u)
                lineTo(48.5f * u, 27.5f * u)
                lineTo(61.5f * u, 34.5f * u)
            }
            drawPath(
                hills, DotColors.PhotoHills,
                style = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        drawCompanionPebble(u)
    }
}

/** Two overlapping speech bubbles and a small heart, with Pebble alongside. */
@Composable
fun SocialMediaIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier.size(DotDimens.PathIllustration).clearAndSetSemantics { }) {
        val u = size.minDimension / BOX
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        // Back bubble.
        drawRoundRect(DotColors.Surface, p(20f, 6f), Size(30f * u, 21f * u), CornerRadius(8f * u))
        drawRoundRect(
            DotColors.BubbleStroke, p(20f, 6f), Size(30f * u, 21f * u),
            CornerRadius(8f * u), style = Stroke(1.4f * u),
        )
        // Two lines of "text" inside it.
        listOf(
            p(27f, 15.5f) to p(43f, 15.5f),
            p(27f, 20f) to p(37f, 20f),
        ).forEach { (start, end) ->
            drawLine(DotColors.BubbleLines, start, end, strokeWidth = 2.2f * u, cap = StrokeCap.Round)
        }

        // Front bubble, with its little tail.
        drawRoundRect(DotColors.Surface, p(34f, 24f), Size(34f * u, 24f * u), CornerRadius(9f * u))
        drawRoundRect(
            DotColors.BubbleStroke, p(34f, 24f), Size(34f * u, 24f * u),
            CornerRadius(9f * u), style = Stroke(1.4f * u),
        )
        drawPath(
            Path().apply {
                moveTo(40f * u, 48.5f * u)
                lineTo(39f * u, 54f * u)
                lineTo(44.5f * u, 48.5f * u)
                close()
            },
            DotColors.Surface,
        )

        // A small rust heart, the one warm mark in the card.
        drawPath(
            Path().apply {
                moveTo(51f * u, 31.6f * u)
                cubicTo(52.5f * u, 29.4f * u, 55.8f * u, 30.1f * u, 55.8f * u, 32.8f * u)
                cubicTo(55.8f * u, 35.7f * u, 51f * u, 38.4f * u, 51f * u, 38.4f * u)
                cubicTo(51f * u, 38.4f * u, 46.2f * u, 35.7f * u, 46.2f * u, 32.8f * u)
                cubicTo(46.2f * u, 30.1f * u, 49.5f * u, 29.4f * u, 51f * u, 31.6f * u)
                close()
            },
            DotColors.Rust,
        )

        drawCompanionPebble(u)
    }
}

/** Pebble tucked into the lower-left of a path illustration, at 0.34 scale. */
internal fun DrawScope.drawCompanionPebble(u: Float) {
    drawPebbleMark(
        topLeft = Offset(2f * u, 34f * u),
        diameter = 100f * u * 0.34f,
        pupilRadius = 6.2f,
        catchlightRadius = 2.3f,
    )
}

private data class RoundRect(val topLeft: Offset, val size: Size, val radius: Float)

@Preview(showBackground = true, backgroundColor = 0xFFEFF3F6)
@Composable
private fun PreviewSharePhotos() = SharePhotosIllustration()

@Preview(showBackground = true, backgroundColor = 0xFFEFF4F0)
@Composable
private fun PreviewSocialMedia() = SocialMediaIllustration()

/** A small letter beside a large one: the same word, made easier to read. */
@Composable
fun TextSizeIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier.size(DotDimens.PathIllustration).clearAndSetSemantics { }) {
        val u = size.minDimension / BOX
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        /** One capital A, standing on a shared baseline. */
        fun letterA(centreX: Float, height: Float, width: Float, stroke: Float) {
            val baseline = 38f
            val apex = baseline - height
            drawPath(
                path = Path().apply {
                    moveTo(p(centreX - width / 2f, baseline).x, p(centreX - width / 2f, baseline).y)
                    lineTo(p(centreX, apex).x, p(centreX, apex).y)
                    lineTo(p(centreX + width / 2f, baseline).x, p(centreX + width / 2f, baseline).y)
                },
                color = DotColors.Ink,
                style = Stroke(
                    width = stroke * u,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            // The crossbar, two thirds of the way down from the apex.
            val barY = apex + height * 0.66f
            drawLine(
                color = DotColors.Ink,
                start = p(centreX - width * 0.33f, barY),
                end = p(centreX + width * 0.33f, barY),
                strokeWidth = stroke * u,
                cap = StrokeCap.Round,
            )
        }

        // Clear of the companion Pebble, which sits in the lower-left corner.
        letterA(centreX = 34f, height = 16f, width = 13f, stroke = 3f)
        letterA(centreX = 58f, height = 28f, width = 22f, stroke = 4.5f)

        drawCompanionPebble(u)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF4F1F7)
@Composable
private fun PreviewTextSize() = TextSizeIllustration()
