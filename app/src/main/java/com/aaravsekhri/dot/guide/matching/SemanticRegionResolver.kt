package com.aaravsekhri.dot.guide.matching

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeDescriptor
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.SemanticTargetRegion
import com.aaravsekhri.dot.guide.model.TargetRegionStrategy
import com.aaravsekhri.dot.guide.model.TargetSpec

/**
 * Works out what area of the screen a step is really talking about.
 *
 * Android does not describe a setting as one thing. "Font size" on the Pixel
 * Display page is a title, a line of explanation, a slider and two buttons —
 * five sibling nodes with no container that means anything. Outlining whichever
 * one matched draws a box around part of a setting and a line through the rest
 * of it, which is not what anybody means when they point at something.
 *
 * So the matcher finds the node, and this decides how much around it belongs.
 * The rules are generic and come from the step's [TargetSpec]; nothing here
 * knows about font size in particular.
 */
object SemanticRegionResolver {

    /**
     * Builds the region for [anchor] under [spec]'s strategy.
     *
     * Returns null only when the anchor itself is unusable.
     */
    fun resolve(
        screen: ScreenSnapshot,
        anchor: NodeSnapshot,
        highlightNode: NodeSnapshot,
        spec: TargetSpec,
        identity: TargetIdentity,
        label: String?,
    ): SemanticTargetRegion? {
        if (anchor.bounds.isEmpty) return null

        val region = when (spec.regionStrategy) {
            TargetRegionStrategy.EXACT_NODE ->
                single(identity, anchor, label)

            TargetRegionStrategy.CLICKABLE_PARENT ->
                single(identity, highlightNode, label, anchorBounds = anchor.bounds)

            TargetRegionStrategy.COMMON_ANCESTOR ->
                commonAncestor(screen, anchor, spec, identity, label)

            TargetRegionStrategy.COMPOSITE_SETTING_ROW ->
                composite(screen, anchor, spec, identity, label)
        }

        // Checked again on the finished region, not only on the node that
        // started it. Growing a region can move it off the thing that
        // identified it — and a region that no longer contains the target's
        // own name is not the target, whatever built it.
        if (!TargetIdentityGate.regionCarriesIdentity(screen, region.boundsInScreen, spec)) {
            return null
        }
        return region
    }

    // ------------------------------------------------------------- simple

    private fun single(
        identity: TargetIdentity,
        node: NodeSnapshot,
        label: String?,
        anchorBounds: Bounds = node.bounds,
    ) = SemanticTargetRegion(
        identity = identity,
        boundsInScreen = node.bounds,
        anchorBounds = anchorBounds,
        matchedNodes = listOf(node.toDescriptor()),
        label = label,
    )

    /**
     * The smallest ancestor that still belongs to this setting.
     *
     * Climbs while the ancestor stays a sensible size and contains nothing from
     * a neighbouring setting, and stops as soon as either stops being true.
     */
    private fun commonAncestor(
        screen: ScreenSnapshot,
        anchor: NodeSnapshot,
        spec: TargetSpec,
        identity: TargetIdentity,
        label: String?,
    ): SemanticTargetRegion {
        val boundaries = boundaryNodes(screen, spec, anchor)
        val screenArea = (screen.screenWidth.toLong() * screen.screenHeight).coerceAtLeast(1L)

        var best = anchor
        var current = anchor.parentId?.let { screen.nodeById(it) }
        var hops = 0

        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            val node = current
            val area = node.bounds.width.toLong() * node.bounds.height
            if (area > screenArea * MAX_CONTAINER_AREA_RATIO) break
            if (boundaries.any { node.bounds.intersects(it.bounds) }) break
            best = node
            current = node.parentId?.let { screen.nodeById(it) }
            hops++
        }

