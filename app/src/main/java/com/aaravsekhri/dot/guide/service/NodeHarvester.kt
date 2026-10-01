package com.aaravsekhri.dot.guide.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.model.ClickDescriptor
import com.aaravsekhri.dot.guide.model.NodeSnapshot
import com.aaravsekhri.dot.guide.model.ScreenSnapshot

/**
 * Turns a live accessibility tree into plain data.
 *
 * Two things matter here beyond the traversal itself:
 *
 * Nothing is retained. Every [AccessibilityNodeInfo] read is converted to a
 * [NodeSnapshot] and dropped within this call, so no framework node outlives
 * the pass that produced it and there is nothing to leak.
 *
 * Nothing is logged. Node wording is the contents of somebody's screen; it is
 * read, scored, and discarded. It is never written to logcat, stored, or sent
 * anywhere, in debug or release.
 */
object NodeHarvester {

    /** Depth beyond which a tree is assumed to be pathological and abandoned. */
    /** How far up the tree a click's labels are gathered from. */
    private const val CLICK_ANCESTOR_DEPTH = 4

    /** How many siblings of each ancestor are read, to bound the work. */
    private const val CLICK_CHILD_FANOUT = 6

    /** How far into the clicked node's own subtree its labels are gathered. */
    private const val CLICK_DESCENDANT_DEPTH = 2

    private const val MAX_DEPTH = 40

    /** Upper bound on nodes per pass, so a huge tree cannot stall the service. */
    private const val MAX_NODES = 1500

    /**
     * Walks [root] and returns a framework-free view of it.
     *
     * @param screenWidth used to reject nodes laid out off-screen.
     */
    fun harvest(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
    ): ScreenSnapshot {
        if (root == null) return ScreenSnapshot.EMPTY

        val nodes = ArrayList<NodeSnapshot>(64)
        val rect = Rect()

        // Explicit stack rather than recursion: Settings hierarchies are deep
        // and a blown stack inside an accessibility callback takes the service
        // down with it.
        val pending = ArrayDeque<PendingNode>()
        pending.addLast(PendingNode(root, parentId = null, depth = 0))

        while (pending.isNotEmpty() && nodes.size < MAX_NODES) {
            val pendingNode = pending.removeFirst()
            val node = pendingNode.node

            try {
                val id = nodes.size
                nodes.add(snapshot(node, id, pendingNode.parentId, pendingNode.depth, rect))

                if (pendingNode.depth < MAX_DEPTH) {
                    for (index in 0 until node.childCount) {
                        val child = try {
                            node.getChild(index)
                        } catch (_: Exception) {
                            // A child can vanish mid-traversal while the screen
                            // is changing; skipping it is the correct response.
                            null
                        } ?: continue
                        pending.addLast(
                            PendingNode(child, parentId = id, depth = pendingNode.depth + 1),
                        )
                    }
                }
            } catch (e: Exception) {
                // A stale node throws rather than returning null. One bad node
                // must not abandon the whole pass. The class of what went wrong
                // is noted (debug builds only), never anything the node said.
                com.aaravsekhri.dot.guide.GuideLog.d("harvest") { "skipped a node: ${e.javaClass.simpleName}" }
            }
        }

        // Anything still queued when a limit was hit is dropped here too.
        pending.clear()

        return ScreenSnapshot(
            packageName = root.packageName?.toString(),
            nodes = nodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            windowId = root.windowId,
            rootClassName = root.className?.toString(),
        )
    }

    private fun snapshot(
        node: AccessibilityNodeInfo,
        id: Int,
        parentId: Int?,
        depth: Int,
        reusableRect: Rect,
    ): NodeSnapshot {
        node.getBoundsInScreen(reusableRect)
        return NodeSnapshot(
            id = id,
            parentId = parentId,
            depth = depth,
            windowId = node.windowId,
            packageName = node.packageName?.toString(),
            className = node.className?.toString(),
            viewId = node.viewIdResourceName,
            // A password field's contents are never read: the flag is recorded
            // so the node can be rejected, and the text is left alone.
            text = if (node.isPassword) null else node.text?.toString(),
            contentDescription = if (node.isPassword) null else node.contentDescription?.toString(),
            bounds = Bounds(
                reusableRect.left,
                reusableRect.top,
                reusableRect.right,
                reusableRect.bottom,
            ),
            isVisibleToUser = node.isVisibleToUser,
            isEnabled = node.isEnabled,
            isClickable = node.isClickable,
            isFocusable = node.isFocusable,
            isScrollable = node.isScrollable,
            isPassword = node.isPassword,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isSelected = node.isSelected,
            isFocused = node.isFocused,
            isEditable = node.isEditable,
            // Never a password field's hint either: some apps put the saved
            // value's shape there.
            hintText = if (node.isPassword) null else node.hintText?.toString(),
            rangeValue = rangeOf(node),
            scrollsHorizontally = node.isScrollable && scrollsSideways(node),
            inputType = if (node.isEditable) node.inputType else 0,
            maxTextLength = if (node.isEditable) node.maxTextLength else -1,
            errorText = if (node.isPassword) null else node.error?.toString()?.takeIf(String::isNotBlank),
            showingHint = node.isShowingHintText,
        )
    }

