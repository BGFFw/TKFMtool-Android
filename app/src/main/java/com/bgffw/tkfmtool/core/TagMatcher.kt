package com.bgffw.tkfmtool.core

/**
 * 词条组合匹配器，逐行对应原版 `TKFMtool.js` 里的
 * `Tag_matcher` / `tags_find` / `ifsureSR` / `tagstomatchs`。
 *
 * 原版行为要点（这里全部保留）：
 *
 * 1. 只取前 5 个 OCR 词条，按「取 3 个 / 取 2 个 / 取 1 个」的顺序生成全部组合，
 *    一共 C(5,3)+C(5,2)+C(5,1) = 25 个组合。
 * 2. 某个组合命中一行数据的条件是：该行包含组合里的每一个词条。
 * 3. 优先级 `pre`：
 *    - 命中 0☆ 且 `pre < 0` → 0
 *    - 命中 1☆ 且 `pre < 1` → 1
 *    - 命中 2☆ 且 `pre < 2` → 2
 *    - 命中 3☆ 且组合里含「领袖」→ 3
 *    - 其余情况（比如组合不含「领袖」却命中 3☆）该行完全不参与
 * 4. `pre` 仍为 -1 的组合直接丢弃。
 * 5. 排序：优先级降序；同优先级下匹配到的角色数升序（越少越“精准”）。
 */
class TagMatcher(val tags: List<String>) {

    val matches: MutableList<List<String>> = ArrayList()

    var priority: Int = -1
        internal set

    companion object {

        /** 原版写死了 `for (i = 0; i < 5; i++)`，即最多 5 个词条。 */
        const val MAX_TAGS = 5

        const val LEADER_TAG = "领袖"

        /**
         * @param detected  OCR 得到并按阅读顺序排好的词条文本
         * @param dataset   角色库（[CharacterDatabase.rows]）
         * @param normalize 是否对词条和角色库做括号后缀归一化
         */
        fun build(
            detected: List<String>,
            dataset: List<List<String>>,
            normalize: Boolean = true
        ): List<TagMatcher> {
            val usable = detected.filter { it.isNotEmpty() }.take(MAX_TAGS)
            if (usable.isEmpty() || dataset.isEmpty()) return emptyList()

            val pick = if (normalize) usable.map(TagVocabulary::normalize) else usable
            val prepared = if (normalize) {
                dataset.map { row -> row.map(TagVocabulary::normalize) }
            } else {
                dataset
            }

            val matchers = ArrayList<TagMatcher>(30)
            for (combo in combinations(pick)) {
                val matcher = TagMatcher(combo)
                val hasLeader = combo.contains(LEADER_TAG)
                for (row in prepared) {
                    if (!containsAll(row, combo)) continue
                    val star = CharacterDatabase.starOf(row) ?: continue
                    // 原版：非 3☆ 一律先进 matchs，再单独按星级抬 pre
                    if (star != '3') matcher.matches.add(row)
                    when (star) {
                        '0' -> if (matcher.priority < 0) matcher.priority = 0
                        '1' -> if (matcher.priority < 1) matcher.priority = 1
                        '2' -> if (matcher.priority < 2) matcher.priority = 2
                        '3' -> if (hasLeader) {
                            matcher.priority = 3
                            matcher.matches.add(row)
                        }
                    }
                }
                if (matcher.priority >= 0) matchers.add(matcher)
            }

            return matchers.sortedWith(
                compareByDescending<TagMatcher> { it.priority }
                    .thenBy { it.matches.size }
            )
        }

        /**
         * 是否“必出 SR 及以上”：只要有一个匹配角色是 0☆ 或 1☆，就不算。
         * 对应原版 `ifsureSR`。
         */
        fun isSureSR(matcher: TagMatcher): Boolean {
            for (row in matcher.matches) {
                when (CharacterDatabase.starOf(row)) {
                    '0', '1' -> return false
                    else -> Unit
                }
            }
            return true
        }

        /**
         * 按用户手选的词条组合重新求匹配列表，用于结果面板里的行点击。
         * 对应原版 `tagstomatchs`。
         */
        fun findMatches(
            tags: List<String>,
            dataset: List<List<String>>,
            normalize: Boolean = true
        ): List<List<String>> {
            if (tags.isEmpty() || dataset.isEmpty()) return emptyList()

            val pick = if (normalize) tags.map(TagVocabulary::normalize) else tags
            val prepared = if (normalize) {
                dataset.map { row -> row.map(TagVocabulary::normalize) }
            } else {
                dataset
            }

            val hasLeader = pick.contains(LEADER_TAG)
            val out = ArrayList<List<String>>()
            for (row in prepared) {
                if (!containsAll(row, pick)) continue
                val star = CharacterDatabase.starOf(row) ?: continue
                if (star != '3') out.add(row)
                else if (hasLeader) out.add(row)
            }
            return out
        }

        /**
         * 生成组合：先全部 3 选、再全部 2 选、最后逐个单选。
         * 顺序与原版三层 for 循环一致，同分时排序稳定性也一致。
         */
        fun combinations(tags: List<String>): List<List<String>> {
            val n = tags.size
            val out = ArrayList<List<String>>(n * n)
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    for (k in j + 1 until n) {
                        out.add(listOf(tags[i], tags[j], tags[k]))
                    }
                }
            }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    out.add(listOf(tags[i], tags[j]))
                }
            }
            for (i in 0 until n) {
                out.add(listOf(tags[i]))
            }
            return out
        }

        private fun containsAll(row: List<String>, tags: List<String>): Boolean {
            for (tag in tags) {
                if (!row.contains(tag)) return false
            }
            return true
        }
    }
}
