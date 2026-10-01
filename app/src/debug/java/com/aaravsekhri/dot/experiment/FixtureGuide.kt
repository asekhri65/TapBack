package com.aaravsekhri.dot.experiment

import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.guide.model.GuideDefinition
import com.aaravsekhri.dot.guide.model.GuideStep
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.model.StepCompletionRule
import com.aaravsekhri.dot.guide.model.TargetRegionStrategy
import com.aaravsekhri.dot.guide.model.TargetSpec

/**
 * A one-step guide that points at the fixture's known row.
 *
 * Debug builds only. It exists so an acceptance run can drive the whole
 * production path — matching, settling, following, clipping, drawing — against
 * a screen that publishes where its target really is. Every other way of
 * checking the outline ends up comparing Dot's drawing to Dot's own idea of
 * where the target was, which cannot fail and therefore proves nothing.
 */
object FixtureGuide {

    const val ID = "debug_fixture"

    val definition = GuideDefinition(
        id = ID,
        titleRes = R.string.guide_text_size_title,
        steps = listOf(
            GuideStep(
                expectedPackages = setOf("com.aaravsekhri.dot"),
                target = TargetSpec(
                    labels = listOf(FixtureScrollActivity.TARGET_LABEL),
                    identityLabels = listOf(FixtureScrollActivity.TARGET_LABEL),
                    regionStrategy = TargetRegionStrategy.CLICKABLE_PARENT,
                ),
                instructionRes = R.string.guide_step_tap,
                targetNameRes = R.string.guide_text_size_step1_target,
                fallbackRes = R.string.guide_text_size_step1_fallback,
                expectedDirection = ScrollDirection.Down,
                completion = StepCompletionRule.UserConfirms,
            ),
        ),
    )
}