        return SemanticTargetRegion(
            identity = identity,
            boundsInScreen = best.bounds,
            anchorBounds = anchor.bounds,
            matchedNodes = listOf(best.toDescriptor()),
            label = label,
        )
    }

    // ---------------------------------------------------------- composite

    /**
     * Gathers every node that reads as part of the same setting.
     *
     * Starts from the anchor and grows outwards, taking in anything that sits
     * close above or below and lines up horizontally with what is already
     * there. Growth stops at the nodes that begin the next setting, so
     * pointing at Font size never swallows Display size underneath it.
     */
    private fun composite(
        screen: ScreenSnapshot,
        anchor: NodeSnapshot,
        spec: TargetSpec,
        identity: TargetIdentity,
        label: String?,
    ): SemanticTargetRegion {
        val boundaries = boundaryNodes(screen, spec, anchor)

        // How far above and below the setting may reach, before running into
        // whatever the boundaries say comes next.
        val ceiling = boundaries
            .filter { it.bounds.bottom <= anchor.bounds.top }
            .maxOfOrNull { it.bounds.bottom } ?: 0
        val floor = boundaries
            .filter { it.bounds.top >= anchor.bounds.bottom }
            .minOfOrNull { it.bounds.top } ?: screen.screenHeight

        val candidates = screen.nodes.filter { candidate ->
            candidate.id != anchor.id &&
                candidate.isVisibleToUser &&
                !candidate.bounds.isEmpty &&
                !candidate.isPassword &&
                candidate.bounds.top >= ceiling &&
                candidate.bounds.bottom <= floor &&
                isPartOfSetting(candidate, spec)
        }

        val members = mutableListOf(anchor)
        var region = anchor.bounds
        val gap = (screen.screenHeight * MEMBER_GAP_RATIO).toInt().coerceAtLeast(MIN_MEMBER_GAP_PX)

        // Repeat until nothing more joins: a title can pull in a subtitle which
        // then pulls in the slider, even though the title and slider are far
        // enough apart that neither would have reached the other directly.
        var grew = true
        while (grew) {
            grew = false
            for (candidate in candidates) {
                if (members.any { it.id == candidate.id }) continue
                if (!belongsWith(region, candidate.bounds, gap)) continue
                // Never grow past a boundary, whatever the geometry says.
                val grown = region.union(candidate.bounds)
                if (boundaries.any { grown.intersects(it.bounds) }) continue
                members += candidate
                region = grown
                grew = true
            }
        }

        // Prefer a real container over a union of parts when the hierarchy
        // offers one. It picks up the pieces that carry no readable label of
        // their own — the minus and plus either side of a slider are typically
        // children of the row and nothing else identifies them.
        val container = commonContainerOf(screen, members, boundaries)
        val bounds = container?.bounds?.union(region) ?: region

        return SemanticTargetRegion(
            identity = identity,
            boundsInScreen = bounds,
            anchorBounds = anchor.bounds,
            matchedNodes = members.map { it.toDescriptor() },
            label = label,
        )
    }

    /**
     * The deepest node that contains every member, if it is a sensible size and
     * holds nothing belonging to the next setting.
     *
     * Returns null when the parts have no useful container — which happens
     * often enough that the union has to remain the fallback.
     */
    private fun commonContainerOf(
        screen: ScreenSnapshot,
        members: List<NodeSnapshot>,
        boundaries: List<NodeSnapshot>,
    ): NodeSnapshot? {
        if (members.size < 2) return null

        val chains = members.map { ancestorChain(screen, it) }
        val shared = chains.reduce { acc, chain -> acc.filter(chain::contains) }

        val screenArea = (screen.screenWidth.toLong() * screen.screenHeight).coerceAtLeast(1L)
        return shared
            .mapNotNull(screen::nodeById)
            .firstOrNull { candidate ->
                val area = candidate.bounds.width.toLong() * candidate.bounds.height
                area <= screenArea * MAX_CONTAINER_AREA_RATIO &&
                    boundaries.none { candidate.bounds.intersects(it.bounds) }
            }
    }

    /** Ids from a node up to the root, nearest first. */
    private fun ancestorChain(screen: ScreenSnapshot, node: NodeSnapshot): List<Int> {
        val chain = mutableListOf<Int>()
        var current = node.parentId?.let(screen::nodeById)
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            chain += current.id
            current = current.parentId?.let(screen::nodeById)
            hops++
        }
        return chain
    }

    /**
     * Whether a node is close enough, and lined up well enough, to be part of
     * the same setting as what has been gathered so far.
     *
     * A setting is laid out two ways at once. Its title, its description and
     * its control are stacked, each below the last and sharing the same
     * columns. Its minus and plus buttons sit beside the slider, sharing the
     * same rows. Both count, and a rule that only knew about stacking left the
     * buttons outside the outline.
     */
    private fun belongsWith(region: Bounds, candidate: Bounds, maxGap: Int): Boolean =
        isStackedWith(region, candidate, maxGap) || isBesideOf(region, candidate, maxGap)

    /** Directly above or below, sharing most of its columns. */
    private fun isStackedWith(region: Bounds, candidate: Bounds, maxGap: Int): Boolean {
        val verticalGap = gapBetween(region.top, region.bottom, candidate.top, candidate.bottom)
        if (verticalGap > maxGap) return false

        val overlap = overlapBetween(region.left, region.right, candidate.left, candidate.right)
        val narrower = minOf(region.width, candidate.width).coerceAtLeast(1)
        return overlap.toFloat() / narrower >= MIN_OVERLAP
    }

    /** Directly to one side, sharing most of its rows. */
    private fun isBesideOf(region: Bounds, candidate: Bounds, maxGap: Int): Boolean {
        val horizontalGap = gapBetween(region.left, region.right, candidate.left, candidate.right)
        if (horizontalGap > maxGap) return false

        val overlap = overlapBetween(region.top, region.bottom, candidate.top, candidate.bottom)
        val shorter = minOf(region.height, candidate.height).coerceAtLeast(1)
        return overlap.toFloat() / shorter >= MIN_OVERLAP
    }

    /** Distance between two spans, or 0 when they overlap. */
    private fun gapBetween(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int = when {
        bStart >= aEnd -> bStart - aEnd
        bEnd <= aStart -> aStart - bEnd
        else -> 0
    }

    /** How much two spans share. */
    private fun overlapBetween(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)

    /** Whether a node has the wording or the role of a part of this setting. */
    private fun isPartOfSetting(node: NodeSnapshot, spec: TargetSpec): Boolean {
        val normalizedText = TextNormalizer.normalize(node.text)
        val normalizedDescription = TextNormalizer.normalize(node.contentDescription)

        val wordingMatches = (spec.groupLabels + spec.labels).any { candidate ->
            val normalized = TextNormalizer.normalize(candidate)
            normalized.isNotEmpty() &&
                (normalizedText.contains(normalized) || normalizedDescription.contains(normalized))
        }
        if (wordingMatches) return true

        val className = node.className?.lowercase() ?: return false
        return spec.groupClassNames.any { it.isNotEmpty() && className.contains(it.lowercase()) }
    }

    /**
     * The nodes that mark where this setting ends.
     *
     * Everything the step lists under [TargetSpec.boundaryLabels] — the title
     * of the setting that follows, typically — wherever it appears on screen.
     */
    private fun boundaryNodes(
        screen: ScreenSnapshot,
        spec: TargetSpec,
        anchor: NodeSnapshot,
    ): List<NodeSnapshot> {
        if (spec.boundaryLabels.isEmpty()) return emptyList()
        return screen.nodes.filter { node ->
            node.id != anchor.id &&
                node.isVisibleToUser &&
                !node.bounds.isEmpty &&
                spec.boundaryLabels.any { boundary ->
                    val normalized = TextNormalizer.normalize(boundary)
                    normalized.isNotEmpty() &&
                        TextNormalizer.normalize(node.text) == normalized
                }
        }
    }

    private fun NodeSnapshot.toDescriptor() = NodeDescriptor(
        nodeId = id,
        viewId = viewId,
        className = className,
        bounds = bounds,
    )

    /** A container bigger than this is the page, not a setting. */
    private const val MAX_CONTAINER_AREA_RATIO = 0.45

    /** Vertical reach when gathering a setting, as a fraction of screen height. */
    private const val MEMBER_GAP_RATIO = 0.035

    /** A floor for the above, for very short screens. */
    private const val MIN_MEMBER_GAP_PX = 24

    /** How much of the smaller node must line up with the region. */
    private const val MIN_OVERLAP = 0.5f

    private const val MAX_ANCESTOR_HOPS = 4
}
