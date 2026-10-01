package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot

/**
 * What to draw around a control — or a decision not to draw at all.
 *
 * The one place both kinds of guidance get their outline from: the model's
 * targets, through [ObservationBuilder], and local sign-in help, through
 * [AuthScreenReader]. Accessibility says where a *node* is, and a node is not
 * always what a person sees as the control. Two real screens showed the
 * difference:
 *
 * - A login box drawn as a bordered container, with its text field only in
 *   the lower part. Outlining the field cut the box in half.
 * - A search pill with icon buttons either side of the text field. Outlining
 *   the field left half the pill outside.
 *
 * So a field's outline is its *meaningful container* when the tree gives
 * structural evidence for one: an exposed ancestor that holds this field and
 * nothing else a person would act on — no second field, no sizeable button —
 * and is only a little larger than it. Never a large clickable ancestor
 * chosen for being clickable; never a text child chosen for having words.
 *
 * And some controls have no boundary worth drawing. A card that holds its own
 * buttons, a region covering most of the screen, a sliver a few pixels high:
 * an outline there says nothing about where to touch, so the verdict is words
 * only. Arbitrary Android controls do not expose enough to draw a perfect
 * outline, and this does not pretend they do; where the evidence is thin the
 * answer is text.
 *
 * Plain Kotlin, reading a frozen snapshot, so every rule is a unit test.
 */
object ControlOutline {

    sealed interface Verdict {
        /** Draw around [bounds]. [container] is true when that is not the node itself. */
        data class Outline(val bounds: Bounds, val container: Boolean) : Verdict

        /** Say it in words. [why] is a fixed phrase, safe to log. */
        data class TextOnly(val why: String) : Verdict
    }

    /**
     * @param pxPerDp the display's density, for the size rules. Everything
     *   here is in screen pixels.
     */
    fun resolve(node: NodeSnapshot, screen: ScreenSnapshot, pxPerDp: Float): Verdict {
        val b = node.bounds
        if (!node.isVisibleToUser || b.isEmpty) return Verdict.TextOnly("not visible")
        if (b.width < MIN_SIDE_DP * pxPerDp || b.height < MIN_SIDE_DP * pxPerDp) {
            return Verdict.TextOnly("too small to outline")
        }
        val screenArea = screen.screenWidth.toLong() * screen.screenHeight
        if (screenArea > 0 && b.width.toLong() * b.height > screenArea * MAX_SCREEN_FRACTION) {
            return Verdict.TextOnly("covers most of the screen")
        }

        val index = Index(screen)
        if (!node.isEditable && !node.isScrollable) {
            // A card that holds its own buttons: an outline round the whole
            // thing does not say which of them to touch.
            val others = index.descendants(node).count { it.isActionable() && it.hasLabel }
            if (others >= 2) return Verdict.TextOnly("holds other controls")
        }
        if (!node.isEditable) return Verdict.Outline(b, container = false)

        val container = fieldContainer(node, index, pxPerDp)
        return if (container != null) Verdict.Outline(container.bounds, container = true) else Verdict.Outline(b, false)
    }

    /**
     * The field's own words, when it has any that are not the person's.
     *
     * Its placeholder, its description, or a plain label inside its
     * container — the "Mobile number or email" drawn inside a login box.
     * Anything that reads like a value (an address, a long number) is not a
     * label and is never returned: this runs on private screens too.
     */
    fun fieldLabel(node: NodeSnapshot, screen: ScreenSnapshot, pxPerDp: Float): String? {
        node.hintText?.trim()?.takeIf { it.isNotEmpty() && !looksLikeValue(it) }?.let { return it }
        node.contentDescription?.trim()?.takeIf { it.isNotEmpty() && !looksLikeValue(it) }?.let { return it }
        val index = Index(screen)
        val container = fieldContainer(node, index, pxPerDp) ?: return null
        return index.descendants(container)
            .filter { it !== node && !it.isActionable() && !it.isEditable }
            .mapNotNull { it.text?.trim() }
            .firstOrNull { it.isNotEmpty() && it.length <= MAX_LABEL && !looksLikeValue(it) }
    }

    /**
     * The smallest-to-largest run of ancestors that wrap only this field.
     * The largest that still qualifies wins: a bordered box around a field
     * with a label inside it, not the field alone.
     */
    private fun fieldContainer(node: NodeSnapshot, index: Index, pxPerDp: Float): NodeSnapshot? {
        var best: NodeSnapshot? = null
        var current = node
        repeat(MAX_CLIMB) {
            val parent = index.parent(current) ?: return best
            if (!wrapsOnly(parent, node, index, pxPerDp)) return best
            best = parent
            current = parent
        }
        return best
    }

    private fun wrapsOnly(candidate: NodeSnapshot, field: NodeSnapshot, index: Index, pxPerDp: Float): Boolean {
        val c = candidate.bounds
        val f = field.bounds
        if (!candidate.isVisibleToUser || c.isEmpty || candidate.isScrollable || candidate.isEditable) return false
        if (!c.contains(f)) return false
        // Only a little larger: room for a label, a border, an icon each side.
        if (c.width - f.width > MAX_EXTRA_WIDTH_DP * pxPerDp) return false
        if (c.height - f.height > MAX_EXTRA_HEIGHT_DP * pxPerDp) return false
        // Nothing else a person would act on, apart from small icons — the
        // magnifier and microphone in a search pill.
        val icon = MAX_ICON_DP * pxPerDp
        return index.descendants(candidate).none { other ->
            other !== field && (
                other.isEditable ||
                    (other.isActionable() && (other.bounds.width > icon || other.bounds.height > icon))
                )
        }
    }

    private fun NodeSnapshot.isActionable() = isClickable || isEditable || isCheckable

    private fun looksLikeValue(text: String): Boolean =
        '@' in text || text.count(Char::isDigit) >= 4

    /** Parent and child lookups over one snapshot, built once per call. */
    private class Index(screen: ScreenSnapshot) {
        private val byId = screen.nodes.associateBy { it.id }
        private val children = screen.nodes.groupBy { it.parentId }

        fun parent(node: NodeSnapshot): NodeSnapshot? = node.parentId?.let(byId::get)

        fun descendants(node: NodeSnapshot, depth: Int = MAX_DESCENT): List<NodeSnapshot> {
            if (depth <= 0) return emptyList()
            val direct = children[node.id].orEmpty()
            return direct + direct.flatMap { descendants(it, depth - 1) }
        }
    }

    /** Below this on either side there is nothing to outline, only a line. */
    private const val MIN_SIDE_DP = 8

    /** A "control" covering more of the screen than this is a region, not a control. */
    private const val MAX_SCREEN_FRACTION = 0.45

    private const val MAX_CLIMB = 3
    private const val MAX_DESCENT = 5
    private const val MAX_EXTRA_WIDTH_DP = 144
    private const val MAX_EXTRA_HEIGHT_DP = 40
    private const val MAX_ICON_DP = 64
    private const val MAX_LABEL = 60
}
