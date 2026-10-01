package com.aaravsekhri.dot.dev

import android.app.Activity

/** A release build reaches a public address and needs nothing; see the debug copy. */
object LocalNetworkAccess {
    @Suppress("UNUSED_PARAMETER")
    fun requestIfNeeded(activity: Activity) = Unit
}
