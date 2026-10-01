package com.aaravsekhri.dot.ui.learn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.courses.CourseArt
import com.aaravsekhri.dot.ui.theme.DotColors

/**
 * One small illustration per course, in the same hand as the Learn boards:
 * a plain object drawn in soft greys with one warm rust mark, and Pebble
 * tucked into the lower-left corner beside it.
 *
 * Generic objects, never an app's logo: a speech bubble, a map pin, an
 * envelope. Decorative — the course's title says what it is — so each clears
 * its semantics.
 */
@Composable
fun CourseIllustration(art: CourseArt, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    when (art) {
        CourseArt.Photo -> SharePhotosIllustration(modifier.size(size))
        CourseArt.Post -> SocialMediaIllustration(modifier.size(size))
        else -> Canvas(modifier.size(size).clearAndSetSemantics { }) {
            val u = this.size.minDimension / 72f
            when (art) {
                CourseArt.Helper -> helper(u)
                CourseArt.Chat -> chat(u)
                CourseArt.Map -> map(u)
                CourseArt.Mail -> mail(u)
                CourseArt.Video -> video(u)
                CourseArt.Calendar -> calendar(u)
                CourseArt.Web -> web(u)
                CourseArt.Text -> text(u)
                CourseArt.Camera -> camera(u)
                CourseArt.Clock -> clock(u)
                CourseArt.Notes -> notes(u)
                CourseArt.Folder -> folder(u)
                CourseArt.VideoCall -> videoCall(u)
                CourseArt.Translate -> translate(u)
                CourseArt.Contact -> contact(u)
                CourseArt.Phone -> phone(u)
                CourseArt.Music -> music(u)
                CourseArt.Lens -> lens(u)
                CourseArt.Cloud -> cloud(u)
                CourseArt.Store -> store(u)
                CourseArt.Bell -> bell(u)
                CourseArt.Wifi -> wifi(u)
                CourseArt.Reading -> reading(u)
                CourseArt.Draft -> draft(u)
                CourseArt.PictureQuestion -> pictureQuestion(u)
                CourseArt.Sources -> sources(u)
                CourseArt.PhotoEdit -> photoEdit(u)
                CourseArt.Invitation -> invitation(u)
                CourseArt.HomeScreen -> homeScreen(u)
                CourseArt.Photo, CourseArt.Post -> Unit
            }
            drawCompanionPebble(u)
        }
    }
}

/** The soft colours a course's card is drawn in. */
fun courseColours(art: CourseArt): Pair<Color, Color> = when (art) {
    CourseArt.Helper, CourseArt.Draft, CourseArt.PictureQuestion, CourseArt.Sources -> DotColors.TextSizeFill to DotColors.TextSizeBorder
    CourseArt.Chat, CourseArt.Map, CourseArt.Text -> DotColors.SocialFill to DotColors.SocialBorder
    CourseArt.Post, CourseArt.Photo, CourseArt.Web -> DotColors.PhotoFill to DotColors.PhotoBorder
    CourseArt.Mail, CourseArt.Calendar -> DotColors.SandFill to DotColors.SandBorder
    CourseArt.Video -> DotColors.RoseFill to DotColors.RoseBorder
    CourseArt.Camera, CourseArt.Folder, CourseArt.Cloud -> DotColors.PhotoFill to DotColors.PhotoBorder
    CourseArt.Clock, CourseArt.Notes, CourseArt.Store -> DotColors.SandFill to DotColors.SandBorder
    CourseArt.VideoCall, CourseArt.Contact, CourseArt.Phone -> DotColors.SocialFill to DotColors.SocialBorder
    CourseArt.Music, CourseArt.Bell -> DotColors.RoseFill to DotColors.RoseBorder
    CourseArt.Translate, CourseArt.Lens, CourseArt.Wifi, CourseArt.Reading -> DotColors.TextSizeFill to DotColors.TextSizeBorder
    CourseArt.PhotoEdit -> DotColors.PhotoFill to DotColors.PhotoBorder
    CourseArt.Invitation -> DotColors.RoseFill to DotColors.RoseBorder
    CourseArt.HomeScreen -> DotColors.TextSizeFill to DotColors.TextSizeBorder
}