    private fun rangeOf(node: AccessibilityNodeInfo): String? {
        val range = node.rangeInfo ?: return null
        val span = range.max - range.min
        if (span <= 0f) return null
        return when (range.type) {
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT -> "${range.current.toInt()}%"
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT ->
                "${(range.current - range.min).toInt() + 1} of ${span.toInt() + 1}"
            else -> "${((range.current - range.min) / span * 100).toInt()}%"
        }
    }

    /**
     * Whether a scrolling container moves sideways.
     *
     * The platform's own answer, where the app gives one: a list that offers
     * to scroll left or right, or a grid whose rows are fewer than its
     * columns, is horizontal. Anything unsure is vertical, which is the
     * common case and the safer one to describe.
     */
    private fun scrollsSideways(node: AccessibilityNodeInfo): Boolean {
        // Pagers say so in their class, whatever actions they expose: a
        // ViewPager offers only "forward" and "back", which read as a list
        // going down, and the model was told a sideways preview scrolled
        // vertically.
        val className = node.className?.toString().orEmpty()
        if ("ViewPager" in className || "HorizontalScrollView" in className) return true
        val actions = node.actionList
        // Paging actions arrived in API 29; before that only the scroll
        // actions exist to be asked about.
        val sidewaysIds = buildSet {
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)
            add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id)
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id)
            }
        }
        val sideways = actions.any { it.id in sidewaysIds }
        val upright = actions.any {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id ||
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        }
        if (sideways && !upright) return true
        val collection = node.collectionInfo ?: return false
        return collection.rowCount in 0..1 && collection.columnCount > 1
    }

    /**
     * Finds the snapshot corresponding to a node the framework handed us
     * directly, such as the source of a click event, by matching on position
     * and identity rather than holding on to the node.
     */
    /**
     * Copies what a click event says about the control, while it is still valid.
     *
     * Walks up a bounded number of ancestors collecting text, because a
     * settings row's clickable container carries no label of its own — the
     * title is a child of it, and the click arrives on the container. Each
     * ancestor's immediate children are read for the same reason.
     *
     * Nothing is retained: every node touched here is recycled by the caller
     * when the event ends, and only the copied strings outlive it.
     */
    fun describeClick(source: AccessibilityNodeInfo?): ClickDescriptor? {
        if (source == null) return null
        return try {
            val labels = LinkedHashSet<String>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null || node.isPassword) return
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
            }

            collect(source)
            // The clicked node is usually the row's container and carries no
            // text of its own; the title is a child of it. Reading only
            // ancestors produced empty descriptors and every tap was rejected.
            collectDescendants(source, labels, depth = 0)

            var ancestor: AccessibilityNodeInfo? = source.parent
            var depth = 0
            while (ancestor != null && depth < CLICK_ANCESTOR_DEPTH) {
                collect(ancestor)
                for (index in 0 until minOf(ancestor.childCount, CLICK_CHILD_FANOUT)) {
                    collect(ancestor.getChild(index))
                }
                ancestor = ancestor.parent
                depth++
            }

            ClickDescriptor(
                packageName = source.packageName?.toString(),
                className = source.className?.toString(),
                viewId = source.viewIdResourceName,
                text = if (source.isPassword) null else source.text?.toString(),
                contentDescription =
                    if (source.isPassword) null else source.contentDescription?.toString(),
                labels = labels.toList(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Text from a node's own subtree, a couple of levels down and no further. */
    private fun collectDescendants(
        node: AccessibilityNodeInfo?,
        into: MutableSet<String>,
        depth: Int,
    ) {
        if (node == null || depth > CLICK_DESCENDANT_DEPTH) return
        for (index in 0 until minOf(node.childCount, CLICK_CHILD_FANOUT)) {
            val child = node.getChild(index) ?: continue
            if (!child.isPassword) {
                child.text?.toString()?.takeIf { it.isNotBlank() }?.let(into::add)
                child.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(into::add)
            }
            collectDescendants(child, into, depth + 1)
        }
    }

    fun locate(screen: ScreenSnapshot, source: AccessibilityNodeInfo?): NodeSnapshot? {
        if (source == null) return null
        val rect = Rect()
        return try {
            source.getBoundsInScreen(rect)
            val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
            val viewId = source.viewIdResourceName
            val text = if (source.isPassword) null else source.text?.toString()

            screen.nodes.firstOrNull { candidate ->
                candidate.bounds == bounds &&
                    candidate.viewId == viewId &&
                    candidate.text == text
            } ?: screen.nodes.firstOrNull { it.bounds == bounds }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * No node is recycled here. `AccessibilityNodeInfo.recycle` has been
     * deprecated and a no-op since API 33, and calling it on a node the
     * framework still owns is a crash on older builds. Letting them fall out
     * of scope is both correct and safe on every level Dot supports.
     */
    private class PendingNode(
        val node: AccessibilityNodeInfo,
        val parentId: Int?,
        val depth: Int,
    )
}
