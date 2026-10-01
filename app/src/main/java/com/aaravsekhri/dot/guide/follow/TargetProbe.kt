package com.aaravsekhri.dot.guide.follow

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import com.aaravsekhri.dot.guide.matching.TextNormalizer
import com.aaravsekhri.dot.guide.model.Bounds

/**
 * Asks one control where it is, when asked to.
 *
 * Deliberately not a loop. An earlier version of this ran continuously so the
 * outline could be dragged along with the page; that was abandoned, because an
 * outline built from a reading is always a little behind the row and a little
 * behind is a confident instruction to tap the wrong thing. What is left is the
 * part that was always sound: one control, asked directly, cheaply.
 *
 * It is asked once per scroll event, which the platform delivers about eight
 * times a second. There is no timer here and nothing runs between requests, so
 * a screen nobody is touching costs nothing at all.
 *
 * Two rules:
 *
 * **One request outstanding.** A request arriving while one is in flight is
 * dropped rather than queued. The answer to "where is it now" does not improve
 * by being asked twice, and a backlog would hand back older and older positions
 * the harder somebody scrolled.
 *
 * **Identity before position.** A list recycles its rows: the node that was the
 * target can quietly become a different row, and a refresh of it succeeds. The
 * label it was acquired with is re-read whenever the row jumps further than
 * half its own height, which is what recycling looks like, and on a timer
 * besides. A row that has stopped being itself reports no position at all.
 *
 * The label is held in memory for comparison and is never logged or stored.
 */
