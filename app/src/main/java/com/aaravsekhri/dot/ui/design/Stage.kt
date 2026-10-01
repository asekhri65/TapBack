package com.aaravsekhri.dot.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aaravsekhri.dot.ui.components.dotClickable
import com.aaravsekhri.dot.ui.pebble.StagePebble
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.SchibstedGrotesk
import com.aaravsekhri.dot.ui.util.rememberReducedMotion

/**
 * The approved onboarding and TapBack Plus boards, as tokens and pieces.
 *
 * Values are the handoff's, in dp and sp at the 390 × 844 reference; text is
 * in sp so it follows the system font size, and at 130 % nothing shrinks —
 * the screen scrolls.
 */
object Stage {
    val Page = Color(0xFFFBFBFC)
    val Surface = Color(0xFFFFFFFF)
    val Charcoal = Color(0xFF16181B)
    val Secondary = Color(0xFF6B7076)
    val Hairline = Color(0xFFE7E9EC)
    val Border = Color(0xFFD6DADF)
    val PaleFill = Color(0xFFF2F3F5)
    val RustText = Color(0xFFA9512C)
    val Halo = Color(0xFFB85B33)
    val RustTint = Color(0xFFFCF5F1)
    val SuccessFill = Color(0xFFEAF3EE)
    val SuccessText = Color(0xFF2F5E42)
    val Scrim = Color(0xFF16181B)

    private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    // Balanced wrap, as the handoff specifies for headlines.
    val Headline = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600, fontSize = 30.sp, lineHeight = 34.8.sp, letterSpacing = (-0.022).em, lineHeightStyle = tight, lineBreak = androidx.compose.ui.text.style.LineBreak.Heading)
    val Welcome = Headline.copy(fontSize = 32.sp, lineHeight = 37.1.sp)
    val PracticeHeadline = Headline.copy(fontSize = 28.sp, lineHeight = 32.5.sp)
    val SheetTitle = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600, fontSize = 21.sp, lineHeight = 26.sp)
    val Price = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em)
    val Body = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W400, fontSize = 18.sp, lineHeight = 26.1.sp)
    val BodyLarge = Body.copy(fontSize = 19.sp, lineHeight = 27.6.sp)
    val Supporting = Body.copy(fontSize = 17.sp, lineHeight = 24.6.sp)
    val Small = Body.copy(fontSize = 16.sp, lineHeight = 23.2.sp)
    val Note = Body.copy(fontSize = 15.5.sp, lineHeight = 22.5.sp)
    val Benefit = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600, fontSize = 19.sp, lineHeight = 24.7.sp)
    val CardTitle = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600, fontSize = 18.sp, lineHeight = 23.4.sp)
    val Primary = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600, fontSize = 18.sp, lineHeight = 23.sp)
    val SecondaryLabel = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W500, fontSize = 17.sp, lineHeight = 22.sp)
    val Link = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W500, fontSize = 17.sp, lineHeight = 22.sp)
    val Counter = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W500, fontSize = 15.sp, lineHeight = 20.sp)
    val Eyebrow = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600, fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 0.1.em)
    val Chip = TextStyle(fontFamily = SchibstedGrotesk, fontWeight = FontWeight.W600, fontSize = 15.5.sp, lineHeight = 20.sp)

    /** The approved pop-in easing, for anything that arrives. */
    val Arrive = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
}

/**
 * Whether the current [StageScaffold] is in flow mode (a short screen, or
 * large text): everything scrolls together, so there is no height for
 * weighted space to share — a screen that fills with weight uses a minimum
 * height instead.
 */
val LocalStageFlow = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The approved screen: 52 dp header (Back or Close on the left, a counter on
 * the right), a scrolling middle with 24 dp gutters, and a pinned footer.
 *
 * On a short screen or with large text — under 42 em tall, as the handoff
 * puts it — the footer joins the scroll so reading order holds, and only the
 * header stays pinned.
 */
@Composable
fun StageScaffold(
    header: @Composable RowScope.() -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Stage.Page)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val emPx = with(LocalDensity.current) { 16.sp.toPx() }
        val tallPx = with(LocalDensity.current) { maxHeight.toPx() }
        val flow = tallPx / emPx < 42f
        androidx.compose.runtime.CompositionLocalProvider(LocalStageFlow provides flow) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(start = 10.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                content = header,
            )
            if (flow) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp), content = content)
                    if (footer != null) {
                        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp), content = footer)
                    }
                }
            } else {
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val viewport = maxHeight
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        // At least the viewport tall, so a screen's weighted
                        // spacers centre its illustration; taller when the
                        // text is large, and then those spacers simply go.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = viewport)
                                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                            content = content,
                        )
                    }
                }
                if (footer != null) {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp), content = footer)
                }
            }
        }
        }
    }
}

