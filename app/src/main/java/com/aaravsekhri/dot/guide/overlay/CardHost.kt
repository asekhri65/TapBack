package com.aaravsekhri.dot.guide.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.util.LocalMotionOff

/**
 * The card, measured at every density and drawn at one.
 *
 * Where the card can go depends on how tall it is, and how tall it is depends
 * on how much of it is drawn. Measuring only the density on screen would make
 * the choice feed back into itself — too tall, so compact; compact fits, so
 * full; too tall again. Measuring both every time gives the placement fixed
 * inputs, so it settles once.
 *
 * Only the chosen density is placed. The other is never drawn, never reached
 * by touch or by accessibility, and never animates.
 */
@Composable
internal fun MeasuredCard(
    chosen: CardDensity,
    /** Which card this is: a new one reports its size even when it is the same as the last one's. */
    key: Any? = null,
    onMeasured: (Map<CardDensity, Int>) -> Unit,
    content: @Composable (CardDensity) -> Unit,
) {
    val last = remember { arrayOfNulls<Pair<Any?, Map<CardDensity, Int>>>(1) }
    SubcomposeLayout { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val measured = CardDensity.entries.associateWith { density ->
            subcompose(density) {
                // The same structure in both slots, whichever is showing, so
                // swapping densities does not rebuild the card and replay its
                // entrance.
                CompositionLocalProvider(LocalMotionOff provides (density != chosen)) {
                    content(density)
                }
            }.map { it.measure(loose) }
        }
        val heights = measured.mapValues { (_, placeables) -> placeables.maxOfOrNull { it.height } ?: 0 }
        if (last[0] != (key to heights)) {
            last[0] = key to heights
            onMeasured(heights)
        }
        val shown = measured.getValue(chosen)
        val width = constraints.maxWidth
        layout(width, heights.getValue(chosen)) {
            shown.forEach { it.place(0, 0) }
        }
    }
}

/**
 * The card's soft lift, drawn on the layer nothing can touch.
 *
 * Drawn around the card and never over it: the card's own shape is cut out,
 * so whichever window happens to be on top, the shadow only ever darkens the
 * app a little at the card's edge.
 */
@Composable
internal fun CardShadow(card: State<Rect?>) {
    Canvas(Modifier.fillMaxSize()) {
        val r = card.value ?: return@Canvas
        val radius = 24.dp.toPx()
        val shape = Path().apply { addRoundRect(RoundRect(r, CornerRadius(radius))) }
        clipPath(shape, ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                // Only a soft halo: a blurred shape in the shadow's colour,
                // with nothing solid in it, so no part of it can ever look
                // like a card.
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = DotColors.Ink.copy(alpha = 0.16f).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(12.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                val drop = 3.dp.toPx()
                canvas.nativeCanvas.drawRoundRect(r.left, r.top + drop, r.right, r.bottom + drop, radius, radius, paint)
            }
        }
    }
}
