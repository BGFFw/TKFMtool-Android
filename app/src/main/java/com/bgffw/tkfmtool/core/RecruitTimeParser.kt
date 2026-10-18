package com.bgffw.tkfmtool.core

/**
 * 从「招募时间」区域的 OCR 结果里解析出当前选中的小时数（01–09）。
 *
 * 这块很脆：ML Kit 对孤立的大号数字判定不稳，真机上出现过把 `01` 读成
 * 字母 `IO` 的情况（`0`→`O`、`1`→`I`）。所以这里做三层处理：
 *
 * 1. 先把易混字符折回数字（`O`→`0`、`I`→`1` …），再按纯数字块判定；
 * 2. 折回后仍不成数，就把所有块拼起来再找；
 * 3. 最后兜底：小时数一定是 `0X` 形式，`X` 是 1..9 里**唯一**的非零数字，
 *    所以只要文本里恰有一个 1–9 的数字，就认定它是 `0X`。
 *
 * 第 3 层专门用于 `IO` 这种整体被认反的情况：折回得到 `10`，正常解析会失败，
 * 但非零数字只有 `1`，于是正确判为 `01`。
 */
object RecruitTimeParser {

    private val PURE_NUMBER = Regex("^[0-9]{1,2}$")
    private val ANY_NUMBER = Regex("[0-9]{1,2}")

    /** 合法取值：01..09。 */
    val VALID_TIMES: List<String> = (1..9).map { it.toString().padStart(2, '0') }

    /** OCR 常见的「字母当数字」误认。 */
    private val LOOK_ALIKE: Map<Char, Char> = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'I' to '1', 'i' to '1', 'l' to '1', 'L' to '1', '|' to '1', '!' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'B' to '8',
        'g' to '9', 'q' to '9'
    )

    /** 把易混字符折回数字。 */
    fun foldLookAlikes(text: String): String = buildString(text.length) {
        for (ch in text) append(LOOK_ALIKE[ch] ?: ch)
    }

    fun parse(blocks: List<OcrText>): String? {
        if (blocks.isEmpty()) return null

        val ordered = blocks.sortedWith(compareBy({ it.top }, { it.left }))

        // 1) 折回后能直接成数的块
        for (block in ordered) {
            val text = foldLookAlikes(block.text.trim())
            if (!PURE_NUMBER.matches(text)) continue
            normalize(text)?.let { return it }
        }

        // 2) 所有块拼起来再找
        val compact = buildString {
            for (block in ordered) {
                for (ch in block.text) {
                    if (!ch.isWhitespace()) append(ch)
                }
            }
        }
        val folded = foldLookAlikes(compact)
        ANY_NUMBER.find(folded)?.value?.let { candidate ->
            normalize(candidate)?.let { return it }
        }

        // 3) 兜底：小时是 0X，X 是唯一的非零数字
        //    用字符个数而不是去重集合，"99" 这种必须判为无效
        val significant = folded.filter { it in '1'..'9' }
        if (significant.length == 1) return "0" + significant

        return null
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
