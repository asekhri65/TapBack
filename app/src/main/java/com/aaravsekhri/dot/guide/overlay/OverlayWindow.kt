package com.aaravsekhri.dot.guide.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * One accessibility overlay window holding a Compose tree.
 *
 * Both of Dot's overlays are [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY],
 * which is the window type an accessibility service is allowed to use without
 * asking for `SYSTEM_ALERT_WINDOW`. Dot does not request that permission.
 *
 * @param touchable false for the highlight, which must let every tap reach the
 *   app underneath. See [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE].
 */
class OverlayWindow(
    private val context: Context,
    private val touchable: Boolean,
) {
    private val windowManager =
        context.getSystemService(WindowManager::class.java)

    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = composeView != null

    /**
     * The window's view, for the few things that belong to a window rather than
     * to what it draws — playing a haptic, for one.
     */
    val view: android.view.View? get() = composeView

    /**
     * Adds or updates the window.
     *
     * @param placement where and how large the window should be. Sizing a
     *   window to its content rather than the screen is what keeps Dot from
     *   putting a layer over everything.
     * @param onLaidOut told the window's real bounds on screen, in pixels,
     *   every time it is laid out — the ground truth for what it covers,
     *   rather than a calculation of where it ought to be.
     */
    fun show(
        placement: OverlayPlacement,
        onLaidOut: ((com.aaravsekhri.dot.guide.model.Bounds) -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        val manager = windowManager ?: return

        val existing = composeView
        if (existing != null) {
            existing.setContent(content)
            move(placement)
            return
        }

        val owner = OverlayLifecycleOwner().apply { start() }
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent(content)
        }
        if (onLaidOut != null) {
            // Checked before every frame, not only on a layout: moving the
            // window changes where it is on screen without laying anything
            // out, and a shadow drawn from the old place is a ghost of the
            // card. Reported only when it has actually changed.
            var last: com.aaravsekhri.dot.guide.model.Bounds? = null
            view.viewTreeObserver.addOnPreDrawListener {
                screenBounds(view)?.takeIf { it != last }?.let {
                    last = it
                    onLaidOut(it)
                }
                true
            }
        }

        val params = buildLayoutParams().also(placement::applyTo)

        try {
            manager.addView(view, params)
        } catch (_: Exception) {
            owner.destroy()
            return
        }

        composeView = view
        lifecycleOwner = owner
        layoutParams = params
    }

    /**
     * Moves or resizes the window without touching what it draws: no new
     * composition, so nothing in it replays its entrance.
     */
    fun move(placement: OverlayPlacement) {
        val manager = windowManager ?: return
        val view = composeView ?: return
        val params = layoutParams ?: return
        val before = WindowManager.LayoutParams().apply { copyFrom(params) }
        placement.applyTo(params)
        if (before.x == params.x && before.y == params.y && before.width == params.width &&
            before.height == params.height && before.gravity == params.gravity && before.alpha == params.alpha &&
            before.flags == params.flags
        ) {
            return
        }
        try {
            manager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // The window can be gone already if the service was torn down
            // between frames; rebuilding on the next render is correct.
            hide()
        }
    }

    /**
     * Lets the window take the keyboard, or gives it back.
     *
     * Only for a card with a box to type in. It stays not touch-modal
     * throughout, so a touch anywhere outside the card still goes to the app
     * underneath; and when it gives focus back the keyboard is put away.
     */
    fun setFocusable(focusable: Boolean) {
        val manager = windowManager ?: return
        val view = composeView ?: return
        val params = layoutParams ?: return
        val flags = overlayFlags(touchable, focusable)
        if (params.flags == flags) return
        params.flags = flags
        params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        if (!focusable) {
            view.clearFocus()
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        try {
            manager.updateViewLayout(view, params)
        } catch (_: Exception) {
            hide()
        }
    }

    /** Where the window actually is on screen, in pixels, or null when it is not up. */
    fun screenBounds(): com.aaravsekhri.dot.guide.model.Bounds? = composeView?.let(::screenBounds)

    private fun screenBounds(view: android.view.View): com.aaravsekhri.dot.guide.model.Bounds? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return com.aaravsekhri.dot.guide.model.Bounds(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    /** Removes the window and tears down its composition. */
    fun hide() {
        val view = composeView ?: return
        composeView = null
        layoutParams = null

        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {
            // Already gone.
        }
        // After the view is detached, so Compose can dispose cleanly.
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val flags = overlayFlags(touchable)

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Every layer, the card included, is placed in the same
                // coordinates accessibility reports controls in: raw screen
                // pixels from the true top-left of the display. The card is
                // kept clear of the bars and the cutout by where it is put,
                // not by the window manager moving its origin — which is what
                // made a calculated card position and the real one disagree.
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // The highlight draws in the same coordinates accessibility
                    // reports nodes in: raw screen pixels from the true top-left
                    // of the display. Laying this window out to avoid the
                    // cutout would move its origin down by the status bar, and
                    // every outline would be drawn that far below the control
                    // it points at. One coordinate space, applied once.
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    // The same intent on API 28 and 29, which have no ALWAYS.
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // No insets applied by the window manager: see above.
                fitInsetsTypes = 0
            }
        }
    }
}

