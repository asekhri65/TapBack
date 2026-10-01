package com.aaravsekhri.dot.guide

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the scrolling design that cannot be reached by calling a method.
 *
 * Each of these is a property of the code rather than of a value it produces:
 * that the state machine has no way to learn which way a finger went, that the
 * card cannot take a touch, that motion is not forced on somebody who has asked
 * for less of it. Left to review, all three are the kind of thing that quietly
 * comes back.
 */
class IntentionalScrollingContractTest {

    private val source = "src/main/java/com/aaravsekhri/dot"

    private fun read(path: String) = File(source, path).readText()

    private fun withoutComments(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the state machine has no way to know which way the page moved`() {
        val controller = withoutComments(read("guide/positional/PositionalGuideController.kt"))
        val locator = withoutComments(read("guide/positional/TargetLocation.kt"))

        // The strongest guarantee available: the direction of travel is not an
        // input. Nothing here can show an arrow because somebody swiped, and no
        // future change can introduce it without this failing.
        listOf("scrollDelta", "deltaY", "deltaX", "scrollY", "ScrollDirection.Down =")
            .forEach { token ->
                assertFalse(
                    "the direction of travel must not reach the decision: $token",
                    controller.contains(token) || locator.contains(token),
                )
            }
    }

    @Test
    fun `the guidance card cannot take a touch`() {
        val overlay = withoutComments(read("guide/overlay/GuideOverlayController.kt"))

        // The card lives in the positional layer, which is created non-touchable
        // and is the only window Dot has that covers the screen. A card that
        // could take a touch would swallow the very scroll it is asking for.
        assertTrue(
            "the positional layer must be built non-touchable",
            overlay.contains("positionalWindow = OverlayWindow(context, touchable = false)"),
        )
        assertTrue(
            "and the guidance must be drawn in it",
            overlay.contains("GuidanceSlot("),
        )
    }

    @Test
    fun `the card respects a request for less motion`() {
        val card = read("guide/overlay/GuidanceCard.kt")
        val slot = read("guide/overlay/GuidanceSlot.kt")

        assertTrue("the card asks", card.contains("rememberReducedMotion()"))
        assertTrue("and so does the move between edges", slot.contains("rememberReducedMotion()"))
        val gesture = withoutComments(read("guide/overlay/GestureIllustration.kt"))
        assertTrue(
            "the demonstration is switched off rather than merely slowed",
            gesture.contains("val still = rememberReducedMotion()") && gesture.contains("if (still) {"),
        )
    }

    @Test
    fun `nothing animates over a page that is standing still`() {
        val controller = withoutComments(read("guide/positional/PositionalGuideController.kt"))
        val card = withoutComments(read("guide/overlay/GuidanceCard.kt"))

        // The card's arrow moves only while the page does. Left running over a
        // still screen it redrew a full-screen layer sixty times a second for
        // as long as the card was up, which measured at two thirds of a
        // processor core with nobody touching the device.
        assertTrue(
            "the state machine decides, from whether a scroll is in progress",
            controller.contains("animate = isScrolling(state)"),
        )
        assertTrue("and the card passes it on", card.contains("animate = animate"))
        val gesture = withoutComments(read("guide/overlay/GestureIllustration.kt"))
        assertTrue(
            "to the one thing on it that moves, which holds still without it",
            gesture.contains("rememberReducedMotion() || !animate"),
        )
    }

    @Test
    fun `a relayout is not a scroll`() {
        val service = withoutComments(read("guide/service/DotAccessibilityService.kt"))

        // Three filters stand between an accessibility scroll event and the
        // outline coming down, and all three exist because of something that
        // went wrong: a font-size notch that replayed Pebble's entrance, a
        // re-measuring list that looked like a gesture, and an event whose
        // container had not moved at all.
        assertTrue(
            "a value change opens a settling window",
            service.contains("settlingAfterValueChangeUntil"),
        )
        assertTrue(
            "and a container that has not moved is not a scroll",
            service.contains("position == lastScrollPosition"),
        )
        assertTrue(
            "and the outline comes down before anything is asked",
            service.indexOf("positional.onScrollActivity") < service.indexOf("requestEvidence()"),
        )
    }

    @Test
    fun `Dot still never touches the screen itself`() {
        File(source).walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val code = withoutComments(file.readText())
            listOf("dispatchGesture", "ACTION_CLICK", "performAction(").forEach { token ->
                assertFalse("${file.name} must never act for the person: $token",
                    code.contains(token))
            }
        }
        // The comment in the service config lists what is deliberately absent,
        // so it names the very flag being checked for. Declarations only.
        val config = File("src/main/res/xml/accessibility_service_config.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        assertFalse(
            "the capability to tap must not be declared",
            config.contains("canPerformGestures"),
        )
    }
}
