package com.spotifytools.lyrics.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QqMusicClient 解析与匹配单元测试（纯 JVM，不触网络）
 * 覆盖：
 *  - client_search_cp 经典响应 / qqProxy 种子缓存精简数据 / musicu.fcg 桌面端响应 / 频控分类
 *  - 歌手感知匹配（防跨语言/跨歌手错配——「Give up」事故回归测试）
 */
class QqMusicClientTest {

    // ── parseClassicSearch（client_search_cp / qqProxy 转发格式） ──

    @Test
    fun `经典搜索响应解析多候选含歌手`() {
        val body = """
            {"data":{"song":{"list":[
              {"songname":"江声入旧年","songid":632261902,"interval":252,
               "singer":[{"name":"聲無哀樂"},{"name":"龚琳娜"}]},
              {"songname":"其他歌","songid":111,"interval":200,"singer":[{"name":"某人"}]}
            ]}}}
        """.trimIndent()
        val cands = QqMusicClient.parseClassicSearch(body)!!
        assertEquals(2, cands.size)
        assertEquals(632261902L, cands[0].songId)
        assertEquals("江声入旧年", cands[0].name)
        assertEquals(252, cands[0].intervalSec)
        assertEquals("聲無哀樂", cands[0].singer)
    }

    @Test
    fun `代理种子缓存精简数据解析（interval 为 0 歌手为空的真实场景）`() {
        // qqProxy 种子缓存中的「江声入旧年」条目：singer 空、interval 0、仅 songid/songname
        val body = """{"data":{"song":{"list":[{"songname":"江声入旧年","singer":[{"name":""}],"interval":0,"songid":632261902}]}}}"""
        val cands = QqMusicClient.parseClassicSearch(body)!!
        assertEquals(1, cands.size)
        assertEquals(632261902L, cands[0].songId)
        assertEquals(0, cands[0].intervalSec)
        assertTrue(cands[0].singer.isEmpty())
    }

    @Test
    fun `无结果返回空列表`() {
        assertEquals(emptyList(), QqMusicClient.parseClassicSearch("""{"data":{"song":{"list":[]}}}"""))
        assertEquals(emptyList(), QqMusicClient.parseClassicSearch("""{"data":{}}"""))
    }

    @Test
    fun `空响应归类网络错误`() {
        assertFailsWith<java.io.IOException> { QqMusicClient.parseClassicSearch("") }
    }

    // ── parseMusicuSearch（musicu.fcg 桌面端格式） ──

    @Test
    fun `musicu 响应解析 id-name 字段格式`() {
        val body = """
            {"code":0,"req_1":{"code":0,"data":{"body":{"song":{"list":[
              {"id":632261902,"name":"江声入旧年","interval":252,"singer":[{"name":"聲無哀樂"}]}
            ]}}}}}
        """.trimIndent()
        val cands = QqMusicClient.parseMusicuSearch(body)
        assertEquals(1, cands.size)
        assertEquals(632261902L, cands[0].songId)
        assertEquals("江声入旧年", cands[0].name)
        assertEquals("聲無哀樂", cands[0].singer)
    }

    @Test
    fun `musicu 响应兼容 songid-songname 字段格式`() {
        val body = """
            {"code":0,"req_1":{"data":{"body":{"song":{"list":[
              {"songid":888,"songname":"旧字段","interval":100,"singer":[{"name":"旧歌手"}]}
            ]}}}}}
        """.trimIndent()
        val cands = QqMusicClient.parseMusicuSearch(body)
        assertEquals(1, cands.size)
        assertEquals(888L, cands[0].songId)
        assertEquals("旧字段", cands[0].name)
    }

    @Test
    fun `musicu 频控 500001 归类网络错误触发重试`() {
        // 实测：频控时 HTTP 200 + {"code":500001,...}，必须抛 IOException 才会自动重试
        val body = """{"code":500001,"ts":1788829552334,"start_ts":1788829552333,"traceid":"abc"}"""
        assertFailsWith<java.io.IOException> { QqMusicClient.parseMusicuSearch(body) }
    }

