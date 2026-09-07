package com.spotifytools.core

/**
 * TextConversionSkill：繁→简转换核心（纯函数，无状态，可独立单测）
 *
 * 输入：任意文本
 * 输出：转换后文本
 * 规则：
 *  - 转换异常返回原文（降级）
 *  - 无繁体时原样返回（零开销短路）
 *  - 英文/数字/标点/表情符号不受影响
 */
object TextConversion {

    /**
     * 繁体 → 简体
     * 词组优先匹配（最长 4 字），单字回退；非映射字符原样保留。
     */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        if (!TradSimpDict.containsTraditional(text)) return text

        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val hit = TradSimpDict.lookup(text, i)
            if (hit != null) {
                sb.append(hit.replacement)
                i += hit.consumed
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    /** 是否包含繁体字（供调用方跳过转换） */
    fun containsTraditional(text: String): Boolean = TradSimpDict.containsTraditional(text)
}
