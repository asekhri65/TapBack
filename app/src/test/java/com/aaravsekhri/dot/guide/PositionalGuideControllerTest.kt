package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.positional.OutlineOutput
import com.aaravsekhri.dot.guide.positional.PositionalGuideController
import com.aaravsekhri.dot.guide.positional.PositionalGuideState
import com.aaravsekhri.dot.guide.positional.ScanCandidate
import com.aaravsekhri.dot.guide.positional.ScreenContext
import com.aaravsekhri.dot.guide.positional.ScrollGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what Dot shows over another app.
 *
 * The product rule underneath all of them: an outline is only ever shown over a
 * page that is standing still, and while a page is moving the person gets words
 * chosen from where the target actually is — never from which way they swiped.
 */
class PositionalGuideControllerTest {

    private val ms = 1_000_000L
    private var now = 1_000L * ms

    private val displayAndTouch = TargetIdentity(
        windowId = 1,
        viewId = "android:id/title",
        normalizedText = "display and touch",
        normalizedDescription = "",
        className = "android.widget.LinearLayout",
    )

    private val accessibility = TargetIdentity(
        windowId = 1,
        viewId = "android:id/title",
        normalizedText = "accessibility",
        normalizedDescription = "",
        className = "android.widget.LinearLayout",
    )

    private val viewport = Bounds(0, 0, 1170, 2532)

    /** The list, inset from the display the way a settings list always is. */
    private val list = Bounds(0, 200, 1170, 2200)

    private val rowHeight = 317

    private fun context(occluders: List<Bounds> = emptyList()) = ScreenContext(
        viewport = viewport,
        occluders = occluders,
        pebbleSizePx = 102,
        pebbleStraddlePx = 51,
        contentBounds = list,
    )

    private fun candidate(
        identity: TargetIdentity = displayAndTouch,
        top: Int = 1000,
        verified: Boolean = true,
    ) = ScanCandidate(
        identity = identity,
        reportedBounds = Bounds(0, top, 1170, top + rowHeight),
        titleVerified = verified,
    )

    /**
     * What accessibility reports for a row whose true top is [top].
     *
     * Taken from readings observed on the device: a row above the list comes
     * back with the list's top edge and the row's own bottom, one below with
     * the row's own top and the list's bottom — edges crossed either way — and
     * one partly in comes back as the part that is in.
     */
    private fun reported(top: Int): Bounds {
        val bottom = top + rowHeight
        return when {
            bottom <= list.top -> Bounds(0, list.top, 1170, bottom)
            top >= list.bottom -> Bounds(0, top, 1170, list.bottom)
            else -> Bounds(0, maxOf(top, list.top), 1170, minOf(bottom, list.bottom))
        }
    }

    /** The true top at which the given share of the row is inside the list. */
    private fun topForVisibility(fraction: Float) =
        list.top - ((1f - fraction) * rowHeight).toInt()

    private fun PositionalGuideController.settle(
        candidate: ScanCandidate = candidate(),
        context: ScreenContext = context(),
    ) = onScanResult(candidate, context, generation, now)

    private fun PositionalGuideController.scroll(at: Long = now) = onScrollActivity(at)

    private fun PositionalGuideController.evidence(top: Int, at: Long = now) =
        onScrollEvidence(reported(top), at)

    private fun PositionalGuideController.message() = output().guidance?.message

    // ------------------------------------------------ shapes from old layouts

    /**
     * The mechanism behind an outline that ran past a search field towards
     * the keyboard: a size remembered from an earlier layout, reused for a
     * later reading that sat flush with its list's edge and so looked clipped.
     */
    private fun tallThenFlush(forget: Boolean): OutlineOutput? {
        val controller = PositionalGuideController()
        // Measured whole, before the layout changed: 300 tall.
        controller.settle(ScanCandidate(displayAndTouch, Bounds(0, 1000, 1170, 1300), titleVerified = true))
        assertEquals(300f, controller.output().outline!!.geometry.heightPx)
        // The same control after the layout changed: 200 tall, flush with
        // the bottom of its list.
        if (forget) controller.forgetShape()
        val later = ScanCandidate(displayAndTouch, Bounds(0, 2000, 1170, 2200), titleVerified = true)
        controller.settle(later)
        controller.settle(later)
        return controller.output().outline
    }

    @Test
    fun `without forgetting, the old height is drawn from the new top`() {
        // Documents the defect this guards against; not a behaviour to keep.
        val drawn = tallThenFlush(forget = false)!!
        assertEquals("the old, taller shape", 300f, drawn.geometry.heightPx)
        assertEquals("drawn from the new top", 2000f, drawn.pose.topPx)
    }

