package com.bgffw.tkfmtool.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 招募时间读数解析。
 *
 * 这里的用例基本都是从真机现象反推出来的：ML Kit 经常把 `09` 拆成两个块，
 * 或者把区域里的说明文字一起吐出来，原版「取第一个数字」的做法会直接读成 `0`。
 */
class RecruitTimeParserTest {

    private fun block(text: String, left: Int = 0, top: Int = 0) =
        OcrText(text, left, top, left + 40, top + 40)

    @Test
    fun singleDigitIsPadded() {
        assertEquals("09", RecruitTimeParser.parse(listOf(block("9"))))
        assertEquals("01", RecruitTimeParser.parse(listOf(block("1"))))
    }

    @Test
    fun twoDigitsAreKept() {
        assertEquals("09", RecruitTimeParser.parse(listOf(block("09"))))
        assertEquals("05", RecruitTimeParser.parse(listOf(block("05"))))
    }

    @Test
    fun splitBlocksAreRecombined() {
        // ML Kit 把 09 拆成 "0" 和 "9" 两块时的真实情况
        val blocks = listOf(block("0", left = 10), block("9", left = 60))
        assertEquals("09", RecruitTimeParser.parse(blocks))
    }

    @Test
    fun invertedReadingIsFixed() {
        // 原版特判：倒过来的 09 被读成 60
        assertEquals("09", RecruitTimeParser.parse(listOf(block("60"))))
    }

    @Test
    fun lettersThatLookLikeDigitsAreFolded() {
        // 真机真实案例：01 被 ML Kit 读成字母 IO
        assertEquals("01", RecruitTimeParser.parse(listOf(block("IO"))))
        assertEquals("08", RecruitTimeParser.parse(listOf(block("OB"))))
        assertEquals("05", RecruitTimeParser.parse(listOf(block("OS"))))
        assertEquals("09", RecruitTimeParser.parse(listOf(block("Og"))))
    }

    @Test
    fun foldingHappensBeforeDirectMatch() {
        // 折叠后才成数，走第一层
        assertEquals("10", RecruitTimeParser.foldLookAlikes("IO"))
        assertEquals("01", RecruitTimeParser.parse(listOf(block("O1"))))
        assertEquals("01", RecruitTimeParser.parse(listOf(block("I0"))))
    }

    @Test
    fun fallbackUsesTheSingleNonZeroDigit() {
        // 折回得到 "10"，正常解析失败，但非零数字只有 1 → 判为 01
        assertEquals("10", RecruitTimeParser.foldLookAlikes("IO"))
        assertEquals("01", RecruitTimeParser.parse(listOf(block("IO"))))
    }

    @Test
    fun surroundingTextIsIgnored() {
        val blocks = listOf(
            block("招募时间", top = 0),
            block("9", top = 60),
            block("确定", top = 120)
        )
        assertEquals("09", RecruitTimeParser.parse(blocks))
    }

    @Test
    fun loneZeroIsRejected() {
        // "0" 补零成 "00"，不在 01..09 内，必须判为无效而不是当成 0 点下去
        assertNull(RecruitTimeParser.parse(listOf(block("0"))))
    }

    @Test
    fun outOfRangeIsRejected() {
        assertNull(RecruitTimeParser.parse(listOf(block("12"))))
        assertNull(RecruitTimeParser.parse(listOf(block("99"))))
    }

    @Test
    fun emptyInputMeansPanelNotReady() {
        assertNull(RecruitTimeParser.parse(emptyList()))
        assertNull(RecruitTimeParser.parse(listOf(block("招募时间"))))
    }

    @Test
    fun normalizeCoversEdgeCases() {
        assertNull(RecruitTimeParser.normalize(""))
        assertNull(RecruitTimeParser.normalize("十"))
        assertNull(RecruitTimeParser.normalize("123"))
        assertEquals("09", RecruitTimeParser.normalize(" 9 "))
        assertEquals("09", RecruitTimeParser.normalize("60"))
    }

    @Test
    fun validityHelper() {
        assertTrue(RecruitTimeParser.isValid("01"))
        assertTrue(RecruitTimeParser.isValid("09"))
        assertFalse(RecruitTimeParser.isValid("00"))
        assertFalse(RecruitTimeParser.isValid("10"))
        assertFalse(RecruitTimeParser.isValid(null))
    }
}
