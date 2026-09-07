package com.spotifytools.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TextConversionSkill 单元测试
 * 覆盖：繁体样本、简繁混合、词组消歧、边界情况（空串/纯英文/表情符号）、降级行为
 */
class TextConversionTest {

    // ── 基础转换 ──
    @Test
    fun `繁体单字转换`() {
        assertEquals("声无哀乐", TextConversion.toSimplified("聲無哀樂"))
        assertEquals("爱与痛的边缘", TextConversion.toSimplified("愛與痛的邊緣"))
        assertEquals("童话", TextConversion.toSimplified("童話"))
    }

    @Test
    fun `繁体歌名场景`() {
        assertEquals("江声入旧年", TextConversion.toSimplified("江聲入舊年"))
        assertEquals("张学友", TextConversion.toSimplified("張學友"))
        assertEquals("刘德华", TextConversion.toSimplified("劉德華"))
    }

    // ── 词组消歧 ──
    @Test
    fun `词组消歧-头发`() {
        assertEquals("头发", TextConversion.toSimplified("頭髮"))
        assertEquals("白头发", TextConversion.toSimplified("白頭髮"))
    }

    @Test
    fun `词组消歧-干净`() {
        assertEquals("干净", TextConversion.toSimplified("乾淨"))
        assertEquals("饼干", TextConversion.toSimplified("餅乾"))
    }

    @Test
    fun `词组消歧-了解`() {
        assertEquals("了解", TextConversion.toSimplified("瞭解"))
        assertEquals("明了", TextConversion.toSimplified("明瞭"))
    }

    @Test
    fun `词组消歧-借口`() {
        assertEquals("借口", TextConversion.toSimplified("藉口"))
        assertEquals("慰藉保留", TextConversion.toSimplified("慰藉保留")) // PHRASE_KEEP
    }

    @Test
    fun `词组消歧-轻松`() {
        assertEquals("轻松", TextConversion.toSimplified("輕鬆"))
        assertEquals("放松", TextConversion.toSimplified("放鬆"))
    }

    // ── 边界情况 ──
    @Test
    fun `空串返回原样`() {
        assertEquals("", TextConversion.toSimplified(""))
    }

    @Test
    fun `纯英文不受影响`() {
        assertEquals("Give up CJX7816", TextConversion.toSimplified("Give up CJX7816"))
    }

    @Test
    fun `纯数字与标点不受影响`() {
        assertEquals("123, 456.789!", TextConversion.toSimplified("123, 456.789!"))
    }

    @Test
    fun `表情符号保留`() {
        assertEquals("爱😊测试", TextConversion.toSimplified("愛😊測試"))
    }

    @Test
    fun `简体文本原样返回`() {
        assertEquals("这是简体文本", TextConversion.toSimplified("这是简体文本"))
    }

    @Test
    fun `简繁混合文本`() {
        assertEquals("爱love与and痛", TextConversion.toSimplified("愛love與and痛"))
    }

    @Test
    fun `日文假名不受影响`() {
        assertEquals("あいうえお", TextConversion.toSimplified("あいうえお"))
    }

    // ── 检测函数 ──
    @Test
    fun `繁体检测`() {
        assertTrue(TextConversion.containsTraditional("繁體字"))
        assertTrue(TextConversion.containsTraditional("mixed 聲 text"))
        assertFalse(TextConversion.containsTraditional("纯简体"))
        assertFalse(TextConversion.containsTraditional("plain english"))
    }

    // ── 长文本（歌词场景）──
    @Test
    fun `歌词多行文本`() {
        val trad = "千裡悠遠 帆懸天邊\n倚窗前 綠酒新添\n風搖清野 枝頭雲卷"
        val expected = "千里悠远 帆悬天边\n倚窗前 绿酒新添\n风摇清野 枝头云卷"
        assertEquals(expected, TextConversion.toSimplified(trad))
    }

    @Test
    fun `运行时词表扩充`() {
        // 清理不可行（object 单例），扩充不影响既有行为
        TradSimpDict.appendEntries(chars = mapOf('龘' to '龘'))
        assertEquals("声无哀乐", TextConversion.toSimplified("聲無哀樂"))
    }
}