    @Test
    fun `musicu 空 body 归类网络错误`() {
        assertFailsWith<java.io.IOException> { QqMusicClient.parseMusicuSearch("") }
    }

    @Test
    fun `musicu 无结果返回空列表`() {
        val body = """{"code":0,"req_1":{"data":{"body":{"song":{"list":[]}}}}}"""
        assertTrue(QqMusicClient.parseMusicuSearch(body).isEmpty())
    }

    // ── parseSmartboxSearch（smartbox 搜索建议接口，封锁期的稳定替代） ──

    @Test
    fun `smartbox 响应解析 docid-name-singer 字段`() {
        // 实测响应结构：data.song.itemlist[]，singer 为斜杠分隔字符串，无时长字段
        val body = """
            {"code":0,"data":{"song":{"count":4,"itemlist":[
              {"name":"江声入旧年","singer":"聲無哀樂/龚琳娜/杨宗勋","docid":"632261902"},
              {"name":"江声入旧年","singer":"郑鱼","docid":"693514375"}
            ]}}}
        """.trimIndent()
        val cands = QqMusicClient.parseSmartboxSearch(body)
        assertEquals(2, cands.size)
        assertEquals(632261902L, cands[0].songId)
        assertEquals("江声入旧年", cands[0].name)
        assertEquals("聲無哀樂/龚琳娜/杨宗勋", cands[0].singer)
        assertEquals(0, cands[0].intervalSec) // smartbox 无时长 → 匹配走「歌名+歌手」规则
    }

    @Test
    fun `smartbox 真实场景 江声入旧年完整链路`() {
        // smartbox 候选（interval 未知 + 完整歌手）→ 规则2 命中 聲無哀樂 版本（非郑鱼版）
        val cands = QqMusicClient.parseSmartboxSearch(
            """{"code":0,"data":{"song":{"itemlist":[
              {"name":"江声入旧年","singer":"聲無哀樂/龚琳娜/杨宗勋","docid":"632261902"},
              {"name":"江声入旧年","singer":"林子祺Liam7","docid":"705709065"},
              {"name":"江声入旧年","singer":"郑鱼","docid":"693514375"}
            ]}}}""",
        )
        val hit = QqMusicClient.matchCandidate(cands, "江聲入舊年", "聲無哀樂SWAL", 252_000)
        assertEquals(632261902L, hit?.songId)
    }

    @Test
    fun `smartbox 真实场景 GIve up 歌手不符全部拒绝`() {
        // 实测「GIve up」smartbox 候选全为其他歌手（Seven./拖鞋pd/Tamboss）→ 全部拒绝
        val cands = QqMusicClient.parseSmartboxSearch(
            """{"code":0,"data":{"song":{"itemlist":[
              {"name":"GIve up","singer":"Seven.","docid":"686771923"},
              {"name":"GIve Up（感觉至上）","singer":"拖鞋pd/Rush Surge","docid":"695418885"},
              {"name":"Give Up","singer":"Tamboss","docid":"668717634"}
            ]}}}""",
        )
        assertNull(QqMusicClient.matchCandidate(cands, "Give up", "CJX7816", 141_000))
    }

    @Test
    fun `smartbox 无歌曲条目返回空列表`() {
        val body = """{"code":0,"data":{"song":{"count":0,"itemlist":[]}}}"""
        assertTrue(QqMusicClient.parseSmartboxSearch(body).isEmpty())
    }

    @Test
    fun `smartbox 空 body 归类网络错误`() {
        assertFailsWith<java.io.IOException> { QqMusicClient.parseSmartboxSearch("") }
    }

    // ── matchCandidate（歌手感知匹配） ──

    private fun cand(id: Long, name: String, interval: Int, singer: String) =
        QqMusicClient.Cand(id, name, interval, singer)

