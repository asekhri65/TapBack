package com.aaravsekhri.dot.guide.catalog

import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.guide.matching.TextNormalizer
import com.aaravsekhri.dot.guide.model.GuideDefinition
import com.aaravsekhri.dot.guide.model.GuideStep
import com.aaravsekhri.dot.guide.model.ScreenSignature
import com.aaravsekhri.dot.guide.model.StepCompletionRule
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.model.TargetRegionStrategy
import com.aaravsekhri.dot.guide.model.StepAction
import com.aaravsekhri.dot.guide.model.TargetSpec

/**
 * The guides Dot can run.
 *
 * Everything here is data. The engine has no knowledge of text size or of
 * Settings — adding a guide later means adding another definition, not more
 * branching in the engine.
 */
object GuideCatalog {

    /** Packages that ship the system Settings app across common builds. */

    /**
     * The three pages this guide walks through, as they actually are.
     *
     * Read off the accessibility hierarchy of a Pixel 8 emulator on API 35
     * rather than guessed. Each signature names labels that are present on its
     * own page and, where two pages could be confused, labels that must be
     * absent — the root Settings list and the Display page both contain a great
     * deal of text, and "Display size and text" is the thing that tells them
     * apart.
     */
    private val SETTINGS_ROOT = ScreenSignature(
        id = "settings-root",
        requiredLabels = listOf("Search settings"),
        forbiddenLabels = listOf("Brightness level", "Font size"),
    )

    private val DISPLAY_AND_TOUCH = ScreenSignature(
        id = "display-and-touch",
        // Both of these are at the top of the page, which matters: a signature
        // is read the moment the page arrives, and anything below the fold is
        // not in the accessibility tree yet. Requiring "Display size and text"
        // here looked reasonable and could never match, because it only exists
        // once the person has scrolled to it.
        requiredLabels = listOf("Brightness level", "Screen timeout"),
        forbiddenLabels = listOf("Search settings", "Font size"),
    )

