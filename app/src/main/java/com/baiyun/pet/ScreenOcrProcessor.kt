package com.baiyun.pet

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * 截屏内容的识别层，两种方案：
 *
 * A. 本地：ML Kit 中文文字识别（免费、离线、隐私好），只取置信度 > 0.7 的行；
 * B. 云端：把图压成 JPEG(base64) 交给 DeepSeek 视觉接口描述画面。
 *
 * 两种都只负责「输出结果字符串列表」，不关心截屏策略。
 */
object ScreenOcrProcessor {

    private const val TAG = "ScreenOcr"

    /** 置信度阈值（需求：> 0.7） */
    private const val MIN_CONFIDENCE = 0.7f

    /** 云端视觉：JPEG 质量 */
    private const val CLOUD_JPEG_QUALITY = 60

    /** 云端视觉：短边最大 512px */
    private const val CLOUD_MAX_SHORT_SIDE = 512

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // ---------------------------------------------------------------------
    // 方案 A：本地 ML Kit
    // ---------------------------------------------------------------------

    /**
     * 识别 Bitmap 里的中文，回调在主线程。
     * 只返回置信度 > 0.7 的文字行；识别失败走 onError。
     */
    fun recognize(
        bitmap: Bitmap,
        onResult: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val lines = result.textBlocks
                    .flatMap { it.lines }
                    .filter { (it.confidence ?: 0f) > MIN_CONFIDENCE }
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }
                Log.d(TAG, "ML Kit 识别到 ${lines.size} 行")
                onResult(lines)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ML Kit 识别失败", e)
                onError(e.message ?: "识别失败")
            }
    }

    // ---------------------------------------------------------------------
    // 方案 B：云端视觉
    // ---------------------------------------------------------------------

    /**
     * 压缩成 base64 JPEG：短边压到 ≤512、quality 60，省流量。
     * 调用方负责 recycle 原 bitmap（这里会新建缩放后的图并回收它）。
     */
    fun encodeJpegBase64(
        bitmap: Bitmap,
        maxShortSide: Int = CLOUD_MAX_SHORT_SIDE,
        quality: Int = CLOUD_JPEG_QUALITY
    ): String {
        val shortSide = min(bitmap.width, bitmap.height)
        val scaled = if (shortSide > maxShortSide) {
            val scale = maxShortSide.toFloat() / shortSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it != bitmap) bitmap.recycle() }
        } else {
            bitmap
        }

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
        val bytes = output.toByteArray()
        if (scaled != bitmap) scaled.recycle()

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
