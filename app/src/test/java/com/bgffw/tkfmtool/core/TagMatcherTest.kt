package com.bgffw.tkfmtool.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 对齐原版 `TKFMtool.js` 的匹配行为。
 *
 * 数据直接用工程里的 assets/characters.tsv（由 tools/extract_dataset.js 从原脚本抽取），
 * 所以这里同时也在守护数据搬运的正确性。
 */
class TagMatcherTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadDataset() {
            val candidates = listOf(
                File("src/main/assets/characters.tsv"),
                File("app/src/main/assets/characters.tsv")
            )
            val file = candidates.firstOrNull { it.exists() }
                ?: error("找不到 characters.tsv（工作目录可能需要是 app/ 或工程根目录）")
            CharacterDatabase.loadFromText(file.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun datasetMatchesOriginalScript() {
        val rows = CharacterDatabase.rows
        assertEquals(77, rows.size)
        assertEquals(77, rows.map { it[0] }.toSet().size)

        val buckets = rows.groupingBy { CharacterDatabase.starOf(it) }.eachCount()
        assertEquals(25, buckets['0'])
        assertEquals(10, buckets['1'])
        assertEquals(14, buckets['2'])
        assertEquals(28, buckets['3'])
    }

    @Test
    fun combinationsMatchOriginalLoopOrder() {
        val combos = TagMatcher.combinations(listOf("a", "b", "c", "d", "e"))
        assertEquals(25, combos.size)
        assertEquals(10, combos.count { it.size == 3 })
        assertEquals(10, combos.count { it.size == 2 })
        assertEquals(5, combos.count { it.size == 1 })

        // 原版先跑完三层 3 选循环，再跑 2 选，最后 1 选
        assertTrue(combos.take(10).all { it.size == 3 })
        assertTrue(combos.subList(10, 20).all { it.size == 2 })
        assertTrue(combos.subList(20, 25).all { it.size == 1 })
    }

    @Test
    fun leaderComboIsHighestPriority() {
        val tags = listOf("领袖", "火属性", "魔族")
        val matchers = TagMatcher.build(tags, CharacterDatabase.rows)
        assertTrue(matchers.isNotEmpty())

        val best = matchers.first()
        assertEquals(3, best.priority)
        assertEquals(tags.toSet(), best.tags.toSet())
        assertTrue(best.matches.any { it[0] == "3☆魔王 巴尔" })
    }

    @Test
    fun threeStarNeverCountsWithoutLeaderTag() {
        val matchers = TagMatcher.build(listOf("中体型", "巨乳"), CharacterDatabase.rows)
        assertTrue(matchers.isNotEmpty())
        for (matcher in matchers) {
            assertTrue(
                "组合 ${matcher.tags} 不该命中 3☆",
                matcher.matches.none { CharacterDatabase.starOf(it) == '3' }
            )
        }
    }

    @Test
    fun sureSrDetection() {
        val sure = TagMatcher.build(listOf("领袖", "火属性", "魔族"), CharacterDatabase.rows).first()
        assertTrue(TagMatcher.isSureSR(sure))

        val zeroOnly = TagMatcher.build(listOf("士兵"), CharacterDatabase.rows).first()
        assertFalse(TagMatcher.isSureSR(zeroOnly))
    }

    @Test
    fun unknownTagProducesNoMatcher() {
        assertTrue(TagMatcher.build(listOf("这个词条不存在"), CharacterDatabase.rows).isEmpty())
    }

    @Test
    fun bracketSuffixIsNormalizedAway() {
        val tags = listOf("领袖", "光属性", "巨乳")
        val target = "3☆天使长 圣米勒"

        // 数据本身确实是带括号的写法，裸词条对不上
        val row = CharacterDatabase.rows.first { it[0] == target }
        assertTrue(row.contains("巨乳(?)"))
        assertFalse(row.contains("巨乳"))
        assertEquals("巨乳", TagVocabulary.normalize("巨乳(?)"))

        // 用精确组合比较，避免「领袖 + 光属性」这种子组合把结果带偏
        val normalized = TagMatcher.findMatches(tags, CharacterDatabase.rows, normalize = true)
        assertTrue("归一化后应能命中 $target", normalized.any { it[0] == target })

        val raw = TagMatcher.findMatches(tags, CharacterDatabase.rows, normalize = false)
        assertFalse("不做归一化时不该命中 $target", raw.any { it[0] == target })
    }

    @Test
    fun findMatchesExcludesThreeStarWithoutLeader() {
        val withLeader = TagMatcher.findMatches(
            listOf("领袖", "光属性"),
            CharacterDatabase.rows
        )
        assertTrue(withLeader.any { CharacterDatabase.starOf(it) == '3' })

        val withoutLeader = TagMatcher.findMatches(
            listOf("光属性"),
            CharacterDatabase.rows
        )
        assertTrue(withoutLeader.isNotEmpty())
        assertTrue(withoutLeader.none { CharacterDatabase.starOf(it) == '3' })
    }

    @Test
    fun sortingKeepsPrecisionFirst() {
        val matchers = TagMatcher.build(listOf("领袖", "火属性", "魔族"), CharacterDatabase.rows)
        // 最高优先级在最前；同优先级内部按命中数量升序
        for (i in 0 until matchers.size - 1) {
            val a = matchers[i]
            val b = matchers[i + 1]
            assertTrue("优先级应降序", a.priority >= b.priority)
            if (a.priority == b.priority) {
                assertTrue("同优先级应按命中数升序", a.matches.size <= b.matches.size)
            }
        }
    }
}
