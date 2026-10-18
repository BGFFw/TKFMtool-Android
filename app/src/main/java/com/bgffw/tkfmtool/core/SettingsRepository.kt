package com.bgffw.tkfmtool.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 设置仓库。沿用原版 `storages.create("chosed")` 的全部键名与取值语义，
 * 只是把 AutoX.js 的本地存储换成 SharedPreferences。
 */
class SettingsRepository(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    /** 0 = 模式一（只弹结果），1 = 模式二（弹结果 + 自动选），2 = 模式三（只自动选）。 */
    var mode: Int
        get() = sp.getInt(KEY_MODE, MODE_RESULT_ONLY).coerceIn(MODE_RESULT_ONLY, MODE_AUTO_ONLY)
        set(value) = sp.edit().putInt(KEY_MODE, value.coerceIn(MODE_RESULT_ONLY, MODE_AUTO_ONLY)).apply()

    /** 原版语义：`SetTime == 0` 表示开启自动设置招募时间，`1` 表示关闭。 */
    var autoSetTime: Boolean
        get() = sp.getInt(KEY_SET_TIME, 1) == 0
        set(value) = sp.edit().putInt(KEY_SET_TIME, if (value) 0 else 1).apply()

    var noneSrTime: String
        get() = normalizeTime(sp.getString(KEY_NONE_SR_TIME, DEFAULT_TIME))
        set(value) = sp.edit().putString(KEY_NONE_SR_TIME, normalizeTime(value)).apply()

    var haveSrTime: String
        get() = normalizeTime(sp.getString(KEY_HAVE_SR_TIME, DEFAULT_TIME))
        set(value) = sp.edit().putString(KEY_HAVE_SR_TIME, normalizeTime(value)).apply()

    /** `Ocr_Mode == 0` 为精准模型，`1` 为快速模型。 */
    var ocrAccurate: Boolean
        get() = sp.getInt(KEY_OCR_MODE, 0) == 0
        set(value) = sp.edit().putInt(KEY_OCR_MODE, if (value) 0 else 1).apply()

    /**
     * 词表智能纠正开关（原生版新增）。
     * 默认开启：把不在词表里的 OCR 文本吸附到最接近的已知词条。
     */
    var smartCorrect: Boolean
        get() = sp.getBoolean(KEY_SMART_CORRECT, true)
        set(value) = sp.edit().putBoolean(KEY_SMART_CORRECT, value).apply()

    /** 上一次（纠正后）的识图结果。 */
    var lastResult: List<String>
        get() = parseStringList(sp.getString(KEY_LAST_RESULT, null))
        set(value) {
            val array = JSONArray()
            for (item in value) array.put(item)
            sp.edit().putString(KEY_LAST_RESULT, array.toString()).apply()
        }

    var corrections: List<Correction>
        get() {
            // 从没写过这个键时给出原版 run.js 里的四项默认纠正
            if (!sp.contains(KEY_CORRECTIONS)) return Correction.defaults()
            return Correction.listFromJson(sp.getString(KEY_CORRECTIONS, null))
        }
        set(value) = sp.edit().putString(KEY_CORRECTIONS, Correction.listToJson(value)).apply()

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) out.add(array.optString(i, ""))
            out.filter { it.isNotEmpty() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    companion object {
        const val STORE_NAME = "chosed"

        const val MODE_RESULT_ONLY = 0
        const val MODE_RESULT_AND_AUTO = 1
        const val MODE_AUTO_ONLY = 2

        private const val KEY_MODE = "mode"
        private const val KEY_SET_TIME = "SetTime"
        private const val KEY_NONE_SR_TIME = "None_SRtime"
        private const val KEY_HAVE_SR_TIME = "Have_SRtime"
        private const val KEY_OCR_MODE = "Ocr_Mode"
        private const val KEY_SMART_CORRECT = "SmartCorrect"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_CORRECTIONS = "UserCorrectSet"

        private const val DEFAULT_TIME = "09"

        /** 把 "1" / "01" / " 9 " 一律规范成两位字符串；非法输入回落到 09。 */
        fun normalizeTime(raw: String?): String {
            val digits = raw?.trim()?.filter { it.isDigit() } ?: return DEFAULT_TIME
            if (digits.isEmpty()) return DEFAULT_TIME
            val value = digits.toIntOrNull() ?: return DEFAULT_TIME
            if (value < 1 || value > 9) return DEFAULT_TIME
            return value.toString().padStart(2, '0')
        }
    }
}
