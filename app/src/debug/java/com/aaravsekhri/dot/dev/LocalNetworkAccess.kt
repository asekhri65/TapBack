package com.aaravsekhri.dot.dev

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import com.aaravsekhri.dot.BuildConfig

/**
 * Android 17 lets an app that targets it reach a private address — the
 * emulator's 10.0.2.2, a computer on the local network — only once the person
 * has allowed it; until then the connection just times out, which looks exactly
 * like a service that is down. A debug build talks to a planning service at such
 * an address, so it asks, once, the first time it opens. A release build talks to
 * a public HTTPS address and never asks.
 */
object LocalNetworkAccess {

    private const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun requestIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 37 || !isPrivate(BuildConfig.DOT_BACKEND_URL)) return
        if (activity.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) return
        runCatching { activity.requestPermissions(arrayOf(PERMISSION), REQUEST) }
    }

    private const val REQUEST = 37

    private fun isPrivate(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        return host == "localhost" || host == "10.0.2.2" || host.startsWith("192.168.") ||
            host.startsWith("10.") || host.endsWith(".local") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
    }
}
