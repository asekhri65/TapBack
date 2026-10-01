package com.aaravsekhri.dot.ui.pebble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.util.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pebble as the approved onboarding and TapBack Plus boards draw it.
 *
 * Arrives with a pop (scale .85 → 1 and a fade, 360 ms; 210 ms when small),
 * then, per [bob] and [blinkMs], bobs 3 dp over 3.6 s and blinks now and
 * then. With [celebrate] it does one 840 ms squash-and-hop with happy eyes
 * and four small marks, and keeps a smile after; it never loops. [key]
 * names the moment, so the same celebration is not played twice.
 *
 * [curious] is the board's "not on yet" pose: leaning 11°, looking up and
 * aside, with a small round mouth.
 *
 * Reduced motion: no animation at all. Pebble holds its pose — the happy
 * one, marks and smile, for a celebration.
 */
@Composable
fun StagePebble(
    size: Dp,
    modifier: Modifier = Modifier,
    bob: Boolean = true,
    blinkMs: Int? = 5_200,
    celebrate: Boolean = false,
    curious: Boolean = false,
    shadow: Boolean = true,
    key: Any = Unit,
    contentDescription: String? = null,
) {
    val still = rememberReducedMotion()
    val small = size < 60.dp
    val pop = remember(key) { Animatable(if (still) 1f else 0f) }
    val hop = remember(key, celebrate) { Animatable(if (still || !celebrate) 1f else 0f) }
    LaunchedEffect(key, celebrate, still) {
        if (still) return@LaunchedEffect
        launch { pop.animateTo(1f, tween(if (small) 210 else 360, easing = EaseOutCubic)) }
        if (celebrate) {
            delay(if (small) 0 else 200)
            hop.animateTo(1f, tween(840, easing = LinearEasing))
        }
    }
    val transition = rememberInfiniteTransition(label = "stage-pebble")
    val bobY by transition.animateFloat(
        0f, 0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 3_600
                0f at 0 using EaseInOut
                1f at 1_800 using EaseInOut
                0f at 3_600
            },
            initialStartOffset = androidx.compose.animation.core.StartOffset(600),
        ),
        label = "bob",
    )
    val blink by transition.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            keyframes {
                val d = blinkMs ?: 5_200
                durationMillis = d
                1f at 0
                1f at (d * 0.92f).toInt()
                0.12f at (d * 0.95f).toInt() using EaseInOut
                1f at d
            },
            initialStartOffset = androidx.compose.animation.core.StartOffset(if ((blinkMs ?: 0) > 6_000) 2_400 else 1_400),
        ),
        label = "blink",
    )
    val semantics = Modifier.clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription }
    Column(modifier.then(semantics), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .graphicsLayer {
                    val p = pop.value
                    alpha = p
                    scaleX = 0.85f + 0.15f * p
                    scaleY = 0.85f + 0.15f * p
                },
        ) {
            val t = hop.value
            val lift = if (still || !bob) 0f else bobY * 3.dp.toPx()
            val lidAlpha = when {
                !celebrate -> 0f
                still -> 1f
                else -> phase(t, 0f, 0.10f, 0.62f, 0.80f)
            }
            val marks = when {
                !celebrate -> 0f
                still -> 1f
                else -> phase(t, 0f, 0.18f, 0.60f, 0.82f)
            }
            val marksScale = if (still) 1f else if (t < 0.18f) 0.7f + 0.3f * (t / 0.18f) else if (t > 0.60f) 1f + 0.06f * ((t - 0.60f) / 0.22f).coerceAtMost(1f) else 1f
            val smile = celebrate && (still || t >= 0.14f)
            val (sx, sy, ty) = hopTransform(if (still || !celebrate) 1f else t)
            translate(top = -lift + ty * this.size.height / 184f * 6f) {
                scale(sx, sy, pivot = Offset(this.size.width / 2f, this.size.height * 0.9f)) {
                    drawStagePebble(
                        eyeOpen = if (blinkMs == null || still) 1f else blink,
                        lidAlpha = lidAlpha,
                        smile = smile,
                        marks = marks,
                        marksScale = marksScale,
                        curious = curious && !celebrate,
                    )
                }
            }
        }
        if (shadow) {
            Canvas(
                Modifier
                    .offset(y = (-6).dp)
                    .size(width = size * (126f / 184f), height = 12.dp),
            ) {
                drawOval(DotColors.Ink.copy(alpha = 0.06f), topLeft = Offset.Zero, size = Size(this.size.width, this.size.height))
            }
        }
    }
}

/** 0 → 1 between [a] and [b], held until [c], back to 0 by [d]. */
private fun phase(t: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
    t <= a -> 0f
    t < b -> (t - a) / (b - a)
    t <= c -> 1f
    t < d -> 1f - (t - c) / (d - c)
    else -> 0f
}

