package com.bgffw.tkfmtool.util

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.roundToInt

val Context.densityValue: Float
    get() = resources.displayMetrics.density

fun Context.dp(value: Int): Int = (value * densityValue).roundToInt()

fun Context.dpf(value: Float): Float = value * densityValue

/** 悬浮窗窗口类型；低版本回落成已废弃但可用的 TYPE_PHONE。 */
fun overlayWindowType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

/** 模式序号转中文数字，与原版 `convertToChineseNumber` 一致。 */
fun convertToChineseNumber(num: Int): String {
    val digits = charArrayOf('零', '一', '二', '三', '四', '五', '六', '七', '八', '九')
    val text = num.toString()
    val builder = StringBuilder(text.length)
    for (ch in text) {
        if (ch in '0'..'9') builder.append(digits[ch - '0']) else builder.append(ch)
    }
    return builder.toString()
}
