package com.spotifytools.lyrics.config

import android.content.Context
import android.content.SharedPreferences

/**
 * ConfigManagementSkill：配置中心
 * 规则：不写死参数；全部可配置；支持热更新（内存快照 + 持久化）
 */
object AppConfig {

    private const val PREFS_NAME = "spotify_lyrics_config"

    // 配置键
    const val KEY_FLOATING_ENABLED = "floating_enabled"           // 悬浮窗开关
    const val KEY_T2S_ENABLED = "t2s_enabled"                     // 繁简转换开关
    const val KEY_FONT_SIZE = "font_size"                          // 歌词字号
    const val KEY_LOCKED = "floating_locked"                       // 悬浮窗锁定（触摸穿透）
    const val KEY_LRCLIB_BASE = "lrclib_base"                      // LRCLIB 地址（可换源）
    const val KEY_QQ_PROXY_BASE = "qq_proxy_base"                  // Windows qqProxy 局域网地址（空 = 直连）
    const val KEY_DEBUG_LOG = "debug_log"                          // 调试日志

    // 默认值（集中声明，不散落各处）
    const val DEFAULT_FONT_SIZE = 18f
    const val DEFAULT_LRCLIB_BASE = "https://lrclib.net"

    private lateinit var prefs: SharedPreferences

    /** 初始化（App 启动时调用一次） */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var floatingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_ENABLED, value).apply()

    var t2sEnabled: Boolean
        get() = prefs.getBoolean(KEY_T2S_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_T2S_ENABLED, value).apply()

    var fontSize: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    /** 悬浮窗锁定：true = 触摸完全穿透（不影响其他应用操作），不可拖动/双击 */
    var floatingLocked: Boolean
        get() = prefs.getBoolean(KEY_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCKED, value).apply()

    /** 悬浮窗位置记忆（距屏幕顶部像素；-1 = 默认底部） */
    var floatingY: Int
        get() = prefs.getInt("floating_y", -1)
        set(value) = prefs.edit().putInt("floating_y", value).apply()

    /** 歌词偏移校准（毫秒；正值 = 歌词延后显示，即歌词偏快时调正；±10s 内有效） */
    var lyricOffsetMs: Int
        get() = prefs.getInt("lyric_offset_ms", 0).coerceIn(-10_000, 10_000)
        set(value) = prefs.edit().putInt("lyric_offset_ms", value.coerceIn(-10_000, 10_000)).apply()

    var lrclibBase: String
        get() = prefs.getString(KEY_LRCLIB_BASE, DEFAULT_LRCLIB_BASE) ?: DEFAULT_LRCLIB_BASE
        set(value) = prefs.edit().putString(KEY_LRCLIB_BASE, value).apply()

    /**
     * Windows qqProxy 局域网地址（如 http://192.168.5.8:39871）
     * 非空 = QQ 音乐搜索/歌词优先走电脑代理（复用其持久缓存，规避搜索接口 IP 频控）；
     * 代理不可达时自动降级直连。空 = 直连。
     */
    var qqProxyBase: String
        get() = (prefs.getString(KEY_QQ_PROXY_BASE, "") ?: "").trim().trimEnd('/')
        set(value) = prefs.edit().putString(KEY_QQ_PROXY_BASE, value.trim().trimEnd('/')).apply()

    var debugLog: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOG, value).apply()
}
