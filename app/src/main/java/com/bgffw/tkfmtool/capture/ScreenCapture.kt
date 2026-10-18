package com.bgffw.tkfmtool.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * MediaProjection 屏幕采集。
 *
 * 对应原版的 `requestScreenCapture()` + `captureScreen()`：
 * 授权一次、常驻一个 VirtualDisplay，之后每次 [capture] 只是从最新一帧取图。
 */
class ScreenCapture(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    var width: Int = 0
        private set

    var height: Int = 0
        private set

    val isReady: Boolean
        get() = imageReader != null && virtualDisplay != null

    fun start(resultCode: Int, data: Intent): Boolean {
        release()

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager ?: return false

        val newProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            null
        } ?: return false

        val size = screenSize(context)
        width = size.first
        height = size.second
        if (width <= 0 || height <= 0) {
            runCatching { newProjection.stop() }
            return false
        }

        val dpi = context.resources.displayMetrics.densityDpi

        return try {
            // Android 14 起必须先注册回调，再创建 VirtualDisplay
            newProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // 用户在系统层点了「停止共享」，通道失效
                }
            }, Handler(Looper.getMainLooper()))

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            val display = newProjection.createVirtualDisplay(
                "tkfm-capture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            imageReader = reader
            virtualDisplay = display
            projection = newProjection
            true
        } catch (t: Throwable) {
            runCatching { newProjection.stop() }
            release()
            false
        }
    }

    /** 取最新一帧；超时或通道未就绪返回 null。 */
    fun capture(timeoutMs: Long = 2_000L): Bitmap? {
        val reader = imageReader ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var image: Image? = null

        while (SystemClock.elapsedRealtime() < deadline) {
            image = try {
                reader.acquireLatestImage()
            } catch (t: Throwable) {
                null
            }
            if (image != null) break
            try {
                Thread.sleep(40L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }

        val frame = image ?: return null
        return try {
            toBitmap(frame)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { frame.close() }
        }
    }

    fun release() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.close() }
        imageReader = null

        runCatching { projection?.stop() }
        projection = null
    }

    private fun toBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null

        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        if (paddedWidth == image.width) return padded

        val exact = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return exact
    }

    companion object {

        fun screenSize(context: Context): Pair<Int, Int> {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
        }
    }
}
