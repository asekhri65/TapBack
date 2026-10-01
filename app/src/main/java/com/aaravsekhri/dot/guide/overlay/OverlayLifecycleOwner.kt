package com.aaravsekhri.dot.guide.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The minimum a Compose view needs to run outside an Activity.
 *
 * Overlay windows are added straight to the WindowManager by the service, so
 * there is no Activity to supply a lifecycle or a saved-state registry.
 * Compose requires both, and this provides them for exactly as long as the
 * overlay is on screen.
 *
 * [destroy] must be called when the window comes down: it moves the lifecycle
 * to DESTROYED so Compose tears down its composition and stops its recomposer,
 * which is what prevents the overlay from outliving the guide.
 */
class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    /** Brings the owner up to RESUMED, ready for a window to be shown. */
    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Tears everything down. Safe to call more than once. */
    fun destroy() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