class TargetProbe(
    private val clock: () -> Long = System::nanoTime,
) {

    /** What the target was when it was acquired, for checking it still is. */
    private class Held(
        val root: AccessibilityNodeInfo,
        /** The node carrying the wording, when the row carries none itself. */
        val label: AccessibilityNodeInfo?,
        val expectedLabel: String?,
    )

    private val thread = HandlerThread("dot-probe", Process.THREAD_PRIORITY_DEFAULT)
    private val handler: Handler
    private val rect = Rect()

    /** Probe-thread only. */
    private var held: Held? = null
    private var lastVerifiedAtNanos: Long = 0L
    private var lastVerifiedTop: Int = Int.MIN_VALUE

    @Volatile
    private var outstanding = false

    @Volatile
    private var stopped = false

    init {
        thread.start()
        handler = Handler(thread.looper)
    }

    /** Begins holding [root], so it can be asked later. */
    fun hold(
        root: AccessibilityNodeInfo,
        label: AccessibilityNodeInfo?,
        expectedLabel: String?,
    ) {
        if (stopped) return
        handler.post {
            held = Held(root, label, expectedLabel)
            lastVerifiedAtNanos = 0L
            lastVerifiedTop = Int.MIN_VALUE
        }
    }

    /** Stops holding anything. Nothing runs afterwards until [hold] is called. */
    fun release() {
        handler.post { held = null }
    }

    fun shutdown() {
        stopped = true
        release()
        handler.post { thread.quitSafely() }
    }

    /** True while a control is being held, so the caller knows whether to ask. */
    val isHolding: Boolean get() = held != null

    /**
     * Asks where the control is.
     *
     * [onResult] runs on the probe's thread with the control's rectangle
     * exactly as accessibility gave it — which for a row partly out of its list
     * is the visible part, and for one entirely out has its edges crossed. Null
     * means the control could not be read, or is no longer itself.
     *
     * Dropped silently when a request is already in flight.
     */
    fun request(onResult: (Bounds?) -> Unit) {
        if (stopped || outstanding) return
        outstanding = true
        handler.post {
            val result = read()
            outstanding = false
            onResult(result)
        }
    }

    /**
     * Finds the control by its wording, holds it, and says where it is.
     *
     * For the stretch before a target has been settled on — somebody scrolling
     * down a list towards a row Dot has not yet outlined. Without this the card
     * would go on saying "keep scrolling down" while the row sat in the middle
     * of the screen, which is the opposite of useful.
     *
     * One query rather than a walk of the tree: the platform matches the text
     * itself and hands back the nodes. Matching by the exact wording is also
     * what makes it safe — a row found this way is the row, not a neighbour
     * that happened to be in the right place.
     *
     * @param maxHeightPx the tallest a row may plausibly be. Climbing to a
     *   clickable ancestor can reach the window itself on a screen whose rows
     *   are not clickable, and a "row" the size of the display would read as
     *   permanently in view.
     */
    fun requestLookup(
        root: () -> AccessibilityNodeInfo?,
        labels: List<String>,
        maxHeightPx: Int,
        onResult: (Bounds?) -> Unit,
    ) {
        if (stopped || outstanding || labels.isEmpty()) return
        outstanding = true
        handler.post {
            val result = lookup(root, labels, maxHeightPx)
            outstanding = false
            onResult(result)
        }
    }

    private fun lookup(
        root: () -> AccessibilityNodeInfo?,
        labels: List<String>,
        maxHeightPx: Int,
    ): Bounds? {
        val tree = safely(root) ?: return null
        val wanted = labels.map(TextNormalizer::normalize).filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return null

        // The platform matches on a fragment: asking for "Display" hands back
        // every node whose wording contains it, which on a settings page is
        // several unrelated rows. Taking the first was the whole of the
        // downward bug — the card announced "stop here" over a row that merely
        // mentioned the word, while the actual target was still far below.
        //
        // Nothing here is accepted unless its own wording *is* one of the
        // step's approved names. Same rule the outline has always had; it
        // simply was not being applied to the words.
        var matchedVia: String? = null
        val labelNode = labels.asSequence()
            .flatMap { safely { tree.findAccessibilityNodeInfosByText(it) }.orEmpty().asSequence() }
            .firstOrNull { node ->
                val text = TextNormalizer.normalize(safely { node.text?.toString() })
                val description =
                    TextNormalizer.normalize(safely { node.contentDescription?.toString() })
                when {
                    text in wanted -> { matchedVia = "its own wording"; true }
                    description in wanted -> { matchedVia = "its description"; true }
                    else -> false
                }
            }
        // How many names were tried and whether one matched — never what the
        // screen says. A row is only accepted when its wording *is* one of the
        // step's approved names, so the fact of a match carries no information
        // the guide did not already have.
        com.aaravsekhri.dot.guide.GuideLog.d("probe") {
            "lookup over ${wanted.size} approved names matched by ${matchedVia ?: "nothing"}"
        }
        if (labelNode == null) return null

        var row: AccessibilityNodeInfo = labelNode
        var climbed = 0
        while (climbed < ANCESTOR_DEPTH && !row.isClickable) {
            row = safely { row.parent } ?: break
            climbed++
        }

        safely { row.getBoundsInScreen(rect) }
        var bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
        if (bounds.bottom - bounds.top > maxHeightPx) {
            // The climb overshot: this is a container, not a row. The wording's
            // own node is a truthful, if smaller, answer.
            row = labelNode
            safely { row.getBoundsInScreen(rect) }
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
        }

        // What the node actually says, not what was searched for. The platform
        // matches on a fragment — looking for "Display" finds "Display & touch"
        // — and holding the fragment as the expected wording meant the very
        // next reading decided the row had stopped being itself.
        val actual = safely { labelNode.text?.toString() }
            ?: safely { labelNode.contentDescription?.toString() }
        held = Held(row, if (row === labelNode) null else labelNode, actual)
        lastVerifiedAtNanos = clock()
        lastVerifiedTop = bounds.top
        return bounds
    }

    private fun read(): Bounds? {
        val current = held ?: return null
        val alive = safely { current.root.refresh() } ?: false
        if (!alive) {
            held = null
            return null
        }

        safely { current.root.getBoundsInScreen(rect) }
        val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)

        // Recycling is what makes a row jump: the view is handed to an item at
        // the other end of the list and its rectangle moves further in one
        // reading than the row is tall. So identity is re-checked whenever it
        // does, and on a timer for any other way a view could be reused.
        val now = clock()
        val jumped = lastVerifiedTop == Int.MIN_VALUE ||
            kotlin.math.abs(bounds.top - lastVerifiedTop) >
            (kotlin.math.abs(bounds.bottom - bounds.top) / 2).coerceAtLeast(1)
        val due = now - lastVerifiedAtNanos >= IDENTITY_RECHECK_NANOS
        lastVerifiedTop = bounds.top
        if (jumped || due) {
            lastVerifiedAtNanos = now
            if (!identityHolds(current)) {
                held = null
                return null
            }
        }
        return bounds
    }

    private fun identityHolds(current: Held): Boolean {
        val expected = current.expectedLabel ?: return true
        val node = current.label ?: current.root
        if (node !== current.root) {
            val refreshed = safely { node.refresh() } ?: false
            if (!refreshed) return false
        }
        val text = safely { node.text?.toString() ?: node.contentDescription?.toString() }
        return text != null && text.equals(expected, ignoreCase = true)
    }

    /** A node that has gone away throws rather than returning null. */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private companion object {
        /**
         * However smoothly a row moves, its label is re-read this often.
         *
         * A backstop for any way a view could be reused without its rectangle
         * jumping. Four times a second is far more often than a list recycles.
         */
        const val IDENTITY_RECHECK_NANOS = 250_000_000L

        /** How far up from a label the clickable row is looked for. */
        const val ANCESTOR_DEPTH = 4
    }
}
