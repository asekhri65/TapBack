package com.aaravsekhri.dot.guide

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The claim the whole tracker rests on, checked on a device.
 *
 * Everything else about following a scroll is arithmetic and can be unit
 * tested. This one thing cannot: whether a control that has already been found
 * will say where it is, repeatedly and freshly, while the page it is on is
 * moving. The unit tests assume it; this is where that assumption meets an
 * actual Android.
 *
 * Deliberately modest thresholds. The detailed measurements live in the
 * benchmark harness, which drives real gestures and grades against the
 * fixture's own ground truth; this exists so that a build which breaks the
 * premise fails rather than passing quietly.
 */
@RunWith(AndroidJUnit4::class)
class TargetRefreshInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * One connection, obtained once.
     *
     * Asking for a UiAutomation with different flags than the one already in
     * hand reconnects it, and every node handed out by the old connection stops
     * answering. Fetching it per call meant the node under test was invalidated
     * between being found and being read, which looks exactly like a control
     * that cannot be followed.
     */
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(
            // Dot's own service must keep running: suppressing it would change
            // what is being measured.
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
    }

    @Test
    fun aHeldControlReportsFreshPositionsWhileThePageMoves() {
        launchFixture()
        val target = findTarget() ?: error("the fixture's target row was not found")

        centre(target)

        val seen = LinkedHashSet<Int>()
        val rect = Rect()

        // The drag runs in the shell while this loop reads. It has to overlap:
        // the whole question is whether a position can be had mid-gesture, and
        // measuring after the page has stopped would answer a different and
        // much easier one.
        val drag = startDrag(-700)
        val deadline = System.nanoTime() + RUN_NANOS
        while (System.nanoTime() < deadline) {
            if (!target.refresh()) break
            target.getBoundsInScreen(rect)
            if (rect.bottom > rect.top) seen += rect.top
        }
        drag.close()

        // A still screen would report one position for ever; anything that
        // cannot follow would report a handful. The benchmark measures fifty or
        // so a second, and this asks for a small fraction of that so a loaded
        // build machine does not fail it spuriously.
        assertTrue(
            "expected several distinct positions during a scroll, saw ${seen.size} " +
                "(last reading $rect)",
            seen.size >= MINIMUM_DISTINCT_POSITIONS,
        )
    }

    @Test
    fun aHeldControlKeepsItsWordingWhileItMoves() {
        launchFixture()
        val target = findTarget() ?: error("the fixture's target row was not found")

        centre(target)
        val drag = startDrag(-500)
        repeat(40) {
            if (!target.refresh()) return@repeat
            val text = target.text?.toString()
            assertTrue(
                "the held node must never come back as a different row: $text",
                text == null || text == TARGET_LABEL,
            )
        }
        drag.close()
    }

    /**
     * Brings the row somewhere near the middle, so a drag keeps it on screen.
     *
     * A row outside its list comes back with its edges crossed, which still
     * says which side it is on: a rectangle that starts below where it ends is
     * a row below the list. Treating that as "cannot tell" and giving up is how
     * this test came to measure a row that was never on screen.
     */
    private fun centre(target: AccessibilityNodeInfo) {
        // To the very top of the list first, so the starting point is known
        // rather than wherever finding the row happened to leave it. Steering
        // by the reading alone oscillated: a row nine thousand pixels away and
        // one a hundred pixels away report the same "not on screen".
        repeat(TOP_DRAGS) { dragAndWait(900) }
        // Then down by what the fixture's own layout says it takes.
        repeat(DOWN_DRAGS) { dragAndWait(-900) }

        val rect = Rect()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val wanted = metrics.heightPixels / 3
        repeat(10) {
            if (!target.refresh()) return
            target.getBoundsInScreen(rect)
            // A row outside its list still says which side it is on: the edges
            // come back crossed, and whichever of them is nearer the list tells
            // you which way it went.
            val delta = when {
                rect.bottom > rect.top -> wanted - rect.top
                rect.bottom < wanted -> 600
                else -> -600
            }
            if (kotlin.math.abs(delta) < 150) return
            dragAndWait(delta.coerceIn(-600, 600))
        }
    }

    private fun launchFixture() {
        val context = instrumentation.targetContext
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.setClassName(context.packageName, FIXTURE)
            ?: Intent().setClassName(context.packageName, FIXTURE)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(SETTLE_MS)
    }

    private fun findTarget(): AccessibilityNodeInfo? {
        repeat(20) {
            val root = automation.rootInActiveWindow
            val found = root?.findAccessibilityNodeInfosByText(TARGET_LABEL)?.firstOrNull()
            if (found != null) return found
            // The row starts below the fold.
            dragAndWait(-900)
        }
        return null
    }

    /**
     * Starts a real drag that moves the list's content by [dy] pixels.
     *
     * Returns while the gesture is still in progress, on purpose.
     */
    private fun startDrag(dy: Int): java.io.Closeable {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels / 2
        val from = metrics.heightPixels / 2 - dy / 2
        val to = metrics.heightPixels / 2 + dy / 2
        val fd = automation.executeShellCommand("input swipe $x $from $x $to $DRAG_MS")
        return java.io.Closeable { fd.close() }
    }

    /**
     * Drags, and waits for the gesture to finish.
     *
     * Reading the command's output to the end is what waits: closing the
     * descriptor instead ends the command, and every setup drag was being
     * cancelled the instant it started — which left the list exactly where it
     * began and the test measuring a row that was never on screen.
     */
    private fun dragAndWait(dy: Int) {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels / 2
        val from = metrics.heightPixels / 2 - dy / 2
        val to = metrics.heightPixels / 2 + dy / 2
        val fd = automation.executeShellCommand("input swipe $x $from $x $to $DRAG_MS")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        Thread.sleep(SETTLE_AFTER_DRAG_MS)
    }

    private companion object {
        const val FIXTURE = "com.aaravsekhri.dot.experiment.FixtureScrollActivity"
        const val TARGET_LABEL = "Fixture target row"
        const val SETTLE_MS = 2_000L
        const val STEP_MS = 700L
        const val DRAG_MS = 1_500
        const val RUN_NANOS = 1_500_000_000L
        const val MINIMUM_DISTINCT_POSITIONS = 10

        /** Enough to reach the top of the fixture's list from anywhere in it. */
        const val TOP_DRAGS = 14

        /** The target sits 24 rows down; this brings it onto the screen. */
        const val DOWN_DRAGS = 5

        /** Long enough for a list to come to rest between setup drags. */
        const val SETTLE_AFTER_DRAG_MS = 400L
    }
}
