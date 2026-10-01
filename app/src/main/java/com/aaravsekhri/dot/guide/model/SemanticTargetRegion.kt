package com.aaravsekhri.dot.guide.model

import com.aaravsekhri.dot.guide.matching.TargetIdentity

/**
 * How much of the hierarchy a step's highlight should cover.
 *
 * The right answer differs by what is being pointed at. A Settings row is one
 * tappable node. A slider is a widget with its title and description sitting
 * beside it as separate nodes, and outlining the widget alone draws a box
 * through the middle of the words that explain it.
 */
enum class TargetRegionStrategy {
    /** Exactly the node that matched. */
    EXACT_NODE,

    /** The nearest tappable ancestor — the usual answer for a list row. */
    CLICKABLE_PARENT,

    /** The smallest ancestor that contains every part of the setting. */
    COMMON_ANCESTOR,

    /**
     * The whole logical setting: its title, its description, and its controls,
     * gathered from wherever the hierarchy happens to have put them.
     */
    COMPOSITE_SETTING_ROW,
}

/** One node that forms part of a target region. Framework-free, and short-lived. */
data class NodeDescriptor(
    val nodeId: Int,
    val viewId: String?,
    val className: String?,
    val bounds: Bounds,
)

/**
 * The area a step points at, and what it was built from.
 *
 * [boundsInScreen] is what gets outlined — the whole setting, not whichever
 * single node happened to match. [anchorBounds] is the node that identified
 * it, which is what the tracker follows from one scan to the next.
 */
data class SemanticTargetRegion(
    val identity: TargetIdentity,
    val boundsInScreen: Bounds,
    val anchorBounds: Bounds,
    val matchedNodes: List<NodeDescriptor>,
    /** The wording to name this target by, when it has any. */
    val label: String? = null,
) {
    /**
     * How much of the region is inside [viewport], from 0f to 1f.
     *
     * A setting half off the bottom of the screen cannot usefully be outlined,
     * and this is what decides whether to keep asking for a scroll.
     */
    fun visibleFractionWithin(viewport: Bounds): Float {
        if (boundsInScreen.isEmpty) return 0f
        val top = maxOf(boundsInScreen.top, viewport.top)
        val bottom = minOf(boundsInScreen.bottom, viewport.bottom)
        val visibleHeight = (bottom - top).coerceAtLeast(0)
        return visibleHeight.toFloat() / boundsInScreen.height.toFloat()
    }
}
