package com.bgffw.tkfmtool.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 端侧中文 OCR。
 *
 * 用 `text-recognition-chinese` 这个 bundled 版本：识别模型直接打进 APK，
 * 运行时不联网、不依赖 Google Play 服务。
 *
 * 返回粒度是「行」：一行通常就是游戏里的一个词条。
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): List<OcrText> =
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(input)
                .addOnSuccessListener { text ->
                    if (cont.isActive) cont.resume(toBlocks(text))
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
        }

    private fun toBlocks(text: com.google.mlkit.vision.text.Text): List<OcrText> {
        val out = ArrayList<OcrText>(8)
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: block.boundingBox ?: continue
                val content = line.text.replace("\n", "").trim()
                if (content.isEmpty()) continue
                out.add(OcrText(content, box.left, box.top, box.right, box.bottom))
            }
        }
        out.sortWith(compareBy({ it.top }, { it.left }))
        return out
    }

    override fun close() {
        runCatching { recognizer.close() }
    }
}
