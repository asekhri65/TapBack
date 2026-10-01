package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.matching.TextNormalizer
import com.aaravsekhri.dot.guide.model.Bounds

/** What a gesture is for. Each starts in a different place, and must never be confused with another. */
enum class GesturePurpose {
    /** From the Home screen, open the list of all apps. */
    OpenAppList,

    /** Move the content of a page. */
    ScrollContent,

    /** Go to the Home screen (gesture navigation: up from the very bottom edge). */
    GoHome,

    /** Show recent apps (gesture navigation: up from the bottom edge, then hold). */
    OpenRecents,

    /** Go back one screen (gesture navigation: in from the side edge). */
    GoBack,
}

/** A point on screen, in the same raw pixels as accessibility bounds. */
data class ScreenPoint(val x: Int, val y: Int)

/**
 * Exactly where a gesture starts and where it goes, on the screen in front.
 *
 * The same meaning as the card's own illustration ([gesture]): the path and
 * the picture are built from one decision, never from the wording.
 *
 * @property holdMs how long to hold at the end, for a gesture that needs it.
 */
data class GesturePlan(
    val purpose: GesturePurpose,
    val start: ScreenPoint,
    val end: ScreenPoint,
    val gesture: Gesture,
    val holdMs: Long = 0,
) {
    /** The strip the demonstration draws in, for keeping the card off it. */
    fun area(margin: Int): Bounds = Bounds(
        minOf(start.x, end.x) - margin,
        minOf(start.y, end.y) - margin,
        maxOf(start.x, end.x) + margin,
        maxOf(start.y, end.y) + margin,
    )
}

/** Which of its screens a launcher is showing. */
enum class LauncherView { Home, AppList, Recents, Unknown }

/**
 * Where to show a gesture, from what is on screen now.
 *
 * Pure, so every rule is tested. Nothing here invents a place: when the start
 * cannot be put somewhere safe with confidence, the answer is null and the
 * card says it in words instead.
 */
object GesturePlanner {

    /**
     * Launchers known to open the list of all apps with an upward swipe that
     * starts on the Home screen itself. Others may not — some use a button,
     * some have no list at all — and get words, not a path.
     */
    /** How long the Recents swipe holds before letting go. */
    const val RECENTS_HOLD_MS = 700L

