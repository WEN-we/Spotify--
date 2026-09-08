package com.spotifytools.lyrics.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.spotifytools.lyrics.modules.FloatingLyricsView
import com.spotifytools.lyrics.ui.MainActivity
import com.spotifytools.lyrics.utils.AppError
import com.spotifytools.lyrics.utils.LogKit
import com.spotifytools.lyrics.config.AppConfig

/**
 * LyricsService：歌词前台服务（编排中枢）
 *
 * 数据流（单向）：
 *   PlaybackBus（状态变化）→ 歌词获取（LyricRepository）
 *   → 定时器取当前进度行 → FloatingLyricsView 渲染
 *
 * 降级：
 *   - 悬浮窗权限未授予 → 不创建悬浮窗（仅前台服务保活，等权限）
 *   - 无歌词 → 显示「未找到歌词」
 *   - 未播放 → 悬浮窗移除
 */
class LyricsService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repository: LyricRepository
    private var floatingView: FloatingLyricsView? = null

    // 当前歌曲状态
    private var currentTrackId: String? = null
    private var currentLines: List<LyricRepository.LrcLine> = emptyList()
    private var currentPlayback: PlaybackBus.State? = null

    // 歌词重试：fetch 失败（网络瞬断等）后不重试会导致整首歌空白，
    // 轮询状态发布时按间隔自动重试（RETRY_MS 内不重复）
    private var fetching = false
    private var lastFetchFailAt = 0L

    // 歌词滚动定时器（500ms）
    private val ticker = object : Runnable {
        override fun run() {
            renderCurrentLine()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }
    private var ticking = false

    // 播放状态观察者
    private val playbackObserver: (PlaybackBus.State?) -> Unit = { state ->
        onPlaybackChanged(state)
    }

    override fun onCreate() {
        super.onCreate()
        repository = LyricRepository(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        ensureFloatingView()
        PlaybackBus.observe(playbackObserver)
        LogKit.i("LyricsService 已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureFloatingView()
        // 悬浮窗延迟创建场景（授权后返回 App 触发 start）：已有播放则补拉歌词
        if (floatingView != null && currentTrackId != null && currentLines.isEmpty()) {
            PlaybackBus.currentState?.let { fetchLyrics(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        PlaybackBus.removeObserver(playbackObserver)
        floatingView?.destroy()
        floatingView = null
        lastSource = null
        instance = null
        LogKit.i("LyricsService 已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 播放状态处理 ──

    private fun onPlaybackChanged(state: PlaybackBus.State?) {
        currentPlayback = state

        if (state == null) {
            // 停止播放：暂停定时器，移除悬浮窗（WindowManager 根视图 visibility 不可靠）
            stopTicker()
            floatingView?.destroy()
            floatingView = null
            return
        }

        ensureFloatingView()

        // 切歌：获取新歌词
        if (state.trackId != currentTrackId) {
            currentTrackId = state.trackId
            currentLines = emptyList()
            lastSource = null
            fetchLyrics(state)
        } else if (currentLines.isEmpty() && !fetching && state.isPlaying &&
            SystemClock.elapsedRealtime() - lastFetchFailAt > RETRY_MS
        ) {
            // 同曲目但无歌词且此前失败：自动重试（轮询状态发布触发，RETRY_MS 节流）
            fetchLyrics(state)
        }

        // 播放/暂停切换定时器
        if (state.isPlaying) startTicker() else stopTicker()
        renderCurrentLine()
    }

    private fun fetchLyrics(state: PlaybackBus.State) {
        // 歌词获取不依赖悬浮窗（无悬浮窗权限时主界面仍显示来源/状态）
        fetching = true
        floatingView?.showHint("正在获取歌词…")
        repository.fetchAsync(
            trackName = state.title,
            artistName = state.artist,
            durationMs = state.durationMs,
        ) { result ->
            fetching = false
            // 回调时可能已切歌：校验 trackId
            if (state.trackId != currentTrackId) return@fetchAsync
            result
                .onSuccess { fetched ->
                    currentLines = fetched.lines
                    lastSource = fetched.source
                    renderCurrentLine()
                }
                .onFailure { err ->
                    currentLines = emptyList()
                    lastSource = null
                    // 网络类失败记录时间，RETRY_MS 后由轮询状态触发自动重试；
                    // NO_RESULT（曲库确认无此歌）不重试，避免无效请求
                    if (err.code != AppError.CODE_NO_RESULT) {
                        lastFetchFailAt = SystemClock.elapsedRealtime()
                    }
                    floatingView?.showHint(when (err.code) {
                        AppError.CODE_NO_RESULT -> "未找到歌词"
                        AppError.CODE_NETWORK -> "网络不可用"
                        else -> "歌词加载失败"
                    })
                }
        }
    }

    private fun renderCurrentLine() {
        val view = floatingView ?: return
        val playback = currentPlayback ?: return
        if (currentLines.isEmpty()) return

        // 应用歌词偏移校准（正值延后显示）；进度快照由 2s 轮询自动回正，此处只做轴偏移
        val position = playback.currentPosition() - AppConfig.lyricOffsetMs
        // 二分查找当前行
        var lo = 0
        var hi = currentLines.size - 1
        var index = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (currentLines[mid].startTimeMs <= position) {
                index = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (index >= 0) view.updateLyrics(currentLines, index)
    }

    // ── 定时器 ──

    private fun startTicker() {
        if (ticking) return
        ticking = true
        mainHandler.post(ticker)
    }

    private fun stopTicker() {
        ticking = false
        mainHandler.removeCallbacks(ticker)
    }

    // ── 悬浮窗 ──

    private fun ensureFloatingView() {
        if (floatingView != null) return
        if (!Settings.canDrawOverlays(this)) {
            LogKit.d("悬浮窗权限未授予，暂不创建（等待授权）")
            return
        }
        floatingView = FloatingLyricsView.create(this)
        if (floatingView == null) LogKit.e("悬浮窗创建失败")
    }

    // ── 前台通知 ──

    private fun buildNotification(): Notification {
        val channelId = "lyrics_service"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "歌词服务", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        // 点击通知回到 App（悬浮窗关闭后快速恢复入口）
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("悬浮歌词运行中")
            .setContentText("正在同步 Spotify 歌词")
            .setContentIntent(contentIntent)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 500L
        private const val RETRY_MS = 20_000L   // 歌词获取失败后的重试间隔

        /** 最近一次歌词来源（主界面展示用；null = 未获取/失败） */
        @Volatile
        var lastSource: String? = null
            private set

        /** 当前服务实例（主界面实时切换锁定用） */
        @Volatile
        private var instance: LyricsService? = null

        /** 切换悬浮窗锁定（持久化 + 实时应用，无需重启服务） */
        fun setFloatingLocked(locked: Boolean) {
            AppConfig.floatingLocked = locked
            instance?.floatingView?.setLocked(locked)
        }

        /** 手动刷新：清当前曲缓存并重新获取歌词（主界面「重新获取」入口） */
        fun refreshLyrics(context: Context): Boolean {
            val svc = instance ?: return false
            val state = PlaybackBus.currentState ?: return false
            svc.repository.evict(state.title, state.artist)
            svc.mainHandler.post {
                svc.currentLines = emptyList()
                lastSource = null
                svc.fetchLyrics(state)
            }
            return true
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LyricsService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LyricsService::class.java))
        }
    }
}
