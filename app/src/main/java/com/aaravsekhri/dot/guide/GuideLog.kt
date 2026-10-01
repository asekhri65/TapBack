package com.aaravsekhri.dot.guide

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Tracing for the guide engine, on debug builds only.
 *
 * What this prints — node bounds, matched wording, target identities — is the
 * contents of somebody's screen. It is genuinely useful while working on the
 * tracker and has no business anywhere near a release build, so the whole
 * thing is inert unless the running app is debuggable. That is read from the
 * installed package's own flags rather than a build constant, so a release
 * build cannot be talked into logging by any means.
 */
object GuideLog {

    private const val TAG = "DotGuide"

    @Volatile
    private var enabled = false

    /** Called once when the service connects. */
    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    val isEnabled: Boolean get() = enabled

    /** Logs lazily, so nothing is even formatted in release. */
    inline fun d(area: String, message: () -> String) {
        if (isEnabled) log(area, message())
    }

    @PublishedApi
    internal fun log(area: String, message: String) {
        Log.d(TAG, "[$area] $message")
    }
}
