package com.manga.translate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class ProjectionCaptureSession(
    context: Context,
    private val onProjectionStopped: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onProjectionStopped()
        }
    }

    fun isReady(): Boolean {
        return mediaProjection != null && imageReader != null
    }

    fun prepare(
        manager: MediaProjectionManager,
        resultCode: Int,
        data: Intent,
        metrics: DisplayMetrics,
        pixelFormat: Int
    ): Boolean {
        release()
        val projection = manager.getMediaProjection(resultCode, data) ?: return false
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi.coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, pixelFormat, 2)
        val display = projection.createVirtualDisplay(
            "floating-ocr-capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
        projection.registerCallback(projectionCallback, mainHandler)
        mediaProjection = projection
        imageReader = reader
        virtualDisplay = display
        AppLogger.log("FloatingOCR", "Projection ready ${width}x${height}@${densityDpi}dpi")
        return true
    }

    suspend fun captureCurrentScreen(timeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS): Bitmap? {
        val reader = imageReader ?: return null
        acquireLatestBitmap(reader)?.let { bitmap ->
            AppLogger.log("FloatingOCR", "Captured frame ${bitmap.width}x${bitmap.height} immediately")
            return bitmap
        }
        val bitmap = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap?> { continuation ->
                val listener = ImageReader.OnImageAvailableListener { availableReader ->
                    val captured = acquireLatestBitmap(availableReader) ?: return@OnImageAvailableListener
                    availableReader.setOnImageAvailableListener(null, null)
                    if (continuation.isActive) {
                        continuation.resume(captured)
                    } else {
                        captured.recycleSafely()
                    }
                }
                reader.setOnImageAvailableListener(listener, mainHandler)
                continuation.invokeOnCancellation {
                    reader.setOnImageAvailableListener(null, null)
                }
            }
        }
        reader.setOnImageAvailableListener(null, null)
        if (bitmap == null) {
            AppLogger.log("FloatingOCR", "Capture frame timeout after ${timeoutMs}ms")
        } else {
            AppLogger.log("FloatingOCR", "Captured frame ${bitmap.width}x${bitmap.height} after wait")
        }
        return bitmap
    }

    fun release() {
        val projection = mediaProjection
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun acquireLatestBitmap(reader: ImageReader): Bitmap? {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null
        return image.use(::imageToBitmap)
    }

    private fun imageToBitmap(frame: Image): Bitmap? {
        val plane = frame.planes.firstOrNull() ?: return null
        val width = frame.width
        val height = frame.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val fullWidth = width + rowPadding / pixelStride
        val fullBitmap = Bitmap.createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
        fullBitmap.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(fullBitmap, 0, 0, width, height).also {
            fullBitmap.recycleSafely()
        }
    }

    companion object {
        private const val DEFAULT_CAPTURE_TIMEOUT_MS = 400L
    }
}