    private val DISPLAY_SIZE_AND_TEXT = ScreenSignature(
        id = "display-size-and-text",
        requiredLabels = listOf("Font size", "Make text bigger or smaller"),
    )

    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.google.android.settings",
        // Samsung's Settings lives under its own package on many devices.
        "com.samsung.android.settings",
    )

    const val TEXT_SIZE_GUIDE_ID = "text_size"

    /**
     * The flagship guide: make on-screen text bigger.
     *
     * It starts at the top of Settings rather than deep-linking to the final
     * control, because the point is that the person learns the route and can
     * walk it again without Dot.
     */
    val textSize = GuideDefinition(
        id = TEXT_SIZE_GUIDE_ID,
        titleRes = R.string.guide_text_size_title,
        steps = listOf(
            // Step 1 — the Display row on the Settings home screen.
            GuideStep(
                expectedPackages = SETTINGS_PACKAGES,
                target = TargetSpec(
                    labels = listOf(
                        "Display",
                        "Display & touch",
                        "Display and brightness",
                        "Display & brightness",
                    ),
                    contentDescriptions = listOf("Display"),
                    viewIdFragments = listOf("display"),
                    expectedClassNames = emptyList(),
                    regionStrategy = TargetRegionStrategy.CLICKABLE_PARENT,
                ),
                instructionRes = R.string.guide_step_tap,
                targetNameRes = R.string.guide_text_size_step1_target,
                fallbackRes = R.string.guide_text_size_step1_fallback,
                action = StepAction.Tap,
                // Settings puts Display well below the fold on most phones.
                expectedDirection = ScrollDirection.Down,
                completion = StepCompletionRule.Either(
                    listOf(
                        StepCompletionRule.ExactTargetClicked,
                        StepCompletionRule.DestinationAppeared(DISPLAY_AND_TOUCH),
                    ),
                ),
                expectedDestination = DISPLAY_AND_TOUCH,
                screen = SETTINGS_ROOT,
            ),
            // Step 2 — the text/display size row inside Display.
            GuideStep(
                expectedPackages = SETTINGS_PACKAGES,
                target = TargetSpec(
                    labels = listOf(
                        "Display size and text",
                        "Font and display size",
                        "Font size and style",
                        "Screen zoom and font",
                    ),
                    // The row is called "Display size and text" on this
                    // emulator. The others are what the same row is called on
                    // other makers' builds; none of them may stand in for the
                    // Font size control one page deeper, so they are all
                    // identity-grade and "Font size" is deliberately not here.
                    identityLabels = listOf(
                        "Display size and text",
                        "Font and display size",
                        "Font size and style",
                        "Screen zoom and font",
                    ),
                    contentDescriptions = listOf("Display size and text"),
                    viewIdFragments = listOf("display_size", "font_size"),
                    expectedClassNames = emptyList(),
                    regionStrategy = TargetRegionStrategy.CLICKABLE_PARENT,
                ),
                instructionRes = R.string.guide_step_tap_next,
                targetNameRes = R.string.guide_text_size_step2_target,
                fallbackRes = R.string.guide_text_size_step2_fallback,
                action = StepAction.Tap,
                // Text size sits near the bottom of the Display page.
                expectedDirection = ScrollDirection.Down,
                completion = StepCompletionRule.Either(
                    listOf(
                        StepCompletionRule.ExactTargetClicked,
                        StepCompletionRule.DestinationAppeared(DISPLAY_SIZE_AND_TEXT),
                    ),
                ),
                expectedDestination = DISPLAY_SIZE_AND_TEXT,
                screen = DISPLAY_AND_TOUCH,
            ),
            // Step 3 — the font size control itself. Dot points at it and
            // explains; the person does the dragging.
            GuideStep(
                expectedPackages = SETTINGS_PACKAGES,
                target = TargetSpec(
                    labels = listOf("Font size", "Text size", "Make text bigger"),
                    contentDescriptions = listOf("Font size", "Text size"),
                    viewIdFragments = listOf("font_size", "text_size", "seekbar", "slider"),
                    // "seekbar" and "slider" help narrow the search but name
                    // nothing: the Display size control on this very page
                    // matches both. Only these two identify Font size itself.
                    identityViewIdFragments = listOf("font_size", "text_size"),
                    expectedClassNames = listOf("SeekBar", "ProgressBar", "Slider"),
                    // A slider is dragged, not tapped, and is not always
                    // reported as clickable.
                    requireInteractive = false,
                    // This screen also carries banners and summaries that say
                    // "font size"; only the actual slider will do.
                    requireStructuralMatch = true,
                    // The slider is one part of the setting. Pointing at it
                    // alone draws a box through the words that explain it, so
                    // the whole setting is outlined together.
                    regionStrategy = TargetRegionStrategy.COMPOSITE_SETTING_ROW,
                    groupLabels = listOf(
                        "Font size",
                        "Text size",
                        "Make text bigger or smaller",
                        "Make text smaller or bigger",
                    ),
                    groupClassNames = listOf("SeekBar", "ImageView", "Button", "TextView"),
                    // Where the setting stops. Display size is the next one
                    // down on this page, and must never be swept in.
                    boundaryLabels = listOf(
                        "Display size",
                        "Bold text",
                        "High contrast text",
                        "Preview",
                    ),
                ),
                instructionRes = R.string.guide_step_drag,
                targetNameRes = R.string.guide_text_size_step3_target,
                fallbackRes = R.string.guide_text_size_step3_fallback,
                action = StepAction.Adjust,
                // The slider is below the preview panel that heads the page.
                expectedDirection = ScrollDirection.Down,
                // Nobody but the person can say when the text is big enough.
                completion = StepCompletionRule.UserConfirms,
                screen = DISPLAY_SIZE_AND_TEXT,
            ),
        ),
    )

    private val all = listOf(textSize)

    /**
     * Guides added at runtime by the benchmark harness.
     *
     * Empty in every release build: the only thing that adds to it is a
     * receiver declared in the debug manifest. It exists so an acceptance run
     * can point the real guide at the test fixture and grade what it draws
     * against the fixture's own ground truth, which is the only way to measure
     * the finished article rather than one component of it.
     */
    private val registered = java.util.concurrent.CopyOnWriteArrayList<GuideDefinition>()

    fun registerForDebug(definition: GuideDefinition) {
        registered.removeAll { it.id == definition.id }
        registered += definition
    }

    fun byId(id: String?): GuideDefinition? =
        all.firstOrNull { it.id == id } ?: registered.firstOrNull { it.id == id }

    /**
     * The guide that answers a request, or null when Dot has nothing for it.
     *
     * This is a deliberately plain keyword match, not interpretation: Phase 2
     * ships one guide, and pretending to understand more than that would set
     * the wrong expectation.
     */
    fun forRequest(request: String): GuideDefinition? {
        val normalized = TextNormalizer.normalize(request)
        if (normalized.isEmpty()) return null

        val mentionsText = TEXT_WORDS.any { normalized.contains(it) }
        val mentionsBigger = SIZE_WORDS.any { normalized.contains(it) }
        return if (mentionsText && mentionsBigger) textSize else null
    }

    private val TEXT_WORDS = listOf("text", "font", "words", "writing", "letters", "print")
    private val SIZE_WORDS = listOf("bigger", "larger", "big", "large", "size", "zoom", "readable")
}
