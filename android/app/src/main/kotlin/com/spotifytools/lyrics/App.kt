package com.spotifytools.lyrics

import android.app.Application
import com.spotifytools.lyrics.config.AppConfig

/**
 * 应用入口：初始化配置中心（全局唯一）
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
    }
}
