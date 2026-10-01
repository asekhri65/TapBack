package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.model.ScreenSnapshot

/**
 * Cost measures that change what the planner is sent or how often.
 *
 * Both are on because a live comparison against the previous behaviour
 * (gemini-3.5-flash, 2026-09-28, fixture and Settings journeys, small sample)
 * found no worse decisions and fewer calls or tokens:
 *
 * - local wait checks: a 20-second download went from 11 planner calls to 4,
 *   same steps, same finish;
 * - selective pictures: 10 paired runs over 4 journeys, the same decisions
 *   every time, 7–26% less estimated cost where the words were enough, and
 *   no change on Settings, where the rule kept the picture.
 *
 * A debug build can switch either off for a comparison run.
 */
object PlanningTuning {

    /**
     * While waiting on something outside Dot — an app installing — look again
     * on the phone, and ask the planner only once the controls on screen have
     * changed (Open appearing, an error, a new page), rather than at every
     * recheck. A percentage ticking up is not a change.
     */
    @Volatile
    var localWaitChecks: Boolean = true

    /**
     * Send the screen as text alone when the accessibility tree describes it
     * fully and unambiguously, and include the picture otherwise.
     */
    @Volatile
    var selectiveScreenshots: Boolean = true

    /**
     * When a screen's description stays nearly empty after looking again, one
     * request with its picture alone — only where a picture is permitted.
     */
    var pictureFallback: Boolean = true
}

/**
 * Whether the accessibility description is enough on its own.
 *
 * Conservative: any sign that something matters visually, or that two
 * controls could be confused, and the picture goes too. There is no second
 * round trip to find out — the picture is included up front whenever the
 * answer is not clearly yes.
 */
object ScreenshotPolicy {

    fun textSuffices(observation: Observation, screen: ScreenSnapshot): Boolean {
        if (observation.truncated) return false
        // The app is saying something about the form: a picture shows where.
        if (observation.notices.isNotEmpty()) return false
        val actionable = observation.candidates.filter { (it.clickable || it.editable) && it.naming.isNotBlank() }
        if (actionable.size < MIN_NAMED) return false
        // Two controls with the same name is a choice words cannot settle.
        val names = actionable.map { it.naming.trim().lowercase() }
        if (names.size != names.toSet().size) return false
        // Pictures, web pages and canvases say things the tree does not.
        if (screen.nodes.any { n -> VISUAL.any { n.className?.contains(it) == true } && n.isVisibleToUser }) return false
        // Tappable things with no name at all: icons the words cannot describe.
        val unnamed = screen.nodes.count { it.isVisibleToUser && it.isClickable && !it.hasLabel && !it.isEditable }
        if (unnamed > MAX_UNNAMED) return false
        return true
    }

    private const val MIN_NAMED = 2
    private const val MAX_UNNAMED = 1
    private val VISUAL = listOf("WebView", "SurfaceView", "TextureView")
}

/**
 * What a page is showing, for telling whether it has meaningfully changed
 * while something is being waited for. The controls and their state, with
 * numbers blurred so a download's percentage or a clock ticking is not news
 * and "Open" appearing is.
 */
internal fun Observation.progressKey(): String = buildString {
    append(packageName).append('|').append(windowId).append('|')
    candidates.asSequence()
        .map { c ->
            c.naming.replace(DIGITS, "#") + (if (c.enabled) "" else "!") + when (c.checked) {
                true -> "+on"
                false -> "+off"
                null -> ""
            }
        }
        .sorted()
        .joinTo(this, ",")
}

private val DIGITS = Regex("\\d+")
