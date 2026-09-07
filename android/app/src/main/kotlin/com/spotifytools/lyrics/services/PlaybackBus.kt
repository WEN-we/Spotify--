package com.spotifytools.lyrics.services

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.spotifytools.lyrics.utils.LogKit

/**
 * 播放状态事件总线（进程内单例，数据流单向：Listener → Bus → 订阅者）
 *
 * 数据流：Spotify 播放 → MediaSession 回调 → PlaybackBus 广播
 *       → LyricsService（歌词获取/滚动）→ 悬浮窗渲染
 */
object PlaybackBus {

    /** 标准化播放状态（MediaSession 快照） */
    data class State(
        val trackId: String,          // 唯一标识（title|artist|duration）
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,         // 歌曲总长
        val positionMs: Long,         // 进度快照
        val lastUpdateTime: Long,     // 快照时间戳（SystemClock.elapsedRealtime 基准）
        val playbackSpeed: Float,    // 播放速率（外推用）
        val isPlaying: Boolean,
    ) {
        /** 实时进度（按播放速率线性外推，elapsedRealtime 单调递增） */
        fun currentPosition(): Long {
            if (!isPlaying || playbackSpeed <= 0f) return positionMs
            val elapsed = SystemClock.elapsedRealtime() - lastUpdateTime
            if (elapsed <= 0) return positionMs
            return positionMs + (elapsed * playbackSpeed).toLong()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<(State?) -> Unit>()

    @Volatile
    var currentState: State? = null
        private set

    /** 订阅播放状态变化（主线程回调；null = 停止播放/会话移除） */
    fun observe(observer: (State?) -> Unit) {
        mainHandler.post {
            observers.add(observer)
            // 立即回放当前状态（新订阅者补发）
            observer(currentState)
        }
    }

    fun removeObserver(observer: (State?) -> Unit) {
        mainHandler.post { observers.remove(observer) }
    }

    /** 发布状态（仅 PlaybackListenerService 调用） */
    fun publish(state: State?) {
        mainHandler.post {
            val changed = currentState?.trackId != state?.trackId
            currentState = state
            LogKit.d("PlaybackBus publish: ${state?.title ?: "null"} changed=$changed")
            observers.forEach { it(state) }
        }
    }
}
