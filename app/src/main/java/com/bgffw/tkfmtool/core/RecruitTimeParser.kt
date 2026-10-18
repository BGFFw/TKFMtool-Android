package com.bgffw.tkfmtool.core

/**
 * 从「招募时间」区域的 OCR 结果里解析出当前选中的小时数（01–09）。
 *
 * 这块不能直接「取第一个数字」：ML Kit 经常把 `09` 拆成 `0` 和 `9` 两个块，
 * 也可能把区域里的其它文字一起吐出来。原版 AutoX 用 `paddle.ocrText` 取
 * `result[0]`，在 Paddle 上刚好能用，换成 ML Kit 后就会读成 `0` 而失效。
 *
 * 这里的策略：
 * 1. 优先取能独立成数的块（`^[0-9]{1,2}$`），按位置从左到右、从上到下；
 * 2. 没有这样的块时，把所有文本拼起来去掉空白再找数字；
 * 3. 统一做 `60 → 09` 特判、补零、范围校验。
 */
object RecruitTimeParser {

    private val PURE_NUMBER = Regex("^[0-9]{1,2}$")
    private val ANY_NUMBER = Regex("[0-9]{1,2}")

    /** 合法取值：01..09。 */
    val VALID_TIMES: List<String> = (1..9).map { it.toString().padStart(2, '0') }

    fun parse(blocks: List<OcrText>): String? {
        if (blocks.isEmpty()) return null

        val ordered = blocks.sortedWith(compareBy({ it.top }, { it.left }))

        for (block in ordered) {
            val text = block.text.trim()
            if (!PURE_NUMBER.matches(text)) continue
            normalize(text)?.let { return it }
        }

        val compact = buildString {
            for (block in ordered) {
                for (ch in block.text) {
                    if (!ch.isWhitespace()) append(ch)
                }
            }
        }
        val found = ANY_NUMBER.find(compact)?.value ?: return null
        return normalize(found)
    }

    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.all { it.isDigit() }) return null

        // 原版特判：OCR 会把倒过来的 09 读成 60
        val fixed = if (text == "60") "09" else text
        val padded = if (fixed.length == 1) "0$fixed" else fixed
        return if (VALID_TIMES.contains(padded)) padded else null
    }

    fun isValid(raw: String?): Boolean = raw != null && VALID_TIMES.contains(raw)
}
