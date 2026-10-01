package com.aaravsekhri.dot.guide.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

/**
 * Answers whether Dot's accessibility service is switched on.
 *
 * Two independent checks, because neither is sufficient on its own. The
 * manager's list reflects services the system has actually bound, but can lag
 * for a moment right after the switch is flipped. The secure setting reflects
 * the switch itself, but says nothing about whether the service is running.
 * Either being true means the person has granted access; [isRunning] is the
 * stricter question of whether Dot can act right now.
 */
object AccessibilityServiceStatus {

    /** True when the person has granted Dot accessibility access. */
    fun isEnabled(context: Context): Boolean =
        isListedByManager(context) || isListedInSecureSettings(context)

    /**
     * True when the service is enabled *and* connected, so a guide can start
     * immediately. Right after returning from system settings this can briefly
     * be false while [isEnabled] is already true.
     */
    fun isRunning(context: Context): Boolean =
        isEnabled(context) && DotAccessibilityService.isConnected

    private fun component(context: Context) =
        ComponentName(context.packageName, DotAccessibilityService::class.java.name)

    /**
     * Opens Android's Accessibility settings, where the person turns Dot on
     * themselves.
     *
     * The public action is the supported one. The page for a single service
     * (ACTION_ACCESSIBILITY_DETAILS_SETTINGS) is a hidden system API, so it is
     * not used. The two extras ask Settings apps that honour them to scroll to
     * and highlight Dot's row; where they are not understood they are
     * ignored. Opening settings proves nothing: whether access was granted is
     * read afresh on the way back, never assumed.
     *
     * @return false when there was nothing to open.
     */
    fun openSettings(context: Context): Boolean {
        val key = component(context).flattenToString()
        val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
            putExtra(EXTRA_SHOW_FRAGMENT_ARGS, android.os.Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    private fun isListedByManager(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = component(context)
        return try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val service = info.resolveInfo?.serviceInfo ?: return@any false
                    service.packageName == expected.packageName &&
                        service.name == expected.className
                }
        } catch (_: Exception) {
            false
        }
    }

    private fun isListedInSecureSettings(context: Context): Boolean {
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        } catch (_: Exception) {
            null
        } ?: return false

        val expected = component(context)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val candidate = ComponentName.unflattenFromString(entry) ?: continue
            if (candidate == expected) return true
        }
        return false
    }
}
