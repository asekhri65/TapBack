package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.matching.SemanticRegionResolver
import com.aaravsekhri.dot.guide.matching.TargetMatcher
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.model.SemanticTargetRegion
import com.aaravsekhri.dot.guide.model.TargetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A setting is not one node. These check that Dot points at the whole of one
 * and no part of the next.
 */
class SemanticRegionResolverTest {

    private val fontSizeSpec = GuideCatalog.textSize.steps[2].target

    private fun resolveFontSize(screen: ScreenSnapshot, spec: TargetSpec = fontSizeSpec): SemanticTargetRegion? {
        val match = TargetMatcher.findBest(screen, spec) ?: return null
        val anchor = screen.nodeById(match.labelNodeId) ?: return null
        val highlight = screen.nodeById(match.highlightNodeId) ?: anchor
        return SemanticRegionResolver.resolve(
            screen = screen,
            anchor = anchor,
            highlightNode = highlight,
            spec = spec,
            identity = match.identity!!,
            label = match.matchedLabel,
        )
    }

    @Test
    fun `Font size resolves to the whole setting, not just the slider`() {
        val builder = ScreenBuilder().apply { fontSizeSection(fontTop = 1400, displaySizeTop = 2000) }
        val screen = builder.build()

        val region = resolveFontSize(screen)

        assertNotNull(region)
        val bounds = region!!.boundsInScreen

        // The title, the description and the slider are all inside it.
        assertTrue("title included", bounds.top <= 1400)
        assertTrue("slider included", bounds.bottom >= 1680)

        // And it is far taller than the 90px slider it used to be.
        val sliderHeight = 90
        assertTrue("region is more than the slider", bounds.height > sliderHeight * 2)
    }

    @Test
    fun `the region takes in the buttons either side of the slider`() {
        val screen = ScreenBuilder().apply { fontSizeSection() }.build()

        val bounds = resolveFontSize(screen)!!.boundsInScreen

        // Minus on the left at x=60, plus on the right out to x=1020.
        assertTrue("minus included", bounds.left <= 60)
        assertTrue("plus included", bounds.right >= 1020)
    }

    @Test
    fun `the region stops before Display size`() {
        val screen = ScreenBuilder().apply {
            fontSizeSection(fontTop = 1400, displaySizeTop = 2000)
        }.build()

        val bounds = resolveFontSize(screen)!!.boundsInScreen

        // Display size's title starts at 2000; nothing of it may be inside.
        assertTrue("stops above Display size", bounds.bottom < 2000)
    }

    @Test
    fun `the region does not reach up into the preview panel`() {
        val screen = ScreenBuilder().apply {
            fontSizeSection(fontTop = 1400, displaySizeTop = 2000)
        }.build()

        val bounds = resolveFontSize(screen)!!.boundsInScreen

        // Preview sits at 800..900 and is listed as a boundary.
        assertTrue("stops below the preview", bounds.top > 900)
    }

    @Test
    fun `every gathered node is recorded so Pebble can avoid them`() {
        val screen = ScreenBuilder().apply { fontSizeSection() }.build()

        val region = resolveFontSize(screen)!!

        // Title, description, minus, slider, plus.
        assertTrue("gathered several nodes", region.matchedNodes.size >= 4)
        assertTrue(
            "every part is inside the region",
            region.matchedNodes.all { node ->
                node.bounds.left >= region.boundsInScreen.left &&
                    node.bounds.right <= region.boundsInScreen.right
            },
        )
    }

    @Test
    fun `a plain row is left exactly as it is`() {
        val builder = ScreenBuilder()
        val row = builder.settingsRow("Display", top = 400)
        val screen = builder.build()
        val spec = GuideCatalog.textSize.steps[0].target

        val match = TargetMatcher.findBest(screen, spec)!!
        val anchor = screen.nodeById(match.labelNodeId)!!
        val region = SemanticRegionResolver.resolve(
            screen, anchor, screen.nodeById(match.highlightNodeId)!!, spec, match.identity!!, null,
        )!!

        // CLICKABLE_PARENT: the tappable row, and nothing more.
        assertEquals(screen.nodes[row].bounds, region.boundsInScreen)
    }

    @Test
    fun `visibility is measured against the viewport`() {
        val region = SemanticTargetRegion(
            identity = TargetMatcher.findBest(
                ScreenBuilder().apply { settingsRow("Display", top = 400) }.build(),
                GuideCatalog.textSize.steps[0].target,
            )!!.identity!!,
            boundsInScreen = Bounds(0, 2200, 1080, 2600),
            anchorBounds = Bounds(0, 2200, 1080, 2600),
            matchedNodes = emptyList(),
        )

        val viewport = Bounds(0, 100, 1080, 2400)

        // 200 of its 400 pixels are on screen.
        assertEquals(0.5f, region.visibleFractionWithin(viewport), 0.01f)
        assertFalse(region.visibleFractionWithin(viewport) >= 0.8f)
    }
}
