package com.spotifytools.lyrics.services

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spotifytools.lyrics.utils.LogKit

/**
 * NotificationListenerSkill：Spotify 播放识别
 *
 * 机制：NotificationListenerService 授权后，通过 [MediaSessionManager.getActiveSessions]
 *      获取活跃 MediaSession（精确元数据 + 进度）。
 *
 * 重绑可靠性（四层保障，解决 Spotify MediaSession 销毁重建后失联/事件丢失）：
 *  1. OnActiveSessionsChangedListener —— session 列表变化即重绑（主通道）
 *  2. onNotificationPosted —— Spotify 通知出现时对账重绑
 *  3. onListenerConnected —— 服务连接时立即绑定
 *  4. 轮询兜底（2s）—— 即使上述回调全部丢失，轮询强制对账 + 重新发布最新状态
 *
 * 降级：权限未授予时服务不连接（系统行为），App 内引导授权。
 * 规则：仅监听媒体会话；Spotify session 消失时发布 null（停止歌词）。
 */
class PlaybackListenerService : NotificationListenerService() {

    private var boundController: MediaController? = null
    private var sessionListListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    // 轮询兜底：回调可能丢失（Spotify session 重建、厂商系统事件裁剪），
    // 每 2s 强制对账一次，保证最终同步。本地 binder 调用，开销可忽略。
    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false
    private val pollTask = object : Runnable {
        override fun run() {
            try {
                bindSpotifySession()
            } catch (_: Exception) { /* 下轮再试 */ }
            pollHandler.postDelayed(this, POLL_MS)
        }
    }

    private val sessionCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            publishState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishState()
        }

        override fun onSessionDestroyed() {
            LogKit.i("Spotify MediaSession 已销毁，等待重绑")
            boundController?.unregisterCallback(this)
            boundController = null
            PlaybackBus.publish(null)
            // 立即尝试重绑（session 列表监听器也会兜底触发）
            bindSpotifySession()
        }
    }

    override fun onListenerConnected() {
        LogKit.i("通知监听已连接，绑定 Spotify MediaSession")
        registerSessionListListener()
        bindSpotifySession()
        startPolling()
    }

    override fun onListenerDisconnected() {
        stopPolling()
        unbindAll()
    }

    /** 通知变化兜底：Spotify 通知出现时对账（幂等，同 token 跳过并仅刷新状态） */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            bindSpotifySession()
        }
    }

    /** 主通道：活跃 session 列表变化（新增/移除）时自动重绑 */
    private fun registerSessionListListener() {
        if (sessionListListener != null) return
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, PlaybackListenerService::class.java)
            sessionListListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                val spotify = controllers?.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
                if (spotify != null && boundController == null) {
                    LogKit.i("检测到 Spotify session 变化，重新绑定")
                    bindSpotifySession()
                }
            }.also {
                msm.addOnActiveSessionsChangedListener(it, componentName)
            }
        } catch (e: Exception) {
            LogKit.e("注册 session 列表监听失败: ${e.message}", e)
        }
    }

    private fun bindSpotifySession() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, PlaybackListenerService::class.java)
            val controllers = msm.getActiveSessions(componentName)
            val spotify = controllers.firstOrNull { it.packageName == SPOTIFY_PACKAGE }

            if (spotify == null) {
                LogKit.d("未发现活跃 Spotify MediaSession（等待播放）")
                return
            }

            // 已绑定同一 session 则跳过
            if (boundController?.packageName == spotify.packageName &&
                boundController?.sessionToken == spotify.sessionToken
            ) {
                publishState()
                return
            }

            boundController?.unregisterCallback(sessionCallback)
            boundController = spotify.also {
                it.registerCallback(sessionCallback)
                LogKit.i("已绑定 Spotify MediaSession")
                publishState()
            }
        } catch (e: Exception) {
            LogKit.e("绑定 MediaSession 失败: ${e.message}", e)
        }
    }

    private fun publishState() {
        val controller = boundController ?: return
        val metadata = controller.metadata ?: run {
            // metadata 为空（session 瞬时失效）：不在此重绑（会与 bindSpotifySession
            // 的同 token 分支互相递归），由 2s 轮询对账兜底
            LogKit.d("metadata 为空，等待轮询对账")
            return
        }
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val pb = controller.playbackState

        val state = PlaybackBus.State(
            trackId = "$title|$artist|$duration",
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            positionMs = pb?.position ?: 0L,
            lastUpdateTime = pb?.lastPositionUpdateTime?.takeIf { it > 0 }
                ?: SystemClock.elapsedRealtime(),
            playbackSpeed = pb?.playbackSpeed ?: 1f,
            // 缓冲中也算播放中（歌词继续走，避免加载间隙歌词停摆）
            isPlaying = pb?.state == PlaybackState.STATE_PLAYING ||
                pb?.state == PlaybackState.STATE_BUFFERING,
        )
        PlaybackBus.publish(state)
    }

    private fun unbindAll() {
        try {
            sessionListListener?.let {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                msm.removeOnActiveSessionsChangedListener(it)
            }
        } catch (_: Exception) { /* 服务已断开 */ }
        sessionListListener = null
        boundController?.unregisterCallback(sessionCallback)
        boundController = null
        PlaybackBus.publish(null)
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollHandler.post(pollTask)
        LogKit.i("轮询兜底已启动（${POLL_MS / 1000}s）")
    }

    private fun stopPolling() {
        polling = false
        pollHandler.removeCallbacks(pollTask)
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val POLL_MS = 2000L
    }
}
