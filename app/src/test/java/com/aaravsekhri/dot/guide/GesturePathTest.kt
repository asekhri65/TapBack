package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.CandidateControl
import com.aaravsekhri.dot.guide.ai.FingerDirection
import com.aaravsekhri.dot.guide.ai.Gesture
import com.aaravsekhri.dot.guide.ai.GesturePlanner
import com.aaravsekhri.dot.guide.ai.GesturePurpose
import com.aaravsekhri.dot.guide.ai.LauncherView
import com.aaravsekhri.dot.guide.ai.NavigationStyle
import com.aaravsekhri.dot.guide.ai.Observation
import com.aaravsekhri.dot.guide.ai.Reveal
import com.aaravsekhri.dot.guide.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a swipe starts on the real screen, and which swipe it is. */
class GesturePathTest {

    private val display = Bounds(0, 0, 1170, 2532)
    private val viewport = Bounds(0, 139, 1170, 2460) // less the status and navigation bars
    private val unit = 3f
    private val pixel = "com.google.android.apps.nexuslauncher"

    // A Pixel Home screen: the dock row and the search bar at the bottom, an icon up top.
    private val homeControls = listOf(
        Bounds(69, 1965, 291, 2188), Bounds(330, 1965, 552, 2188), Bounds(618, 1965, 840, 2188), Bounds(879, 1965, 1101, 2188),
        Bounds(69, 2210, 1101, 2340),
        Bounds(69, 200, 291, 440),
        // The page that holds them all, as launchers report it.
        Bounds(0, 139, 1170, 2460),
    )

    @Test
    fun `the app list swipe starts on the Home screen itself, above the dock and the gesture strip`() {
        val plan = GesturePlanner.appList(pixel, LauncherView.Home, viewport, homeControls, unit)!!
        assertEquals(GesturePurpose.OpenAppList, plan.purpose)
        assertEquals(Gesture.Slide(FingerDirection.Up), plan.gesture)
        assertTrue("above the dock", plan.start.y < 1965)
        assertTrue("well clear of the bottom edge's gesture strip", plan.start.y < viewport.bottom - 72 * unit)
        assertTrue("upward", plan.end.y < plan.start.y)
        assertTrue("never starting on an icon", homeControls.dropLast(1).none { plan.start.x in it.left until it.right && plan.start.y in it.top until it.bottom })
    }

    @Test
    fun `an unknown launcher, or a launcher not on its Home screen, gets words rather than a path`() {
        assertNull(GesturePlanner.appList("com.example.launcher", LauncherView.Home, viewport, homeControls, unit))
        assertNull(GesturePlanner.appList(pixel, LauncherView.Recents, viewport, homeControls, unit))
        assertNull(GesturePlanner.appList(pixel, LauncherView.AppList, viewport, homeControls, unit))
    }

    @Test
    fun `Home, recent apps and Back start at the system edges, never where the app list's swipe starts`() {
        val appList = GesturePlanner.appList(pixel, LauncherView.Home, viewport, homeControls, unit)!!
        val home = GesturePlanner.system(GesturePurpose.GoHome, NavigationStyle.Gestures, display, unit)!!
        val recents = GesturePlanner.system(GesturePurpose.OpenRecents, NavigationStyle.Gestures, display, unit)!!
        val back = GesturePlanner.system(GesturePurpose.GoBack, NavigationStyle.Gestures, display, unit)!!
        assertTrue("Home starts at the very bottom edge", home.start.y >= display.bottom - 10 * unit)
        assertTrue("the app list does not", appList.start.y < home.start.y - 200)
        assertTrue("recent apps needs a hold", recents.holdMs > 0)
        assertEquals(0, home.holdMs)
        assertTrue("Back comes in from the side", back.start.x <= 5 * unit && back.end.x > back.start.x)
    }