    @Test
    fun `a fresh measurement never borrows a shape from an earlier layout`() {
        val drawn = tallThenFlush(forget = true)
        assertTrue(
            "either drawn at its real size or not at all: ${drawn?.geometry}",
            drawn == null || drawn.geometry.heightPx == 200f,
        )
    }

    // ------------------------------------------- the outline and the scroll

    @Test
    fun `a genuine scroll removes the outline in exactly one transition`() {
        val controller = PositionalGuideController()
        controller.settle()
        assertNotNull("the outline is up over a still page", controller.output().outline)
        val before = controller.generation

        controller.scroll()

        assertNull("and gone the moment the page moves", controller.output().outline)
        assertEquals("in one transition, not several", before + 1, controller.generation)
    }

    @Test
    fun `no outline is ever shown while the page is moving`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()

        // Every kind of evidence, and every kind of reading, while moving.
        listOf(1000, topForVisibility(0.5f), -900, 2600).forEachIndexed { index, top ->
            controller.evidence(top, now + index * 120L * ms)
            assertNull(
                "nothing may be drawn over a moving page (top=$top)",
                controller.output().outline,
            )
        }
    }

    @Test
    fun `a target in view while moving says stop here, not a direction`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()

        controller.evidence(top = 1000)

        assertEquals(ScrollGuidance.StopHere, controller.message())
        assertTrue(controller.state is PositionalGuideState.UserScrollingStopHere)
    }

    @Test
    fun `a target below says keep scrolling down`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()

        controller.evidence(top = 2600)

        assertEquals(ScrollGuidance.KeepScrollingDown, controller.message())
    }

    @Test
    fun `a target above says scroll up`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()

        controller.evidence(top = -900)

        assertEquals(ScrollGuidance.ScrollUp, controller.message())
    }

    @Test
    fun `a target that cannot be placed says stop for a moment`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()

        controller.onScrollEvidence(null, now)

        assertEquals(ScrollGuidance.StopForAMoment, controller.message())
        assertNull("and offers no arrow", controller.output().outline)
    }

    @Test
    fun `where the target is beats which way the page moved`() {
        val controller = PositionalGuideController()
        controller.settle()

        // Somebody scrolling downward, hard, past the row they wanted. The
        // controller is told nothing about the direction of travel — it has no
        // input for it — so the only thing that can decide the message is the
        // reading, and the reading says the row is now above.
        controller.scroll()
        controller.evidence(top = 2600)
        assertEquals(ScrollGuidance.KeepScrollingDown, controller.message())

        controller.evidence(top = 1000, at = now + 120 * ms)
        assertEquals(ScrollGuidance.StopHere, controller.message())

        controller.evidence(top = -900, at = now + 240 * ms)
        assertEquals(
            "carried on past it: the arrow must turn round",
            ScrollGuidance.ScrollUp,
            controller.message(),
        )
    }

    // ------------------------------------------------------------- haptics

    @Test
    fun `arriving at the target buzzes once`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        val before = controller.output().hapticPulses

        controller.evidence(top = 2600)
        assertEquals("not for an arrow", before, controller.output().hapticPulses)

        controller.evidence(top = 1000, at = now + 120 * ms)
        assertEquals(before + 1, controller.output().hapticPulses)
    }

    @Test
    fun `staying at the target does not buzz again`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        controller.evidence(top = 2600)
        controller.evidence(top = 1000, at = now + 120 * ms)
        val afterArriving = controller.output().hapticPulses

        repeat(10) { controller.evidence(top = 1000 + it, at = now + (2 + it) * 120L * ms) }

        assertEquals(afterArriving, controller.output().hapticPulses)
    }

    @Test
    fun `leaving the target and coming back buzzes once more`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        controller.evidence(top = 1000)
        val afterFirst = controller.output().hapticPulses

        controller.evidence(top = -900, at = now + 120 * ms)
        controller.evidence(top = 1000, at = now + 240 * ms)

        assertEquals(afterFirst + 1, controller.output().hapticPulses)
    }

    @Test
    fun `holding the message through the checks does not buzz again`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        controller.evidence(top = 1000)
        val afterArriving = controller.output().hapticPulses

        // The page stops, the card keeps saying "stop here" while the target is
        // checked, and then the outline comes back.
        controller.onTick(now + 700 * ms)
        assertEquals(ScrollGuidance.StopHere, controller.message())
        assertEquals(afterArriving, controller.output().hapticPulses)
    }

    // ---------------------------------------------------------- hysteresis

    @Test
    fun `visibility wavering between the thresholds does not change the message`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        controller.evidence(top = 1000)
        assertEquals(ScrollGuidance.StopHere, controller.message())

        // Back and forth across the band between 45% and 60%, which is exactly
        // where a hand resting on a list sits.
        listOf(0.58f, 0.47f, 0.55f, 0.46f, 0.59f).forEachIndexed { index, fraction ->
            controller.evidence(topForVisibility(fraction), now + (index + 1) * 120L * ms)
            assertEquals(
                "must not flicker at $fraction visible",
                ScrollGuidance.StopHere,
                controller.message(),
            )
        }

        // Below the exit threshold it must change, and only then.
        controller.evidence(topForVisibility(0.30f), now + 900 * ms)
        assertEquals(ScrollGuidance.ScrollUp, controller.message())
    }

    @Test
    fun `arriving takes more than staying`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()

        // Half visible, reached from an arrow: not enough to claim arrival.
        controller.evidence(top = -900)
        assertEquals(ScrollGuidance.ScrollUp, controller.message())
        controller.evidence(topForVisibility(0.50f), now + 120 * ms)
        assertEquals(ScrollGuidance.ScrollUp, controller.message())

        // The same half, reached from having arrived: enough to stay.
        controller.evidence(top = 1000, at = now + 240 * ms)
        controller.evidence(topForVisibility(0.50f), now + 360 * ms)
        assertEquals(ScrollGuidance.StopHere, controller.message())
    }

    // ------------------------------------------------- stopping and settling

    @Test
    fun `the card holds while the target is checked, then the outline appears`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        controller.evidence(top = 1000)
        assertEquals(ScrollGuidance.StopHere, controller.message())

        controller.onTick(now + 700 * ms)
        assertTrue(controller.state is PositionalGuideState.WaitingForStability)
        assertEquals("the message is held, not blanked", ScrollGuidance.StopHere, controller.message())
        assertNull("and no outline yet", controller.output().outline)

        // One reading is not enough.
        controller.onScanResult(candidate(top = 900), context(), controller.generation, now)
        assertNull(controller.output().outline)
        assertEquals(ScrollGuidance.StopHere, controller.message())

        // Two agreeing readings are.
        controller.onScanResult(candidate(top = 903), context(), controller.generation, now)
        assertNotNull(controller.output().outline)
        assertNull("and the card goes in the same change", controller.output().guidance)
    }

    @Test
    fun `readings that disagree do not count as confirmation`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        controller.evidence(top = 1000)
        controller.onTick(now + 700 * ms)

        controller.onScanResult(candidate(top = 900), context(), controller.generation, now)
        controller.onScanResult(candidate(top = 1400), context(), controller.generation, now)

        assertTrue(controller.state is PositionalGuideState.WaitingForStability)
        assertNull(controller.output().outline)
    }

    @Test
    fun `scrolling again during the checks throws the pending reading away`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        controller.evidence(top = 1000)
        controller.onTick(now + 700 * ms)
        controller.onScanResult(candidate(top = 900), context(), controller.generation, now)
        val staleGeneration = controller.generation

        controller.scroll(at = now + 500 * ms)
        controller.onScanResult(candidate(top = 903), context(), staleGeneration, now + 500 * ms)

        assertTrue(
            "the reading from before the page moved may not draw anything",
            controller.output().outline == null,
        )
        assertTrue(controller.state is PositionalGuideState.UserScrollingStopHere)
    }

    @Test
    fun `a reading from an older generation cannot render`() {
        val controller = PositionalGuideController()
        controller.settle()
        val staleGeneration = controller.generation
        controller.onNavigation()

        controller.onScanResult(candidate(), context(), staleGeneration, now)

        assertTrue(controller.state is PositionalGuideState.Transitioning)
        assertNull(controller.output().outline)
    }

    // ------------------------------------------------------------ the rest

    @Test
    fun `navigation clears the target and shows nothing at all`() {
        val controller = PositionalGuideController()
        controller.settle()

        controller.onNavigation()

        assertNull(controller.output().outline)
        assertNull("no arrow on the way out of a page", controller.output().guidance)
    }

    @Test
    fun `a value change is not a scroll and does not replay the entrance`() {
        val controller = PositionalGuideController()
        controller.settle()
        val key = controller.output().appearanceKey

        controller.onSameScreenControlChange()
        assertTrue("still settled", controller.state is PositionalGuideState.TargetStable)
        assertNotNull(controller.output().outline)

        // The control grows, and the page around it is re-read.
        controller.settle(candidate(top = 990))
        assertEquals("the same control is not a new one", key, controller.output().appearanceKey)
        assertNull(controller.output().guidance)
    }

    @Test
    fun `a control that comes back after its window was rebuilt keeps its entrance`() {
        val controller = PositionalGuideController()
        controller.settle()
        val key = controller.output().appearanceKey

        // What a font-size change does: everything goes and comes back.
        controller.onScanResult(null, context(), controller.generation, now)
        controller.settle(candidate(top = 1000))

        assertEquals(key, controller.output().appearanceKey)
    }

    @Test
    fun `an unverified title is never drawn`() {
        val controller = PositionalGuideController()
        controller.settle(candidate(verified = false))

        assertNull(controller.output().outline)
    }

    @Test
    fun `a neighbouring row never receives the outline`() {
        val controller = PositionalGuideController()
        controller.settle()
        val drawn = controller.output().outline!!.pose.topPx

        // The row next to it, offered twice, exactly where the target was.
        controller.onScanResult(candidate(accessibility, 1000), context(), controller.generation, now)
        controller.onScanResult(candidate(accessibility, 1000), context(), controller.generation, now)

        assertEquals(
            "the outline stays on the control it was verified for",
            displayAndTouch,
            (controller.state as PositionalGuideState.TargetStable).identity,
        )
        assertEquals(drawn, controller.output().outline!!.pose.topPx, 0.01f)
    }

    @Test
    fun `a neighbouring row cannot end the checks after a scroll`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()
        controller.evidence(top = 1000)
        controller.onTick(now + 700 * ms)

        repeat(4) {
            controller.onScanResult(
                candidate(accessibility, 900),
                context(),
                controller.generation,
                now,
            )
        }

        assertTrue(controller.state is PositionalGuideState.WaitingForStability)
        assertNull(controller.output().outline)
    }

    // ------------------------------------------------------- card placement

    @Test
    fun `the card sits away from the target`() {
        val low = PositionalGuideController()
        low.settle(candidate(top = 1800))
        low.scroll()
        low.evidence(top = 1800)
        assertEquals(
            "target low, card high",
            false,
            low.output().guidance!!.atBottom,
        )

        val high = PositionalGuideController()
        high.settle(candidate(top = 300))
        high.scroll()
        high.evidence(top = 300)
        assertEquals("target high, card low", true, high.output().guidance!!.atBottom)
    }

    @Test
    fun `an arrow puts the card at the end it is pointing away from`() {
        val controller = PositionalGuideController()
        controller.settle()
        controller.scroll()

        controller.evidence(top = 2600)
        assertEquals("it is below, so the card is above", false, controller.output().guidance!!.atBottom)

        controller.evidence(top = -900, at = now + 120 * ms)
        assertEquals("it is above, so the card is below", true, controller.output().guidance!!.atBottom)
    }

    @Test
    fun `a target behind the card is not reported as reachable`() {
        val card = Bounds(0, 900, 1170, 1500)
        val controller = PositionalGuideController()
        controller.settle(candidate(top = 1900))
        controller.onOccludersChanged(listOf(card))
        controller.scroll()

        // Fully inside the list, and completely behind Dot's own card.
        controller.evidence(top = 1000)

        assertEquals(
            "covered is not the same as reachable",
            ScrollGuidance.StopForAMoment,
            controller.message(),
        )
    }

    // ------------------------------------------------- before a first sighting

    @Test
    fun `scrolling towards a target never seen keeps the step's own direction`() {
        val controller = PositionalGuideController()
        controller.onScanResult(null, context(), controller.generation, now)
        controller.onSearching(ScrollDirection.Down)
        assertEquals(ScrollGuidance.KeepScrollingDown, controller.message())

        controller.scroll()

        assertEquals(
            "the guide's own hint, not the swipe",
            ScrollGuidance.KeepScrollingDown,
            controller.message(),
        )
    }

    @Test
    fun `a target never seen is still recognised on the way past it`() {
        val controller = PositionalGuideController()
        controller.onScanResult(null, context(), controller.generation, now)
        controller.onSearching(ScrollDirection.Down)
        controller.scroll()

        controller.evidence(top = 1000)

        assertEquals(ScrollGuidance.StopHere, controller.message())
    }

    // --------------------------------------------- a general session's target

    /**
     * The contract a general, AI-planned session depends on.
     *
     * There is no tutorial engine behind those sessions announcing each step,
     * so the session announces its own. This is the thing it has to get right:
     * a reading offered while the controller is parked after a navigation is
     * ignored by design, and saying "I am looking for something now" is what
     * makes it listen. Getting this wrong showed the instruction card with no
     * outline under it, which is the one combination the guide must never be in.
     */
    @Test
    fun `a target offered after navigation is ignored until the guide says it is searching`() {
        val controller = PositionalGuideController()
        controller.onNavigation()

        controller.onScanResult(candidate(), context(), controller.generation, now)
        assertNull(
            "parked after navigation, a reading is not something to draw from",
            controller.output().outline,
        )

        controller.onSearching()
        controller.onScanResult(candidate(), context(), controller.generation, now)

        assertTrue(controller.state is PositionalGuideState.TargetStable)
        assertNotNull("once it is looking, the same reading settles", controller.output().outline)
    }

    /**
     * An outline is never drawn through Dot's own card.
     *
     * The caller's job is to move the card; this is the half that makes it
     * necessary, and it is what caught the general session pointing at a row
     * that its own instruction card was sitting on top of.
     */
    @Test
    fun `a target the instruction card is covering is not outlined`() {
        val controller = PositionalGuideController()
        controller.onSearching()

        val row = Bounds(0, 1800, 1170, 1800 + rowHeight)
        val card = Bounds(0, 1700, 1170, 2532)
        controller.onScanResult(
            candidate = ScanCandidate(displayAndTouch, row, titleVerified = true),
            context = context(occluders = listOf(card)),
            scanGeneration = controller.generation,
            nowNanos = now,
        )

        assertNull(
            "a row under Dot's own card is not something to point at",
            controller.output().outline,
        )

        // The card moves to the other edge, and the same row is offered again.
        controller.onOccludersChanged(listOf(Bounds(0, 0, 1170, 532)))
        controller.onScanResult(
            candidate = ScanCandidate(displayAndTouch, row, titleVerified = true),
            context = context(occluders = listOf(Bounds(0, 0, 1170, 532))),
            scanGeneration = controller.generation,
            nowNanos = now,
        )

        assertNotNull(
            "with the card out of the way it is drawn",
            controller.output().outline,
        )
    }

    /**
     * Found on the device: a launcher's last row reaches under the gesture
     * bar. Measured against the viewport it looked clipped, its real size was
     * never known, and the outline was refused for good. A caller that can
     * show the bounds are whole may say so; one that cannot still gets the
     * refusal, which is right for a row half off a list.
     */
    @Test
    fun `a target reaching under the gesture bar is drawn when known to be whole`() {
        val underBar = Bounds(21, 2134, 339, 2519)
        val tight = ScreenContext(
            viewport = Bounds(0, 139, 1170, 2460),
            occluders = emptyList(),
            pebbleSizePx = 102,
            pebbleStraddlePx = 51,
            contentBounds = null,
        )

        val refused = PositionalGuideController().apply { onSearching() }
        refused.onScanResult(ScanCandidate(displayAndTouch, underBar, titleVerified = true), tight, refused.generation, now)
        assertNull("unproven: refused, as before", refused.output().outline)

        val drawn = PositionalGuideController().apply { onSearching() }
        drawn.onScanResult(
            ScanCandidate(displayAndTouch, underBar, titleVerified = true, knownWhole = true),
            tight,
            drawn.generation,
            now,
        )
        assertNotNull("proven whole: drawn, clipped by the viewport rather than squashed", drawn.output().outline)
    }

    /**
     * Found on the device, and not only in the general guide: a full-width
     * settings row put Pebble three-quarters off the right edge. The anchor
     * resolver had pulled it inside the screen, but only the corner it chose
     * was kept, and Pebble was drawn at the raw corner.
     */
    @Test
    fun `Pebble on a full-width row is drawn inside the screen`() {
        val controller = PositionalGuideController()
        controller.onSearching()
        val fullWidth = Bounds(-24, 1400, 1194, 1700)

        controller.onScanResult(
            ScanCandidate(displayAndTouch, fullWidth, titleVerified = true, knownWhole = true),
            context(),
            controller.generation,
            now,
        )

        val pebble = requireNotNull(controller.output().outline).pebble
        assertTrue("Pebble's right edge $pebble is on screen", pebble.right <= viewport.right)
        assertTrue("and its left edge", pebble.left >= viewport.left)
    }
}