/**
 * The window flags each overlay layer runs with.
 *
 * Pulled out as a plain function so the safety-critical part — that the
 * highlight and the scroll cue are incapable of taking a touch — is asserted by
 * a test rather than trusted to review. Every tap and every scroll reaches the
 * app underneath because `FLAG_NOT_TOUCHABLE` makes it impossible for these
 * windows to receive input at all.
 */
internal fun overlayFlags(touchable: Boolean, focusable: Boolean = false): Int {
    // A card being typed on takes focus for the keyboard, and is never
    // touch-modal: touches outside it still reach the app.
    var flags = if (focusable && touchable) {
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    } else {
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    if (!touchable) {
        flags = flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
    return flags
}

/** Where an overlay window sits and how big it is. */
sealed interface OverlayPlacement {

    /** An exact rectangle in screen pixels, used by the highlight. */
    data class Exact(val x: Int, val y: Int, val width: Int, val height: Int) : OverlayPlacement

    /**
     * The card: its exact width and left edge, its top edge in screen pixels,
     * and exactly as tall as its content. Nothing wider or taller than what
     * can be seen, so nothing transparent takes a touch.
     *
     * @param visible false for the one frame before the card has been
     *   measured and placed, so it never appears somewhere and then jumps.
     */
    data class CardAt(val x: Int, val y: Int, val width: Int, val visible: Boolean = true) : OverlayPlacement

    /** Content-sized, centred on screen — used by the scroll cue. */
    data object Centred : OverlayPlacement

    /**
     * The whole display — used by the target highlight.
     *
     * Only ever combined with `FLAG_NOT_TOUCHABLE`, so it is incapable of
     * taking a touch: every tap and scroll reaches the app beneath it. Covering
     * the display is what lets the outline be animated in screen coordinates
     * instead of being dragged around by repositioning the window, which is
     * what made it feel detached from the thing it points at.
     */
    data object FullScreen : OverlayPlacement

    fun applyTo(params: WindowManager.LayoutParams) {
        when (this) {
            is Exact -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = x
                params.y = y
                params.width = width
                params.height = height
            }

            is CardAt -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = x
                params.y = y
                params.width = width
                // Content height only: the window covers the card and nothing
                // more, so the rest of the screen stays the app's.
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.alpha = if (visible) 1f else 0f
                // An unseen card takes no touches: the app underneath gets
                // every one, a scroll included, while the card is stood down.
                params.flags = if (visible) {
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
            }

            FullScreen -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = 0
                params.y = 0
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
            }

            Centred -> {
                params.gravity = Gravity.CENTER
                params.x = 0
                params.y = 0
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
        }
    }
}
