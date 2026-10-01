package com.aaravsekhri.dot.guide.matching

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.model.TargetSpec

/**
 * One candidate the matcher is willing to point at.
 *
 * @param labelNodeId the node whose wording matched.
 * @param highlightNodeId the node to draw around — the nearest tappable
 *   ancestor when the wording sat on a plain label inside a row.
 */
data class TargetMatch(
    val labelNodeId: Int,
    val highlightNodeId: Int,
    val bounds: Bounds,
    val score: Int,
    /**
     * The wording actually on screen, when the control carries any.
     *
     * The card says this rather than the guide's own name for the step, so a
     * phone that calls the row "Display & touch" is not told to tap
     * "Display". Shown to the person and nothing else — never logged or kept.
     */
    val matchedLabel: String? = null,
    /** What makes this the same target across scans. See [TargetIdentity]. */
    val identity: TargetIdentity? = null,
)

/**
 * What makes two scans agree that they found the same thing.
 *
 * Node ids are positions in a traversal and change every pass, so they cannot
 * be used. This is built from the properties that genuinely persist: the
 * window, the resource id, the wording, and the class. Two scans producing the
 * same identity are looking at the same control.
 */
data class TargetIdentity(
    val windowId: Int,
    val viewId: String?,
    val normalizedText: String,
    val normalizedDescription: String,
    val className: String?,
) {
    /**
     * The same control, ignoring which window it happens to live in.
     *
     * Changing the system font size rebuilds a Settings page into a new window
     * while the person stays exactly where they are. Comparing window ids would
     * call that a different control; comparing what it says and what it is does
     * not.
     */
    fun isSameControlAs(other: TargetIdentity): Boolean =
        viewId == other.viewId &&
            normalizedText == other.normalizedText &&
            normalizedDescription == other.normalizedDescription &&
            className == other.className
}

/**
 * Decides which node on screen is the one a step is talking about.
 *
 * This scores every visible candidate rather than looking for one exact
 * string, so a guide keeps working when a manufacturer renames "Display" to
 * "Display & touch". It is deliberately conservative: below
 * [MINIMUM_ACCEPTABLE_SCORE] it returns nothing rather than pointing somewhere
 * plausible-looking, because sending someone to the wrong control is worse
 * than admitting Dot cannot see it.
 *
 * Pure Kotlin over [NodeSnapshot], so all of it is unit tested.
 */
object TargetMatcher {

    /**
     * Below this, Dot says it cannot find the target instead of guessing.
     * An exact label match alone (100) clears it comfortably; a loose partial
     * word overlap on its own does not.
     */
    const val MINIMUM_ACCEPTABLE_SCORE = 70

    // Wording.
    private const val SCORE_LABEL_EXACT = 100
    private const val SCORE_LABEL_PREFIX = 62
    private const val SCORE_LABEL_CONTAINS = 46
    private const val SCORE_LABEL_OVERLAP_MAX = 50
    private const val SCORE_DESCRIPTION_EXACT = 90
    private const val SCORE_DESCRIPTION_CONTAINS = 40

    // Structure.
    private const val SCORE_VIEW_ID = 45
    private const val SCORE_CLASS = 15
    private const val SCORE_INTERACTIVE = 20
    private const val SCORE_ENABLED = 10
    private const val PENALTY_DISABLED = 40

