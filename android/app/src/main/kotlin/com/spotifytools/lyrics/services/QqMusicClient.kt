package com.spotifytools.lyrics.services

import com.spotifytools.core.TextConversion
import com.spotifytools.lyrics.config.AppConfig
import com.spotifytools.lyrics.utils.AppError
import com.spotifytools.lyrics.utils.LogKit
import com.spotifytools.lyrics.utils.Result
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * QqMusicClient：QQ音乐歌词源（中文歌曲覆盖最佳，与 Windows 端同源逻辑）
 *
 * 搜索四链路降级（规避搜索接口封锁——2026-09 实测 client_search_cp/musicu.fcg 已全端封锁，
 * smartbox 搜索建议接口仍可用；歌词接口不受影响）：
 *   1. Windows qqProxy 局域网代理（复用电脑持久缓存；不可达 5 分钟熔断）
 *   2. 直连 client_search_cp（网页搜索端点）
 *   3. 直连 musicu.fcg（桌面客户端搜索端点；频控时返回 HTTP 200 + code 500001）
 *   4. 直连 smartbox（搜索建议端点：歌名/完整歌手/songid，无时长字段）
 * 匹配（歌手感知）：歌名相等（大小写/繁简不敏感）+ 歌手兼容（双向包含）为主，
 * 时长接近为辅助；禁止纯时长匹配（防英文歌匹配到同时长中文歌）。
 * 歌词拉取同样代理优先、降级直连。
 * 降级：任一步失败返回标准错误结构；网络错误（频控/断网）由 LyricsService 20s 自动重试。
 */
object QqMusicClient {

    data class LyricsResult(
        val trackName: String,
        val artistName: String,
        val syncedLrc: String,
    )

    // 代理熔断：不可达后 5 分钟内跳过代理链路（手机离开家庭 WiFi 时避免逐次等超时）
    @Volatile
    private var proxyDownUntil = 0L
    private const val PROXY_BREAKER_MS = 5 * 60_000L
    private const val PROXY_TIMEOUT_MS = 2_000          // 局域网连接毫秒级，2s 已足够

    private const val SEARCH_API =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=10&w="
    private const val SEARCH_API_DESKTOP =
        "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val SEARCH_API_SMARTBOX =
        "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key="
    private const val LYRIC_API =
        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&musicid="

    fun fetch(trackName: String, artistName: String, durationMs: Long): Result<LyricsResult> =
        try {
            Result.Success(fetchInternal(trackName, artistName, durationMs))
        } catch (e: java.io.IOException) {
            // HTTP 层失败（频控 500/断网/全链路不可达）→ network 错误：LyricsService 20s 自动重试，
            // 与「无结果」（noResult，不重试）区分——频控解除后歌词自动恢复
            LogKit.d("QQ音乐上游失败: ${e.message}")
            Result.Failure(AppError.network("QQ音乐请求失败（稍后自动重试）"))
        } catch (e: Exception) {
            LogKit.d("QQ音乐无结果: ${e.message}")
            Result.Failure(AppError.noResult("QQ音乐无结果"))
        }

    private fun fetchInternal(trackName: String, artistName: String, durationMs: Long): LyricsResult {
        val cleanTitle = cleanTitle(trackName)
        if (cleanTitle.isEmpty()) throw NoSuchFieldException("歌名为空")

        // 1. 搜索候选（歌名 + 第一主唱歌手；三链路降级）
        val query = "$cleanTitle ${firstArtist(artistName)}".trim()
        val cands = searchCandidates(query)
        if (cands.isEmpty()) throw NoSuchFieldException("搜索无结果")

        // 2. 歌手感知匹配（禁止纯时长匹配——防止英文歌匹配到同时长中文歌）
        val hit = matchCandidate(cands, cleanTitle, artistName, durationMs)
            ?: run {
                // 匹配失败：输出全部候选明细供诊断（debugLog 开启时可见）
                LogKit.d(
                    "QQ候选(${cands.size}): " +
                        cands.joinToString(" | ") { "${it.name}/${it.singer}/${it.intervalSec}s" },
                )
                throw NoSuchFieldException("无匹配歌曲（歌手/歌名/时长校验未通过）")
            }
        LogKit.d("QQ匹配: ${hit.name} / ${hit.singer} / ${hit.songId}")

        // 3. 拉取歌词（代理优先降级直连；nobase64=1 明文 LRC）
        val lrc = fetchLyric(hit.songId)
        return LyricsResult(trackName = hit.name, artistName = hit.singer.ifEmpty { artistName }, syncedLrc = lrc)
    }

