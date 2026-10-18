package com.bgffw.tkfmtool.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户纠错项，对应原版 `storage.get("UserCorrectSet")` 里的数组元素。
 *
 * 原字段：`title`（识别到的原文）、`summary`（改正后的词条）、`color`（列表色条）、`done`（是否启用）。
 */
data class Correction(
    val title: String,
    val summary: String,
    val color: String,
    val done: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("summary", summary)
        put("color", color)
        put("done", done)
    }

    companion object {
        /** 原版 run.js 里的四项默认纠正。 */
        fun defaults(): List<Correction> = listOf(
            Correction("間属性", "闇属性", "#f44336", true),
            Correction("周属性", "闇属性", "#ff5722", true),
            Correction("防碍者", "妨碍者", "#4caf50", true),
            Correction("留院", "削弱", "#2196f3", true)
        )

        fun fromJson(json: JSONObject): Correction = Correction(
            title = json.optString("title"),
            summary = json.optString("summary"),
            color = json.optString("color", "#2196F3"),
            done = json.optBoolean("done", true)
        )

        fun listToJson(items: List<Correction>): String {
            val array = JSONArray()
            for (item in items) array.put(item.toJson())
            return array.toString()
        }

        fun listFromJson(raw: String?): List<Correction> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                val out = ArrayList<Correction>(array.length())
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { out.add(fromJson(it)) }
                }
                out
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }
}
