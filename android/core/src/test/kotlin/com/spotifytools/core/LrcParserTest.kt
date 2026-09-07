package com.spotifytools.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LrcParser 单元测试
 * 覆盖：标准解析、多时间标签、元数据跳过、边界（空/无时间戳）、二分查找
 */
class LrcParserTest {

    @Test
    fun `标准LRC解析`() {
        val lrc = """
            [ti:江声入旧年]
            [ar:声无哀乐]
            [00:10.38]千里悠远 帆悬天边
            [00:41.00]倚窗前 绿酒新添
            [00:48.12]风摇清野 枝头云卷
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)
        assertEquals(10_380L, lines[0].startTimeMs)
        assertEquals("千里悠远 帆悬天边", lines[0].text)
        assertEquals(41_000L, lines[1].startTimeMs)
        assertEquals(48_120L, lines[2].startTimeMs)
    }

    @Test
    fun `多时间标签展开`() {
        val lines = LrcParser.parse("[00:10.00][01:20.50]重复歌词")
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].startTimeMs)
        assertEquals(80_500L, lines[1].startTimeMs)
        assertEquals("重复歌词", lines[1].text)
    }

    @Test
    fun `元数据标签跳过`() {
        val lines = LrcParser.parse("[ti:标题]\n[al:专辑]\n[by:制作人]\n[offset:0]\n[00:01.00]正文")
        assertEquals(1, lines.size)
        assertEquals("正文", lines[0].text)
    }

    @Test
    fun `无时间戳行跳过`() {
        val lines = LrcParser.parse("纯文本行\n没有标签")
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `空文本返回空列表`() {
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test
    fun `CRLF换行兼容`() {
        val lines = LrcParser.parse("[00:01.00]第一行\r\n[00:02.00]第二行")
        assertEquals(2, lines.size)
        assertEquals("第二行", lines[1].text)
    }

    @Test
    fun `毫秒三位数解析`() {
        val lines = LrcParser.parse("[00:01.123]精确毫秒")
        assertEquals(1_123L, lines[0].startTimeMs)
    }

    @Test
    fun `时间排序`() {
        val lines = LrcParser.parse("[00:30.00]后\n[00:10.00]先")
        assertEquals(10_000L, lines[0].startTimeMs)
        assertEquals(30_000L, lines[1].startTimeMs)
    }

    @Test
    fun `二分查找当前行`() {
        val lines = LrcParser.parse("[00:10.00]A\n[00:20.00]B\n[00:30.00]C")
        assertEquals(-1, LrcParser.findCurrentIndex(lines, 5_000))   // 开场前
        assertEquals(0, LrcParser.findCurrentIndex(lines, 10_000))   // 正好 A
        assertEquals(0, LrcParser.findCurrentIndex(lines, 19_999))   // A 持续中
        assertEquals(1, LrcParser.findCurrentIndex(lines, 25_000))   // B
        assertEquals(2, LrcParser.findCurrentIndex(lines, 60_000))   // 结尾后
    }

    @Test
    fun `空列表查找返回负一`() {
        assertEquals(-1, LrcParser.findCurrentIndex(emptyList(), 1_000))
    }
}