    // ── 搜索链路 ──

    /** 搜索候选（songid/歌名/时长秒/第一歌手） */
    internal data class Cand(val songId: Long, val name: String, val intervalSec: Int, val singer: String)

    /**
     * 四链路依次搜索，任一返回非空候选即成功：
     * 代理（配置且可达）→ client_search_cp → musicu.fcg → smartbox。
     * smartbox（搜索建议接口）不随搜索接口封锁，返回 歌名/完整歌手/songid(docid)，
     * 无时长字段（匹配落到「歌名+歌手」规则）。
     * 全部失败时：见过网络错误 → IOException（触发自动重试）；仅无结果 → NoSuchFieldException。
     */
    private fun searchCandidates(query: String): List<Cand> {
        var sawNetworkError = false
        val proxy = AppConfig.qqProxyBase

        // 链路 1：Windows qqProxy 局域网代理（命中电脑缓存可完全绕过频控）。
        // 熔断：不可达后 5 分钟内跳过（移动网络下代理地址不可达，避免每次等 2s 连接超时）
        if (proxy.isNotEmpty() && System.currentTimeMillis() >= proxyDownUntil) {
            try {
                val body = httpGet("$proxy/search?q=${enc(query)}", connectTimeoutMs = PROXY_TIMEOUT_MS)
                parseClassicSearch(body)?.let { cands ->
                    if (cands.isNotEmpty()) {
                        proxyDownUntil = 0L
                        LogKit.d("QQ搜索(代理): $query")
                        return cands
                    }
                }
            } catch (e: IOException) {
                proxyDownUntil = System.currentTimeMillis() + PROXY_BREAKER_MS
                sawNetworkError = true
                LogKit.d("代理不可达（熔断${PROXY_BREAKER_MS / 60000}分钟），降级直连: ${e.message}")
            } catch (e: Exception) {
                LogKit.d("代理响应异常: ${e.message}")
            }
        }

        // 链路 2：直连 client_search_cp（频控时空 body/HTTP 500）
        try {
            val body = httpGet(SEARCH_API + enc(query))
            parseClassicSearch(body)?.let { cands ->
                if (cands.isNotEmpty()) {
                    LogKit.d("QQ搜索(直连): $query")
                    return cands
                }
            }
        } catch (e: IOException) {
            sawNetworkError = true
            LogKit.d("client_search_cp 失败，降级 musicu.fcg: ${e.message}")
        } catch (e: Exception) {
            LogKit.d("client_search_cp 解析异常: ${e.message}")
        }

        // 链路 3：musicu.fcg 桌面端点（频控时 HTTP 200 + code 500001）
        try {
            val body = httpPostJson(
                SEARCH_API_DESKTOP,
                """{"req_1":{"method":"DoSearchForQQMusicDesktop","module":"music.search.SearchCgiService",""" +
                    """"param":{"search_type":0,"query":${JSONObject.quote(query)},"page_num":1,"num_per_page":10}}}""",
            )
            val cands = parseMusicuSearch(body)
            if (cands.isNotEmpty()) {
                LogKit.d("QQ搜索(musicu): $query")
                return cands
            }
        } catch (e: IOException) {
            sawNetworkError = true
            LogKit.d("musicu.fcg 失败: ${e.message}")
        } catch (e: Exception) {
            LogKit.d("musicu.fcg 解析异常: ${e.message}")
        }

        // 链路 4：smartbox 搜索建议接口（搜索接口被封锁时的稳定替代；无时长字段）
        try {
            val body = httpGet(SEARCH_API_SMARTBOX + enc(query) + "&format=json")
            val cands = parseSmartboxSearch(body)
            if (cands.isNotEmpty()) {
                LogKit.d("QQ搜索(smartbox): $query")
                return cands
            }
        } catch (e: IOException) {
            sawNetworkError = true
            LogKit.d("smartbox 失败: ${e.message}")
        } catch (e: Exception) {
            LogKit.d("smartbox 解析异常: ${e.message}")
        }

        if (sawNetworkError) throw IOException("搜索全链路不可达（疑似频控）")
        throw NoSuchFieldException("搜索无结果")
    }

