package com.bgffw.tkfmtool.core

/**
 * OCR 文本清洗管线。
 *
 * 顺序与原版一致：
 * 1. 硬编码规则（原版 `correctresult()` 里那几条 `search()` 替换）
 * 2. 用户纠错表（原版 `userCorrect()`，精确匹配整段文本）
 * 3. 词表吸附（原生版新增，可关）
 *
 * 与原始实现的唯一差别：这里在生成 [TagEntry] 时**保留**了词条对应的 OCR 位置块。
 * 原版把纠正后的文本写回 `result`，却在点击时拿纠正后的文本去比对**纠正前**的
 * `_result`，于是被纠正过的词条永远点不到。这里修掉了。
 */
object CorrectionPipeline {

    private const val EPS = 1e-9

    /**
     * 原版 `captureAndOcr()` / `correctresult()` 里写死的三条替换规则。
     *
     * 注意 `碍者` 会命中 `妨碍者` 自身，替换结果相同，无副作用。
     */
    private val HARD_RULES: List<Pair<String, String>> = listOf(
        "碍者" to "妨碍者",
        "周" to "闇属性",
        "間" to "闇属性"
    )

    fun apply(
        blocks: List<OcrText>,
        corrections: List<Correction>,
        vocabulary: List<String>,
        smartSnap: Boolean
    ): List<TagEntry> {
        if (blocks.isEmpty()) return emptyList()

        val enabled = corrections.filter { it.done && it.title.isNotBlank() }
        val out = ArrayList<TagEntry>(blocks.size)

        for (block in blocks) {
            val raw = block.text
                .replace("\n", "")
                .replace(" ", "")
                .replace("\u3000", "")
                .trim()
            if (raw.isEmpty()) continue

            var text = raw

            for ((needle, replacement) in HARD_RULES) {
                if (text.contains(needle)) {
                    text = replacement
                    break
                }
            }

            for (correction in enabled) {
                if (text == correction.title.trim()) {
                    val fixed = correction.summary.trim()
                    if (fixed.isNotEmpty()) text = fixed
                    break
                }
            }

            if (smartSnap) text = snapToVocabulary(text, vocabulary)

            out.add(TagEntry(text = text, raw = raw, block = block))
        }

        return out
    }

    /**
     * 把一段不在词表里的文本吸附到最接近的已知词条。
     *
     * 只在「得分达标」且「最优解唯一」时替换，避免把本来没错的字改坏。
     */
    fun snapToVocabulary(text: String, vocabulary: List<String>): String {
        if (vocabulary.isEmpty() || text.isEmpty()) return text
        if (vocabulary.contains(text)) return text

        var best: String? = null
        var bestScore = -1.0
        var ambiguous = false

        for (candidate in vocabulary) {
            val score = similarity(text, candidate)
            if (score > bestScore + EPS) {
                bestScore = score
                best = candidate
                ambiguous = false
            } else if (best != null && kotlin.math.abs(score - bestScore) <= EPS && candidate != best) {
                ambiguous = true
            }
        }

        val chosen = best ?: return text
        if (ambiguous) return text

        // 字数越少越容易被改错，所以单字一律不动
        val threshold = when {
            text.length >= 3 -> 0.66
            text.length == 2 -> 0.5
            else -> 1.01
        }
        return if (bestScore >= threshold) chosen else text
    }

    fun similarity(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.length]
    }
}
