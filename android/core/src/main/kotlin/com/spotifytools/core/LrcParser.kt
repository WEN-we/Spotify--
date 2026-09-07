package com.spotifytools.core

/**
 * LRC 歌词解析器（纯函数）
 *
 * 输入：LRC 格式文本（[mm:ss.xx]歌词行，支持多时间标签）
 * 输出：按时间排序的歌词行列表
 * 规则：
 *  - 元数据标签（[ti:] [ar:] [al:] 等）自动跳过
 *  - 无时间戳的行跳过
 *  - 解析失败返回空列表（降级，由调用方提示）
 */
object LrcParser {

    /** 单行歌词 */
    data class Line(val startTimeMs: Long, val text: String)

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * 解析 LRC 文本
     * @param lrcText 原始 LRC 文本（\r\n 或 \n 分行）
     * @return 按时间升序的歌词行；空文本/无有效行返回空列表
     */
    fun parse(lrcText: String): List<Line> {
        if (lrcText.isEmpty()) return emptyList()
        val lines = ArrayList<Line>()

        for (raw in lrcText.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val stamps = TIMESTAMP_REGEX.findAll(line).toList()
            if (stamps.isEmpty()) continue // 元数据或纯文本行，跳过

            val text = line.replace(TIMESTAMP_REGEX, "").trim()
            for (m in stamps) {
                val min = m.groupValues[1].toIntOrNull() ?: continue
                val sec = m.groupValues[2].toIntOrNull() ?: continue
                val fracRaw = m.groupValues[3].ifEmpty { "0" }
                val ms = fracRaw.padEnd(3, '0').take(3).toIntOrNull() ?: 0
                if (min >= 0 && sec in 0..59) {
                    lines.add(Line(min * 60_000L + sec * 1_000L + ms, text))
                }
            }
        }
        lines.sortBy { it.startTimeMs }
        return lines
    }

    /**
     * 二分查找当前播放进度对应的歌词行下标
     * @return 当前行下标；进度在第一行之前返回 -1；空列表返回 -1
     */
    fun findCurrentIndex(lines: List<Line>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].startTimeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
