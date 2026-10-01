package com.aaravsekhri.dot.guide.matching

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import com.aaravsekhri.dot.guide.model.TargetSpec

/**
 * The hard requirement a candidate must meet before Dot will point at it.
 *
 * Scoring alone is not safe enough. On the Pixel Settings list the
 * Accessibility row is summarised "Display, interaction, audio" — which
 * contains the word the Display step is looking for, is clickable, is enabled,
 * and is the same shape as the row that was wanted. It out-scored the
 * threshold and Dot told somebody to tap it. Sending a person to the wrong
 * setting is a worse failure than admitting the right one cannot be seen, so
 * the score is now only permission to be considered.
 *
 * This is the gate: the region must actually carry the name of the thing being
 * pointed at, spelled out in full. Nothing about position, class, clickability,
 * size, parentage or proximity to the last known location can substitute for
 * it.
 */
object TargetIdentityGate {

    /**
     * The wording that identifies this target.
     *
     * A step may name them explicitly; otherwise the labels it matches on serve,
     * which keeps every existing guide gated without extra ceremony.
     */
    fun approvedLabels(spec: TargetSpec): List<String> =
        spec.identityLabels.ifEmpty { spec.labels }

    /**
     * Whether [region] contains a node that is unmistakably this target.
     *
     * Exact equality after normalisation, never a substring: "display
     * interaction audio" is not "display", however much of the word it shares.
     */
    fun regionCarriesIdentity(
        screen: ScreenSnapshot,
        region: Bounds,
        spec: TargetSpec,
    ): Boolean {
        val approved = approvedNormalized(spec)

        // Nothing to identify it by. Such targets are gated structurally
        // instead, by requireStructuralMatch.
        if (approved.isEmpty() && identityFragments(spec).isEmpty()) {
            return spec.requireStructuralMatch
        }

        return screen.nodes.any { node ->
            node.isVisibleToUser &&
                !node.bounds.isEmpty &&
                region.contains(node.bounds) &&
                proves(node, approved, spec)
        }
    }

    /**
     * Whether this individual node is an exact, approved name for the target.
     *
     * Used to reject a candidate before a region is even built around it.
     */
    fun nodeCarriesIdentity(
        node: com.aaravsekhri.dot.guide.model.NodeSnapshot,
        spec: TargetSpec,
    ): Boolean = proves(node, approvedNormalized(spec), spec)

    /**
     * Whether one node proves the target's identity.
     *
     * Two things count, and only two. The control's name, spelled out exactly —
     * "display interaction audio" is not "display", however much of the word it
     * shares. Or its resource id, which names the control in the app's own
     * source and is the only evidence available for a widget that carries no
     * readable label at all, such as a bare slider.
     *
     * Position, class, clickability, size, parentage and proximity to the last
     * known location are all deliberately absent: those are exactly the signals
     * that agreed when the wrong row was chosen.
     */
    private fun proves(
        node: com.aaravsekhri.dot.guide.model.NodeSnapshot,
        approved: Set<String>,
        spec: TargetSpec,
    ): Boolean {
        if (TextNormalizer.normalize(node.text) in approved) return true
        if (TextNormalizer.normalize(node.contentDescription) in approved) return true

        val viewId = node.viewId?.lowercase() ?: return false
        return identityFragments(spec).any { fragment ->
            viewId.contains(fragment)
        }
    }

    /** Resource-id fragments trusted to prove identity, lower-cased. */
    private fun identityFragments(spec: TargetSpec): List<String> =
        spec.identityViewIdFragments.ifEmpty { spec.viewIdFragments }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }

    private fun approvedNormalized(spec: TargetSpec): Set<String> =
        approvedLabels(spec)
            .map(TextNormalizer::normalize)
            .filter { it.isNotEmpty() }
            .toSet()
}
