package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot

/**
 * Builds screens the way the harvester produces them — a flat list with
 * parents before children — so tests read like the hierarchy they describe.
 */
class ScreenBuilder(
    private val packageName: String = SETTINGS,
    private val width: Int = 1080,
    private val height: Int = 2400,
    private val windowId: Int = 1,
    private val rootClassName: String? = "android.widget.FrameLayout",
) {
    private val nodes = mutableListOf<NodeSnapshot>()

    fun node(
        text: String? = null,
        contentDescription: String? = null,
        viewId: String? = null,
        className: String? = "android.widget.TextView",
        bounds: Bounds = Bounds(0, 100, 1080, 200),
        parentId: Int? = null,
        clickable: Boolean = false,
        focusable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        password: Boolean = false,
    ): Int {
        val id = nodes.size
        nodes += NodeSnapshot(
            id = id,
            parentId = parentId,
            depth = if (parentId == null) 0 else 1,
            windowId = windowId,
            packageName = packageName,
            className = className,
            viewId = viewId,
            text = text,
            contentDescription = contentDescription,
            bounds = bounds,
            isVisibleToUser = visible,
            isEnabled = enabled,
            isClickable = clickable,
            isFocusable = focusable,
            isScrollable = false,
            isPassword = password,
            isCheckable = false,
        )
        return id
    }

    /** A Settings list row: a tappable container with its label inside. */
    fun settingsRow(
        label: String,
        top: Int,
        viewId: String? = null,
        enabled: Boolean = true,
    ): Int {
        val row = node(
            className = "android.widget.LinearLayout",
            bounds = Bounds(0, top, 1080, top + 200),
            clickable = true,
            enabled = enabled,
        )
        node(
            text = label,
            viewId = viewId,
            bounds = Bounds(120, top + 40, 900, top + 120),
            parentId = row,
            enabled = enabled,
        )
        return row
    }

    /** The scrolling container Settings puts its rows inside. */
    fun scrollingList(): Int {
        val id = nodes.size
        nodes += NodeSnapshot(
            id = id,
            parentId = null,
            depth = 0,
            windowId = windowId,
            packageName = packageName,
            className = "androidx.recyclerview.widget.RecyclerView",
            viewId = null,
            text = null,
            contentDescription = null,
            bounds = Bounds(0, 0, width, height),
            isVisibleToUser = true,
            isEnabled = true,
            isClickable = false,
            isFocusable = false,
            isScrollable = true,
            isPassword = false,
            isCheckable = false,
        )
        return id
    }

    /**
     * The Pixel "Display size and text" page: two sliders, each with its own
     * title and description, one after the other.
     */
    fun fontSizeSection(fontTop: Int = 1400, displaySizeTop: Int = 2000): Int {
        node(text = "Preview", bounds = Bounds(60, fontTop - 600, 1020, fontTop - 500))

        val title = node(text = "Font size", bounds = Bounds(60, fontTop, 500, fontTop + 80))
        node(
            text = "Make text bigger or smaller",
            bounds = Bounds(60, fontTop + 90, 900, fontTop + 160),
        )
        node(
            className = "android.widget.ImageView",
            contentDescription = "Decrease",
            bounds = Bounds(60, fontTop + 190, 150, fontTop + 280),
        )
        node(
            className = "android.widget.SeekBar",
            viewId = "com.android.settings:id/font_size_slider",
            bounds = Bounds(170, fontTop + 190, 910, fontTop + 280),
        )
        node(
            className = "android.widget.ImageView",
            contentDescription = "Increase",
            bounds = Bounds(930, fontTop + 190, 1020, fontTop + 280),
        )

        // The next setting down, which must never be swept in.
        node(text = "Display size", bounds = Bounds(60, displaySizeTop, 500, displaySizeTop + 80))
        node(
            text = "Make everything bigger or smaller",
            bounds = Bounds(60, displaySizeTop + 90, 900, displaySizeTop + 160),
        )
        node(
            className = "android.widget.SeekBar",
            viewId = "com.android.settings:id/display_size_slider",
            bounds = Bounds(170, displaySizeTop + 190, 910, displaySizeTop + 280),
        )
        return title
    }

    /**
     * A Settings row with a title and a summary beneath it — the shape that
     * made the Accessibility row look like Display & touch.
     */
    fun settingsRowWithSummary(
        title: String,
        summary: String,
        top: Int,
        enabled: Boolean = true,
    ): Int {
        val row = node(
            className = "android.widget.LinearLayout",
            bounds = Bounds(0, top, 1080, top + 200),
            clickable = true,
            enabled = enabled,
        )
        node(
            text = title,
            viewId = "android:id/title",
            bounds = Bounds(120, top + 30, 900, top + 110),
            parentId = row,
            enabled = enabled,
        )
        node(
            text = summary,
            viewId = "android:id/summary",
            bounds = Bounds(120, top + 115, 900, top + 175),
            parentId = row,
            enabled = enabled,
        )
        return row
    }

    fun build() = ScreenSnapshot(
        packageName = packageName,
        nodes = nodes.toList(),
        screenWidth = width,
        screenHeight = height,
        windowId = windowId,
        rootClassName = rootClassName,
    )

    /**
     * A row whose label is offset by [shift] pixels, for testing how the
     * stabilizer treats small versus material movement.
     */
    fun settingsRowAt(label: String, top: Int, enabled: Boolean = true): Int =
        settingsRow(label, top, enabled = enabled)

    companion object {
        const val SETTINGS = "com.android.settings"
    }
}
