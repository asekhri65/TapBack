package com.aaravsekhri.dot.guide.visual

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.motion.MotionClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Captures the screen and reduces each frame to a small grayscale buffer.
 *
 * Every frame stays in memory for as long as it takes to convert and estimate
 * on, and is then released. Nothing is written to disk, uploaded, attached to
 * analytics or crash reports, or logged. The `Image` from the reader is closed
 * on every path, including the ones where the frame is thrown away.
 *
 * Backpressure is deliberate and aggressive: `acquireLatestImage` discards
 * anything queued behind the newest frame, and a frame arriving while the
 * previous one is still being processed is dropped rather than buffered. A
 * stale frame is worse than no frame — it would move the overlay to where the
 * target used to be.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaProjectionFrameSource(
    private val context: Context,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val densityDpi: Int,
    private val scale: Float = CaptureTransform.DEFAULT_TRACKING_SCALE,
    private val onProjectionStopped: () -> Unit = {},
) : FrameSource {

    private val _frames = MutableSharedFlow<CapturedFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<CapturedFrame> = _frames.asSharedFlow()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    /** True while a frame is being converted, so newer ones are dropped. */
    private val busy = AtomicBoolean(false)

    /**
     * Two buffers, used alternately.
     *
     * A frame is handed to another thread to be measured on, so the buffer it
     * points at cannot be the one the next frame is written into. Two is
     * enough because capture waits for the previous frame to be released
     * before starting another.
     */
    private val luminanceBuffers = arrayOfNulls<ByteArray>(2)
    private var nextBuffer = 0

    /** One row of RGBA, reused, so the plane is read in bulk rather than per pixel. */
    private var rowScratch: ByteArray? = null

    /** The size of the content being mirrored, which the system may change. */
    private var contentSize: Pair<Int, Int> = displayWidth to displayHeight

    /** The geometry the virtual display is currently mirroring. */
    private var readerWidth = 0
    private var readerHeight = 0

    /** How capture pixels relate to screen pixels right now. */
    @Volatile
    private var transform: CaptureTransform = CaptureTransform.fit(
        displayWidth,
        displayHeight,
        displayWidth,
        displayHeight,
    )

    /** Counts frames the reader offered while the previous was still in flight. */
    @Volatile
    var droppedFrames: Long = 0
        private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The person revoked capture, or the system ended it. Everything
            // goes at once.
            GuideLog.d("visual") { "projection stopped by system or user" }
            releaseEverything()
            onProjectionStopped()
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onCapturedContentResize(width: Int, height: Int) {
            GuideLog.d("visual") { "captured content is now ${width}x$height" }
            // A rotation or window resize invalidates the reader's geometry.
            // Anything that goes wrong here has to stay inside the capture
            // thread: this is an experiment layered over a guide that works
            // without it, and it must never take the process down.
            // The callback reports the size of the captured *content*, not the
            // surface it is being drawn into. Passing it straight through
            // resized the virtual display back to the full display size, which
            // quietly undid reduced-resolution capture: frames went back to
            // three megabytes and everything downstream slowed to match.
            runCatching { onContentResized(width, height) }.onFailure { error ->
                GuideLog.d("visual") {
                    "resize failed (${error.javaClass.simpleName}); ending capture"
                }
                releaseEverything()
                onProjectionStopped()
            }
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            GuideLog.d("visual") { "captured content visible=$isVisible" }
            if (!isVisible) {
                // A secure surface, or the content went away. Stop reading.
                releaseEverything()
                onProjectionStopped()
            }
        }
    }

    override suspend fun start(permission: ProjectionPermission) {
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return

        val media = try {
            manager.getMediaProjection(permission.resultCode, permission.data)
        } catch (error: Exception) {
            // A token the system will not honour, or a foreground service the
            // platform has not accepted yet. Neither is worth a crash.
            GuideLog.d("visual") {
                "projection refused (${error.javaClass.simpleName}: ${error.message})"
            }
            null
        } ?: run {
            GuideLog.d("visual") { "projection token refused" }
            return
        }
        projection = media

        val thread = HandlerThread("dot-visual-capture").apply { start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        // Required from Android 14 before a virtual display may be created.
        media.registerCallback(projectionCallback, handler)

        // The virtual display is created at tracking resolution. The
        // compositor scales into it, so no frame is ever full size on the CPU.
        val (captureWidth, captureHeight) =
            CaptureTransform.captureSizeFor(displayWidth, displayHeight, scale)
        startReader(captureWidth, captureHeight, handler)
        GuideLog.d("visual") {
            "capture started ${captureWidth}x$captureHeight " +
                "from ${displayWidth}x$displayHeight scale=$scale"
        }
    }

    private fun startReader(width: Int, height: Int, handler: Handler) {
        // Two buffers: one being read, one being filled. Any more only adds
        // latency, because a queued frame is a frame about to be discarded.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, BUFFER_COUNT)
        imageReader = reader

        reader.setOnImageAvailableListener({ source -> safeOnImageAvailable(source) }, handler)
        readerWidth = width
        readerHeight = height
        transform = CaptureTransform.fit(contentSize.first, contentSize.second, width, height)

        virtualDisplay = projection?.createVirtualDisplay(
            "dot-visual",
            width,
            height,
            // The density is scaled with the surface. Leaving it at the
            // display's value makes the mirrored content lay itself out for a
            // screen far larger than the surface it is being drawn into.
            (densityDpi * scale).roundToInt().coerceAtLeast(1),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
    }

    /**
     * Follows a rotation or window resize.
     *
     * The virtual display is resized and pointed at a fresh reader rather than
     * recreated: a MediaProjection grants exactly one virtual display, and
     * asking it for a second one is a SecurityException.
     */
    /**
     * Frame conversion, with the capture thread protected.
     *
     * A buffer that arrives mid-teardown, or geometry that no longer matches,
     * must end this experiment quietly rather than the whole process.
     */
    private fun safeOnImageAvailable(source: ImageReader) {
        try {
            onImageAvailable(source)
        } catch (error: Exception) {
            GuideLog.d("visual") { "frame dropped (${error.javaClass.simpleName})" }
            busy.set(false)
        }
    }

    /**
     * The mirrored content changed size — a rotation, or a resized window.
     *
     * The tracking surface is recomputed at the same reduced scale for the new
     * content, so capture stays small and the transform still describes the
     * real relationship between the two.
     */
    private fun onContentResized(contentWidth: Int, contentHeight: Int) {
        if (contentWidth <= 0 || contentHeight <= 0) return
        contentSize = contentWidth to contentHeight
        val (captureWidth, captureHeight) =
            CaptureTransform.captureSizeFor(contentWidth, contentHeight, scale)
        resizeReader(captureWidth, captureHeight)
    }

    private fun resizeReader(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == readerWidth && height == readerHeight) return
        val handler = captureHandler ?: return
        val display = virtualDisplay ?: return

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, BUFFER_COUNT)
        reader.setOnImageAvailableListener({ source -> safeOnImageAvailable(source) }, handler)

        val previous = imageReader
        imageReader = reader
        readerWidth = width
        readerHeight = height
        transform = CaptureTransform.fit(contentSize.first, contentSize.second, width, height)
        luminanceBuffers.fill(null)
        rowScratch = null

        display.resize(width, height, (densityDpi * scale).roundToInt().coerceAtLeast(1))
        display.surface = reader.surface
        previous?.close()
    }

    /**
     * Converts the newest frame and lets the rest go.
     *
     * The image is closed on every path — dropped, converted, or failed — so a
     * buffer is never held beyond the moment it is needed.
     */
    private fun onImageAvailable(reader: ImageReader) {
        // Newest only; anything behind it is already out of date.
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        if (!busy.compareAndSet(false, true)) {
            droppedFrames++
            image.close()
            return
        }

        try {
            val frame = toGrayscale(image)
            if (frame != null) _frames.tryEmit(frame)
        } catch (_: Exception) {
            // A malformed or secure frame is skipped silently. Its contents are
            // never inspected or reported.
        } finally {
            image.close()
            busy.set(false)
        }
    }

    /**
     * Reduces an already-small RGBA frame to luminance, one pass, no copy.
     *
     * There is no downscaling left to do here: the virtual display hands over
     * a frame that is already tracking size. The previous version mirrored the
     * display at 1170x2532 and then sampled it down on the capture thread —
     * about three million buffer reads per frame — which was the reason
     * capture topped out around fourteen frames a second however fast the
     * estimator got.
     *
     * The row stride is honoured because ImageReader pads rows, and reading
     * past the padding shears the image.
     */
    private fun toGrayscale(image: Image): CapturedFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val startedAt = MotionClock.nowNanos()
        val width = image.width
        val height = image.height
        val needed = width * height

        val slot = nextBuffer
        nextBuffer = 1 - nextBuffer
        val output = luminanceBuffers[slot]
            ?.takeIf { it.size == needed }
            ?: ByteArray(needed).also { luminanceBuffers[slot] = it }

        // A row at a time into an ordinary array, then read from that.
        // Indexed reads on a direct ByteBuffer cross into native code each
        // time; at three reads per pixel that was most of a fifteen
        // millisecond conversion. One bulk copy per row and the rest is plain
        // array arithmetic.
        val rowBytes = rowStride
        val row = rowScratch?.takeIf { it.size >= rowBytes }
            ?: ByteArray(rowBytes).also { rowScratch = it }

        var outIndex = 0
        for (y in 0 until height) {
            val rowStart = y * rowStride
            if (rowStart >= buffer.limit()) {
                java.util.Arrays.fill(output, outIndex, outIndex + width, 0)
                outIndex += width
                continue
            }
            val available = minOf(rowBytes, buffer.limit() - rowStart)
            buffer.position(rowStart)
            buffer.get(row, 0, available)

            var offset = 0
            for (x in 0 until width) {
                if (offset + 2 >= available) {
                    output[outIndex++] = 0
                    offset += pixelStride
                    continue
                }
                val r = row[offset].toInt() and 0xFF
                val g = row[offset + 1].toInt() and 0xFF
                val b = row[offset + 2].toInt() and 0xFF
                // Rec. 601 luma, integer arithmetic.
                output[outIndex++] = (((r * 77) + (g * 150) + (b * 29)) shr 8).toByte()
                offset += pixelStride
            }
        }

        return CapturedFrame(
            width = width,
            height = height,
            luminance = output,
            transform = transform,
            // The same monotonic clock Choreographer frame times come from,
            // so an age computed against a display frame means something.
            timestampNanos = MotionClock.nowNanos(),
            conversionNanos = MotionClock.nowNanos() - startedAt,
        )
    }

    override suspend fun stop() {
        releaseEverything()
    }

    /**
     * Releases everything, in the order that avoids callbacks firing into
     * half-torn-down state. Safe to call more than once.
     */
    private fun releaseEverything() {
        readerWidth = 0
        readerHeight = 0
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        projection?.let { media ->
            try {
                media.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
                // Already gone.
            }
            media.stop()
        }
        projection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        // The last frame does not outlive the session.
        luminanceBuffers.fill(null)
        busy.set(false)
    }

    private companion object {
        const val BUFFER_COUNT = 2
    }
}
