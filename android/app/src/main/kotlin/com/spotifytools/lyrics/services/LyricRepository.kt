package com.spotifytools.lyrics.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.spotifytools.core.LrcParser
import com.spotifytools.core.TextConversion
import com.spotifytools.lyrics.config.AppConfig
import com.spotifytools.lyrics.utils.AppError
import com.spotifytools.lyrics.utils.LogKit
import com.spotifytools.lyrics.utils.Result

/**
 * 歌词仓库（LyricRepository）：缓存优先 → 多源回退（LRCLIB → QQ音乐）→ 繁转简 → 写缓存
 *
 * 数据流单向：LyricsService → 本仓库 → (缓存 | LrclibClient | QqMusicClient)
 * 降级：单源失败自动回退下一源；全部失败返回标准错误结构。
 */
class LyricRepository(context: Context) {

    /** 转换后的歌词行（时间戳 + 简体文本） */
    data class LrcLine(val startTimeMs: Long, val text: String)

    /** 获取结果：歌词行 + 来源标签（缓存/LRCLIB/QQ音乐） */
    data class FetchResult(val lines: List<LrcLine>, val source: String)

    private val cache = LyricsCache(context)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 异步获取歌词（回调主线程；缓存命中走快路径） */
    fun fetchAsync(
        trackName: String,
        artistName: String,
        durationMs: Long,
        onDone: (Result<FetchResult>) -> Unit,
    ) {
        // 快路径：缓存命中直接主线程回调（零延迟）
        val cached = cache.get(trackName, artistName)
        if (!cached.isNullOrEmpty()) {
            LogKit.d("歌词缓存命中: $trackName")
            mainHandler.post { onDone(Result.Success(FetchResult(convert(cached), SOURCE_CACHE))) }
            return
        }
        // 慢路径：网络请求（LRCLIB → QQ音乐 回退）
        executor.execute {
            val result = fetchFromNetwork(trackName, artistName, durationMs)
            mainHandler.post { onDone(result) }
        }
    }

    private fun fetchFromNetwork(
        trackName: String,
        artistName: String,
        durationMs: Long,
    ): Result<FetchResult> {
        val durationSec = if (durationMs > 0) durationMs / 1000 else 0
        val sources = listOf(
            "LRCLIB" to { LrclibClient.fetch(trackName, artistName, durationSec).map { it.syncedLrc } },
            "QQ音乐" to { QqMusicClient.fetch(trackName, artistName, durationMs).map { it.syncedLrc } },
        )

        var lastError: AppError = AppError.noResult("无可用歌词源")
        for ((name, fetcher) in sources) {
            when (val fetched = fetcher()) {
                is Result.Success -> {
                    val lines = sanitize(LrcParser.parse(fetched.data))
                    if (lines.isEmpty()) {
                        // 该源解析后无有效行（如仅制作人员信息）→ 尝试下一源
                        LogKit.d("$name 解析为空，回退下一源: $trackName")
                        lastError = AppError.parse("$name 歌词为空")
                        continue
                    }
                    cache.put(trackName, artistName, fetched.data)
                    LogKit.i("歌词获取成功($name): $trackName (${lines.size} 行)")
                    return Result.Success(FetchResult(convert(lines), name))
                }
                is Result.Failure -> {
                    LogKit.d("$name 失败: ${fetched.error.message}")
                    lastError = fetched.error
                }
            }
        }
        LogKit.i("歌词未找到: $trackName - ${lastError.message}")
        return Result.Failure(lastError)
    }

    /** 过滤制作人员行（作词/作曲/编曲/混音等 credits），保留纯音乐提示行 */
    private fun sanitize(lines: List<LrcParser.Line>): List<LrcParser.Line> =
        lines.filterNot { CREDITS_REGEX.containsMatchIn(it.text) }

    /** 繁转简（按配置开关；纯函数，时间戳不变） */
    private fun convert(lines: List<LrcParser.Line>): List<LrcLine> =
        if (AppConfig.t2sEnabled) {
            lines.map { LrcLine(it.startTimeMs, TextConversion.toSimplified(it.text)) }
        } else {
            lines.map { LrcLine(it.startTimeMs, it.text) }
        }

    fun clearCache() = cache.clear()
    fun cacheSize() = cache.size()

    companion object {
        const val SOURCE_CACHE = "本地缓存"

        /** 制作人员行（与 Windows 端 lyrics-plus 补丁同规则） */
        private val CREDITS_REGEX = Regex(
            """^(\s?作?\s*词|\s?作?\s*曲|\s?编\s*曲?|\s?监\s*制?|.*编写|.*和音|.*和声|.*合声|""" +
                """.*提琴|.*录|.*工程|.*工作室|.*设计|.*剪辑|.*制作|.*发行|.*出品|.*后期|""" +
                """.*混音|.*缩混|原唱|翻唱|题字|文案|海报|古筝|二胡|钢琴|吉他|贝斯|笛子|鼓|弦乐|""" +
                """lrc|publish|vocal|guitar|program|produce|write|mix).*(:|：)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
