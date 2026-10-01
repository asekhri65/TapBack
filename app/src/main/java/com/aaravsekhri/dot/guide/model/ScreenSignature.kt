package com.aaravsekhri.dot.guide.model

import com.aaravsekhri.dot.guide.matching.TextNormalizer

/**
 * A screen recognised by what is written on it.
 *
 * Window identifiers are no use for this. Settings reuses the same activity
 * and often the same window id when it navigates, so "the window changed" and
 * "the person got somewhere new" are different questions, and only the second
 * one matters. What does change is the text: the page after tapping Display &
 * touch has a brightness slider on it and the one before does not.
 *
 * [requiredLabels] must all be present. [forbiddenLabels] must all be absent,
 * which is what separates a destination from the page it was reached from when
 * the two share most of their content.
 *
 * Labels are compared after normalisation, so "Display & touch" and
 * "Display and touch" are the same label and neither is a substring match.
 */
data class ScreenSignature(
    val id: String,
    val requiredLabels: List<String>,
    val forbiddenLabels: List<String> = emptyList(),
) {

    private val required by lazy { requiredLabels.map(TextNormalizer::normalize).filter { it.isNotEmpty() } }
    private val forbidden by lazy { forbiddenLabels.map(TextNormalizer::normalize).filter { it.isNotEmpty() } }

    /** True when [screen] is this page. */
    fun matches(screen: ScreenSnapshot): Boolean {
        if (required.isEmpty()) return false
        val present = labelsOf(screen)
        if (!present.containsAll(required)) return false
        return forbidden.none { it in present }
    }

    /** How many of the required labels are present, for tracing a near miss. */
    fun presentCount(screen: ScreenSnapshot): Int {
        val present = labelsOf(screen)
        return required.count { it in present }
    }

    private fun labelsOf(screen: ScreenSnapshot): Set<String> {
        val labels = HashSet<String>(screen.nodes.size)
        screen.nodes.forEach { node ->
            TextNormalizer.normalize(node.text).takeIf { it.isNotEmpty() }?.let(labels::add)
            TextNormalizer.normalize(node.contentDescription)
                .takeIf { it.isNotEmpty() }
                ?.let(labels::add)
        }
        return labels
    }

    override fun toString(): String = "ScreenSignature($id)"
}

/**
 * What has to happen before a step counts as done.
 *
 * Previously there was one rule, applied everywhere: the person tapped
 * something that matched this step's target. That rule could not be satisfied,
 * because by the time Dot looked at the screen the tap had already navigated
 * away and neither the tapped control nor the target existed any more. Making
 * completion explicit and per-step means each step can say what finishing
 * actually looks like for it.
 */
sealed interface StepCompletionRule {

    /** The person activated this step's exact control. */
    data object ExactTargetClicked : StepCompletionRule

    /** The page this step leads to is now in front. */
    data class DestinationAppeared(val signature: ScreenSignature) : StepCompletionRule

    /** Any one of these is enough. */
    data class Either(val rules: List<StepCompletionRule>) : StepCompletionRule

    /** Only the person can say when a value is right. */
    data object UserConfirms : StepCompletionRule
}
