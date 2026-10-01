package com.aaravsekhri.dot.guide.continuity

import com.aaravsekhri.dot.guide.matching.TextNormalizer
import com.aaravsekhri.dot.guide.model.ScreenSnapshot

/**
 * What makes a screen recognisably itself.
 *
 * Deliberately built from things a person would use to answer "am I still on
 * the same page": the app, the step being worked on, the control being pointed
 * at, and the headings around it. Not from window ids or node identity, both of
 * which Android throws away for reasons that have nothing to do with
 * navigation — changing the font size replaces the window without the person
 * going anywhere.
 */
data class ScreenFingerprint(
    val packageName: String?,
    val stepIndex: Int,
    /** Normalised wording of the control being pointed at, when known. */
    val targetLabel: String,
    /** The control's class, e.g. a SeekBar. */
    val targetRole: String?,
    /** Normalised headings and setting titles on screen, for comparison. */
    val landmarks: Set<String>,
) {
    /**
     * How much of this screen's wording is shared with [other], 0f to 1f.
     *
     * A font-size change re-lays-out every node on the page but leaves what it
     * says almost untouched, so a high overlap is strong evidence the person
     * has not gone anywhere.
     */
    fun landmarkOverlapWith(other: ScreenFingerprint): Float {
        if (landmarks.isEmpty() || other.landmarks.isEmpty()) return 0f
        val shared = landmarks.count(other.landmarks::contains)
        return shared.toFloat() / minOf(landmarks.size, other.landmarks.size)
    }

    companion object {
        /** Wording long enough to be a heading rather than a value or a digit. */
        private const val MIN_LANDMARK_LENGTH = 3

        /** Enough landmarks to compare, without walking the whole tree. */
        private const val MAX_LANDMARKS = 24

        fun of(
            screen: ScreenSnapshot,
            stepIndex: Int,
            targetLabel: String?,
            targetRole: String?,
        ) = ScreenFingerprint(
            packageName = screen.packageName,
            stepIndex = stepIndex,
            targetLabel = TextNormalizer.normalize(targetLabel),
            targetRole = targetRole,
            landmarks = screen.nodes
                .asSequence()
                .filter { it.isVisibleToUser && !it.bounds.isEmpty }
                .mapNotNull { node ->
                    TextNormalizer.normalize(node.text)
                        .takeIf { it.length >= MIN_LANDMARK_LENGTH }
                }
                .distinct()
                .take(MAX_LANDMARKS)
                .toSet(),
        )
    }
}
