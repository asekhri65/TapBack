package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.continuity.ScreenChangeKind
import com.aaravsekhri.dot.guide.continuity.ScreenContinuityClassifier
import com.aaravsekhri.dot.guide.continuity.ScreenFingerprint
import com.aaravsekhri.dot.guide.matching.TargetIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No single accessibility event says whether the person went anywhere.
 *
 * Changing the font size by one notch fires the same
 * `TYPE_WINDOW_STATE_CHANGED` as opening a new page, replaces every node, and
 * hands back a different window id — while the person sits looking at the
 * screen they were already on. These cover telling those apart.
 */
class ScreenContinuityTest {

    private val settings = "com.android.settings"

    /** The Font size screen, as it reads before anything changes. */
    private fun fontSizeScreen(
        stepIndex: Int = 2,
        landmarks: Set<String> = setOf(
            "font size",
            "make text bigger or smaller",
            "display size",
            "make everything bigger or smaller",
            "bold text",
            "preview",
        ),
        targetLabel: String = "font size",
        packageName: String = settings,
    ) = ScreenFingerprint(
        packageName = packageName,
        stepIndex = stepIndex,
        targetLabel = targetLabel,
        targetRole = "android.widget.SeekBar",
        landmarks = landmarks,
    )

    private fun classify(
        previous: ScreenFingerprint?,
        current: ScreenFingerprint,
        stepAdvanced: Boolean = false,
        scrollObserved: Boolean = false,
        targetStillPresent: Boolean = true,
    ) = ScreenContinuityClassifier.classify(
        previous, current, stepAdvanced, scrollObserved, targetStillPresent,
    )

    // ------------------------------------------------------- same screen

    @Test
    fun `changing the Font size value is a control mutation, not navigation`() {
        val before = fontSizeScreen()
        // A notch later: same page, same wording, rebuilt underneath.
        val after = fontSizeScreen()

        assertEquals(
            ScreenChangeKind.SAME_SCREEN_CONTROL_MUTATION,
            classify(before, after),
        )
    }

    @Test
    fun `a rebuilt hierarchy with the same wording is a layout rebuild`() {
        val before = fontSizeScreen()
        // Mid-rebuild: the page still reads the same, the control has not come
        // back yet.
        val after = fontSizeScreen()

        assertEquals(
            ScreenChangeKind.SAME_SCREEN_LAYOUT_REBUILD,
            classify(before, after, targetStillPresent = false),
        )
    }

    @Test
    fun `a new window id on the same Font size screen is not navigation`() {
        // The fingerprint carries no window id at all, by design: a font-size
        // change replaces the window without the person moving.
        val before = fontSizeScreen()
        val after = fontSizeScreen()

        assertNotEquals(ScreenChangeKind.TRUE_NAVIGATION, classify(before, after))
    }

    @Test
    fun `scrolling the same page is recognised as scrolling`() {
        val before = fontSizeScreen()
        val after = fontSizeScreen()

        assertEquals(
            ScreenChangeKind.SAME_SCREEN_SCROLL,
            classify(before, after, scrollObserved = true),
        )
    }

    @Test
    fun `most of the page changing wording is still the same page`() {
        val before = fontSizeScreen()
        // A rebuild that drops a couple of rows and adds one.
        val after = fontSizeScreen(
            landmarks = setOf(
                "font size",
                "make text bigger or smaller",
                "display size",
                "bold text",
                "high contrast text",
            ),
        )

        assertTrue(classify(before, after) != ScreenChangeKind.TRUE_NAVIGATION)
    }

    // ---------------------------------------------------- true navigation

    @Test
    fun `the guide advancing a step is navigation by definition`() {
        val before = fontSizeScreen()

        assertEquals(
            ScreenChangeKind.TRUE_NAVIGATION,
            classify(before, fontSizeScreen(), stepAdvanced = true),
        )
    }

    @Test
    fun `a different app is navigation`() {
        val before = fontSizeScreen()
        val after = fontSizeScreen(packageName = "com.example.other")

        assertEquals(ScreenChangeKind.TRUE_NAVIGATION, classify(before, after))
    }

    @Test
    fun `a different step is navigation`() {
        val before = fontSizeScreen(stepIndex = 1)
        val after = fontSizeScreen(stepIndex = 2)

        assertEquals(ScreenChangeKind.TRUE_NAVIGATION, classify(before, after))
    }

    @Test
    fun `a page with nothing in common is navigation`() {
        val before = fontSizeScreen()
        val after = fontSizeScreen(
            landmarks = setOf("wi fi", "mobile network", "hotspot and tethering"),
            targetLabel = "wi fi",
        )

        assertEquals(ScreenChangeKind.TRUE_NAVIGATION, classify(before, after))
    }

    @Test
    fun `the first screen seen is not classified either way`() {
        assertEquals(ScreenChangeKind.UNKNOWN, classify(null, fontSizeScreen()))
    }

    // ------------------------------------------------------- fingerprints

    @Test
    fun `a fingerprint is built from what the screen says, not from ids`() {
        val screen = ScreenBuilder().apply {
            fontSizeSection(fontTop = 900, displaySizeTop = 1600)
        }.build()

        val first = ScreenFingerprint.of(screen, stepIndex = 2, targetLabel = "Font size", targetRole = "SeekBar")
        // The same page read again after being rebuilt into a new window.
        val rebuilt = ScreenBuilder(windowId = 77).apply {
            fontSizeSection(fontTop = 900, displaySizeTop = 1600)
        }.build()
        val second = ScreenFingerprint.of(rebuilt, stepIndex = 2, targetLabel = "Font size", targetRole = "SeekBar")

        assertEquals(first, second)
    }

    @Test
    fun `overlap is measured against the smaller set, so a longer page still matches`() {
        val short = fontSizeScreen(landmarks = setOf("font size", "display size"))
        val long = fontSizeScreen(
            landmarks = setOf("font size", "display size", "bold text", "preview", "reset"),
        )

        assertEquals(1f, short.landmarkOverlapWith(long), 0.001f)
    }

    @Test
    fun `identity comparison can ignore the window a control happens to live in`() {
        val before = TargetIdentity(
            windowId = 651,
            viewId = "android:id/seekbar",
            normalizedText = "",
            normalizedDescription = "font size",
            className = "android.widget.SeekBar",
        )
        val afterRebuild = before.copy(windowId = 655)

        assertNotEquals(before, afterRebuild)
        assertTrue("the same control, in a new window", before.isSameControlAs(afterRebuild))
    }
}
