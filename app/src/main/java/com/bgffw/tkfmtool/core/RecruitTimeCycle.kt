package com.bgffw.tkfmtool.core

/**
 * 招募时间的「减少小时」按钮循环规则。
 *
 * 游戏里的小时数是 01..09 递减循环：
 *
 * ```
 * 01 → 09 → 08 → 07 → 06 → 05 → 04 → 03 → 02 → 01 → 09 → …
 * ```
 *
 * 也就是说每次点击是「减 1，减到 1 再点就回到 9」。
 *
 * 既然规律固定，就不用「点一下、截一次图、再判断」：
 * 只要读一次当前值，就能算出到目标还差几次点击，一次点完。
 * 这比原来每轮都做一次截图 + OCR 要快得多，也不会因为某次 OCR
 * 失败就整段中断。
 */
object RecruitTimeCycle {

    const val MIN_HOUR = 1
    const val MAX_HOUR = 9

    /** 一轮的取值个数，也是取模的基数。 */
    const val CYCLE = MAX_HOUR - MIN_HOUR + 1

    val HOURS: List<Int> = (MIN_HOUR..MAX_HOUR).toList()

    fun isValid(hour: Int): Boolean = hour in MIN_HOUR..MAX_HOUR

    /** 点一次之后的小时数。 */
    fun nextAfterTap(hour: Int): Int = if (hour <= MIN_HOUR) MAX_HOUR else hour - 1

    /**
     * 从 [current] 点到 [target] 需要点几下。
     *
     * @return 0 表示已经是目标；-1 表示输入不在 01..09 范围内
     */
    fun tapsBetween(current: Int, target: Int): Int {
        if (!isValid(current) || !isValid(target)) return -1
        return ((current - target) % CYCLE + CYCLE) % CYCLE
    }

    /** 把 OCR 出来的字符串转成小时数，非法返回 null。 */
    fun parseHour(text: String?): Int? {
        val value = text?.trim()?.toIntOrNull() ?: return null
        return if (isValid(value)) value else null
    }
}
