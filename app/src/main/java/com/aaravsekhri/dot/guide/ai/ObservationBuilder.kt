package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.matching.TextNormalizer

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import java.util.UUID

/**
 * Turns one reading of the accessibility tree into something worth asking about.
 *
 * Two jobs. Reduce — a settings page is a hundred-odd nodes of which a dozen
 * are things a person could touch, and sending the rest costs money and
 * crowds the answer. And freeze — give every control an id that belongs to
 * this reading and no other, so a decision can be checked against the screen
 * it was made about.
 */
object ObservationBuilder {

    /**
     * Controls worth offering. Anything a person could plausibly act on, plus
     * the scrolling containers, because "which list" is part of an answer.
     */
    private fun isInteresting(node: NodeSnapshot): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.bounds.isEmpty) return false
        if (node.isPassword) return false
        if (node.isScrollable) return true
        // A row is clickable and usually says nothing itself; its words are a
        // child. Requiring a label here dropped the row, and the label on its
        // own is not clickable, so between them a whole settings list
        // disappeared. The words are borrowed in `collapse`, and anything that
        // still ends up nameless is dropped there.
        if (node.isClickable) return true
        // Fields, even unlabelled ones: a search box is often named only by its
        // placeholder, and typing into it is a step in its own right.
        if (node.isEditable) return true
        // Sliders. The font-size and brightness controls say nothing about
        // themselves, and their position is the evidence that a step worked.
        if (node.rangeValue != null && node.isInteractive) return true
        if (!node.isInteractive) return false
        return node.hasLabel || node.isCheckable
    }

    fun build(
        sessionId: String,
        screen: ScreenSnapshot,
        screenGeneration: Long,
        windowTitle: String,
        takenAtMs: Long,
        screenshotJpeg: ByteArray? = null,
        limit: Int = MAX_CANDIDATES,
        keyboardVisible: Boolean = false,
        homeScreen: Boolean = false,
        screenshotNote: String = "",
        pxPerDp: Float = DEFAULT_PX_PER_DP,
        /** What this session has watched of the form's boxes; null when nothing is kept. */
        forms: FormMemory? = null,
        /** Also list the page's readable information, for a lesson's teaching points. */
        readable: Boolean = false,
    ): Observation {
        val interesting = screen.nodes.filter(::isInteresting)

        // A row and the text inside it are the same thing to a person. Where a
        // clickable container holds a labelled child, the container is what is
        // offered, wearing the child's words — otherwise the model is asked to
        // choose between "the row" and "the words in the row", which is not a
        // choice anybody meant to offer it.
        val fieldNodes = HashMap<String, NodeSnapshot>()
        val chosen = judgeForms(collapse(interesting, screen, pxPerDp, fieldNodes), screen, forms, fieldNodes)
        val truncated = chosen.size > limit
        val kept = if (chosen.size > limit) insideLists(screen).let { listed -> keep(chosen, limit) { it.id in listed } } else chosen

        return Observation(
            sessionId = sessionId,
            observationId = UUID.randomUUID().toString().take(24),
            takenAtMs = takenAtMs,
            screenGeneration = screenGeneration,
            packageName = screen.packageName.orEmpty(),
            windowTitle = windowTitle,
            windowId = screen.windowId,
            screenWidth = screen.screenWidth,
            screenHeight = screen.screenHeight,
            candidates = kept,
            scrollContainerIds = kept.filter { it.scrollable }.map { it.id },
            truncated = truncated,
            screenshotJpeg = screenshotJpeg,
            keyboardVisible = keyboardVisible,
            homeScreen = homeScreen,
            screenshotNote = screenshotNote,
            notices = notices(screen),
            readable = if (readable) readableText(screen, chosen) else emptyList(),
        )
    }

    /**
     * Plain words on the page that are neither controls nor part of one:
     * the smallest pieces that say something — a line of opening hours, an
     * address — never the lists and panels that hold them.
     */
    private fun readableText(screen: ScreenSnapshot, controls: List<CandidateControl>): List<CandidateControl> {
        val named = controls.map { TextNormalizer.normalize(it.naming) }.toSet()
        val tall = screen.screenHeight / 4
        return screen.nodes.asSequence()
            .filter { it.isVisibleToUser && !it.bounds.isEmpty && !it.isPassword && !it.isEditable && !it.isClickable }
            .filter { it.bounds.height < tall }
            .mapNotNull { node ->
                val words = (node.text ?: node.contentDescription)?.trim()?.takeIf { it.length in 2..MAX_TEXT } ?: return@mapNotNull null
                if (TextNormalizer.normalize(words) in named) return@mapNotNull null
                CandidateControl(
                    id = "t${node.id}",
                    label = words,
                    description = "",
                    role = READABLE_ROLE,
                    bounds = node.bounds,
                    clickable = false,
                    editable = false,
                    enabled = true,
                    scrollable = false,
                    parentId = node.parentId?.let { "n$it" },
                )
            }
            .distinctBy { TextNormalizer.normalize(it.label) }
            .take(MAX_READABLE)
            .toList()
    }

    /** The role every readable piece of information has. */
    const val READABLE_ROLE = "Text"

    private const val MAX_READABLE = 30

    /**
     * How ready each of the form's boxes is. Fingerprints of what is in them
     * go to [forms], in memory, for this session only; no value is kept, and
     * nothing here is logged.
     */
    private fun judgeForms(
        controls: List<CandidateControl>,
        screen: ScreenSnapshot,
        forms: FormMemory?,
        /** The nodes behind the form's boxes, by candidate id. */
        fieldNodes: Map<String, NodeSnapshot>,
    ): List<CandidateControl> {
        val ageZero = FormFields.showsAgeZero(screen.nodes.filter { it.isVisibleToUser }.mapNotNull { it.text })
        val seen = HashMap<String, Int>()
        return controls.map { c ->
            val node = fieldNodes[c.id] ?: return@map c
            val base = FormFields.key(screen.packageName.orEmpty(), c, 0).removeSuffix("|0")
            val occurrence = seen.merge(base, 1, Int::plus)!! - 1
            val key = FormFields.key(screen.packageName.orEmpty(), c, occurrence)
            val text = node.text
            val empty = text.isNullOrBlank() || node.showingHint || text == node.hintText
            val error = !node.errorText.isNullOrBlank()
            // A date turned on a picker is not a date the person has said is
            // right: a birthday counts as ready only once they confirm it.
            val confirmOnly = FormFields.kindOf(c) == FieldKind.Birthday
            var readiness = forms?.readiness(key, if (empty) null else text.hashCode(), error, editedIsEnough = !confirmOnly)
                ?: when {
                    error -> Readiness.Error
                    empty -> Readiness.Empty
                    else -> Readiness.Prefilled
                }
            // "(0 years old)" beside a birthday: the date is still the one
            // the app started with, however many times it was scrolled.
            if (ageZero && readiness == Readiness.Confirmed && FormFields.kindOf(c) == FieldKind.Birthday &&
                forms?.isConfirmed(key) != true
            ) {
                readiness = Readiness.Prefilled
            }
            c.copy(readiness = readiness, error = node.errorText.orEmpty().take(MAX_TEXT), fieldKey = key)
        }
    }

    /**
     * Words on the screen that say something is wrong with the form, or what
     * a date works out to: never a control, so never a candidate, and exactly
     * what a person reads when a step is refused.
     */
    private fun notices(screen: ScreenSnapshot): List<String> =
        screen.nodes.asSequence()
            .filter { it.isVisibleToUser && !it.isEditable && !it.isPassword && !it.isClickable }
            .mapNotNull { it.text?.trim()?.takeIf { t -> t.length in 4..MAX_TEXT && NOTICE.containsMatchIn(t) } }
            .distinct()
            .take(MAX_NOTICES)
            .toList()

    private val NOTICE = Regex(
        "(?i)(wrong info|not valid|isn.t valid|invalid|incorrect|required|must be|you must|please (enter|use|choose|select|make sure)|" +
            "try again|can.t be empty|cannot be empty|too young|not eligible|real birthday|years? old|doesn.t match|enter a valid|error)",
    )

    private const val MAX_NOTICES = 4

    /** Looks like a date a picker shows: "September 29, 2026", "29/09/2026", "2026-09-29". */
    private val DATE = Regex(
        "(?i)^\\s*(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|" +
            "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? \\d{1,2},? \\d{4}|\\d{1,2} (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? \\d{4})\\s*$",
    )

    private fun collapse(
        nodes: List<NodeSnapshot>,
        screen: ScreenSnapshot,
        pxPerDp: Float,
        fieldNodes: MutableMap<String, NodeSnapshot>,
    ): List<CandidateControl> {
        val out = ArrayList<CandidateControl>(nodes.size)
        val consumed = HashSet<Int>()

        for (node in nodes) {
            if (node.id in consumed) continue
            val children = screen.nodes.filter { it.parentId == node.id }
            // A picker's box drawn as a tappable panel holding a title and a
            // date ("Birthday (0 years old)" over "September 30, 2026"): the
            // date is the box's value, the title its name.
            val dateChild = if (node.isClickable && !node.isEditable && !DATE.matches(node.text.orEmpty())) {
                children.firstOrNull { !it.isClickable && DATE.matches(it.text.orEmpty()) }
            } else {
                null
            }
            // "Birthday (0 years old)" is named "Birthday": the part in brackets
            // is the app's working-out, not the box's name.
            val dateTitle = dateChild?.let { d -> children.firstOrNull { it !== d && !it.text.isNullOrBlank() && !it.isClickable }?.text }
                ?.replace(Regex("\\s*\\(.*\\)\\s*$"), "")?.trim()?.ifBlank { null }
            if (dateChild != null) children.filter { !it.isClickable }.forEach { consumed += it.id }
            val label = dateChild?.text ?: node.text.orEmpty().ifBlank {
                // A clickable row with no words of its own: borrow the words of
                // the first labelled thing inside it — but only a row, and only
                // from words that are not a control in their own right. A
                // launcher's scrolling workspace once borrowed "Play Store" from
                // the icon inside it and swallowed the icon: the model was told
                // Play Store was a full-screen list and asked, reasonably, to
                // scroll for it.
                if (!node.isClickable || node.isScrollable) {
                    ""
                } else {
                    // Not only the direct child: a Compose button keeps its
                    // words two or three levels down (Button > Row > Text), and
                    // a nameless button is dropped from the list below — the
                    // planner, told "Get started" is in the picture but not in
                    // the list, could then only describe it. Never through
                    // another control: its words are its own.
                    labelBelow(node, screen, depth = LABEL_DEPTH)?.also { consumed += it.id }?.text.orEmpty()
                }
            }.ifBlank {
                // A slider with no words: named by the title beside it, which
                // is how a person would find it — "the Font size slider".
                if (node.rangeValue != null) siblingTitle(node, screen).orEmpty() else ""
            }
            // A settings row's switch is its own node, usually not clickable
            // itself; the row is what is tapped and the switch is what shows
            // whether it worked. The row carries the switch's state.
            val switch = if (node.isCheckable) {
                node
            } else {
                descendants(node, screen, depth = 3).firstOrNull { it.isCheckable }
            }
            switch?.takeIf { it !== node }?.let { consumed += it.id }
            val slider = node.takeIf { it.rangeValue != null }
            // An account box — email, username, phone — says whether it has
            // something in it, never what. The planner needs "filled" to know
            // the step worked; it does not need the address.
            val account = node.isEditable && accountBox(node)
            // What an outline would go round, or why there should be none.
            val verdict = ControlOutline.resolve(node, screen, pxPerDp)
            // A field with no placeholder of its own is named by the label
            // drawn inside its box, so it can be talked about and found again.
            val hint = node.hintText?.takeIf(String::isNotBlank)
                ?: dateTitle
                ?: when {
                    node.isEditable -> ControlOutline.fieldLabel(node, screen, pxPerDp).orEmpty()
                    !node.isEditable && node.isClickable && DATE.matches(node.text.orEmpty()) -> siblingTitle(node, screen).orEmpty()
                    else -> ""
                }
            // A date shown on a tappable control is a picker's box, even
            // though it cannot be typed in: named by the title beside it.
            val dateBox = !node.isEditable && node.isClickable && (DATE.matches(node.text.orEmpty()) || dateChild != null)
            if (node.isEditable || dateBox) fieldNodes["n${node.id}"] = if (dateChild != null) node.copy(text = dateChild.text) else node
            // A birthday is somebody's date of birth. The planner needs to know
            // whether one is set and whether it is still the app's default —
            // which the phone works out below — never the date itself.
            val birthdayBox = dateBox && FormFields.kindOf(hint) == FieldKind.Birthday
            out += CandidateControl(
                id = "n${node.id}",
                // A field's own text is what has been typed into it, and it is
                // somebody's words: offered so a step can be checked, capped
                // so a pasted essay is not.
                label = if (account || birthdayBox) "" else label.take(if (node.isEditable) MAX_FIELD_TEXT else MAX_TEXT),
                description = node.contentDescription.orEmpty().take(MAX_TEXT),
                role = shortRole(node.className),
                bounds = node.bounds,
                clickable = node.isClickable,
                editable = node.isEditable || isEditable(node.className),
                enabled = node.isEnabled,
                scrollable = node.isScrollable,
                parentId = node.parentId?.let { "n$it" },
                checked = switch?.isChecked,
                selected = node.isSelected,
                focused = node.isFocused,
                value = when {
                    account -> if (node.text.isNullOrBlank()) "" else "filled"
                    birthdayBox -> "set"
                    else -> slider?.rangeValue.orEmpty()
                },
                hint = hint.take(MAX_TEXT),
                horizontal = node.scrollsHorizontally,
                outline = (verdict as? ControlOutline.Verdict.Outline)?.bounds?.takeIf { it != node.bounds },
                wordsOnly = (verdict as? ControlOutline.Verdict.TextOnly)?.why,
            )
        }
        return out.filter { it.naming.isNotBlank() || it.scrollable }
    }

    /** The first words inside [node], breadth first, not crossing into another control or list. */
    private fun labelBelow(node: NodeSnapshot, screen: ScreenSnapshot, depth: Int): NodeSnapshot? {
        var level = listOf(node)
        repeat(depth) {
            val next = level.flatMap { parent -> screen.nodes.filter { it.parentId == parent.id } }
                .filterNot { it.isClickable || it.isScrollable || it.isEditable }
            next.firstOrNull { !it.text.isNullOrBlank() }?.let { return it }
            if (next.isEmpty()) return null
            level = next
        }
        return null
    }

    /**
     * What is kept when a screen offers more than [limit] controls: the ones
     * a step is most likely to be about — boxes, and buttons that are not
     * one item among many in a list — ahead of rows deep in a list, which a
     * scroll brings back anyway. Kept in screen order.
     */
    internal fun keep(chosen: List<CandidateControl>, limit: Int, inList: (CandidateControl) -> Boolean): List<CandidateControl> {
        if (chosen.size <= limit) return chosen
        val priority = chosen.filter { it.editable || it.scrollable || (it.clickable && !inList(it)) }
        val rest = chosen.filterNot { it in priority }
        val picked = (priority.take(limit) + rest.take((limit - priority.size).coerceAtLeast(0))).toHashSet()
        return chosen.filter { it in picked }
    }

    /** Ids ("n…") of every node that sits inside a scrolling container. */
    private fun insideLists(screen: ScreenSnapshot): Set<String> {
        val byId = screen.nodes.associateBy { it.id }
        val out = HashSet<String>()
        for (node in screen.nodes) {
            var parent = node.parentId?.let(byId::get)
            var hops = 0
            while (parent != null && hops < 40) {
                if (parent.isScrollable) { out += "n${node.id}"; break }
                parent = parent.parentId?.let(byId::get)
                hops++
            }
        }
        return out
    }

    private fun descendants(node: NodeSnapshot, screen: ScreenSnapshot, depth: Int): List<NodeSnapshot> {
        if (depth <= 0) return emptyList()
        val direct = screen.nodes.filter { it.parentId == node.id }
        return direct + direct.flatMap { descendants(it, screen, depth - 1) }
    }

    /** The nearest labelled node before this one under the same parent, or its parent's. */
    private fun siblingTitle(node: NodeSnapshot, screen: ScreenSnapshot): String? {
        var current: NodeSnapshot? = node
        repeat(2) {
            val here = current ?: return null
            val before = screen.nodes.filter { it.parentId == here.parentId && it.id < here.id }
            before.lastOrNull { !it.text.isNullOrBlank() }?.text?.let { return it }
            current = screen.nodes.firstOrNull { it.id == here.parentId }
        }
        return null
    }

    private fun accountBox(node: NodeSnapshot): Boolean =
        InputKinds.isEmail(node.inputType) || InputKinds.isPhone(node.inputType) ||
            AuthScreenReader.roleOf(node) == FieldRole.Email

    /** The last part of a class name: "Button", "SeekBar", "RecyclerView". */
    private fun shortRole(className: String?): String =
        className?.substringAfterLast('.')?.take(32).orEmpty()

    private fun isEditable(className: String?): Boolean =
        className?.contains("EditText") == true

    /** A typical phone's density, for readings that were not given one. */
    const val DEFAULT_PX_PER_DP = 2.625f

    /** A dense settings page fits inside this with room to spare. */
    const val MAX_CANDIDATES = 80

    /** How far below a nameless button its words are looked for. */
    private const val LABEL_DEPTH = 3

    private const val MAX_TEXT = 120

    /**
     * As long as the words a step may ask for — a museum's full name and city
     * ran past sixty, and its typing step never saw its own words arrive.
     */
    const val MAX_FIELD_TEXT = 120

    /** Bounds as the service wants them. */
    fun Bounds.asList(): List<Int> = listOf(left, top, right, bottom)
}
