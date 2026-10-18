package com.bgffw.tkfmtool.core

/**
 * 词条词表与文本归一化。
 *
 * 原版同一批角色里，作者给对方写了两套写法，例如一行写 `巨乳`、另一行写 `巨乳(?)`。
 * 原版用精确字符串比较（`dataset[j].includes(element)`），于是 `巨乳(?)` 这一项
 * 永远不会被 OCR 出来的 `巨乳` 命中，那些角色实际上匹配不到。
 *
 * 这里统一把 `(?)`、`(80%)` 这类作者手写的不确定后缀剥掉，让匹配按真实词条走。
 */
object TagVocabulary {

    /** 去掉末尾括号注释：`巨乳(?)` → `巨乳`，`支援(80%)` → `支援`。 */
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        var end = text.length
        for (open in intArrayOf(text.indexOf('('), text.indexOf('（'))) {
            if (open > 0 && open < end) end = open
        }
        if (end == text.length) return text
        val trimmed = text.substring(0, end).trim()
        return if (trimmed.isEmpty()) text else trimmed
    }

    /** 从角色库里抽出全部规范词条，保持首次出现顺序。 */
    fun build(rows: List<List<String>>): List<String> {
        val set = LinkedHashSet<String>()
        for (row in rows) {
            for (i in 1 until row.size) {
                val tag = normalize(row[i])
                if (tag.isNotEmpty()) set.add(tag)
            }
        }
        return set.toList()
    }
}