private fun p(u: Float, x: Float, y: Float) = Offset(x * u, y * u)

private fun DrawScope.card(u: Float, x: Float, y: Float, w: Float, h: Float, r: Float) {
    drawRoundRect(DotColors.Surface, p(u, x, y), Size(w * u, h * u), CornerRadius(r * u))
    drawRoundRect(DotColors.PhotoFrameStroke, p(u, x, y), Size(w * u, h * u), CornerRadius(r * u), style = Stroke(1.4f * u))
}

private fun DrawScope.line(u: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Color = DotColors.BubbleLines, w: Float = 2.2f) =
    drawLine(color, p(u, x1, y1), p(u, x2, y2), strokeWidth = w * u, cap = StrokeCap.Round)

/** A speech bubble with a small four-point spark: a helper that answers. */
private fun DrawScope.helper(u: Float) {
    card(u, 24f, 8f, 42f, 30f, 10f)
    line(u, 32f, 19f, 52f, 19f)
    line(u, 32f, 26f, 45f, 26f)
    val spark = Path().apply {
        moveTo(58f * u, 34f * u)
        quadraticTo(59f * u, 40f * u, 65f * u, 41f * u)
        quadraticTo(59f * u, 42f * u, 58f * u, 48f * u)
        quadraticTo(57f * u, 42f * u, 51f * u, 41f * u)
        quadraticTo(57f * u, 40f * u, 58f * u, 34f * u)
        close()
    }
    drawPath(spark, DotColors.Rust)
}

/** A note being written: a card of lines, with a rust pencil at its corner. */
private fun DrawScope.draft(u: Float) {
    card(u, 20f, 10f, 36f, 40f, 8f)
    line(u, 27f, 21f, 49f, 21f)
    line(u, 27f, 29f, 47f, 29f)
    line(u, 27f, 37f, 41f, 37f)
    val pencil = Path().apply {
        moveTo(52f * u, 50f * u)
        lineTo(64f * u, 34f * u)
        lineTo(68f * u, 37f * u)
        lineTo(56f * u, 53f * u)
        close()
    }
    drawPath(pencil, DotColors.Rust)
}