    /**
     * Best match for [spec] on [screen], or null when nothing scores well
     * enough to be worth pointing at.
     */
    fun findBest(screen: ScreenSnapshot, spec: TargetSpec): TargetMatch? =
        screen.nodes
            .mapNotNull { node -> evaluate(screen, node, spec) }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= MINIMUM_ACCEPTABLE_SCORE }

    /** Every acceptable match, best first. Used by recovery to offer alternatives. */
    fun findAll(screen: ScreenSnapshot, spec: TargetSpec): List<TargetMatch> =
        screen.nodes
            .mapNotNull { node -> evaluate(screen, node, spec) }
            .filter { it.score >= MINIMUM_ACCEPTABLE_SCORE }
            .sortedByDescending { it.score }

    /**
     * Scores one node, or rejects it outright.
     *
     * Rejection comes first and is absolute: anything invisible, empty,
     * off-screen or taking a password is never a candidate, whatever its
     * wording says.
     */
    private fun evaluate(
        screen: ScreenSnapshot,
        node: NodeSnapshot,
        spec: TargetSpec,
    ): TargetMatch? {
        if (!isEligible(node, screen)) return null

        // Some targets are a particular widget, not merely the right words.
        if (spec.requireStructuralMatch &&
            !matchesViewId(node, spec) &&
            !matchesClassName(node, spec)
        ) {
            return null
        }

        var score = scoreWording(node, spec)
        if (score == 0) return null

        score += scoreStructure(node, spec)

        if (node.isEnabled) score += SCORE_ENABLED else score -= PENALTY_DISABLED
        if (node.isInteractive) score += SCORE_INTERACTIVE

        val highlight = resolveHighlightNode(screen, node, spec)
        if (spec.requireInteractive && !highlight.isInteractive) return null

        // The hard gate. A high score buys consideration, not acceptance: the
        // thing being pointed at has to carry the target's name in full,
        // somewhere inside what would be outlined. Without this, a row merely
        // summarised with the right word — "Display, interaction, audio" on the
        // Accessibility row — scores past the threshold and gets highlighted.
        if (!TargetIdentityGate.nodeCarriesIdentity(node, spec) &&
            !TargetIdentityGate.regionCarriesIdentity(screen, highlight.bounds, spec)
        ) {
            return null
        }

        return TargetMatch(
            labelNodeId = node.id,
            highlightNodeId = highlight.id,
            bounds = highlight.bounds,
            score = score,
            matchedLabel = usableLabel(node.text) ?: usableLabel(node.contentDescription),
            identity = identityOf(node, highlight),
        )
    }

    /** Hard requirements. A node failing any of these can never be a target. */
    private fun isEligible(node: NodeSnapshot, screen: ScreenSnapshot): Boolean = when {
        node.isPassword -> false
        !node.isVisibleToUser -> false
        node.bounds.isEmpty -> false
        node.bounds.isOffScreen(screen.screenWidth, screen.screenHeight) -> false
        else -> true
    }

    /** Points for what the node says, taking the strongest single signal. */
    private fun scoreWording(node: NodeSnapshot, spec: TargetSpec): Int {
        var best = 0

        val normalizedText = TextNormalizer.normalize(node.text)
        if (normalizedText.isNotEmpty()) {
            for (label in spec.labels) {
                val normalizedLabel = TextNormalizer.normalize(label)
                if (normalizedLabel.isEmpty()) continue
                val points = when {
                    normalizedText == normalizedLabel -> SCORE_LABEL_EXACT
                    normalizedText.startsWith(normalizedLabel) -> SCORE_LABEL_PREFIX
                    normalizedText.contains(normalizedLabel) -> SCORE_LABEL_CONTAINS
                    else -> {
                        val overlap = TextNormalizer.tokenOverlap(normalizedLabel, normalizedText)
                        // Require most of the expected wording before a loose
                        // overlap counts for anything at all.
                        if (overlap >= 0.75f) (SCORE_LABEL_OVERLAP_MAX * overlap).toInt() else 0
                    }
                }
                best = maxOf(best, points)
            }
        }

        val normalizedDescription = TextNormalizer.normalize(node.contentDescription)
        if (normalizedDescription.isNotEmpty()) {
            val descriptionCandidates = spec.contentDescriptions + spec.labels
            for (candidate in descriptionCandidates) {
                val normalizedCandidate = TextNormalizer.normalize(candidate)
                if (normalizedCandidate.isEmpty()) continue
                val points = when {
                    normalizedDescription == normalizedCandidate -> SCORE_DESCRIPTION_EXACT
                    normalizedDescription.contains(normalizedCandidate) -> SCORE_DESCRIPTION_CONTAINS
                    else -> 0
                }
                best = maxOf(best, points)
            }
        }

        // A resource id is strong evidence on its own — some controls, like a
        // bare slider, carry no readable wording at all.
        if (best == 0 && matchesViewId(node, spec)) {
            best = SCORE_VIEW_ID + SCORE_CLASS
        }

        return best
    }

    /** Points for what the node is, on top of what it says. */
    private fun scoreStructure(node: NodeSnapshot, spec: TargetSpec): Int {
        var score = 0
        if (matchesViewId(node, spec)) score += SCORE_VIEW_ID
        if (matchesClassName(node, spec)) score += SCORE_CLASS
        return score
    }

    private fun matchesViewId(node: NodeSnapshot, spec: TargetSpec): Boolean {
        val viewId = node.viewId?.lowercase() ?: return false
        return spec.viewIdFragments.any { fragment ->
            fragment.isNotEmpty() && viewId.contains(fragment.lowercase())
        }
    }

    private fun matchesClassName(node: NodeSnapshot, spec: TargetSpec): Boolean {
        val className = node.className?.lowercase() ?: return false
        return spec.expectedClassNames.any { expected ->
            expected.isNotEmpty() && className.contains(expected.lowercase())
        }
    }

    /**
     * The node worth drawing around.
     *
     * Settings rows put their wording on a child `TextView` inside a tappable
     * row, so highlighting the text alone would draw a box around the words
     * rather than the thing to press. Walking up to the nearest interactive
     * ancestor is what makes the highlight match what the person must hit.
     *
     * The walk stops once an ancestor grows past [MAX_ANCESTOR_AREA_RATIO] of
     * the screen, so a match never ends up highlighting a whole scrolling list.
     */
    fun resolveHighlightNode(
        screen: ScreenSnapshot,
        labelNode: NodeSnapshot,
        spec: TargetSpec,
    ): NodeSnapshot {
        if (!spec.requireInteractive) return labelNode

        // Clickable first, across the whole chain, before settling for merely
        // focusable. Taking whichever comes first would let the answer flip
        // between a row and its label from one scan to the next, depending on
        // which flags the framework happened to report — and the outline would
        // jump with it.
        climbTo(screen, labelNode) { it.isClickable }?.let { return it }
        climbTo(screen, labelNode) { it.isFocusable }?.let { return it }
        return labelNode
    }

    /**
     * The nearest node at or above [from] satisfying [predicate], stopping
     * before any ancestor large enough to be the page itself.
     */
    private inline fun climbTo(
        screen: ScreenSnapshot,
        from: NodeSnapshot,
        predicate: (NodeSnapshot) -> Boolean,
    ): NodeSnapshot? {
        val screenArea = (screen.screenWidth.toLong() * screen.screenHeight).coerceAtLeast(1L)
        var current: NodeSnapshot? = from
        var hops = 0

        while (current != null && hops <= MAX_ANCESTOR_HOPS) {
            val node = current
            val area = node.bounds.width.toLong() * node.bounds.height
            if (area > screenArea * MAX_ANCESTOR_AREA_RATIO) return null
            if (predicate(node) && !node.bounds.isEmpty) return node
            current = node.parentId?.let { screen.nodeById(it) }
            hops++
        }
        return null
    }

    /**
     * Builds the identity of a match.
     *
     * The wording comes from the node that carried it and the structure from
     * the node being outlined, so the identity stays the same whether the
     * label sits on the row or on a child of it.
     */
    private fun identityOf(labelNode: NodeSnapshot, highlightNode: NodeSnapshot) = TargetIdentity(
        windowId = highlightNode.windowId,
        viewId = highlightNode.viewId ?: labelNode.viewId,
        normalizedText = TextNormalizer.normalize(labelNode.text),
        normalizedDescription = TextNormalizer.normalize(labelNode.contentDescription),
        className = highlightNode.className,
    )

    /**
     * A label only worth repeating back to the person.
     *
     * Screen text is used in the instruction ("Tap Display & touch") so the
     * card names things the way their phone does. That is only safe for short,
     * label-shaped strings: a banner or a summary sentence would turn the
     * instruction into nonsense, so anything sentence-like is refused and the
     * guide's own wording is used instead.
     */
    private fun usableLabel(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_LABEL_LENGTH) return null
        if (trimmed.contains(". ") || trimmed.contains('\n')) return null
        return trimmed
    }

    private const val MAX_LABEL_LENGTH = 40
    private const val MAX_ANCESTOR_HOPS = 6
    private const val MAX_ANCESTOR_AREA_RATIO = 0.6
}
