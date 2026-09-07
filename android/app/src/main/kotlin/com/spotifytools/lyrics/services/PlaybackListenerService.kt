package com.spotifytools.lyrics.services

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
 * 重绑可靠性（三层保障，解决 Spotify MediaSession 销毁重建后失联）：
 *  1. OnActiveSessionsChangedListener —— session 列表变化即重绑（主通道）
 *  2. onNotificationPosted —— Spotify 通知出现时兜底重绑
 *  3. onListenerConnected —— 服务连接时立即绑定
 *
 * 降级：权限未授予时服务不连接（系统行为），App 内引导授权。
 * 规则：仅监听媒体会话；Spotify session 消失时发布 null（停止歌词）。
 */
class PlaybackListenerService : NotificationListenerService() {

    private var boundController: MediaController? = null
    private var sessionListListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

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
    }

    override fun onListenerDisconnected() {
        unbindAll()
    }

    /** 通知变化兜底：Spotify 通知出现时若无绑定则尝试重绑 */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == SPOTIFY_PACKAGE && boundController == null) {
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
            // metadata 为空：session 失效，尝试重绑
            bindSpotifySession()
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

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
