package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.model.Bounds

/**
 * One control the guide can currently see.
 *
 * [id] is scoped to the observation it was born in and means nothing outside
 * it. Accessibility node ids are positions in a traversal: the same id on the
 * next reading is whatever happens to be walked in that place, which after a
 * scroll is a different row. Every decision that names one of these is
 * re-grounded against a fresh reading before anything is drawn.
 */
data class CandidateControl(
    val id: String,
    val label: String,
    val description: String,
    val role: String,
    val bounds: Bounds,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val parentId: String?,
    /** Null when the control is not a switch or checkbox at all. */
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val focused: Boolean = false,
    /** A slider's or progress bar's position. */
    val value: String = "",
    /** A field's placeholder. */
    val hint: String = "",
    /** Scrolling containers only. */
    val horizontal: Boolean = false,
    /**
     * What to draw, when that is not [bounds] itself — a login field's
     * bordered box. Decided by [ControlOutline] from this reading.
     */
    val outline: Bounds? = null,
    /** Set when [ControlOutline] found no boundary worth drawing: why, as a fixed phrase. */
    val wordsOnly: String? = null,
    /** For a form's box: how ready it is, as far as the phone can tell. See [Readiness]. */
    val readiness: Readiness = Readiness.Unknown,
    /** The app's own error message on this box, if it set one. */
    val error: String = "",
    /** Which box this is for [FormMemory], within this session; blank for anything else. Never sent. */
    val fieldKey: String = "",
) {
    /** The rectangle an outline goes round. */
    val drawBounds: Bounds get() = outline ?: bounds

    /**
     * What identifies this control to a person, and to a matcher.
     *
     * An empty field is named by its placeholder, which is the only thing a
     * person could call it — "the Search box". A field with text in it is
     * still named by its placeholder when it has one, so typing into it does
     * not turn it into a different control halfway through a step.
     */
    val naming: String
        get() = if (editable && hint.isNotBlank()) hint else label.ifBlank { description.ifBlank { hint } }
}

/**
 * One settled reading of the screen, frozen.
 *
 * Immutable on purpose: a decision arrives some hundreds of milliseconds after
 * the observation that prompted it, and the only way to tell whether it is
 * still about this screen is to have kept the screen it was about.
 */
data class Observation(
    val sessionId: String,
    val observationId: String,
    val takenAtMs: Long,
    /** Rises whenever the screen changes underneath; a stale reading carries an old one. */
    val screenGeneration: Long,
    val packageName: String,
    val windowTitle: String,
    val windowId: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val candidates: List<CandidateControl>,
    val scrollContainerIds: List<String>,
    val truncated: Boolean,
    /** JPEG of the settled screen, when one could be taken and was permitted. */
    val screenshotJpeg: ByteArray? = null,
    val keyboardVisible: Boolean = false,
    /** The phone's own judgement that this is the launcher's home screen. */
    val homeScreen: Boolean = false,
    /** Anything the model should know about the picture, such as masked areas. */
    val screenshotNote: String = "",
    /**
     * Short words the app shows about the form — an error, a warning, the
     * age a date works out to — that are not controls and so are not among
     * [candidates]. Without them a rejected step looked unchanged.
     */
    val notices: List<String> = emptyList(),
    /**
     * In a lesson: words on the page that are information, not controls —
     * opening hours, an address, a journey time — so a teaching point can
     * name exactly what to read. Ids start with "t". Never tapped.
     */
    val readable: List<CandidateControl> = emptyList(),
) {
    fun candidate(id: String): CandidateControl? = candidates.firstOrNull { it.id == id } ?: readable.firstOrNull { it.id == id }

    /**
     * What makes two readings "the same screen" for deduplication.
     *
     * The app, the window, what the controls say, and what state they are in —
     * so that a switch flipping or a field filling in is something new to ask
     * about, while a list redrawing itself is not. Position is not part of it:
     * a page scrolled by ten pixels is the same page and does not deserve
     * another billable request.
     */
    val contentKey: String
        get() = buildString {
            append(packageName).append('|').append(windowId).append('|')
            append(if (keyboardVisible) "kb" else "").append('|')
            candidates.asSequence()
                .mapNotNull { c ->
                    c.naming.takeIf(String::isNotBlank)?.let { name ->
                        name + when (c.checked) {
                            true -> "+on"
                            false -> "+off"
                            null -> ""
                        } + (if (c.value.isNotBlank()) "=${c.value}" else "") +
                            (if (c.editable) "~${c.label}" else "")
                    }
                }
                .sorted()
                .joinTo(this, ",")
            if (notices.isNotEmpty()) append("|!").append(notices.joinToString("|"))
        }

    /**
     * Everything [contentKey] has, plus where each control is.
     *
     * Used to decide whether a picture and a reading describe the same moment.
     * A page signature, or even [contentKey], misses a scroll: the same rows
     * are on the page, in different places, and a picture of one position
     * sent with the positions of another describes nothing that exists.
     */
    val layoutKey: String
        get() = contentKey + "#" + candidates.joinToString(",") {
            "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}"
        }

    // Data classes and ByteArray do not agree about equality; the id is the
    // identity here and comparing screenshots byte by byte would be absurd.
    override fun equals(other: Any?): Boolean =
        this === other || (other is Observation && other.observationId == observationId)

    override fun hashCode(): Int = observationId.hashCode()
}