/**
 * A screen that covers Ask takes the keyboard down with it: the field
 * underneath keeps its words, not the focus.
 */
@Composable
fun DismissKeyboardOnShow() {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
}

@Composable
fun StageCounter(step: Int, of: Int) {
    Text(
        "$step of $of",
        style = Stage.Counter,
        color = Stage.Secondary,
        modifier = Modifier.semantics { contentDescription = "Step $step of $of" },
    )
}

@Composable
fun StageBack(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .dotClickable(onClick = onClick, role = Role.Button, shape = RoundedCornerShape(12.dp))
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StageIcon(StageIcons.ChevronLeft, Stage.Charcoal, 24.dp, stroke = 2.1f)
        Text(label, style = Stage.Link, color = Stage.Charcoal)
    }
}

@Composable
fun StageClose(description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .dotClickable(onClick = onClick, role = Role.Button, shape = CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { StageIcon(StageIcons.Close, Stage.Charcoal, 22.dp, stroke = 2.1f) }
}

@Composable
fun StagePrimary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .dotClickable(onClick = onClick, role = Role.Button, shape = shape, enabled = enabled)
            .background(if (enabled) Stage.Charcoal else Stage.Charcoal.copy(alpha = 0.35f), shape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Stage.Primary, color = Color.White, textAlign = TextAlign.Center)
        trailing?.invoke()
    }
}

@Composable
fun StageSecondary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .dotClickable(onClick = onClick, role = Role.Button, shape = shape)
            .background(Stage.Surface, shape)
            .border(1.dp, Stage.Border, shape)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Stage.SecondaryLabel, color = Stage.Charcoal, textAlign = TextAlign.Center) }
}

@Composable
fun StageLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .dotClickable(onClick = onClick, role = Role.Button, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Stage.Link, color = Stage.Secondary, textDecoration = TextDecoration.Underline) }
}

@Composable
fun StageEyebrow(text: String, color: Color = Stage.Secondary, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Stage.Eyebrow, color = color, modifier = modifier)
}

@Composable
fun StageHeadline(text: String, style: TextStyle = Stage.Headline, align: TextAlign = TextAlign.Start, modifier: Modifier = Modifier) {
    Text(text, style = style, color = Stage.Charcoal, textAlign = align, modifier = modifier.semantics { heading() })
}

/**
 * The recommended action: 2 dp halo 8 dp outside the button, radius 22,
 * a 7 % rust wash, and Pebble 34 dp on its top edge. Fades in 170 ms after
 * Pebble lands; never takes a tap.
 */
@Composable
fun StagePreferred(
    modifier: Modifier = Modifier,
    pebbleOnStart: Boolean = false,
    pebbleSize: Dp = 34.dp,
    showPebble: Boolean = true,
    haloVisible: Boolean = true,
    content: @Composable () -> Unit,
) {
    val still = rememberReducedMotion()
    val halo = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(haloVisible, still) {
        if (still) halo.snapTo(if (haloVisible) 1f else 0f)
        else if (haloVisible) halo.animateTo(1f, tween(200, delayMillis = 170))
        else halo.animateTo(0f, tween(220))
    }
    Box(
        modifier
            .padding(top = 8.dp)
            .drawBehind {
                val inset = 8.dp.toPx()
                val a = halo.value
                if (a <= 0f) return@drawBehind
                val tl = Offset(-inset, -inset)
                val sz = Size(size.width + inset * 2, size.height + inset * 2)
                val r = CornerRadius(22.dp.toPx())
                drawRoundRect(DotColors.Rust.copy(alpha = 0.07f * a), tl, sz, r)
                drawRoundRect(Stage.Halo.copy(alpha = a), tl, sz, r, style = Stroke(2.dp.toPx()))
            },
    ) {
        content()
        if (showPebble) {
            StagePebble(
                size = pebbleSize,
                shadow = false,
                bob = false,
                blinkMs = 7_000,
                modifier = Modifier
                    .align(if (pebbleOnStart) Alignment.TopStart else Alignment.TopEnd)
                    .graphicsLayer {
                        translationY = -(pebbleSize.toPx() * 0.72f)
                        translationX = if (pebbleOnStart) -4.dp.toPx() else 4.dp.toPx()
                    },
            )
        }
    }
}