    @Test
    fun `recent apps is its own gesture, held at the end, and never Home's`() {
        val home = GesturePlanner.system(GesturePurpose.GoHome, NavigationStyle.Gestures, display, unit)!!
        val recents = GesturePlanner.system(GesturePurpose.OpenRecents, NavigationStyle.Gestures, display, unit)!!
        assertEquals(com.aaravsekhri.dot.guide.ai.Gesture.Recents(NavigationStyle.Gestures), recents.gesture)
        assertEquals(com.aaravsekhri.dot.guide.ai.Gesture.Home(NavigationStyle.Gestures), home.gesture)
        assertTrue("recent apps goes further up than Home, and holds", recents.end.y < home.end.y && recents.holdMs > 0)
    }

    @Test
    fun `a held gesture waits at its end, visibly, and never travels backwards`() {
        val hold = GesturePlanner.RECENTS_HOLD_MS
        val samples = (0..400).map { it / 400f }
        val frames = samples.map { com.aaravsekhri.dot.guide.ai.GestureMotion.frame(it, hold) }
        val held = frames.filter { it.holding }
        assertTrue(held.isNotEmpty())
        assertTrue("held at the end, fully shown", held.all { it.progress == 1f && it.alpha == 1f })
        var last = 0f
        frames.forEach { f ->
            if (f.alpha > 0f) assertTrue("never back along the path while seen", f.progress >= last)
            last = if (f.alpha > 0f) f.progress else 0f
        }
        assertEquals("the cycle ends unseen", 0f, frames.last().alpha)
        val heldMs = held.size / 400f * com.aaravsekhri.dot.guide.ai.GestureMotion.cycleMs(hold)
        assertTrue("about as long as the hold ($heldMs ms)", heldMs in (hold * 0.8f)..(hold * 1.2f))
        assertTrue("no hold, no holding", samples.none { com.aaravsekhri.dot.guide.ai.GestureMotion.frame(it).holding })
    }

    @Test
    fun `with navigation buttons there is no path to draw`() {
        assertNull(GesturePlanner.system(GesturePurpose.GoHome, NavigationStyle.ThreeButton, display, unit))
        assertNull(GesturePlanner.system(GesturePurpose.OpenRecents, NavigationStyle.ThreeButton, display, unit))
        assertNull(GesturePlanner.system(GesturePurpose.GoBack, NavigationStyle.TwoButton, display, unit))
    }

    @Test
    fun `scrolling is shown inside the list that moves, the finger going against the reveal`() {
        val list = Bounds(0, 400, 1170, 2200)
        val down = GesturePlanner.scroll(Reveal.Below, list)!!
        assertEquals(Gesture.Slide(FingerDirection.Up), down.gesture)
        assertTrue(down.start.y in list.top..list.bottom && down.end.y in list.top..list.bottom)
        assertTrue(down.end.y < down.start.y)
        val sideways = GesturePlanner.scroll(Reveal.Right, list)!!
        assertTrue("finger goes left to see more on the right", sideways.end.x < sideways.start.x)
    }

    private fun control(label: String, top: Int, editable: Boolean = false, hint: String = "") = CandidateControl(
        id = label, label = label, description = "", role = "View", bounds = Bounds(0, top, 200, top + 100),
        clickable = true, editable = editable, enabled = true, scrollable = false, parentId = null, hint = hint,
    )

    private fun launcherScreen(controls: List<CandidateControl>) = Observation(
        sessionId = "s", observationId = "o", takenAtMs = 0, screenGeneration = 1, packageName = pixel,
        windowTitle = "", windowId = 1, screenWidth = 1170, screenHeight = 2532, candidates = controls,
        scrollContainerIds = emptyList(), truncated = false, homeScreen = true,
    )

    @Test
    fun `recent apps, the app list and Home are told apart`() {
        assertEquals(
            LauncherView.Recents,
            GesturePlanner.viewOf(launcherScreen(listOf(control("Screenshot", 2000), control("Select", 2000), control("Maps", 800)))),
        )
        val apps = (1..14).map { control("App $it", 400 + it * 100) } + control("", 200, editable = true, hint = "Search your phone and more")
        assertEquals(LauncherView.AppList, GesturePlanner.viewOf(launcherScreen(apps)))
        assertEquals(LauncherView.Home, GesturePlanner.viewOf(launcherScreen(listOf(control("Phone", 1965), control("Chrome", 1965)))))
    }
}
