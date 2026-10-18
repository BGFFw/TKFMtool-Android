package com.bgffw.tkfmtool.core

import android.content.Context

/**
 * 角色词条库，等价于原版 `TKFMtool.js` 里的 `var dataset`。
 *
 * 数据来自 `assets/characters.tsv`；该文件由 `tools/extract_dataset.js`
 * 从原脚本逐字抽取生成，保证不会因为重写而抄错。
 *
 * 格式：一行一个角色，字段用制表符分隔。
 * 第 1 个字段是角色名（形如 `3☆魔王 巴尔`），其余字段是词条。
 * 角色名首字符同时就是稀有度 0/1/2/3，匹配算法依赖这个约定。
 */
object CharacterDatabase {

    const val ASSET_NAME = "characters.tsv"

    private const val SEPARATOR = '\t'

    private var cache: List<List<String>>? = null

    val rows: List<List<String>>
        get() = cache ?: emptyList()

    val isLoaded: Boolean
        get() = cache != null

    fun load(context: Context) {
        if (cache != null) return
        val text = context.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        loadFromText(text)
    }

    fun loadFromText(text: String) {
        val out = ArrayList<List<String>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            val cells = line.split(SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (cells.isNotEmpty()) out.add(cells)
        }
        require(out.isNotEmpty()) { "角色库内容为空" }
        cache = out
    }

    /** 行首字符即稀有度，例如 `3☆魔王 巴尔` → `'3'`。 */
    fun starOf(row: List<String>): Char? = row.firstOrNull()?.firstOrNull()
}
