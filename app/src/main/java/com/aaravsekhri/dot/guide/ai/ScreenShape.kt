package com.aaravsekhri.dot.guide.ai

import com.aaravsekhri.dot.guide.model.ScreenSnapshot

/**
 * What a page is, structurally, read on the phone and never sent: which app
 * and window, whether it is private, and which boxes it asks for.
 *
 * For noticing that a new page has arrived in the same window — a sign-up
 * that swaps its birthday page for its password page without the window
 * changing — without reading anything anybody typed. A keystroke, the cursor
 * moving, the keyboard opening or a list scrolling leave it as it was; a
 * box appearing or going, or a password box arriving, change it.
 *
 * Built from structure only: kinds of box (from their input type and the
 * app's name for them), never their contents. A password box's placeholder
 * is never read at all.
 */
data class ScreenShape(
    val packageName: String,
    val windowId: Int,
    val inDot: Boolean = false,
    /** A password, code or payment box, or a window the app keeps private. */
    val private: Boolean = false,
    /** The page's boxes, as kinds, in order. */
    val fields: List<String> = emptyList(),
) {
    /** True when [other] is a different page, not the same page changing a little. */
    fun isNewPageAfter(other: ScreenShape): Boolean =
        packageName != other.packageName || inDot != other.inDot || private != other.private || fields != other.fields ||
            windowId != other.windowId

    /**
     * Hashes of the words on the page's visible controls — a fingerprint to
     * tell a page from the one that replaced it in the same window, never
     * the words themselves, never logged. Not part of equality: the same
     * page with a button relabelled is still the same page to [isNewPageAfter].
     */
    var controls: Set<Int> = emptySet()
        internal set

    /**
     * True when the page's controls have almost all changed since [other]:
     * "Sign in" and "Create account" gone and a "Checking info…" page with
     * nothing to tap in their place, though window and boxes are the same.
     * A loading page that keeps its buttons, or a list that adds a row, is not.
     */
    fun controlsReplacedSince(other: ScreenShape): Boolean {
        if (packageName != other.packageName || windowId != other.windowId) return true
        val before = other.controls
        if (before.size < 2) return false
        val kept = before.count { it in controls }
        return kept * 3 < before.size
    }

    companion object {
        val DOT = ScreenShape("", -1, inDot = true)

        fun of(screen: ScreenSnapshot): ScreenShape {
            val boxes = screen.nodes.filter { it.isVisibleToUser && !it.bounds.isEmpty && (it.isEditable || it.isPassword) }
            val shape = ScreenShape(
                packageName = screen.packageName.orEmpty(),
                windowId = screen.windowId,
                private = SensitiveScreenPolicy.judge(screen).sensitive,
                fields = boxes.map { node ->
                    when {
                        node.isPassword || InputKinds.isPassword(node.inputType) -> "password"
                        else -> {
                            val kind = FormFields.kindOf(node.hintText.orEmpty())
                            val type = when {
                                InputKinds.isEmail(node.inputType) -> "email"
                                InputKinds.isPhone(node.inputType) -> "phone"
                                InputKinds.isNumber(node.inputType) -> "number"
                                else -> "text"
                            }
                            "$type/${kind.name}"
                        }
                    }
                },
            )
            shape.controls = screen.nodes
                .filter { it.isVisibleToUser && !it.bounds.isEmpty && it.isClickable && !it.isEditable && !it.isPassword }
                .mapNotNull { (it.text ?: it.contentDescription)?.trim()?.lowercase()?.takeIf { t -> t.length in 2..60 }?.hashCode() }
                .toSet()
            return shape
        }
    }
}