/** A small picture, and a rust question mark beside it. */
private fun DrawScope.pictureQuestion(u: Float) {
    card(u, 18f, 12f, 34f, 28f, 7f)
    drawCircle(DotColors.PhotoSun, 3.4f * u, p(u, 44f, 20f))
    val hill = Path().apply {
        moveTo(21f * u, 37f * u)
        lineTo(31f * u, 26f * u)
        lineTo(40f * u, 37f * u)
        close()
    }
    drawPath(hill, DotColors.PhotoHills)
    val mark = Path().apply {
        moveTo(56f * u, 34f * u)
        cubicTo(56f * u, 26f * u, 68f * u, 26f * u, 68f * u, 33f * u)
        cubicTo(68f * u, 38f * u, 62f * u, 38f * u, 62f * u, 43f * u)
    }
    drawPath(mark, DotColors.Rust, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    drawCircle(DotColors.Rust, 1.9f * u, p(u, 62f, 49f))
}

/** A page with numbered lines, one number marked in rust. */
private fun DrawScope.sources(u: Float) {
    card(u, 22f, 8f, 38f, 42f, 8f)
    listOf(18f, 27f, 36f).forEachIndexed { i, y ->
        drawCircle(if (i == 1) DotColors.Rust else DotColors.BubbleLines, 2.4f * u, p(u, 30f, y))
        line(u, 36f, y, if (i == 2) 46f else 52f, y)
    }
}

/** Two rounded bubbles, one answering the other. */
private fun DrawScope.chat(u: Float) {
    card(u, 22f, 8f, 30f, 20f, 9f)
    card(u, 34f, 26f, 32f, 22f, 10f)
    listOf(42f, 50f, 58f).forEach { x -> drawCircle(DotColors.BubbleLines, 2f * u, p(u, x, 37f)) }
    drawCircle(DotColors.Rust, 2.4f * u, p(u, 30f, 18f))
}

/** A folded map with a rust pin. */
private fun DrawScope.map(u: Float) {
    val fold = Path().apply {
        moveTo(26f * u, 14f * u)
        lineTo(39f * u, 10f * u)
        lineTo(52f * u, 14f * u)
        lineTo(65f * u, 10f * u)
        lineTo(65f * u, 44f * u)
        lineTo(52f * u, 48f * u)
        lineTo(39f * u, 44f * u)
        lineTo(26f * u, 48f * u)
        close()
    }
    drawPath(fold, DotColors.Surface)
    drawPath(fold, DotColors.PhotoFrameStroke, style = Stroke(1.4f * u, join = StrokeJoin.Round))
    line(u, 39f, 10f, 39f, 44f, DotColors.PhotoFrameStroke, 1.2f)
    line(u, 52f, 14f, 52f, 48f, DotColors.PhotoFrameStroke, 1.2f)
    val pin = Path().apply {
        moveTo(46f * u, 36f * u)
        cubicTo(40f * u, 28f * u, 40f * u, 20f * u, 46f * u, 20f * u)
        cubicTo(52f * u, 20f * u, 52f * u, 28f * u, 46f * u, 36f * u)
        close()
    }
    drawPath(pin, DotColors.Rust)
    drawCircle(DotColors.Surface, 2.2f * u, p(u, 46f, 25.5f))
}

/** An envelope with a rust seal. */
private fun DrawScope.mail(u: Float) {
    card(u, 24f, 12f, 42f, 30f, 4f)
    val flap = Path().apply {
        moveTo(25f * u, 14f * u)
        lineTo(45f * u, 29f * u)
        lineTo(65f * u, 14f * u)
    }
    drawPath(flap, DotColors.PhotoFrameStroke, style = Stroke(1.4f * u, join = StrokeJoin.Round, cap = StrokeCap.Round))
    drawCircle(DotColors.Rust, 3.2f * u, p(u, 45f, 29f))
}

/** A rounded screen with a rust play mark. */
private fun DrawScope.video(u: Float) {
    card(u, 22f, 10f, 44f, 30f, 7f)
    val play = Path().apply {
        moveTo(40f * u, 18f * u)
        lineTo(52f * u, 25f * u)
        lineTo(40f * u, 32f * u)
        close()
    }
    drawPath(play, DotColors.Rust)
    line(u, 26f, 46f, 62f, 46f, DotColors.BubbleLines, 2f)
    drawCircle(DotColors.PhotoHills, 2.4f * u, p(u, 38f, 46f))
}

/** A calendar page with its rings and one day marked in rust. */
private fun DrawScope.calendar(u: Float) {
    card(u, 24f, 12f, 42f, 36f, 5f)
    drawRoundRect(DotColors.PhotoSky, p(u, 24.7f, 12.7f), Size(40.6f * u, 8f * u), CornerRadius(4f * u))
    line(u, 34f, 8f, 34f, 16f, DotColors.PhotoHills, 2.4f)
    line(u, 56f, 8f, 56f, 16f, DotColors.PhotoHills, 2.4f)
    for (row in 0..2) for (col in 0..3) {
        val c = p(u, 31f + col * 9f, 27f + row * 7f)
        if (row == 1 && col == 2) drawCircle(DotColors.Rust, 3f * u, c) else drawCircle(DotColors.BubbleLines, 1.5f * u, c)
    }
}

/** A browser window: an address bar and a rust magnifier. */
private fun DrawScope.web(u: Float) {
    card(u, 22f, 8f, 44f, 36f, 6f)
    drawRoundRect(DotColors.PhotoSky, p(u, 27f, 13f), Size(34f * u, 7f * u), CornerRadius(3.5f * u))
    line(u, 28f, 28f, 58f, 28f)
    line(u, 28f, 35f, 50f, 35f)
    drawCircle(DotColors.Rust, 5f * u, p(u, 58f, 44f), style = Stroke(2.2f * u))
    line(u, 61.5f, 47.5f, 66f, 52f, DotColors.Rust, 2.4f)
}

/** A text bubble and a small rust send arrow. */
private fun DrawScope.text(u: Float) {
    card(u, 24f, 10f, 40f, 24f, 11f)
    line(u, 32f, 19f, 54f, 19f)
    line(u, 32f, 25f, 46f, 25f)
    val arrow = Path().apply {
        moveTo(50f * u, 38f * u)
        lineTo(66f * u, 44f * u)
        lineTo(50f * u, 50f * u)
        lineTo(53f * u, 44f * u)
        close()
    }
    drawPath(arrow, DotColors.Rust)
}

/** A camera body with a rust shutter. */
private fun DrawScope.camera(u: Float) {
    card(u, 22f, 16f, 44f, 30f, 6f)
    drawRoundRect(DotColors.PhotoFrameStroke, p(u, 30f, 11f), Size(12f * u, 6f * u), CornerRadius(2f * u))
    drawCircle(DotColors.PhotoHills, 8f * u, p(u, 44f, 31f), style = Stroke(2.2f * u))
    drawCircle(DotColors.Rust, 3f * u, p(u, 58f, 22f))
}

/** An alarm clock with rust hands. */
private fun DrawScope.clock(u: Float) {
    drawCircle(DotColors.Surface, 17f * u, p(u, 45f, 29f))
    drawCircle(DotColors.PhotoFrameStroke, 17f * u, p(u, 45f, 29f), style = Stroke(1.6f * u))
    line(u, 33f, 12f, 38f, 16f, DotColors.PhotoHills, 3f)
    line(u, 57f, 12f, 52f, 16f, DotColors.PhotoHills, 3f)
    line(u, 45f, 29f, 45f, 19f, DotColors.Rust, 2.4f)
    line(u, 45f, 29f, 52f, 33f, DotColors.Rust, 2.4f)
}

/** A note with a checklist, one item ticked in rust. */
private fun DrawScope.notes(u: Float) {
    card(u, 26f, 8f, 38f, 40f, 5f)
    for (i in 0..2) {
        val y = 18f + i * 9f
        drawRoundRect(DotColors.PhotoFrameStroke, p(u, 31f, y - 3f), Size(6f * u, 6f * u), CornerRadius(1.5f * u), style = Stroke(1.2f * u))
        line(u, 41f, y, 57f - i * 4f, y)
    }
    line(u, 32f, 18f, 34f, 20f, DotColors.Rust, 1.8f)
    line(u, 34f, 20f, 37f, 15.5f, DotColors.Rust, 1.8f)
}

/** A folder with a rust tab. */
private fun DrawScope.folder(u: Float) {
    drawRoundRect(DotColors.Rust, p(u, 24f, 12f), Size(16f * u, 8f * u), CornerRadius(3f * u))
    card(u, 22f, 17f, 44f, 30f, 5f)
    line(u, 30f, 31f, 58f, 31f)
}

/** A video camera. */
private fun DrawScope.videoCall(u: Float) {
    card(u, 22f, 16f, 30f, 24f, 6f)
    val lens = Path().apply {
        moveTo(54f * u, 24f * u)
        lineTo(66f * u, 17f * u)
        lineTo(66f * u, 39f * u)
        lineTo(54f * u, 32f * u)
        close()
    }
    drawPath(lens, DotColors.Surface)
    drawPath(lens, DotColors.PhotoFrameStroke, style = Stroke(1.4f * u, join = StrokeJoin.Round))
    drawCircle(DotColors.Rust, 2.6f * u, p(u, 30f, 23f))
}

/** Two speech bubbles, one with a rust mark: saying it in another language. */
private fun DrawScope.translate(u: Float) {
    card(u, 22f, 8f, 26f, 20f, 7f)
    card(u, 40f, 24f, 26f, 20f, 7f)
    line(u, 29f, 18f, 41f, 18f)
    drawCircle(DotColors.Rust, 3f * u, p(u, 53f, 34f))
}

/** A contact card with a person. */
private fun DrawScope.contact(u: Float) {
    card(u, 22f, 10f, 44f, 34f, 5f)
    drawCircle(DotColors.PhotoHills, 5f * u, p(u, 34f, 22f))
    drawRoundRect(DotColors.PhotoHills, p(u, 27f, 29f), Size(14f * u, 7f * u), CornerRadius(3.5f * u))
    line(u, 46f, 21f, 60f, 21f)
    line(u, 46f, 28f, 56f, 28f, DotColors.Rust, 2.2f)
}

/** A handset with a rust sound mark. */
private fun DrawScope.phone(u: Float) {
    val handset = Path().apply {
        moveTo(30f * u, 12f * u)
        quadraticTo(26f * u, 30f * u, 44f * u, 44f * u)
        lineTo(49f * u, 39f * u)
        lineTo(43f * u, 33f * u)
        lineTo(39f * u, 36f * u)
        quadraticTo(33f * u, 30f * u, 35f * u, 22f * u)
        lineTo(39f * u, 20f * u)
        lineTo(35f * u, 12f * u)
        close()
    }
    drawPath(handset, DotColors.Surface)
    drawPath(handset, DotColors.PhotoFrameStroke, style = Stroke(1.4f * u, join = StrokeJoin.Round))
    drawArc(DotColors.Rust, -60f, 60f, false, p(u, 46f, 10f), Size(14f * u, 14f * u), style = Stroke(2f * u, cap = StrokeCap.Round))
}

/** A music note in rust. */
private fun DrawScope.music(u: Float) {
    card(u, 24f, 8f, 40f, 38f, 8f)
    line(u, 50f, 16f, 50f, 34f, DotColors.Rust, 2.4f)
    line(u, 50f, 16f, 58f, 19f, DotColors.Rust, 2.4f)
    drawCircle(DotColors.Rust, 4f * u, p(u, 46f, 34f))
}

/** A camera frame's corners around a rust dot. */
private fun DrawScope.lens(u: Float) {
    val c = DotColors.PhotoHills
    listOf(
        Triple(26f, 12f, 1), Triple(62f, 12f, -1), Triple(26f, 44f, 1), Triple(62f, 44f, -1),
    ).forEach { (x, y, dx) ->
        val dy = if (y < 30f) 1 else -1
        line(u, x, y, x + dx * 7f, y, c, 2.4f)
        line(u, x, y, x, y + dy * 7f, c, 2.4f)
    }
    drawCircle(DotColors.Rust, 6f * u, p(u, 44f, 28f), style = Stroke(2.4f * u))
}

/** A cloud with a rust arrow going up into it. */
private fun DrawScope.cloud(u: Float) {
    drawCircle(DotColors.Surface, 9f * u, p(u, 38f, 24f))
    drawCircle(DotColors.Surface, 11f * u, p(u, 50f, 22f))
    drawRoundRect(DotColors.Surface, p(u, 28f, 24f), Size(34f * u, 12f * u), CornerRadius(6f * u))
    drawCircle(DotColors.PhotoFrameStroke, 11f * u, p(u, 50f, 22f), style = Stroke(1.2f * u))
    line(u, 45f, 42f, 45f, 28f, DotColors.Rust, 2.4f)
    line(u, 45f, 28f, 40f, 33f, DotColors.Rust, 2.4f)
    line(u, 45f, 28f, 50f, 33f, DotColors.Rust, 2.4f)
}

/** A shopping bag with a rust play mark. */
private fun DrawScope.store(u: Float) {
    card(u, 26f, 16f, 36f, 30f, 5f)
    drawArc(DotColors.PhotoHills, 180f, 180f, false, p(u, 36f, 8f), Size(16f * u, 16f * u), style = Stroke(2f * u))
    val play = Path().apply {
        moveTo(41f * u, 25f * u)
        lineTo(51f * u, 31f * u)
        lineTo(41f * u, 37f * u)
        close()
    }
    drawPath(play, DotColors.Rust)
}

/** A bell with a rust clapper. */
private fun DrawScope.bell(u: Float) {
    val shape = Path().apply {
        moveTo(33f * u, 38f * u)
        quadraticTo(35f * u, 34f * u, 35f * u, 24f * u)
        quadraticTo(35f * u, 12f * u, 45f * u, 12f * u)
        quadraticTo(55f * u, 12f * u, 55f * u, 24f * u)
        quadraticTo(55f * u, 34f * u, 57f * u, 38f * u)
        close()
    }
    drawPath(shape, DotColors.Surface)
    drawPath(shape, DotColors.PhotoFrameStroke, style = Stroke(1.4f * u, join = StrokeJoin.Round))
    drawCircle(DotColors.Rust, 3f * u, p(u, 45f, 42f))
}

/** Signal arcs with a rust dot. */
private fun DrawScope.wifi(u: Float) {
    listOf(20f, 13f, 6f).forEachIndexed { i, r ->
        drawArc(
            if (i == 0) DotColors.PhotoFrameStroke else DotColors.PhotoHills,
            225f, 90f, false, p(u, 45f - r, 38f - r), Size(2 * r * u, 2 * r * u),
            style = Stroke(2.4f * u, cap = StrokeCap.Round),
        )
    }
    drawCircle(DotColors.Rust, 3f * u, p(u, 45f, 38f))
}

/** A small and a large letter with a rust sun: reading comfortably. */
private fun DrawScope.reading(u: Float) {
    card(u, 24f, 10f, 40f, 34f, 6f)
    line(u, 31f, 34f, 35f, 24f, DotColors.Ink, 2.2f)
    line(u, 35f, 24f, 39f, 34f, DotColors.Ink, 2.2f)
    line(u, 42f, 36f, 49f, 18f, DotColors.Ink, 3f)
    line(u, 49f, 18f, 56f, 36f, DotColors.Ink, 3f)
    drawCircle(DotColors.Rust, 3.4f * u, p(u, 58f, 15f))
}

/** A photo with crop corners and a brightness slider, its knob in rust. */
private fun DrawScope.photoEdit(u: Float) {
    card(u, 24f, 8f, 40f, 30f, 5f)
    val hills = Path().apply {
        moveTo(28f * u, 34f * u)
        lineTo(38f * u, 23f * u)
        lineTo(45f * u, 29f * u)
        lineTo(51f * u, 22f * u)
        lineTo(60f * u, 34f * u)
        close()
    }
    drawPath(hills, DotColors.PhotoSky)
    // crop corners
    line(u, 21f, 12f, 21f, 5f, DotColors.PhotoHills, 2.4f)
    line(u, 21f, 5f, 28f, 5f, DotColors.PhotoHills, 2.4f)
    line(u, 67f, 34f, 67f, 41f, DotColors.PhotoHills, 2.4f)
    line(u, 67f, 41f, 60f, 41f, DotColors.PhotoHills, 2.4f)
    // slider
    line(u, 30f, 48f, 62f, 48f, DotColors.PhotoFrameStroke, 2.2f)
    drawCircle(DotColors.Rust, 3.6f * u, p(u, 52f, 48f))
}

/** A folded invitation card with a rust star, two lines for the title and the date. */
private fun DrawScope.invitation(u: Float) {
    card(u, 26f, 8f, 36f, 40f, 4f)
    line(u, 44f, 8f, 44f, 48f, DotColors.PhotoFrameStroke, 1.2f)
    line(u, 49f, 30f, 58f, 30f)
    line(u, 49f, 37f, 55f, 37f)
    val star = Path().apply {
        val cx = 53.5f; val cy = 19f; val r1 = 5.5f; val r2 = 2.4f
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) r1 else r2
            val a = Math.toRadians((-90 + i * 36).toDouble())
            val x = (cx + r * Math.cos(a)).toFloat() * u
            val y = (cy + r * Math.sin(a)).toFloat() * u
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(star, DotColors.Rust)
}

/** A phone's home screen: a soft wallpaper, four app squares, one in rust. */
private fun DrawScope.homeScreen(u: Float) {
    drawRoundRect(DotColors.Surface, p(u, 32f, 4f), Size(26f * u, 46f * u), CornerRadius(5f * u))
    drawRoundRect(DotColors.PhotoSky, p(u, 34f, 6f), Size(22f * u, 18f * u), CornerRadius(3f * u))
    drawRoundRect(DotColors.PhotoFrameStroke, p(u, 32f, 4f), Size(26f * u, 46f * u), CornerRadius(5f * u), style = Stroke(1.4f * u))
    val squares = listOf(36f to 29f, 47f to 29f, 36f to 39f, 47f to 39f)
    squares.forEachIndexed { i, (x, y) ->
        drawRoundRect(if (i == 1) DotColors.Rust else DotColors.PhotoHills, p(u, x, y), Size(7f * u, 7f * u), CornerRadius(2f * u))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF4F1F7, widthDp = 360, heightDp = 80)
@Composable
private fun PreviewCourseArt() {
    androidx.compose.foundation.layout.Row {
        CourseArt.entries.forEach { CourseIllustration(it, size = 36.dp) }
    }
}
