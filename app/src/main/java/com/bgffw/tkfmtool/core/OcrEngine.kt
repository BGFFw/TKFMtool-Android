package com.bgffw.tkfmtool.core

import android.graphics.Bitmap

/**
 * OCR 引擎抽象。
 *
 * 原版调用的是 AutoX.js 内置的 PaddleOCR（`paddle.ocr(img, cpuThreadNum, useSlim)`）。
 * 原生版默认用 ML Kit 端侧中文识别（模型随 APK 打包，离线、免 Play 服务）。
 * 想换成 PaddleOCR / ONNX Runtime 时，只要再实现一个 [OcrEngine] 即可。
 */
interface OcrEngine {

    /**
     * @param bitmap 待识别图片（已裁剪到词条区域）
     * @return 识别结果，坐标是相对于 [bitmap] 左上角的
     */
    suspend fun recognize(bitmap: Bitmap): List<OcrText>

    fun close()
}
