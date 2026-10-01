package com.aaravsekhri.dot.guide.model

/**
 * A rectangle in screen pixels.
 *
 * Deliberately not [android.graphics.Rect] so that everything which reasons
 * about positions and matching stays plain Kotlin and can be unit tested.
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun inflate(by: Int): Bounds = Bounds(left - by, top - by, right + by, bottom + by)

    fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /**
     * True when [other] is the same rectangle give or take [tolerance] pixels
     * on every edge.
     *
     * Accessibility bounds shift by a pixel or two as a list settles, which is
     * invisible to a person but would make a redrawn outline shimmer. Treating
     * those as "the same place" is what keeps the highlight still.
     */
    fun isEffectivelySameAs(other: Bounds, tolerance: Int): Boolean =
        kotlin.math.abs(left - other.left) <= tolerance &&
            kotlin.math.abs(top - other.top) <= tolerance &&
            kotlin.math.abs(right - other.right) <= tolerance &&
            kotlin.math.abs(bottom - other.bottom) <= tolerance

    /** True when [other] lies wholly inside this rectangle. */
    fun contains(other: Bounds): Boolean =
        other.left >= left && other.top >= top &&
            other.right <= right && other.bottom <= bottom

    fun union(other: Bounds): Bounds = Bounds(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    /** True when this lies wholly outside a screen of the given size. */
    fun isOffScreen(screenWidth: Int, screenHeight: Int): Boolean =
        right <= 0 || bottom <= 0 || left >= screenWidth || top >= screenHeight

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/**
 * The system bars and cutouts an overlay must stay clear of.
 *
 * Accessibility node bounds are in raw screen coordinates, including the area
 * under the status and navigation bars. Overlay windows are laid out in the
 * same space, so these are applied in exactly one place — when deciding what
 * counts as visible and where a highlight may be drawn — rather than being
 * added by some paths and forgotten by others.
 */
data class Insets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    companion object {
        val NONE = Insets()
    }
}

/**
 * A short-lived, framework-free copy of one accessibility node.
 *
 * Nodes are converted to snapshots the moment they are read and the framework
 * objects are recycled immediately, so nothing holds an
 * `AccessibilityNodeInfo` beyond the traversal that produced it. Matching then
 * works on ordinary data, which is what makes it testable.
 *
 * Snapshots exist only for the duration of one matching pass. They are never
 * persisted, logged, or transmitted.
 */
data class NodeSnapshot(
    /** Position of this node within the traversal, used to reference parents. */
    val id: Int,
    val parentId: Int?,
    val depth: Int,
    /**
     * The window this node belongs to. Part of a target's identity: the same
     * wording on a new page is a different thing to point at.
     */
    val windowId: Int,
    val packageName: String?,
    val className: String?,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: Bounds,
    val isVisibleToUser: Boolean,
    val isEnabled: Boolean,
    val isClickable: Boolean,
    val isFocusable: Boolean,
    val isScrollable: Boolean,
    val isPassword: Boolean,
    val isCheckable: Boolean,
    /**
     * State, for the general guide: whether a step worked is judged from what
     * the screen now shows, and a switch that flipped or a slider that moved
     * shows it here rather than in any wording. Defaults keep every existing
     * reading — and every fixture — exactly as it was.
     */
    val isChecked: Boolean = false,
    val isSelected: Boolean = false,
    val isFocused: Boolean = false,
    val isEditable: Boolean = false,
    /** A field's placeholder, which is often the only thing naming it. */
    val hintText: String? = null,
    /** A slider or progress bar's position, as "3 of 7" or "34%". */
    val rangeValue: String? = null,
    /** For a scrolling container: true when it moves sideways. */
    val scrollsHorizontally: Boolean = false,
    /**
     * An editable field's declared kind (android.text.InputType bits): an
     * email address, a number, a password. Structure, never content — it is
     * how a code box or an email box is recognised without reading it.
     */
    val inputType: Int = 0,
    /** A field's declared maximum length, or -1. Code boxes are short. */
    val maxTextLength: Int = -1,
    /**
     * The app's own error on a field ("Enter a valid date"), when it sets one.
     * Never read from a password field.
     */
    val errorText: String? = null,
    /** True when what a field shows is its placeholder, not anything typed. */
    val showingHint: Boolean = false,
) {
    /** True when this node could plausibly be the thing a person taps. */
    val isInteractive: Boolean get() = isClickable || isFocusable

    /** True when there is any wording on this node to match against. */
    val hasLabel: Boolean get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank()
}

/**
 * One pass over the visible hierarchy.
 *
 * @param nodes flattened tree, parents always before their children.
 */
data class ScreenSnapshot(
    val packageName: String?,
    val nodes: List<NodeSnapshot>,
    val screenWidth: Int,
    val screenHeight: Int,
    /** The active window's id, or -1 when it could not be read. */
    val windowId: Int = -1,
    /** The root node's class, used to notice one page replacing another. */
    val rootClassName: String? = null,
) {
    /**
     * What makes this "the same page" as a previous look.
     *
     * A change here means navigation has happened and anything Dot was
     * pointing at belongs to a screen that is no longer in front.
     */
    val pageSignature: String
        get() = "$windowId|$packageName|$rootClassName"

    /** The area an overlay may usefully occupy, in screen coordinates. */
    fun viewport(insets: Insets): Bounds = Bounds(
        left = insets.left,
        top = insets.top,
        right = screenWidth - insets.right,
        bottom = screenHeight - insets.bottom,
    )

    /** True when something on screen can be scrolled by the person. */
    val hasScrollableContent: Boolean
        get() = nodes.any { it.isScrollable && !it.bounds.isEmpty && it.isVisibleToUser }

    fun nodeById(id: Int): NodeSnapshot? = nodes.getOrNull(id)?.takeIf { it.id == id }

    /**
     * True when any visible field on screen is taking a password.
     *
     * Dot removes its overlays entirely in that case rather than sitting on top
     * of someone entering a credential.
     */
    val containsPasswordField: Boolean get() = nodes.any { it.isPassword }

    companion object {
        val EMPTY = ScreenSnapshot(null, emptyList(), 0, 0)
    }
}
