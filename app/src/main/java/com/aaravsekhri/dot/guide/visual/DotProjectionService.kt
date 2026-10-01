package com.aaravsekhri.dot.guide.visual

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import com.aaravsekhri.dot.R
import com.aaravsekhri.dot.guide.GuideLog

/**
 * The foreground service screen capture runs inside.
 *
 * Android requires this: from Android 10 a projection must belong to a
 * foreground service, from Android 14 that service must declare the
 * `mediaProjection` type and be running *before* the token is redeemed, and
 * throughout there must be a notification the person can see. All of that is
 * the platform making screen capture impossible to do quietly, which is
 * correct, and Dot does nothing to work around it — the notification is plain
 * about what is happening and the system's own recording indicator is left
 * exactly where it is.
 *
 * The service exists only for the duration of one guide's tracking session.
 *
 * **Research code, and deliberately undeclared.** It is not in the manifest,
 * so the system cannot start it and no ordinary guide runs any capture. Lint
 * rightly points out that a service calling `startForeground` needs a
 * `foregroundServiceType`; the answer here is that this service is not
 * declared at all, which is a stronger guarantee than declaring it correctly.
 * Restoring the experiment means restoring the manifest entry and the
 * `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission along with it.
 */
@SuppressLint("ForegroundServiceType")
class DotProjectionService : Service() {

    private var frameSource: MediaProjectionFrameSource? = null

    inner class LocalBinder : Binder() {
        val service: DotProjectionService get() = this@DotProjectionService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        // Only now is the projection token redeemable: Android requires a
        // running mediaProjection foreground service before it will honour
        // one, and startForegroundService() is asynchronous, so anything that
        // needs capture has to wait for this exact moment rather than for a
        // guessed delay.
        notifyForeground()
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        createChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.projection_notification_title))
            .setContentText(getString(R.string.projection_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.projection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.projection_channel_description)
                setShowBadge(false)
            },
        )
    }

    /** Hands the capture pipeline to the caller, once consent has been given. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createFrameSource(
        width: Int,
        height: Int,
        densityDpi: Int,
        onStopped: () -> Unit,
    ): MediaProjectionFrameSource {
        val source = MediaProjectionFrameSource(
            context = applicationContext,
            displayWidth = width,
            displayHeight = height,
            densityDpi = densityDpi,
            onProjectionStopped = onStopped,
        )
        frameSource = source
        return source
    }

    private fun notifyForeground() {
        val pending = synchronized(waiting) {
            isForeground = true
            waiting.toList().also { waiting.clear() }
        }
        pending.forEach { it() }
    }

    override fun onDestroy() {
        // Whatever ended the session, capture stops with it.
        GuideLog.d("visual") { "projection service destroyed; releasing capture" }
        synchronized(waiting) {
            isForeground = false
            waiting.clear()
        }
        frameSource = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "dot_visual_tracking"
        private const val NOTIFICATION_ID = 4711

        @Volatile
        private var isForeground = false
        private val waiting = mutableListOf<() -> Unit>()

        /**
         * Starts the service and runs [onForeground] once it really is in the
         * foreground — not once the start has merely been requested.
         */
        fun start(context: Context, onForeground: () -> Unit = {}) {
            synchronized(waiting) {
                if (isForeground) {
                    onForeground()
                } else {
                    waiting += onForeground
                }
            }
            context.startForegroundService(Intent(context, DotProjectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DotProjectionService::class.java))
        }
    }
}
