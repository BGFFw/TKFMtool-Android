package com.bgffw.tkfmtool.core

/**
 * 版本号解析与比较。
 *
 * 只处理本项目实际用到的形式：
 * `2.0.8`、`2.0.8-debug`（debug 包的 versionNameSuffix）、`v2.0.8`（GitHub tag）。
 *
 * 按数字段比较，缺少的段按 0 处理，所以 `2.0.8` 与 `2.0.8.0` 视为相同。
 */
object AppVersion {

    /** 去掉前缀 `v` 和 `-debug` 之类的后缀。 */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw.trim()
        if (text.startsWith("v", ignoreCase = true)) text = text.substring(1)
        val dash = text.indexOf('-')
        if (dash > 0) text = text.substring(0, dash)
        return text.trim()
    }

    /** 解析成数字段列表；非法返回 null。 */
    fun parse(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return null
        val parts = normalized.split('.')
        if (parts.size < 2) return null
        val numbers = ArrayList<Int>(parts.size)
        for (part in parts) {
            val value = part.toIntOrNull() ?: return null
            if (value < 0) return null
            numbers.add(value)
        }
        return numbers
    }

    /**
     * 比较版本：[a] 比 [b] 旧返回负数，相同返回 0，更新返回正数。
     * 任一方无法解析时按「相同」处理，避免误报更新。
     */
    fun compare(a: String?, b: String?): Int {
        val left = parse(a) ?: return 0
        val right = parse(b) ?: return 0
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l - r
        }
        return 0
    }

    /** [remote] 是否比 [local] 新。 */
    fun isNewer(remote: String?, local: String?): Boolean = compare(remote, local) > 0
}
