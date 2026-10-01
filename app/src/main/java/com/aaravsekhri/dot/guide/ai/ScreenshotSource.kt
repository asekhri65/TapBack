package com.aaravsekhri.dot.guide.ai

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import com.aaravsekhri.dot.guide.model.Bounds
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.aaravsekhri.dot.guide.GuideLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * An occasional picture of a settled screen, when the platform will give one.
 *
 * Not screen recording. One frame, taken when the page has stopped and a
 * decision is about to be asked for, handed straight to the request and then
 * dropped. Nothing is written to disk and nothing is kept between requests.
 *
 * Feature-gated twice over. `takeScreenshot` arrived in API 30 and Dot supports
 * 26, so on older phones there is simply no image and the guide runs on the
 * accessibility tree alone — which it must be able to do anyway, because the
 * platform refuses the image on plenty of screens. `takeScreenshotOfWindow`
 * arrived in 34 and is preferred where available: a window is a tighter frame
 * than a display and excludes Dot's own overlays without any masking.
 *
 * The platform refuses outright on windows an app has marked secure. That
 * refusal is treated as information rather than an inconvenience: see
 * [SensitiveScreenPolicy].
 */
class ScreenshotSource(private val service: AccessibilityService) {

    sealed interface Result {
        /**
         * A picture of the screen, already encoded.
         *
         * @param masked true when Dot's own overlays were painted out of it,
         *   which happens only on the whole-display path used before API 34.
         */
        data class Image(val jpeg: ByteArray, val takenAtMs: Long, val masked: Boolean = false) : Result

        /** The app marked this window secure. Dot stops looking at it. */
        data object Refused : Result

        /** No image this time; carry on with the accessibility tree. */
        data class Unavailable(val why: String) : Result
    }

    private var lastAttemptAtMs: Long = 0L

    /** True on builds where an image can be asked for at all. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Takes one, or says why not.
     *
     * @param windowId the window the observation describes. On API 34 and
     *   above the picture is of that window alone, which is both tighter and
     *   free of Dot's own overlays.
     * @param dotOverlays where Dot's own card and outline are, in screen
     *   pixels. On the whole-display path they are in the picture, and they
     *   are painted out: the model is asked what is on somebody's screen, not
     *   what Dot has put on top of it, and an orange outline in the picture is
     *   exactly the kind of thing it would otherwise try to act on.
     */
    suspend fun capture(windowId: Int, dotOverlays: List<Bounds> = emptyList()): Result {
        if (!supported) return Result.Unavailable("this version of Android cannot")

        // The platform throttles these, and answers a too-soon request with an
        // error rather than a picture. Asking more often than a screen settles
        // is pointless anyway.
        val now = System.currentTimeMillis()
        if (now - lastAttemptAtMs < MIN_INTERVAL_MS) {
            return Result.Unavailable("asked too soon")
        }
        lastAttemptAtMs = now

        return try {
            // The platform answers through a callback, and has been seen not to
            // answer at all — while another accessibility client suppresses
            // this one, for instance. Waiting for ever there wedged the whole
            // session, so the wait is bounded and a missing answer is simply
            // no picture.
            kotlinx.coroutines.withTimeoutOrNull(CALLBACK_TIMEOUT_MS) { takeOne(windowId, dotOverlays) }
                ?: Result.Unavailable("the platform did not answer")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Result.Unavailable("the platform declined")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeOne(windowId: Int, dotOverlays: List<Bounds>): Result =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val windowOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && windowId != -1
            val executor = Executor { it.run() }
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val mask = if (windowOnly) emptyList() else dotOverlays
                    val encoded = try {
                        encode(result, mask)
                    } catch (_: Throwable) {
                        null
                    }
                    // A picture that came back as one flat colour is not a
                    // picture of anybody's screen. Seen on the emulator after
                    // a secure window: every capture black. Sent, it would be
                    // believed; dropped, the words still describe the screen.
                    if (encoded === BLANK) {
                        if (continuation.isActive) {
                            continuation.resumeWith(kotlin.Result.success(Result.Unavailable("the picture came back blank")))
                        }
                        return
                    }
                    if (continuation.isActive) {
                        continuation.resumeWith(
                            kotlin.Result.success(
                                encoded?.let { Result.Image(it, result.timestamp, masked = mask.isNotEmpty()) }
                                    ?: Result.Unavailable("the frame could not be read"),
                            ),
                        )
                    }
                }

                override fun onFailure(errorCode: Int) {
                    val result = when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> Result.Refused
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                            Result.Unavailable("asked too soon")

                        else -> Result.Unavailable("error $errorCode")
                    }
                    GuideLog.d("observe") { "no screenshot: $result" }
                    if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(result))
                }
            }

            if (windowOnly) {
                service.takeScreenshotOfWindow(windowId, executor, callback)
            } else {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
            }
        }

    /**
     * Hardware buffer to a small JPEG.
     *
     * Downscaled hard. The model is being asked which row to point at, not to
     * read fine print, and a full-resolution phone screen is a megabyte of
     * upload for no extra answer.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun encode(result: AccessibilityService.ScreenshotResult, mask: List<Bounds>): ByteArray? {
        val buffer = result.hardwareBuffer ?: return null
        return try {
            val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val scale = (MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            val readable = Bitmap.createScaledBitmap(
                bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return null,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true,
            ).let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
            if (isBlank(readable)) return BLANK
            if (mask.isNotEmpty()) {
                val canvas = android.graphics.Canvas(readable)
                val paint = android.graphics.Paint().apply { color = MASK_COLOUR }
                mask.forEach { m ->
                    canvas.drawRect(m.left * scale, m.top * scale, m.right * scale, m.bottom * scale, paint)
                }
            }
            ByteArrayOutputStream().use { out ->
                readable.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                out.toByteArray()
            }
        } finally {
            buffer.close()
        }
    }

    /** True when a grid of samples across the picture finds almost no variety. */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val colours = HashSet<Int>()
        val stepX = (bitmap.width / 20).coerceAtLeast(1)
        val stepY = (bitmap.height / 40).coerceAtLeast(1)
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                colours += bitmap.getPixel(x, y) and 0xF0F0F0
                if (colours.size > BLANK_MAX_COLOURS) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private companion object {
        /** Marks an encode that found nothing but one colour. */
        val BLANK = ByteArray(0)

        /** Any real screen, even a dark one, has more than this across 800 samples. */
        const val BLANK_MAX_COLOURS = 2

        /**
         * The platform's own throttle is a third of a second. This is well
         * past it, because an image is wanted once per settled screen, not per
         * frame.
         */
        const val MIN_INTERVAL_MS = 1_200L

        const val CALLBACK_TIMEOUT_MS = 2_500L

        const val MAX_EDGE = 900
        const val QUALITY = 70

        /** A flat mid-grey: plainly not part of anybody's app. */
        const val MASK_COLOUR = 0xFF808080.toInt()
    }
}


