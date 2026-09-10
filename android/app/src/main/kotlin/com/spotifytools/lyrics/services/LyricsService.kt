package com.spotifytools.lyrics.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
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

    // 歌词重试：fetch 失败（网络瞬断等）后不重试会导致整首歌空白。
    // 分级：首次失败 6s 快速重试（网络抖动居多），连续失败退避到 20s（接口频控期不轰炸）
    private var fetching = false
    private var lastFetchFailAt = 0L
    private var retryCount = 0

    // 用户 ✕ 关闭的曲目：该曲目内不再重建悬浮窗（换歌自动重现）
    private var dismissedForTrackId: String? = null

    // 网络恢复回调：无网期间失败的歌，网络一回来立即重取（不等重试定时）
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 按需生命周期：Spotify 无会话时自动退出，避免常驻后台（Spotify 播放时由监听服务自动拉起）
    private val idleStopRunnable = Runnable {
        if (currentPlayback == null) {
            LogKit.i("无播放会话，歌词服务自动退出（Spotify 播放时自动重启）")
            stopSelf()
        }
    }

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
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
        ensureFloatingView()
        PlaybackBus.observe(playbackObserver)
        mainHandler.post(rebindWatchdog)
        registerNetworkCallback()
        // 按需生命周期：创建后一段时间仍无播放会话 → 自动退出（通知消失，后台零占用）
        mainHandler.postDelayed(idleStopRunnable, CREATE_IDLE_MS)
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
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) { /* 未注册 */ }
        }
        networkCallback = null
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
            // 停止播放：暂停定时器，移除悬浮窗；30s 后仍无会话 → 服务自动退出
            stopTicker()
            floatingView?.destroy()
            floatingView = null
            mainHandler.postDelayed(idleStopRunnable, SESSION_LOST_MS)
            return
        }

        // 有会话：取消空闲退出
        mainHandler.removeCallbacks(idleStopRunnable)

        ensureFloatingView()

        // 切歌：优先用音乐软件自带歌词（有则零网络延迟），否则走网络源
        if (state.trackId != currentTrackId) {
            currentTrackId = state.trackId
            currentLines = emptyList()
            lastSource = null
            retryCount = 0
            dismissedForTrackId = null   // 换歌自动重现被 ✕ 关闭的悬浮窗
            ensureFloatingView()

            val sessionLrc = state.sessionLyrics?.let { repository.fromSessionLrc(it) }
            if (sessionLrc != null) {
                currentLines = sessionLrc.lines
                lastSource = sessionLrc.source
                renderCurrentLine()
            } else {
                fetchLyrics(state)
            }
        } else if (currentLines.isEmpty() && !fetching && state.isPlaying &&
            SystemClock.elapsedRealtime() - lastFetchFailAt > retryWaitMs()
        ) {
            // 同曲目但无歌词且此前失败：按分级间隔自动重试（轮询状态发布触发）
            fetchLyrics(state)
        }

        // 播放/暂停切换定时器
        if (state.isPlaying) startTicker() else stopTicker()
        renderCurrentLine()
    }

    /** 分级重试间隔：首次失败 6s（多为网络抖动），连续失败退避 20s（频控期不轰炸） */
    private fun retryWaitMs(): Long = if (retryCount == 0) FIRST_RETRY_MS else RETRY_MS

    private fun isNetworkOnline(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        false
    }

    /** 网络恢复即重取：无网期间失败的歌，网络一回来立即重取（不等重试定时） */
    private fun registerNetworkCallback() {
        try {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post {
                        val st = currentPlayback ?: return@post
                        if (currentLines.isEmpty() && !fetching) fetchLyrics(st)
                    }
                }
            }
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(networkCallback!!)
        } catch (_: Exception) {
            networkCallback = null
        }
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
                    // 网络类失败记录时间，按分级间隔自动重试；
                    // NO_RESULT（曲库确认无此歌）不重试，避免无效请求
                    if (err.code != AppError.CODE_NO_RESULT) {
                        retryCount++
                        lastFetchFailAt = SystemClock.elapsedRealtime()
                    }
                    floatingView?.showHint(when (err.code) {
                        AppError.CODE_NO_RESULT -> "未找到歌词"
                        // 网络可用却失败 = 接口瞬断/频控，别误导用户「无网络」
                        AppError.CODE_NETWORK ->
                            if (isNetworkOnline()) "获取失败，自动重试中…" else "等待网络连接…"
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

    // ── 监听自愈看门狗 ──

    private var lastRebindAt = 0L

    /**
     * 重绑看门狗：MIUI/HyperOS 在 App 进程被杀后不会自动重绑通知监听
     * （系统 rebind 被自启动管理拦截/吞掉，实测多次），导致「等待播放」永不恢复。
     * 本前台服务存活期间每 10s 检查一次：监听掉线且有权限 → 请求重绑（节流 15s）。
     * 自启动已授权后重绑即成功；首次触发可能因系统背压稍延迟，属正常。
     */
    private val rebindWatchdog = object : Runnable {
        override fun run() {
            try {
                if (!PlaybackListenerService.listenerConnected && hasListenerPermission()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRebindAt > REBIND_THROTTLE_MS) {
                        lastRebindAt = now
                        NotificationListenerService.requestRebind(
                            ComponentName(this@LyricsService, PlaybackListenerService::class.java),
                        )
                        LogKit.i("看门狗: 监听未连接，已请求系统重绑")
                    }
                }
            } catch (_: Exception) { /* 下轮再试 */ }
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun hasListenerPermission(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    // ── 悬浮窗 ──

    private fun ensureFloatingView() {
        if (floatingView != null) return
        if (!Settings.canDrawOverlays(this)) {
            LogKit.d("悬浮窗权限未授予，暂不创建（等待授权）")
            return
        }
        // 当前曲目被用户 ✕ 关闭 → 本曲内不重建（换歌自动重现）
        if (currentTrackId != null && currentTrackId == dismissedForTrackId) return
        floatingView = FloatingLyricsView.create(this) { dismissFloating() }
        if (floatingView == null) LogKit.e("悬浮窗创建失败")
    }

    /** 用户点 ✕：隐藏悬浮窗（服务继续运行），换歌自动重现 */
    private fun dismissFloating() {
        dismissedForTrackId = currentTrackId
        floatingView?.destroy()
        floatingView = null
        LogKit.i("悬浮窗已由用户关闭（换歌自动重现）")
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
        private const val FIRST_RETRY_MS = 6_000L    // 首次失败快速重试（网络抖动居多）
        private const val RETRY_MS = 20_000L         // 连续失败退避间隔（频控期不轰炸）
        private const val WATCHDOG_MS = 10_000L        // 监听看门狗检查间隔
        private const val REBIND_THROTTLE_MS = 15_000L // 重绑请求节流
        private const val CREATE_IDLE_MS = 45_000L     // 创建后无播放会话的退出时限
        private const val SESSION_LOST_MS = 30_000L    // Spotify 会话消失后的退出宽限（防会话重建抖动）

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
