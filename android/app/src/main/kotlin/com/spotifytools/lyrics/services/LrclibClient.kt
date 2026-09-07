package com.spotifytools.lyrics.services

import com.spotifytools.lyrics.config.AppConfig
import com.spotifytools.lyrics.utils.AppError
import com.spotifytools.lyrics.utils.LogKit
import com.spotifytools.lyrics.utils.Result
import com.spotifytools.lyrics.utils.runCatchingApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LyricFetchSkill：LRCLIB 歌词获取（唯一网络源，可替换）
 *
 * API（官方公开，免费）：
 *  1. GET /api/get?artist_name=&track_name=&duration=   精确匹配
 *  2. GET /api/search?track_name=&artist_name=           模糊搜索
 *
 * 规则：
 *  - 仅 HttpURLConnection（零第三方依赖）
 *  - 8s 超时；失败返回标准错误结构
 *  - 同步实现（调用方保证在后台线程执行）
 *  - 返回原始 LRC（繁转简由上层处理）
 */
object LrclibClient {

    /** LRCLIB 歌词结果 */
    data class LyricsResult(
        val trackName: String,
        val artistName: String,
        val syncedLrc: String,
        val plainLyrics: String?,
    )

    /** 精确匹配（歌名+歌手+时长），失败降级搜索 */
    fun fetch(trackName: String, artistName: String, durationSec: Long): Result<LyricsResult> {
        val exact = getExact(trackName, artistName, durationSec)
        if (exact is Result.Success) return exact

        LogKit.d("精确匹配失败，降级搜索: $trackName - $artistName")
        return search(trackName, artistName)
    }

    /** GET /api/get —— 精确匹配 */
    private fun getExact(trackName: String, artistName: String, durationSec: Long): Result<LyricsResult> =
        runCatchingApp(AppError.network("LRCLIB 请求失败")) {
            val url = buildString {
                append(AppConfig.lrclibBase).append("/api/get?")
                append("artist_name=").append(enc(artistName))
                append("&track_name=").append(enc(trackName))
                if (durationSec > 0) append("&duration=").append(durationSec)
            }
            val body = httpGet(url)
            parseLyrics(JSONObject(body)) ?: throw NoSuchFieldException("无 syncedLyrics 字段")
        }

    /** GET /api/search —— 模糊搜索，取首个含同步歌词的结果 */
    private fun search(trackName: String, artistName: String): Result<LyricsResult> =
        runCatchingApp(AppError.noResult("LRCLIB 搜索失败")) {
            val url = buildString {
                append(AppConfig.lrclibBase).append("/api/search?")
                append("track_name=").append(enc(trackName))
                append("&artist_name=").append(enc(artistName))
            }
            val arr = JSONArray(httpGet(url))
            for (i in 0 until arr.length()) {
                val item = parseLyrics(arr.getJSONObject(i))
                if (item != null) return@runCatchingApp item
            }
            throw NoSuchFieldException("搜索无同步歌词结果")
        }

    private fun parseLyrics(obj: JSONObject): LyricsResult? {
        val synced = obj.optString("syncedLyrics", "")
        if (synced.isEmpty()) return null
        return LyricsResult(
            trackName = obj.optString("trackName", ""),
            artistName = obj.optString("artistName", ""),
            syncedLrc = synced,
            plainLyrics = obj.optString("plainLyrics", "").ifEmpty { null },
        )
    }

    /** HTTP GET（8s 超时，User-Agent 必填——LRCLIB 要求标识客户端） */
    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "SpotifyLyrics-Android/1.0 (floating lyrics overlay)")
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