/** A confirmation chip: pale green, a tick, a few words. */
@Composable
fun StageSuccessChip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Stage.SuccessFill, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StageIcon(StageIcons.Check, Stage.SuccessText, 20.dp, stroke = 2.4f)
        Text(text, style = Stage.Chip, color = Stage.SuccessText)
    }
}

/** A small dashed tag — "Preview", "Test Store · no charge" — that labels, and is never a control. */
@Composable
fun StageTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Stage.Eyebrow.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
        color = Stage.Secondary,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .drawBehind {
                drawRoundRect(
                    Stage.Border,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(
                        1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A 44 dp tile holding an icon, as the benefit and explanation rows have. */
@Composable
fun StageTile(icon: String, fill: Color, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .background(fill, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { StageIcon(icon, tint, 24.dp, stroke = 1.9f) }
}

/** Text with **bold** runs, for the approved sentences that emphasise a word or two. */
@Composable
fun StageRich(text: String, style: TextStyle, color: Color, boldColor: Color = Stage.Charcoal, align: TextAlign = TextAlign.Start, modifier: Modifier = Modifier) {
    val annotated = remember(text) {
        buildAnnotatedString {
            text.split("**").forEachIndexed { i, part ->
                if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.W600, color = boldColor)) { append(part) } else append(part)
            }
        }
    }
    Text(annotated, style = style, color = color, textAlign = align, modifier = modifier)
}

/** The board's icons, as their own 24-unit SVG path data. */
object StageIcons {
    const val ChevronLeft = "M15 5l-7 7 7 7"
    const val Close = "M6.5 6.5l11 11M17.5 6.5l-11 11"
    const val Check = "M4.5 12.5l5 5 10-10.5"
    const val Hand = "M9 11.5V5a1.6 1.6 0 0 1 3.2 0v5.5 M12.2 10V8.6a1.6 1.6 0 0 1 3.2 0V11 M15.4 10.4a1.6 1.6 0 0 1 3.2 0v3.8a6.3 6.3 0 0 1-6.3 6.3h-.6a6 6 0 0 1-4.9-2.6l-2.6-3.8a1.6 1.6 0 0 1 2.5-2l1.3 1.4"
    const val Screen = "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-12a2.5 2.5 0 0 1-2.5-2.5v-7a2.5 2.5 0 0 1 2.5-2.5z M8.5 20h7M12 16.5V20 M8 10.5h8M8 8h5"
    const val Lock = "M7 11V8a5 5 0 0 1 10 0v3 M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18.5v-6A1.5 1.5 0 0 1 6.5 11z"
    const val External = "M14 4h6v6M20 4l-9 9 M18 14v4.5A1.5 1.5 0 0 1 16.5 20h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10"
    const val Calendar = "M6.1 5h11.8a2.6 2.6 0 0 1 2.6 2.6v9.8a2.6 2.6 0 0 1-2.6 2.6H6.1a2.6 2.6 0 0 1-2.6-2.6V7.6A2.6 2.6 0 0 1 6.1 5z M3.5 9.5h17M8 3v4M16 3v4 M8.6 14.6l2.2 2.2 4.6-4.6"
    const val Book = "M12 6.5C10 5 7 4.6 4 5v13c3-.4 6 0 8 1.5 2-1.5 5-1.9 8-1.5V5c-3-.4-6 0-8 1.5z M12 6.5v13"
    const val ChevronDown = "M6 9l6 6 6-6"
    const val Info = "M12 3.4a8.6 8.6 0 1 1 0 17.2a8.6 8.6 0 1 1 0-17.2z M12 11v5.2M12 7.6v.1"
    const val Search = "M10.5 4a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13z M15.5 15.5L20 20"
}

@Composable
fun StageIcon(path: String, tint: Color, size: Dp, stroke: Float, modifier: Modifier = Modifier, rotation: Float = 0f) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(size).graphicsLayer { rotationZ = rotation }.clearAndSetSemantics { }) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            drawPath(parsed, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A dot for a list item: 6 dp, secondary grey. */
@Composable
fun StageBullet(text: String, style: TextStyle = Stage.Supporting) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .padding(top = 10.dp)
                .size(6.dp)
                .background(Stage.Secondary, CircleShape),
        )
        Text(text, style = style, color = Stage.Charcoal)
    }
}

@Composable
fun StageGap(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun StageHGap(width: Dp) = Spacer(Modifier.width(width))
