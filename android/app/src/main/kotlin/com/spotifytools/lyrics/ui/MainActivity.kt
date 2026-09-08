package com.spotifytools.lyrics.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.spotifytools.lyrics.config.AppConfig
import com.spotifytools.lyrics.services.LyricsCache
import com.spotifytools.lyrics.services.LyricsService
import com.spotifytools.lyrics.services.PlaybackBus
import com.spotifytools.lyrics.services.PlaybackListenerService

/**
 * 主界面：卡片式控制中心
 *
 * 分区：状态徽章 → 正在播放卡片 → 设置卡片（开关/字号）→ 权限卡片 → 缓存卡片
 * 降级：任一权限未授予时仅提示，不崩溃；授权后自动启动服务。
 */
class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // 视图引用
    private lateinit var badgeText: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var nowSource: TextView
    private lateinit var fontSizeValue: TextView
    private lateinit var offsetValue: TextView
    private lateinit var proxyState: TextView
    private lateinit var proxyInput: EditText
    private lateinit var diagListener: TextView
    private lateinit var diagSession: TextView
    private lateinit var diagEvent: TextView
    private lateinit var cacheCount: TextView
    private var playbackObserver: ((PlaybackBus.State?) -> Unit)? = null

    // ── 主题色 ──
    private val bgPage = Color.parseColor("#0E0E0E")
    private val bgCard = Color.parseColor("#1C1C1E")
    private val green = Color.parseColor("#1DB954")
    private val orange = Color.parseColor("#FFB74D")
    private val gray = Color.parseColor("#9A9A9A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshBadges()
        refreshCacheCount()
        observePlayback()
        mainHandler.post(diagRefresher)   // 同步诊断每秒刷新；onPause 统一移除
    }

    override fun onPause() {
        super.onPause()
        playbackObserver?.let { PlaybackBus.removeObserver(it) }
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ── UI 构建（纯 framework，零依赖） ──

    private fun buildUi() {
        val root = ScrollView(this).apply { setBackgroundColor(bgPage) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 56)
        }

        // 标题行：App 名 + 权限徽章
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "悬浮歌词"
                textSize = 26f
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            badgeText = TextView(this@MainActivity).apply {
                textSize = 13f
                setPadding(28, 10, 28, 10)
            }
            addView(badgeText)
        })

        // ── 正在播放卡片 ──
        val nowCard = card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("正在播放").apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                // 手动刷新：清当前曲缓存并重新获取（歌词源更新/首次失败时使用）
                addView(smallButton("重新获取") {
                    if (LyricsService.refreshLyrics(this@MainActivity)) {
                        PlaybackBus.currentState?.let { st ->
                            nowSource.text = "正在获取歌词…"
                            nowSource.setTextColor(gray)
                            pollSourceLabel(st.trackId, 0)
                        }
                        Toast.makeText(this@MainActivity, "已重新获取", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "当前无播放歌曲", Toast.LENGTH_SHORT).show()
                    }
                })
            })
            nowTitle = value().apply { text = "等待播放…" }
            addView(nowTitle)
            nowArtist = TextView(this@MainActivity).apply {
                text = ""
                textSize = 14f
                setTextColor(gray)
                setPadding(0, 4, 0, 10)
            }
            addView(nowArtist)
            nowSource = TextView(this@MainActivity).apply {
                text = "启动 Spotify 播放歌曲后自动显示歌词"
                textSize = 12f
                setTextColor(gray)
            }
            addView(nowSource)
        }
        page.addView(nowCard, cardParams())

        // ── 同步诊断卡片（定位「没同步到 Spotify」断在哪一层） ──
        val diagCard = card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "同步诊断"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, 12)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(smallButton("重新连接") { reconnectListener() })
            })
            diagListener = TextView(this@MainActivity).apply { textSize = 14f; setPadding(0, 4, 0, 4) }
            addView(diagListener)
            diagSession = TextView(this@MainActivity).apply { textSize = 14f; setPadding(0, 4, 0, 4) }
            addView(diagSession)
            diagEvent = TextView(this@MainActivity).apply { textSize = 14f; setPadding(0, 4, 0, 4) }
            addView(diagEvent)
            addView(TextView(this@MainActivity).apply {
                text = "「通知监听」未连接时：去系统设置把「通知使用权」关闭再重新开启；MIUI/国产系统还需允许本应用自启动与后台运行"
                textSize = 12f
                setTextColor(gray)
                setPadding(0, 10, 0, 0)
            })
        }
        page.addView(diagCard, cardParams())

        // ── 设置卡片 ──
        val settingCard = card().apply {
            addView(label("设置"))
            addView(switchRow("悬浮歌词", AppConfig.floatingEnabled) { checked ->
                AppConfig.floatingEnabled = checked
                if (checked && allPermissionsGranted()) LyricsService.start(this@MainActivity)
                else if (!checked) LyricsService.stop(this@MainActivity)
            })
            // 锁定模式（QQ音乐级）：触摸完全穿透，不影响其他应用操作
            addView(switchRow("锁定悬浮窗（触摸穿透）", AppConfig.floatingLocked) { checked ->
                LyricsService.setFloatingLocked(checked)
            })
            addView(switchRow("繁体转简体", AppConfig.t2sEnabled) { checked ->
                AppConfig.t2sEnabled = checked
            })
            addView(switchRow("调试日志", AppConfig.debugLog) { checked ->
                AppConfig.debugLog = checked
            })

            // 字号调节（悬浮窗实时生效）
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 20, 0, 4)
                addView(TextView(this@MainActivity).apply {
                    text = "歌词字号"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                fontSizeValue = TextView(this@MainActivity).apply {
                    textSize = 14f
                    setTextColor(green)
                }
                addView(fontSizeValue)
            })
            addView(SeekBar(this@MainActivity).apply {
                min = 12
                max = 32
                progress = AppConfig.fontSize.toInt()
                updateFontLabel()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        AppConfig.fontSize = value.toFloat()
                        updateFontLabel()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })

            // 歌词偏移校准：歌词偏快调「+」（延后），偏慢调「-」（提前）；500ms 内生效
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 20, 0, 4)
                addView(TextView(this@MainActivity).apply {
                    text = "歌词校准"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                offsetValue = TextView(this@MainActivity).apply {
                    textSize = 14f
                    setTextColor(green)
                }
                addView(offsetValue)
                updateOffsetLabel()
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 0)
                addView(smallButton("-1s") { adjustOffset(-1000) })
                addView(TextView(this@MainActivity).apply { text = "  "; textSize = 1f })
                addView(smallButton("-0.5s") { adjustOffset(-500) })
                addView(TextView(this@MainActivity).apply { text = "  "; textSize = 1f })
                addView(smallButton("+0.5s") { adjustOffset(500) })
                addView(TextView(this@MainActivity).apply { text = "  "; textSize = 1f })
                addView(smallButton("+1s") { adjustOffset(1000) })
                addView(TextView(this@MainActivity).apply { text = "  "; textSize = 1f })
                addView(smallButton("重置") { adjustOffset(-AppConfig.lyricOffsetMs) })
            })

            // 歌词代理：QQ音乐搜索接口频控时，同 WiFi 走电脑 qqProxy 缓存（电脑需运行代理）
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 20, 0, 4)
                addView(TextView(this@MainActivity).apply {
                    text = "歌词代理"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                proxyState = TextView(this@MainActivity).apply {
                    textSize = 14f
                }
                addView(proxyState)
                updateProxyState()
            })
            proxyInput = EditText(this@MainActivity).apply {
                hint = "http://192.168.x.x:39871（留空 = 直连）"
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(gray)
                setText(AppConfig.qqProxyBase)
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#232326"))
                    cornerRadius = 24f
                }
                setPadding(24, 18, 24, 18)
            }
            addView(proxyInput)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
                addView(smallButton("保存") {
                    AppConfig.qqProxyBase = proxyInput.text.toString()
                    updateProxyState()
                    if (AppConfig.qqProxyBase.isNotEmpty()) testProxy()
                    else Toast.makeText(this@MainActivity, "已改为直连", Toast.LENGTH_SHORT).show()
                })
                addView(TextView(this@MainActivity).apply { text = "  "; textSize = 1f })
                addView(smallButton("测试") { testProxy() })
                addView(TextView(this@MainActivity).apply { text = "  "; textSize = 1f })
                addView(smallButton("清空") {
                    AppConfig.qqProxyBase = ""
                    proxyInput.setText("")
                    updateProxyState()
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = "QQ音乐搜索接口频控时，手机与电脑同 WiFi 可走电脑代理缓存获取歌词（电脑端 qqProxy 需运行）"
                textSize = 12f
                setTextColor(gray)
                setPadding(0, 10, 0, 0)
            })
        }
        page.addView(settingCard, cardParams())

        // ── 权限卡片 ──
        val permCard = card().apply {
            addView(label("权限"))
            addView(permRow("通知监听", notificationListenerGranted()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            addView(permRow("悬浮窗", overlayGranted()) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            })
            if (Build.VERSION.SDK_INT >= 33) {
                addView(permRow("通知", notificationGranted()) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
                })
            }
        }
        page.addView(permCard, cardParams())

        // ── 缓存卡片 ──
        val cacheCard = card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(label("离线缓存"))
                    cacheCount = TextView(this@MainActivity).apply {
                        textSize = 14f
                        setTextColor(gray)
                        setPadding(0, 4, 0, 0)
                    }
                    addView(cacheCount)
                })
                addView(smallButton("清空") {
                    LyricsCache(this@MainActivity).clear()
                    refreshCacheCount()
                    Toast.makeText(this@MainActivity, "缓存已清空", Toast.LENGTH_SHORT).show()
                })
            })
        }
        page.addView(cacheCard, cardParams())

        root.addView(page, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(root)
    }

    // ── 组件工厂 ──

    /** 圆角卡片容器 */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 28, 32, 32)
        background = GradientDrawable().apply {
            setColor(bgCard)
            cornerRadius = 48f
        }
    }

    private fun cardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 36
        }

    /** 卡片小标签 */
    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(gray)
        setPadding(0, 0, 0, 12)
    }

    /** 播放卡主值 */
    private fun value(): TextView = TextView(this).apply {
        textSize = 19f
        setTextColor(Color.WHITE)
        paint.isFakeBoldText = true
        setPadding(0, 0, 0, 0)
    }

    /** 开关行 */
    private fun switchRow(labelText: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 18, 0, 18)
            addView(TextView(this@MainActivity).apply {
                text = labelText
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(this@MainActivity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            })
        }

    /** 权限行：状态 + 名称 + 未授权时显示去授权按钮 */
    private fun permRow(name: String, granted: Boolean, onGrant: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
            addView(TextView(this@MainActivity).apply {
                text = if (granted) "✓" else "✗"
                textSize = 16f
                setTextColor(if (granted) green else orange)
            })
            addView(TextView(this@MainActivity).apply {
                text = name
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(20, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!granted) {
                addView(smallButton("去授权", onGrant))
            }
        }

    /** 小按钮（胶囊） */
    private fun smallButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(green)
        isAllCaps = false
        setPadding(36, 8, 36, 8)
        minHeight = 0
        minWidth = 0
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#232326"))
            cornerRadius = 60f
            setStroke(3, green)
        }
        setOnClickListener { onClick() }
    }

    private fun updateFontLabel() {
        fontSizeValue.text = "${AppConfig.fontSize.toInt()}sp"
    }

    /** 偏移校准：调整后立即持久化，悬浮窗下一次渲染（≤500ms）自动生效 */
    private fun adjustOffset(deltaMs: Int) {
        AppConfig.lyricOffsetMs = AppConfig.lyricOffsetMs + deltaMs
        updateOffsetLabel()
    }

    private fun updateOffsetLabel() {
        val ms = AppConfig.lyricOffsetMs
        offsetValue.text = when {
            ms == 0 -> "同步"
            ms > 0 -> "+%.1fs".format(ms / 1000f)
            else -> "%.1fs".format(ms / 1000f)
        }
    }

    // ── 歌词代理 ──

    private fun updateProxyState() {
        val base = AppConfig.qqProxyBase
        proxyState.text = if (base.isEmpty()) "直连" else "已启用"
        proxyState.setTextColor(if (base.isEmpty()) gray else green)
    }

    /** 测试代理可达性（后台线程 ping /ping200，3s 超时） */
    private fun testProxy() {
        val base = proxyInput.text.toString().trim().trimEnd('/')
        if (base.isEmpty()) {
            Toast.makeText(this, "请先输入代理地址", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                val conn = java.net.URL("$base/ping200").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "代理可达 ✓" else "代理不可达（检查地址 / 电脑代理运行 / 防火墙）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.start()
    }

    // ── 同步诊断 ──

    private val diagRefresher = object : Runnable {
        override fun run() {
            updateDiag()
            mainHandler.postDelayed(this, 1000)
        }
    }

    /** 逐层显示同步链路状态：系统监听连接 → Spotify 会话绑定 → 最近播放事件 */
    private fun updateDiag() {
        val connected = PlaybackListenerService.listenerConnected
        val bound = PlaybackListenerService.spotifyBound
        val elapsed = PlaybackListenerService.lastPlaybackEventAt

        diagListener.text = when {
            !notificationListenerGranted() -> "通知监听：✗ 未授权"
            connected -> "通知监听：✓ 已连接"
            else -> "通知监听：✗ 未连接（点「重新连接」或重开通知使用权）"
        }
        diagListener.setTextColor(
            when {
                !notificationListenerGranted() || !connected -> orange
                else -> green
            }
        )

        diagSession.text = when {
            bound -> "Spotify 会话：✓ 已绑定（可获取播放进度）"
            connected -> "Spotify 会话：等待 Spotify 播放（打开 Spotify 播放任意歌曲）"
            else -> "Spotify 会话：—（依赖通知监听连接）"
        }
        diagSession.setTextColor(if (bound) green else gray)

        diagEvent.text = when {
            elapsed == 0L -> "最近播放事件：暂无"
            else -> {
                val sec = (android.os.SystemClock.elapsedRealtime() - elapsed) / 1000
                "最近播放事件：$sec 秒前" + if (sec > 10) "（长时间无更新，可能 Spotify 在后台被系统限制）" else ""
            }
        }
        diagEvent.setTextColor(if (elapsed == 0L) gray else green)
    }

    /** 手动请求系统重连通知监听（官方 API，APK 更新后 MIUI 不自动重绑的补救） */
    private fun reconnectListener() {
        if (!notificationListenerGranted()) {
            Toast.makeText(this, "请先授予「通知使用权」", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        try {
            val cn = ComponentName(this, PlaybackListenerService::class.java)
            NotificationListenerService.requestRebind(cn)
            Toast.makeText(this, "已请求系统重连监听，几秒后自动生效", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "重连请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 状态刷新 ──

    private fun refreshBadges() {
        val missing = mutableListOf<String>()
        if (!notificationListenerGranted()) missing.add("通知监听")
        if (!overlayGranted()) missing.add("悬浮窗")
        if (!notificationGranted()) missing.add("通知")

        // 关键修复：APK 更新后 MIUI 不重绑通知监听器 —— 主动请求系统重绑（官方 API）
        if (notificationListenerGranted()) {
            try {
                val cn = android.content.ComponentName(
                    this, com.spotifytools.lyrics.services.PlaybackListenerService::class.java,
                )
                android.service.notification.NotificationListenerService.requestRebind(cn)
            } catch (_: Exception) { /* 重绑失败不影响主流程 */ }
        }

        if (missing.isEmpty()) {
            badge("已就绪", green)
            if (AppConfig.floatingEnabled) LyricsService.start(this)
        } else {
            badge("待授权 ${missing.size} 项", orange)
        }
    }

    private fun badge(text: String, color: Int) {
        badgeText.text = text
        badgeText.setTextColor(color)
        badgeText.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 60f
            setStroke(3, color)
        }
    }

    private fun refreshCacheCount() {
        val n = LyricsCache(this).size()
        cacheCount.text = if (n > 0) "已缓存 $n 首歌词（断网可用）" else "暂无缓存，播放后自动缓存"
    }

    private fun observePlayback() {
        playbackObserver?.let { PlaybackBus.removeObserver(it) }
        var shownTrackId: String? = null
        playbackObserver = { state ->
            runOnUiThread {
                if (state == null) {
                    shownTrackId = null
                    nowTitle.text = "等待播放…"
                    nowArtist.text = ""
                    nowSource.text = "启动 Spotify 播放歌曲后自动显示歌词"
                } else if (state.trackId != shownTrackId) {
                    // 仅切歌时重置标签并启动轮询——播放中 2s 状态发布会刷新进度，
                    // 不能每次都重置为「正在获取」（否则已加载的来源标签反复闪烁）
                    shownTrackId = state.trackId
                    nowTitle.text = state.title
                    nowArtist.text = state.artist
                    nowSource.text = "正在获取歌词…"
                    nowSource.setTextColor(gray)
                    // 轮询刷新来源标签（双源并行最长 ~8s，最多轮询 20 次）
                    pollSourceLabel(state.trackId, 0)
                }
            }
        }
        PlaybackBus.observe(playbackObserver!!)
    }

    /** 轮询歌词来源标签（每秒一次，最多 20 次；切歌后停止旧轮询） */
    private fun pollSourceLabel(trackId: String, attempt: Int) {
        if (attempt >= 20) {
            nowSource.text = "未找到歌词"
            nowSource.setTextColor(orange)
            return
        }
        mainHandler.postDelayed({
            // 已切歌：放弃本次轮询
            if (PlaybackBus.currentState?.trackId != trackId) return@postDelayed
            val src = LyricsService.lastSource
            if (src != null) {
                nowSource.text = "● $src · 同步滚动中"
                nowSource.setTextColor(green)
            } else {
                nowSource.text = if (attempt < 2) "正在获取歌词…" else "多源搜索中…"
                nowSource.setTextColor(gray)
                pollSourceLabel(trackId, attempt + 1)
            }
        }, 1000)
    }

    // ── 权限判定 ──

    private fun notificationListenerGranted(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun overlayGranted(): Boolean = Settings.canDrawOverlays(this)

    private fun notificationGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun allPermissionsGranted(): Boolean =
        notificationListenerGranted() && overlayGranted() && notificationGranted()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshBadges()
        buildUi()  // 重建以刷新权限行按钮
    }
}
