package com.aaravsekhri.dot.guide.service

import android.content.Context
import androidx.core.content.edit

/**
 * The very small amount Dot remembers between launches.
 *
 * Three facts only: that the on-device disclosure was accepted, that cloud
 * guidance was separately accepted, and which guide was waiting when access
 * was requested. No screen content of any kind is stored here or anywhere
 * else.
 */
class GuidePreferences(context: Context) {

    // Application context: this outlives any Activity and must not hold one.
    private val preferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** True once the person has read the disclosure and chosen to continue. */
    var disclosureAccepted: Boolean
        get() = preferences.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        set(value) = preferences.edit { putBoolean(KEY_DISCLOSURE_ACCEPTED, value) }

    /**
     * True once the person has agreed that screen content may be sent away.
     *
     * Deliberately separate from [disclosureAccepted]. That one covers Dot
     * reading the screen on the phone, which is what the fixed tutorials do
     * and all they do. This one covers a materially different thing — relevant
     * parts of the screen, and sometimes a picture of it, leaving the device
     * for a service to look at — and agreeing to the first is not agreeing to
     * the second.
     */
    var cloudGuidanceAccepted: Boolean
        get() = preferences.getBoolean(KEY_CLOUD_ACCEPTED, false)
        set(value) = preferences.edit { putBoolean(KEY_CLOUD_ACCEPTED, value) }

    /**
     * The guide the person asked for before being sent to system settings, so
     * it can be offered again when they come back. Cleared once resumed or
     * abandoned.
     */
    var pendingGuideId: String?
        get() = preferences.getString(KEY_PENDING_GUIDE, null)
        set(value) = preferences.edit {
            if (value == null) remove(KEY_PENDING_GUIDE) else putString(KEY_PENDING_GUIDE, value)
        }

    private companion object {
        const val NAME = "dot_guide"
        const val KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted"
        const val KEY_PENDING_GUIDE = "pending_guide_id"
        const val KEY_CLOUD_ACCEPTED = "cloud_guidance_accepted"
    }
}
