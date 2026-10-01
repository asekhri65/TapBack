package com.aaravsekhri.dot.guide.model

import com.aaravsekhri.dot.guide.matching.TextNormalizer

/**
 * What the person tapped, copied out while the event is still valid.
 *
 * This exists because the old progression path could not work. It waited for
 * the click, then read the screen, then looked for the tapped node in what it
 * had just read — but a tap on a settings row navigates, so by then the node
 * was gone and so was the target. The lookup returned null every time and the
 * guide never left step one.
 *
 * So the useful facts are taken straight from the event, at the moment it
 * arrives, and everything else is done with this. No `AccessibilityNodeInfo`
 * is kept: those are only valid for the duration of the callback, and holding
 * one past that is a use-after-free with extra steps.
 *
 * Ancestor labels are included because a settings row's clickable container
 * usually carries no text of its own — the title belongs to a child, and the
 * click arrives on the parent.
 */
data class ClickDescriptor(
    val packageName: String?,
    val className: String?,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    /** Text found on this node, its ancestors and their immediate children. */
    val labels: List<String>,
) {

    private val normalizedLabels by lazy {
        labels.map(TextNormalizer::normalize).filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Whether this tap was on the control [spec] describes.
     *
     * Exact normalised labels only. A tap is the one irreversible thing in a
     * guide — it is what moves someone to another screen — so "something
     * roughly like the target" is not good enough to act on.
     */
    fun matches(spec: TargetSpec): Boolean {
        val approved = approvedLabelsOf(spec)
        if (approved.isEmpty()) return false
        return normalizedLabels.any { it in approved }
    }

    private fun approvedLabelsOf(spec: TargetSpec): Set<String> =
        spec.identityLabels.ifEmpty { spec.labels }
            .map(TextNormalizer::normalize)
            .filter { it.isNotEmpty() }
            .toSet()

    /** Short, and free of anything typed into a field. Safe for a debug log. */
    override fun toString(): String =
        "Click(id=$viewId cls=${className?.substringAfterLast('.')} labels=$normalizedLabels)"
}
