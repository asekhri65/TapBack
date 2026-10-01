package com.aaravsekhri.dot.guide.visual

import com.aaravsekhri.dot.guide.GuideLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the experiment is switched on, and where consent ends up.
 *
 * Visual tracking is off unless someone deliberately turns it on for a guide.
 * It is not a release behaviour, it is not requested at launch, and it is never
 * asked for before a guide has started — there is nothing to track until then.
 */
object VisualTrackingCoordinator {

    /** Experimental, debug-only, and off by default. */
    @Volatile
    var experimentEnabled: Boolean = false
        private set

    private val _permission = MutableStateFlow<ProjectionPermission?>(null)
    val permission: StateFlow<ProjectionPermission?> = _permission.asStateFlow()

    private val _state = MutableStateFlow<VisualTrackingState>(VisualTrackingState.Inactive)
    val state: StateFlow<VisualTrackingState> = _state.asStateFlow()

    /**
     * Called when consent lands, so the service can start capture.
     *
     * A callback rather than a collected flow because the accessibility
     * service has no lifecycle scope to collect in, and this has to happen at
     * the moment consent arrives — the token does not keep.
     */
    @Volatile
    var onPermissionGranted: (() -> Unit)? = null

    /**
     * Fired once the capture dialog is answered, either way.
     *
     * The guide waits for this rather than starting alongside the dialog: two
     * activities racing to the front means the person taps whichever wins, and
     * on the emulator the dialog lost.
     */
    @Volatile
    var onConsentResolved: ((granted: Boolean) -> Unit)? = null

    /** Set from the enhanced-tracking offer, or from the debug toggle. */
    fun setExperimentEnabled(enabled: Boolean) {
        experimentEnabled = enabled
        if (!enabled) clear()
        GuideLog.d("visual") { "experiment enabled=$enabled" }
    }

    internal fun onConsentResult(granted: ProjectionPermission?) {
        _permission.value = granted
        if (granted != null) onPermissionGranted?.invoke()
        onConsentResolved?.let { resolved ->
            onConsentResolved = null
            resolved(granted != null)
        }
    }

    internal fun publish(state: VisualTrackingState) {
        _state.value = state
    }

    /**
     * Forgets the consent token and the session state.
     *
     * The token is single-use and belongs to the guide that asked for it, so it
     * is dropped the moment that guide ends rather than kept for the next one.
     */
    fun clear() {
        _permission.value = null
        _state.value = VisualTrackingState.Inactive
    }
}
