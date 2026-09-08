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
    // 双线程池：LRCLIB 与 QQ音乐真正并行竞速（串行最坏 16s → 并行最快即时，最坏 8s）
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r).apply { isDaemon = true; name = "lyric-fetch" }
    }
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
        fetchFromNetwork(trackName, artistName, durationMs, onDone)
    }

    /** 逐出单曲缓存（手动刷新用） */
    fun evict(trackName: String, artistName: String) = cache.evict(trackName, artistName)

    /**
     * 双源并行竞速：LRCLIB 与 QQ音乐同时发起，先成功者胜出（不等慢源）。
     * 全部失败时聚合错误：network 类优先（供 LyricsService 20s 重试）。
     */
    private fun fetchFromNetwork(
        trackName: String,
        artistName: String,
        durationMs: Long,
        onDone: (Result<FetchResult>) -> Unit,
    ) {
        val durationSec = if (durationMs > 0) durationMs / 1000 else 0
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val pending = java.util.concurrent.atomic.AtomicInteger(2)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Pair<String, AppError>>())

        // 全部源失败后统一回调（network 错误优先 → 触发上层重试）
        fun allFailed() {
            if (finished.getAndSet(true)) return
            val err = errors.firstOrNull { it.second.code == AppError.CODE_NETWORK }?.second
                ?: errors.firstOrNull()?.second
                ?: AppError.noResult("无可用歌词源")
            LogKit.i("歌词未找到: $trackName - ${err.message}")
            mainHandler.post { onDone(Result.Failure(err)) }
        }

        fun handle(name: String, fetched: Result<String>) {
            if (finished.get()) return
            when (fetched) {
                is Result.Success -> {
                    val lines = sanitize(LrcParser.parse(fetched.data))
                    if (lines.isNotEmpty() && finished.compareAndSet(false, true)) {
                        cache.put(trackName, artistName, fetched.data)
                        LogKit.i("歌词获取成功($name): $trackName (${lines.size} 行)")
                        mainHandler.post { onDone(Result.Success(FetchResult(convert(lines), name))) }
                        return
                    }
                    if (lines.isEmpty()) {
                        LogKit.d("$name 解析为空: $trackName")
                        errors.add(name to AppError.parse("$name 歌词为空"))
                    }
                }
                is Result.Failure -> {
                    LogKit.d("$name 失败: ${fetched.error.message}")
                    errors.add(name to fetched.error)
                }
            }
            if (pending.decrementAndGet() == 0) allFailed()
        }

        // 防御：源客户端抛出未捕获异常时转为标准错误，绝不让异常杀死线程、
        // 导致 pending 计数不归零 → allFailed 永不触发 → fetching 永久卡死
        fun safeFetch(name: String, block: () -> Result<String>): Result<String> =
            try {
                block()
            } catch (e: Exception) {
                LogKit.e("$name 未捕获异常", e)
                Result.Failure(AppError.parse("$name 内部异常"))
            }

        executor.execute { handle("LRCLIB", safeFetch("LRCLIB") { LrclibClient.fetch(trackName, artistName, durationSec).map { it.syncedLrc } }) }
        executor.execute { handle("QQ音乐", safeFetch("QQ音乐") { QqMusicClient.fetch(trackName, artistName, durationMs).map { it.syncedLrc } }) }
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