/** The approved squash-and-hop: scaleX, scaleY, and a lift in "6 px at 184" units (negative is up). */
private fun hopTransform(t: Float): Triple<Float, Float, Float> {
    fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    val e = HopEasing.transform(t.coerceIn(0f, 1f))
    return when {
        e < 0.08f -> { val f = e / 0.08f; Triple(lerp(1f, 1.06f, f), lerp(1f, 0.94f, f), 0f) }
        e < 0.26f -> { val f = (e - 0.08f) / 0.18f; Triple(lerp(1.06f, 0.96f, f), lerp(0.94f, 1.07f, f), lerp(0f, -1f, f)) }
        e < 0.44f -> { val f = (e - 0.26f) / 0.18f; Triple(lerp(0.96f, 1.02f, f), lerp(1.07f, 0.99f, f), lerp(-1f, 0f, f)) }
        e < 0.60f -> { val f = (e - 0.44f) / 0.16f; Triple(lerp(1.02f, 1f, f), lerp(0.99f, 1f, f), 0f) }
        else -> Triple(1f, 1f, 0f)
    }
}

/**
 * Pebble in the 100-unit board space: the approved body and gaze, happy
 * closed eyes fading in over the open ones, four marks and a smile.
 */
internal fun DrawScope.drawStagePebble(
    eyeOpen: Float = 1f,
    lidAlpha: Float = 0f,
    smile: Boolean = false,
    marks: Float = 0f,
    marksScale: Float = 1f,
    curious: Boolean = false,
) {
    val u = size.minDimension / 100f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    if (marks > 0f) {
        val c = Offset(50f * u, 50f * u)
        scale(marksScale, marksScale, pivot = c) {
            listOf(
                floatArrayOf(-4f, 30f, -24f, 20f), floatArrayOf(12f, 4f, 2f, -14f),
                floatArrayOf(104f, 30f, 124f, 20f), floatArrayOf(88f, 4f, 98f, -14f),
            ).forEach { (x1, y1, x2, y2) ->
                drawLine(DotColors.Rust.copy(alpha = marks), p(x1, y1), p(x2, y2), strokeWidth = 8f * u, cap = StrokeCap.Round)
            }
        }
    }
    if (curious) {
        drawPebbleMark(
            topLeft = Offset.Zero, diameter = size.minDimension, pupilRadius = 4.6f, catchlightRadius = 1.8f,
            leanDegrees = 11f, gazeX = -4f, gazeY = -5.5f, eyeOpen = eyeOpen,
        )
        rotate(11f, pivot = p(50f, 92f)) {
            drawOval(DotColors.EyeWhite, topLeft = p(56f - 3.8f, 69f - 3.4f), size = Size(7.6f * u, 6.8f * u))
        }
        return
    }
    drawPebbleMark(topLeft = Offset.Zero, diameter = size.minDimension, pupilRadius = 4.6f, catchlightRadius = 1.8f, eyeOpen = if (lidAlpha >= 0.99f) 0.001f else eyeOpen)
    if (lidAlpha > 0f) {
        listOf(37f, 63f).forEach { cx ->
            drawCircle(DotColors.EyeWhite.copy(alpha = lidAlpha), 9.5f * u, p(cx, 47f))
            val arc = Path().apply {
                moveTo((cx - 8f) * u, 49f * u)
                quadraticTo(cx * u, 42f * u, (cx + 8f) * u, 49f * u)
            }
            drawPath(arc, DotColors.Ink.copy(alpha = lidAlpha), style = Stroke(3.6f * u, cap = StrokeCap.Round))
        }
    }
    if (smile) {
        val s = Path().apply {
            moveTo(43f * u, 70f * u)
            quadraticTo(50f * u, 75f * u, 57f * u, 70f * u)
        }
        drawPath(s, DotColors.EyeWhite, style = Stroke(3.6f * u, cap = StrokeCap.Round))
    }
}

/**
 * Waiting on a store: Pebble crosses its lane in 2.4 s with four 600 ms hops
 * of 20 dp, leaves by the right and comes round again unseen. Stops the
 * moment it leaves the screen; reduced motion: centred and still.
 */
@Composable
fun LanePebble(modifier: Modifier = Modifier, height: Dp = 88.dp, size: Dp = 40.dp) {
    val still = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "lane")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Box(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val d = size.toPx()
            val base = this.size.height - 10.dp.toPx()
            val x = if (still) (this.size.width - d) / 2f else -52.dp.toPx() + t * (this.size.width + 52.dp.toPx())
            val h = if (still) 0f else {
                val local = (t * 4f) % 1f
                val up = if (local < 0.5f) HopUp.transform(local / 0.5f) else 1f - HopDown.transform((local - 0.5f) / 0.5f)
                up * 20.dp.toPx()
            }
            drawLine(Color(0xFFE7E9EC), Offset(0f, this.size.height - 0.5f), Offset(this.size.width, this.size.height - 0.5f), strokeWidth = 1.dp.toPx())
            translate(left = x, top = base - d - h) {
                drawIntoSquare(d) { drawStagePebble() }
            }
        }
    }
}

private inline fun DrawScope.drawIntoSquare(side: Float, crossinline block: DrawScope.() -> Unit) {
    val scaleBy = side / size.minDimension
    scale(scaleBy, scaleBy, pivot = Offset.Zero) { block() }
}

private val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val HopEasing = CubicBezierEasing(0.34f, 0.92f, 0.28f, 1f)
private val HopUp = CubicBezierEasing(0.25f, 0.6f, 0.45f, 1f)
private val HopDown = CubicBezierEasing(0.55f, 0f, 0.75f, 0.4f)
