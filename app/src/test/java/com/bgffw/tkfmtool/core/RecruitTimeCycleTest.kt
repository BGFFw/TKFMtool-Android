package com.bgffw.tkfmtool.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「减少小时」按钮的循环规则。
 *
 * 真机行为：初始为 01，点一下变 09，再点是 08、07…一直到 01 再回到 09。
 * 下面的模拟测试会把这个序列跑一遍，确保 [RecruitTimeCycle.tapsBetween]
 * 算出来的次数真的能把时间点到目标。
 */
class RecruitTimeCycleTest {

    @Test
    fun tapSequenceWrapsFromOneToNine() {
        var hour = 1
        val sequence = mutableListOf(hour)
        repeat(11) {
            hour = RecruitTimeCycle.nextAfterTap(hour)
            sequence.add(hour)
        }
        assertEquals(listOf(1, 9, 8, 7, 6, 5, 4, 3, 2, 1, 9, 8), sequence)
    }

    @Test
    fun tapsFromOneToNineIsOne() {
        // 初始 01，点一下就 09
        assertEquals(1, RecruitTimeCycle.tapsBetween(1, 9))
    }

    @Test
    fun alreadyAtTargetNeedsNoTap() {
        for (hour in RecruitTimeCycle.HOURS) {
            assertEquals(0, RecruitTimeCycle.tapsBetween(hour, hour))
        }
    }

    @Test
    fun descendingIsDirectSubtraction() {
        assertEquals(8, RecruitTimeCycle.tapsBetween(9, 1))
        assertEquals(5, RecruitTimeCycle.tapsBetween(5, 9))
        assertEquals(3, RecruitTimeCycle.tapsBetween(6, 3))
    }

    @Test
    fun invalidInputIsRejected() {
        assertEquals(-1, RecruitTimeCycle.tapsBetween(0, 9))
        assertEquals(-1, RecruitTimeCycle.tapsBetween(10, 9))
        assertEquals(-1, RecruitTimeCycle.tapsBetween(5, 0))
        assertNull(RecruitTimeCycle.parseHour("00"))
        assertNull(RecruitTimeCycle.parseHour(null))
        assertNull(RecruitTimeCycle.parseHour("十"))
        assertEquals(9, RecruitTimeCycle.parseHour("09"))
        assertEquals(1, RecruitTimeCycle.parseHour("1"))
    }

    @Test
    fun parseAndTapsAreConsistent() {
        val current = RecruitTimeCycle.parseHour("05")!!
        val target = RecruitTimeCycle.parseHour("09")!!
        assertEquals(5, RecruitTimeCycle.tapsBetween(current, target))
    }

    /**
     * 把所有「起点 × 目标」组合都跑一遍：
     * 按算出来的次数点击，必须精确落到目标上，一次不多一次不少。
     */
    @Test
    fun simulationReachesEveryTargetExactly() {
        for (from in RecruitTimeCycle.HOURS) {
            for (to in RecruitTimeCycle.HOURS) {
                val taps = RecruitTimeCycle.tapsBetween(from, to)
                assertTrue("taps 应为非负: $from -> $to", taps >= 0)

                var hour = from
                repeat(taps) { hour = RecruitTimeCycle.nextAfterTap(hour) }
                assertEquals("从 $from 点 $taps 下应到 $to", to, hour)

                // 再多点一下必须偏离目标，证明次数是最小的
                if (from != to) {
                    val extra = RecruitTimeCycle.nextAfterTap(hour)
                    assertFalse("多点一下不该还在目标 $to", extra == to)
                }
            }
        }
    }
}