    /** 解析 client_search_cp / qqProxy 转发响应：data.song.list[]（songid/songname/interval/singer） */
    internal fun parseClassicSearch(body: String): List<Cand>? {
        if (body.isEmpty()) throw IOException("空响应")
        val list = JSONObject(body)
            .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("songid")
            if (id <= 0) null
            else Cand(id, o.optString("songname"), o.optInt("interval"), firstSinger(o))
        }
    }

    /** 解析 musicu.fcg 响应：req_1.data.body.song.list[]（兼容 id|songid / name|songname） */
    internal fun parseMusicuSearch(body: String): List<Cand> {
        if (body.isEmpty()) throw IOException("空响应")
        val root = JSONObject(body)
        // 频控返回 HTTP 200 + {"code":500001,...}，归类网络错误触发重试
        val code = root.optInt("code", 0)
        if (code == 500001) throw IOException("musicu 频控")
        val list = root.optJSONObject("req_1")
            ?.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONObject("song")?.optJSONArray("list")
            ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = if (o.optLong("songid", 0) > 0) o.optLong("songid") else o.optLong("id")
            val name = o.optString("songname").ifEmpty { o.optString("name") }
            if (id <= 0 || name.isEmpty()) null
            else Cand(id, name, o.optInt("interval"), firstSinger(o))
        }
    }

    /** 解析 smartbox 响应：data.song.itemlist[]（name/singer/docid→songid；无时长字段） */
    internal fun parseSmartboxSearch(body: String): List<Cand> {
        if (body.isEmpty()) throw IOException("空响应")
        val list = JSONObject(body)
            .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("itemlist")
            ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("docid")
            val name = o.optString("name")
            if (id <= 0 || name.isEmpty()) null
            else Cand(id, name, 0, o.optString("singer").trim())
        }
    }

    // ── 匹配（歌手感知；纯函数可单测） ──

    /**
     * 匹配规则（按优先级）：
     *  1. 歌名相等 + 时长接近(±3s) + 歌手兼容
     *  2. 歌名相等 + 时长未知(种子缓存 interval=0) + 歌手兼容
     *  3. 歌名相等 + 歌手兼容（时长不一致：同歌手 Live/不同版本）
     *  4. 歌名包含（Live/feat 后缀）+ 时长接近 + 歌手兼容
     *  5. 兜底：歌名相等（忽略大小写/繁简/歌手）→ 取搜索首位（QQ 搜索序≈热度，翻唱版歌词）
     *  6. 兜底：歌名包含（Live/feat/版本后缀）→ 取首位
     * 歌手兼容 = 候选歌手任一部分与本地歌手双向包含（大小写/繁简不敏感）；
     * 候选歌手为空（种子缓存）或本地歌手为空时放行。
     * 禁止纯时长匹配（曾致英文歌匹配到同时长中文歌）——5/6 仍要求歌名一致/包含，
     * 跨歌曲错配（Give up→江声入旧年）依旧不可能。
     */
    internal fun matchCandidate(
        cands: List<Cand>,
        title: String,
        artist: String,
        durationMs: Long,
    ): Cand? {
        val wantName = TextConversion.toSimplified(title).trim().lowercase()
        val wantArtist = TextConversion.toSimplified(firstArtist(artist)).trim().lowercase()

        fun nameOf(c: Cand) = TextConversion.toSimplified(c.name).trim().lowercase()
        fun nameEq(c: Cand) = nameOf(c) == wantName
        fun nameSimilar(c: Cand): Boolean {
            val n = nameOf(c)
            return n.length >= 2 && wantName.length >= 2 && (n.contains(wantName) || wantName.contains(n))
        }
        fun artistOk(c: Cand): Boolean {
            if (c.singer.isBlank() || wantArtist.isBlank()) return true
            return c.singer.split('/', '，', ',', '、', ';')
                .map { TextConversion.toSimplified(it).trim().lowercase() }
                .any { part -> part.isNotEmpty() && (part.contains(wantArtist) || wantArtist.contains(part)) }
        }
        fun durClose(c: Cand): Boolean =
            durationMs > 0 && c.intervalSec > 0 &&
                kotlin.math.abs(durationMs - c.intervalSec * 1000L) < 3_000
        fun durUnknown(c: Cand) = c.intervalSec <= 0

        return cands.firstOrNull { nameEq(it) && durClose(it) && artistOk(it) }
            ?: cands.firstOrNull { nameEq(it) && durUnknown(it) && artistOk(it) }
            ?: cands.firstOrNull { nameEq(it) && artistOk(it) }
            ?: cands.firstOrNull { nameSimilar(it) && durClose(it) && artistOk(it) }
            // 兜底（用户要求）：找不到原作者 → 使用其他歌手最热版本（QQ 搜索序≈热度）。
            // 注意：本函数保持纯函数（可单测），兜底命中与否的日志由 fetchInternal 统一输出
            ?: cands.firstOrNull { nameEq(it) }
            ?: cands.firstOrNull { nameSimilar(it) }
    }

    // ── 歌词链路 ──

    /** 拉歌词：代理优先（命中电脑 30 天缓存零上游请求），不可达降级直连（同熔断策略） */
    private fun fetchLyric(songId: Long): String {
        val proxy = AppConfig.qqProxyBase
        if (proxy.isNotEmpty() && System.currentTimeMillis() >= proxyDownUntil) {
            try {
                val body = httpGet("$proxy/lyric/$songId", connectTimeoutMs = PROXY_TIMEOUT_MS)
                val lrc = JSONObject(body).optString("lyric")
                if (lrc.isNotEmpty()) {
                    proxyDownUntil = 0L
                    LogKit.d("QQ歌词(代理): $songId")
                    return lrc
                }
            } catch (e: Exception) {
                if (e is IOException) proxyDownUntil = System.currentTimeMillis() + PROXY_BREAKER_MS
                LogKit.d("代理歌词降级直连: ${e.message}")
            }
        }
        val body = httpGet(LYRIC_API + songId)
        val lrc = JSONObject(body).optString("lyric")
        if (lrc.isEmpty()) throw NoSuchFieldException("QQ音乐无歌词字段")
        return lrc
    }

    // ── 工具 ──

    /** 清理歌名后缀（Live/Remix/feat. 等，提升搜索命中率） */
    private fun cleanTitle(t: String): String = t
        .replace(Regex("""\s*[-–(（]\s*(live|acoustic|remix|.*version|edit|demo|instrumental|伴奏|现场|翻自).*${'$'}""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*[(-]\s*feat\.?\s*[^)）]*[)）]""", RegexOption.IGNORE_CASE), "")
        .trim()

    /** 取第一主唱歌手（Spotify 多歌手格式：A / B 或 A, B） */
    private fun firstArtist(artist: String): String =
        artist.split(",", ";", "/", "&")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: ""

    /** 取 QQ 候选的第一歌手名（singer 数组；无字段返回空 = 歌手未知） */
    private fun firstSinger(o: JSONObject): String =
        o.optJSONArray("singer")?.optJSONObject(0)?.optString("name")?.trim() ?: ""

    /** HTTP GET（默认 5s 连接/8s 读取；connectTimeoutMs 可覆盖——代理用 2s 快速失败；非 200 与 IO 异常统一抛 IOException） */
    private fun httpGet(urlStr: String, connectTimeoutMs: Int = 5_000): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            // QQ 音乐接口要求浏览器 UA + Referer（走代理时代理自带，此处冗余无害）
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://y.qq.com/")
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    /** HTTP POST JSON（musicu.fcg 用；非 200/IO 异常抛 IOException） */
    private fun httpPostJson(urlStr: String, json: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://y.qq.com/")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