    @Test
    fun `规则1 歌名相等加时长接近加歌手兼容`() {
        val cands = listOf(cand(1, "江声入旧年", 252, "聲無哀樂/龚琳娜"), cand(2, "别的歌", 252, "谁"))
        val hit = QqMusicClient.matchCandidate(cands, "江声入旧年", "聲無哀樂SWAL", 252_000)
        assertEquals(1L, hit?.songId)
    }

    @Test
    fun `规则2 种子缓存 interval 未知歌手未知仍可命中`() {
        // 江声入旧年 种子缓存场景：interval=0、singer 空 → 规则 2 放行
        val cands = listOf(cand(632261902, "江声入旧年", 0, ""))
        val hit = QqMusicClient.matchCandidate(cands, "江聲入舊年", "聲無哀樂SWAL", 252_000)
        assertEquals(632261902L, hit?.songId)
    }

    @Test
    fun `事故回归 歌名相同歌手不同拒绝匹配`() {
        // 「Give up」CJX7816 vs QQ「GIve up」Seven.：歌名相等（忽略大小写）但歌手完全不同 → 拒绝
        val cands = listOf(cand(686771923, "GIve up", 141, "Seven."))
        assertNull(QqMusicClient.matchCandidate(cands, "Give up", "CJX7816", 141_000))
    }

    @Test
    fun `事故回归 时长接近但歌名与歌手均不同拒绝匹配`() {
        // 旧「纯时长匹配」规则会把 141s 的英文歌匹配到任意同时长歌曲 → 已禁止
        val cands = listOf(cand(632261902, "江声入旧年", 141, "聲無哀樂"))
        assertNull(QqMusicClient.matchCandidate(cands, "Give up", "CJX7816", 141_000))
    }

    @Test
    fun `歌手双向包含 联合歌手场景`() {
        // Spotify 艺人「聲無哀樂SWAL」vs QQ 歌手部分「聲無哀樂」：wantArtist 包含 part → 兼容
        val cands = listOf(cand(1, "江声入旧年", 252, "聲無哀樂, 龚琳娜, 杨的草稿箱"))
        val hit = QqMusicClient.matchCandidate(cands, "江声入旧年", "聲無哀樂SWAL", 252_000)
        assertEquals(1L, hit?.songId)
    }

    @Test
    fun `规则3 同名同歌手不同时长版本仍可命中`() {
        // Live 版时长不同但歌手一致：规则 3 兜底
        val cands = listOf(cand(1, "晴天", 300, "周杰伦"))
        val hit = QqMusicClient.matchCandidate(cands, "晴天", "周杰伦", 269_000)
        assertEquals(1L, hit?.songId)
    }

    @Test
    fun `规则4 歌名包含后缀加时长接近`() {
        // 候选「江声入旧年 (Live)」包含查询「江声入旧年」+ 时长接近 + 歌手兼容
        val cands = listOf(cand(1, "江声入旧年 (Live)", 253, "聲無哀樂"))
        val hit = QqMusicClient.matchCandidate(cands, "江声入旧年", "聲無哀樂", 252_000)
        assertEquals(1L, hit?.songId)
    }

    @Test
    fun `大小写不敏感匹配`() {
        val cands = listOf(cand(1, "GIve up", 141, "CJX7816"))
        val hit = QqMusicClient.matchCandidate(cands, "Give up", "CJX7816", 141_000)
        assertEquals(1L, hit?.songId)
    }

    @Test
    fun `本地歌手为空时候选歌手已知仍可匹配`() {
        // Spotify 元数据无歌手：放行歌手校验（歌名+时长仍需通过）
        val cands = listOf(cand(1, "晴天", 269, "周杰伦"))
        val hit = QqMusicClient.matchCandidate(cands, "晴天", "", 269_000)
        assertEquals(1L, hit?.songId)
    }

    @Test
    fun `空候选返回 null`() {
        assertNull(QqMusicClient.matchCandidate(emptyList(), "任意", "任意", 100_000))
    }
}
