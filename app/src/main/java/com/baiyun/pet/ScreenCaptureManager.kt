package com.baiyun.pet

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.max

/**
 * 截屏管理器（MediaProjection）。
 *
 * 职责只有「拿到帧」：
 * 1. 用 [MediaProjectionManager.getMediaProjection] 创建投影（授权 Intent 必须由 Activity 拿到结果）；
 * 2. 建 ImageReader + VirtualDisplay，按 [intervalMs] 定时抓最新一帧；
 * 3. 抓到的帧缩到 720p（长边 ≤1280），回调出去。
 *
 * 注意：
 * - 每帧都 `image.close()`，否则 ImageReader 队列很快会被占满，表现为"卡死/不再出帧"；
 * - 是否该截（前台是不是游戏/阅读、电量、亮屏）由外部 [canCapture] 决定，这里不管策略；
 * - 用户在下拉通知栏/系统设置里停止投屏时，MediaProjection 会回调 onStop，这里自动释放。
 */
class ScreenCaptureManager(
    private val context: Context,
    private val canCapture: () -> Boolean,
    private val onFrame: (Bitmap) -> Unit,
    private val onStopped: () -> Unit = {}
) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var running = false

    /** 防止 MediaProjection.Callback.onStop 与主动 stop() 互相递归 */
    private var stopping = false

    /** 截帧间隔，运行时可改（服务会根据前台 App 调频） */
    @Volatile
    var intervalMs: Long = 5_000L

    /**
     * 开始截屏。
     *
     * @param resultCode Activity 拿到的 resultCode
     * @param data       Activity 拿到的 resultData（必须原样传进来）
     */
    fun start(resultCode: Int, data: Intent): Boolean {
        if (running) stop()

        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.w(TAG, "获取 MediaProjection 失败", e)
            null
        } ?: return false

        thread = HandlerThread("baiyun-capture").also { it.start() }
        val captureHandler = Handler(thread!!.looper)
        handler = captureHandler

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户从通知栏/系统里停掉了投屏
                Log.d(TAG, "MediaProjection 被系统停止")
                stop()
                onStopped()
            }
        }, captureHandler)

        val metrics = context.resources.displayMetrics
        val width = CAPTURE_WIDTH
        val height = (metrics.heightPixels.toFloat() / metrics.widthPixels * width)
            .toInt()
            .coerceAtLeast(1)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "BaiyunCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )

        projection = mediaProjection
        running = true
        captureHandler.postDelayed(tick, intervalMs)
        Log.d(TAG, "开始截屏：${width}x$height，间隔 ${intervalMs}ms")
        return true
    }

    /** 修改间隔，下一帧开始生效 */
    fun setInterval(millis: Long) {
        intervalMs = millis.coerceIn(1_000L, 60_000L)
    }

    fun stop() {
        if (stopping) return
        stopping = true
        running = false
        handler?.removeCallbacksAndMessages(null)
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放 VirtualDisplay 失败", e)
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 ImageReader 失败", e)
        }
        imageReader = null
        try {
            projection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止投影失败", e)
        }
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
        stopping = false
        Log.d(TAG, "截屏已停止")
    }

    // ---------------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                if (canCapture()) {
                    captureFrame()?.let(onFrame)
                }
            } catch (e: Exception) {
                Log.w(TAG, "截帧失败", e)
            }
            handler?.postDelayed(this, intervalMs.coerceAtLeast(1_000L))
        }
    }

    /** 抓最新一帧；没有新画面时返回 null */
    private fun captureFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage 失败", e)
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * width

            // buffer 的行宽可能带 padding，先按带 padding 的宽度建图再裁掉
            val paddedWidth = width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)

            val cropped = if (paddedWidth != width) {
                Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            } else {
                padded
            }

            scaleDown(cropped)
        } catch (e: Exception) {
            Log.w(TAG, "图像转换失败", e)
            null
        } finally {
            // 必须 close，否则队列占满后再也拿不到帧
            image.close()
        }
    }

    /** 长边压到 1280（约 720p），省内存也省 OCR 时间 */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_LONG_SIDE) return bitmap
        val scale = MAX_LONG_SIDE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    companion object {
        private const val TAG = "ScreenCapture"

        /** 抓帧宽度，高度按屏幕比例算；720 宽约等于 720p */
        private const val CAPTURE_WIDTH = 720

        /** 长边上限 */
        private const val MAX_LONG_SIDE = 1280

        /** 给 Activity 直接用的静态方法 */
        fun createRequestIntent(context: Context): Intent =
            context.getSystemService(MediaProjectionManager::class.java)
                .createScreenCaptureIntent()
    }
}
