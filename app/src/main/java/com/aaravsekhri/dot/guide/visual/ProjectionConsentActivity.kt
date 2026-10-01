package com.aaravsekhri.dot.guide.visual

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.aaravsekhri.dot.guide.GuideLog

/**
 * Puts Android's own screen-capture dialog in front of the person.
 *
 * A transparent, no-UI activity, because the system dialog is the interface
 * here. Dot's own explanation has already been shown and accepted before this
 * is reached — this exists purely to host the platform consent flow, which
 * must be launched from an Activity.
 *
 * Declining is a completely ordinary outcome: the guide carries on using
 * accessibility alone.
 */
class ProjectionConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            finishWith(null)
            return
        }

        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Activity result APIs; this Activity has no lifecycle owner to use the newer one")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) {
            finishWith(null)
            return
        }

        if (resultCode == RESULT_OK && data != null) {
            GuideLog.d("visual") { "capture consent granted for this session" }
            finishWith(ProjectionPermission(resultCode, data))
        } else {
            // Declined. Accessibility-only tracking continues unchanged.
            GuideLog.d("visual") { "capture consent declined; staying accessibility-only" }
            finishWith(null)
        }
    }

    private fun finishWith(permission: ProjectionPermission?) {
        // The coordinator tells the service, which starts the foreground
        // service and only then redeems the token.
        VisualTrackingCoordinator.onConsentResult(permission)
        finish()
    }

    companion object {
        private const val REQUEST_CAPTURE = 0xD07
        fun launch(context: android.content.Context) {
            context.startActivity(
                // Its own task, so dismissing the system dialog returns the
                // person to the app they were being guided through rather
                // than pulling Dot in front of it.
                Intent(context, ProjectionConsentActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
            )
        }
    }
}