    val APP_LIST_BY_SWIPE = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
    )

    /**
     * Opening the list of all apps from a verified Home screen.
     *
     * Starts in the Home screen's own space, above the dock and search bar
     * and well above the system's gesture strip at the bottom edge — an
     * upward swipe from that strip goes Home or to recent apps instead. Never
     * starts on an icon.
     *
     * @param viewport the display less its status and navigation bars.
     * @param controls what is on the Home screen now.
     * @param unit one density-independent pixel, in pixels.
     */
    fun appList(
        launcher: String,
        view: LauncherView,
        viewport: Bounds,
        controls: List<Bounds>,
        unit: Float,
    ): GesturePlan? {
        if (launcher !in APP_LIST_BY_SWIPE || view != LauncherView.Home) return null
        if (viewport.height <= 0) return null
        val h = viewport.height
        // Icons, folders and the search bar — not the pages and panels that
        // hold them, which cover the whole screen and say nothing about where
        // the Home screen's free space is.
        val items = controls.filter { !it.isEmpty && it.height < h / 4 && it.width < viewport.width * 0.95f }
        // The dock and the search bar: whatever sits in the lowest part of the screen.
        val lowZone = viewport.top + (h * 0.62f).toInt()
        val dockTop = items.filter { it.top >= lowZone }.minOfOrNull { it.top }
            ?: (viewport.bottom - (h * 0.22f).toInt())
        var startY = dockTop - (40 * unit).toInt()
        // Clear of any icon it would otherwise start on.
        items.firstOrNull { it.holds(viewport.centerX, startY) }?.let { startY = it.top - (16 * unit).toInt() }
        val safeFromEdge = viewport.bottom - (72 * unit).toInt()
        val highest = viewport.top + (h * 0.40f).toInt()
        if (startY > safeFromEdge || startY < highest) {
            com.aaravsekhri.dot.guide.GuideLog.d("gesture") { "no safe start: dockTop=$dockTop start=$startY safe=$safeFromEdge highest=$highest" }
            return null
        }
        val endY = (startY - h * 0.24f).toInt().coerceAtLeast(viewport.top + (h * 0.12f).toInt())
        return GesturePlan(
            GesturePurpose.OpenAppList,
            ScreenPoint(viewport.centerX, startY),
            ScreenPoint(viewport.centerX, endY),
            Gesture.Slide(FingerDirection.Up),
        )
    }

    /** Moving a page's content, inside the list that moves when it is known. */
    fun scroll(reveal: Reveal, area: Bounds): GesturePlan? {
        if (area.width <= 0 || area.height <= 0) return null
        val cx = area.centerX
        val cy = area.centerY
        val (start, end) = when (reveal) {
            Reveal.Below -> ScreenPoint(cx, area.top + (area.height * 0.72f).toInt()) to ScreenPoint(cx, area.top + (area.height * 0.30f).toInt())
            Reveal.Above -> ScreenPoint(cx, area.top + (area.height * 0.30f).toInt()) to ScreenPoint(cx, area.top + (area.height * 0.72f).toInt())
            Reveal.Right -> ScreenPoint(area.left + (area.width * 0.78f).toInt(), cy) to ScreenPoint(area.left + (area.width * 0.25f).toInt(), cy)
            Reveal.Left -> ScreenPoint(area.left + (area.width * 0.25f).toInt(), cy) to ScreenPoint(area.left + (area.width * 0.78f).toInt(), cy)
        }
        return GesturePlan(GesturePurpose.ScrollContent, start, end, Gesture.Slide(reveal.finger()))
    }

    /**
     * The system gestures, only where the phone uses gesture navigation. With
     * buttons, the button is the thing — there is no path to draw.
     *
     * @param display the whole display, bars included: these start at its edge.
     */
    fun system(purpose: GesturePurpose, navigation: NavigationStyle, display: Bounds, unit: Float): GesturePlan? {
        if (navigation != NavigationStyle.Gestures || display.height <= 0) return null
        val h = display.height
        val edge = display.bottom - (6 * unit).toInt()
        return when (purpose) {
            GesturePurpose.GoHome -> GesturePlan(
                purpose,
                ScreenPoint(display.centerX, edge),
                ScreenPoint(display.centerX, display.bottom - (h * 0.22f).toInt()),
                Gesture.Home(navigation),
            )
            GesturePurpose.OpenRecents -> GesturePlan(
                purpose,
                ScreenPoint(display.centerX, edge),
                ScreenPoint(display.centerX, display.bottom - (h * 0.38f).toInt()),
                Gesture.Recents(navigation),
                holdMs = RECENTS_HOLD_MS,
            )
            GesturePurpose.GoBack -> GesturePlan(
                purpose,
                ScreenPoint(display.left + (4 * unit).toInt(), display.centerY),
                ScreenPoint(display.left + (display.width * 0.22f).toInt(), display.centerY),
                Gesture.Back(navigation),
            )
            else -> null
        }
    }

    /**
     * Which launcher screen [observation] shows.
     *
     * Recent apps are told by their own controls — "Clear all", "Screenshot",
     * "Select" — and the list of all apps by its search box among many apps.
     * Anything that is not clearly one of them is [LauncherView.Unknown].
     */
    fun viewOf(observation: Observation): LauncherView {
        if (!observation.homeScreen) return LauncherView.Unknown
        val names = observation.candidates.map { TextNormalizer.normalize(it.naming + " " + it.description) }
        val recentsWords = listOf("clear all", "close all", "screenshot", "select", "recent apps")
        if (recentsWords.count { w -> names.any { " $w " in " $it " } } >= 2) return LauncherView.Recents
        val search = observation.candidates.any {
            it.editable || TextNormalizer.normalize(it.hint + " " + it.naming).let { n -> "search" in n && ("apps" in n || "phone" in n) }
        }
        return if (search && observation.candidates.count { it.clickable } >= 12) LauncherView.AppList else LauncherView.Home
    }
}

private fun Bounds.holds(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
