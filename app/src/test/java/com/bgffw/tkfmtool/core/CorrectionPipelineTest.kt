package com.bgffw.tkfmtool.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验文本清洗管线与原版 `correctresult()` / `userCorrect()` 的等价性，
 * 以及原生版新增的词表吸附不会乱改文本。
 */
class CorrectionPipelineTest {

    private val vocabulary = listOf(
        "领袖", "火属性", "水属性", "风属性", "光属性", "闇属性",
        "魔族", "人类", "亚人", "中体型", "小体型", "巨乳", "美乳", "贫乳",
        "攻击者", "守护者", "治疗者", "妨碍者", "辅助者",
        "输出", "爆发力", "生存力", "回击", "防御", "保护", "回复", "支援",
        "群体攻击", "越战越强", "削弱", "干扰"
    )

    private fun build(
        texts: List<String>,
        corrections: List<Correction> = emptyList(),
        smart: Boolean = true
    ): List<TagEntry> {
        val blocks = texts.mapIndexed { index, text ->
            OcrText(text, index * 10, index * 20, index * 10 + 100, index * 20 + 40)
        }
        return CorrectionPipeline.apply(blocks, corrections, vocabulary, smart)
    }

    private fun single(
        text: String,
        corrections: List<Correction> = emptyList(),
        smart: Boolean = true
    ): String = build(listOf(text), corrections, smart).single().text

    @Test
    fun hardRulesMatchOriginal() {
        // 原版：碍者 → 妨碍者，周 → 闇属性，間 → 闇属性
        assertEquals("妨碍者", single("防碍者"))
        assertEquals("妨碍者", single("妨碍者"))
        assertEquals("闇属性", single("間属性"))
        assertEquals("闇属性", single("周属性"))
    }

    @Test
    fun userCorrectionAppliesExactMatchOnly() {
        val fixes = listOf(Correction("留院", "削弱", "#2196f3", true))
        assertEquals("削弱", single("留院", fixes))
        // 原版是整段精确相等才替换，包含关系不触发
        assertEquals("留院吧", single("留院吧", fixes))
    }

    @Test
    fun disabledCorrectionIsIgnored() {
        val fixes = listOf(Correction("留院", "削弱", "#2196f3", false))
        assertEquals("留院", single("留院", listOf(), smart = false))
        assertEquals("留院", single("留院", fixes, smart = false))
    }

    @Test
    fun whitespaceIsStripped() {
        assertEquals("攻击者", single("攻 击 者"))
        assertEquals("闇属性", single("間 属性"))
    }

    @Test
    fun vocabularySnapFixesSingleCharacterErrors() {
        assertEquals("妨碍者", CorrectionPipeline.snapToVocabulary("妨碍着", vocabulary))
        assertEquals("削弱", CorrectionPipeline.snapToVocabulary("消弱", vocabulary))
        assertEquals("越战越强", CorrectionPipeline.snapToVocabulary("越战越强", vocabulary))
    }

    @Test
    fun vocabularySnapLeavesUnknownAndAmbiguousAlone() {
        assertEquals("随便", CorrectionPipeline.snapToVocabulary("随便", vocabulary))
        assertEquals("攻击者", CorrectionPipeline.snapToVocabulary("攻击者", vocabulary))
    }

    @Test
    fun smartSnapCanBeDisabled() {
        assertEquals("妨碍着", single("妨碍着", smart = false))
        assertEquals("妨碍者", single("妨碍着", smart = true))
    }

    @Test
    fun blockGeometryIsPreservedThroughCorrection() {
        val block = OcrText("防碍者", 100, 200, 180, 240)
        val entry = CorrectionPipeline.apply(
            listOf(block),
            emptyList(),
            vocabulary,
            smartSnap = true
        ).single()

        // 纠正后的文本用于匹配与点击，但位置必须还是原来那个块的
        assertEquals("妨碍者", entry.text)
        assertEquals("防碍者", entry.raw)
        assertEquals(100, entry.block.left)
        assertEquals(200, entry.block.top)
        assertEquals(180, entry.block.right)
        assertEquals(240, entry.block.bottom)
    }

    @Test
    fun levenshteinBasics() {
        assertEquals(0, CorrectionPipeline.levenshtein("妨碍者", "妨碍者"))
        assertEquals(1, CorrectionPipeline.levenshtein("防碍者", "妨碍者"))
        assertEquals(3, CorrectionPipeline.levenshtein("", "妨碍者"))
        assertEquals(1.0, CorrectionPipeline.similarity("巨乳", "巨乳"), 1e-9)
        assertEquals(0.5, CorrectionPipeline.similarity("巨乳", "贫乳"), 1e-9)
    }
}
