package com.aaravsekhri.dot.guide.ai

/**
 * Android asking the person to allow something — location, the camera,
 * notifications — told from the permission window's own title, the one
 * thing about it Android shares.
 *
 * TapBack explains the kinds of choice such a window offers and leaves the
 * choice to the person. It never recommends allowing, and it cannot point
 * inside the window: current Android keeps a permission window's buttons
 * from every service that is not an accessibility tool, so their positions
 * are unknown and none is invented.
 */
object PermissionPrompt {

    enum class Kind { Location, Camera, Microphone, Notifications, Contacts, Photos, Other }

    private val ASKING = Regex("(?i)^\\s*allow\\b.+\\?\\s*$")
    private val PERMISSION = Regex("(?i)\\bpermission\\b")

    fun isPermission(title: String): Boolean = ASKING.containsMatchIn(title) || PERMISSION.containsMatchIn(title)

    fun kindOf(title: String): Kind {
        val t = title.lowercase()
        return when {
            "location" in t -> Kind.Location
            "camera" in t || "take pictures" in t || "record video" in t -> Kind.Camera
            "microphone" in t || "record audio" in t -> Kind.Microphone
            "notification" in t -> Kind.Notifications
            "contacts" in t -> Kind.Contacts
            "photos" in t || "videos" in t || "media" in t -> Kind.Photos
            else -> Kind.Other
        }
    }
}
