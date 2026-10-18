package com.bgffw.tkfmtool.core

/**
 * 一段被识别出来的文字及其在**屏幕坐标系**下的包围盒。
 *
 * 原版用 `paddle.ocr` 返回的 `element.text` + `element.bounds`，这里等价。
 */
data class OcrText(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun shifted(dx: Int, dy: Int): OcrText =
        OcrText(text, left + dx, top + dy, right + dx, bottom + dy)

    fun scaled(factor: Float): OcrText {
        if (factor == 1f) return this
        return OcrText(
            text,
            (left * factor).toInt(),
            (top * factor).toInt(),
            (right * factor).toInt(),
            (bottom * factor).toInt()
        )
    }
}

/**
 * 最终参与匹配与点击的词条条目。
 *
 * [text]  用户纠错 / 词表纠正之后的规范词条（用于匹配、用于点击定位）
 * [raw]   OCR 原始文本（便于排查）
 * [block] 该词条在屏幕上的位置（用于点击）
 */
data class TagEntry(
    val text: String,
    val raw: String,
    val block: OcrText
)
