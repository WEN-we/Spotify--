package com.spotifytools.lyrics.services

import com.spotifytools.core.TextConversion
import com.spotifytools.lyrics.utils.AppError
import com.spotifytools.lyrics.utils.LogKit
import com.spotifytools.lyrics.utils.Result
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * QqMusicClient：QQ音乐歌词源（中文歌曲覆盖最佳，与 Windows 端同源逻辑）
 *
 * 机制：搜索（client_search_cp）→ 智能匹配（歌名简体归一化 + 时长接近）→ 拉歌词（fcg_query_lyric_new）
 * 安卓端无 CORS 限制，直连即可（需 Referer 头）。
 * 降级：任一步失败返回标准错误结构，由 LyricRepository 回退下一源。
 */
object QqMusicClient {

    data class LyricsResult(
        val trackName: String,
        val artistName: String,
        val syncedLrc: String,
    )

    private const val SEARCH_API =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=10&w="
    private const val LYRIC_API =
        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&musicid="

    fun fetch(trackName: String, artistName: String, durationMs: Long): Result<LyricsResult> =
        try {
            Result.Success(fetchInternal(trackName, artistName, durationMs))
        } catch (e: java.io.IOException) {
            // HTTP 层失败（频控 500/断网）→ network 错误：LyricsService 20s 自动重试，
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

        // 1. 搜索候选（歌名 + 第一主唱歌手）
        val query = "$cleanTitle ${firstArtist(artistName)}".trim()
        val searchBody = httpGet(SEARCH_API + enc(query))
        val items = JSONObject(searchBody)
            .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: throw NoSuchFieldException("搜索无结果")

        // 2. 匹配策略（与 Windows 端一致：简体归一化歌名 + 时长接近）
        val simpTitle = TextConversion.toSimplified(cleanTitle)
        data class Cand(val songId: Long, val name: String, val intervalSec: Int)

        val cands = (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("songid")
            if (id <= 0) null else Cand(id, o.optString("songname"), o.optInt("interval"))
        }
        if (cands.isEmpty()) throw NoSuchFieldException("候选为空")

        fun nameOf(n: String) = TextConversion.toSimplified(n)
        fun durDiff(c: Cand) =
            if (durationMs <= 0) Long.MAX_VALUE
            else kotlin.math.abs(durationMs - c.intervalSec * 1000L)

        val hit = cands.firstOrNull { nameOf(it.name) == simpTitle && durDiff(it) < 3_000 }
            ?: cands.firstOrNull { durDiff(it) < 3_000 }
            ?: cands.firstOrNull { nameOf(it.name) == simpTitle }
            ?: throw NoSuchFieldException("无匹配歌曲")
        LogKit.d("QQ匹配: ${hit.name} / ${hit.songId}")

        // 3. 拉取歌词（nobase64=1 明文 LRC）
        val lyricBody = httpGet(LYRIC_API + hit.songId)
        val lrc = JSONObject(lyricBody).optString("lyric")
        if (lrc.isEmpty()) throw NoSuchFieldException("QQ音乐无歌词字段")

        return LyricsResult(trackName = hit.name, artistName = artistName, syncedLrc = lrc)
    }

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

    /** HTTP GET（8s 超时；非 200 与 IO 异常统一抛 IOException，供上层分类为 network） */
    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            // QQ 音乐接口要求浏览器 UA + Referer
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://y.qq.com/")
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
