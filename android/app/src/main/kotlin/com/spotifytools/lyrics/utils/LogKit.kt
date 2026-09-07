package com.spotifytools.lyrics.utils

import android.util.Log
import com.spotifytools.lyrics.config.AppConfig

/**
 * LoggingSkill + ErrorHandlingSkill（横切）
 * 规则：默认静默；debug 模式开启；所有错误返回标准结构，禁止崩溃宿主
 */
object LogKit {
    private const val TAG = "SpotifyLyrics"

    fun d(msg: String) {
        if (AppConfig.debugLog) Log.d(TAG, msg)
    }

    fun i(msg: String) = Log.i(TAG, msg)

    fun e(msg: String, tr: Throwable? = null) = Log.e(TAG, msg, tr)
}

/** 标准错误结构：{code, message, fallback} */
data class AppError(
    val code: Int,
    val message: String,
    /** 降级行为描述（供 UI 提示） */
    val fallback: String,
) {
    companion object {
        const val CODE_NETWORK = 1
        const val CODE_NO_RESULT = 2
        const val CODE_PARSE = 3
        const val CODE_PERMISSION = 4
        const val CODE_STORAGE = 5

        fun network(msg: String) = AppError(CODE_NETWORK, msg, "使用本地缓存")
        fun noResult(msg: String) = AppError(CODE_NO_RESULT, msg, "显示「未找到歌词」")
        fun parse(msg: String) = AppError(CODE_PARSE, msg, "跳过该源")
        fun permission(msg: String) = AppError(CODE_PERMISSION, msg, "降级为手动搜索")
        fun storage(msg: String) = AppError(CODE_STORAGE, msg, "跳过缓存")
    }
}

/** 统一结果封装 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure(val error: AppError) : Result<Nothing>()

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): Result<T> {
        if (this is Success) block(data)
        return this
    }

    inline fun onFailure(block: (AppError) -> Unit): Result<T> {
        if (this is Failure) block(error)
        return this
    }
}

/** 安全执行：任何异常转为标准错误结构（禁止崩溃） */
inline fun <T> runCatchingApp(fallback: AppError, block: () -> T): Result<T> = try {
    Result.Success(block())
} catch (e: Exception) {
    LogKit.e("${fallback.message}: ${e.message}", e)
    Result.Failure(fallback.copy(message = "${fallback.message}: ${e.message}"))
}
