package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.ObservationBuilder
import com.aaravsekhri.dot.guide.ai.ScreenshotPolicy
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the words are enough, and every kind of doubt that sends the picture too. */
class ScreenshotPolicyTest {

    private fun node(id: Int, text: String?, cls: String = "android.widget.Button", clickable: Boolean = true, top: Int = id * 200) =
        NodeSnapshot(
            id = id, parentId = null, depth = 1, windowId = 1, packageName = "p", className = cls,
            viewId = null, text = text, contentDescription = null, bounds = Bounds(0, top, 1080, top + 150),
            isVisibleToUser = true, isEnabled = true, isClickable = clickable, isFocusable = clickable,
            isScrollable = false, isPassword = false, isCheckable = false,
        )

    private fun suffices(vararg nodes: NodeSnapshot): Boolean {
        val screen = ScreenSnapshot("p", nodes.toList(), 1080, 2400, 1)
        return ScreenshotPolicy.textSuffices(ObservationBuilder.build("s", screen, 1, "", 0), screen)
    }

    @Test
    fun `a clearly labelled page is described well enough`() {
        assertTrue(suffices(node(0, "Reply"), node(1, "Share"), node(2, "Delete")))
    }

    @Test
    fun `two controls with the same name need the picture`() {
        assertFalse(suffices(node(0, "Remove"), node(1, "Remove"), node(2, "Save")))
    }

    @Test
    fun `web content needs the picture`() {
        assertFalse(suffices(node(0, "Reply"), node(1, "Share"), node(2, null, "android.webkit.WebView", clickable = false)))
    }

    @Test
    fun `unnamed icons need the picture`() {
        assertFalse(suffices(node(0, "Reply"), node(1, "Share"), node(2, null), node(3, null)))
    }

    @Test
    fun `too little to go on needs the picture`() {
        assertFalse(suffices(node(0, "Install"), node(1, "About this app", "android.widget.TextView", clickable = false)))
    }
}
