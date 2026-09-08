# Spotify 繁简转换 + 歌词增强工具

解决 Spotify 两大痛点：**界面/歌词显示繁体字**、**部分歌曲无歌词**（尤其是中文歌曲）。

双端支持：

| 平台 | 方案 | 功能 |
|---|---|---|
| Windows | Spicetify 扩展 + lyrics-plus 补丁 + 本地代理 | 全界面繁体转简体、QQ音乐歌词源、LRCLIB 模糊搜索回退 |
| Android | 自研「悬浮歌词」App（零第三方依赖） | 系统级悬浮歌词、繁简转换、多歌词源回退、QQ音乐式锁定模式 |

## 功能亮点

### Windows 端
- **t2s-converter 扩展**：基于 opencc-js + MutationObserver，实时将 Spotify 界面繁体文本转换为简体
- **QQ音乐歌词源**：为 lyrics-plus 新增 QQ 音乐 Provider，补齐中文歌词覆盖
- **LRCLIB 模糊搜索**：精确匹配失败后自动降级模糊搜索
- **本地 CORS 代理**：解决 Spotify CEF 环境下 QQ 音乐 API 的跨域限制

### Android 端（悬浮歌词）
- **精准同步**：通知监听（NotificationListenerService）+ MediaSession 获取精确进度与元数据，Spotify 会话重建后自动重绑（三层保障）
- **多歌词源回退**：LRCLIB → QQ音乐，自动切换
- **搜索抗封锁**：QQ音乐搜索四链路降级（局域网代理→网页→桌面端点→smartbox）+ 歌手感知匹配，拒绝同名不同歌手的错配
- **繁简转换**：内置词典，繁体歌词实时转简体
- **QQ音乐式锁定模式**：歌词卡片右上角锁按钮，锁定后歌词单行显示、触摸完全穿透（不遮挡任何应用操作），锁按钮始终可点击
- **完整交互**：拖动、双击切换多行/单行模式、位置记忆、字号实时调节
- **本地缓存**：已播放歌曲歌词缓存，重复播放零请求
- **零第三方运行时依赖**：仅使用 Android 标准 API

## 安装

### Android App（推荐普通用户）

从 [Releases](../../releases) 下载最新 APK 安装。要求 Android 8.0+。

安装后需授予三项权限（App 内有引导）：

1. **通知使用权**（「Spotify 播放监听」）—— 读取播放状态
2. **悬浮窗权限** —— 显示桌面歌词
3. **通知权限** —— 前台服务保活

然后打开 Spotify 播放音乐，悬浮歌词自动出现。详细使用说明见 [android/README.md](android/README.md)。

### Windows 端

前置要求：

- [Spicetify](https://spicetify.app/)（含 Spotify 桌面客户端）
- Node.js ≥ 18

```powershell
cd windows
npm install

# 1. 启用 lyrics-plus（Spicetify 内置应用）
spicetify config custom_apps lyrics-plus

# 2. 部署繁简转换扩展
npm run deploy

# 3. 部署 lyrics-plus 中文歌词增强补丁
node build/deploy-lyrics-patch.js --apply

# 4. 启用扩展并应用
spicetify config extensions t2s-converter.js
spicetify apply
```

QQ音乐源依赖本地代理（监听 127.0.0.1:39871）。推荐注册开机自启（登录后 30 秒自动启动，异常退出自动重启）：

```powershell
$action   = New-ScheduledTaskAction -Execute "wscript.exe" -Argument """<项目路径>\windows\services\start-proxy.vbs"""
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$trigger.Delay = "PT30S"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "SpotifyQQProxy" -Action $action -Trigger $trigger -Settings $settings -Force
```

或临时手动启动（新开一个终端，保持运行）：

```powershell
cd windows
node services/qqProxy.mjs
```

> qqProxy 监听 `0.0.0.0`（局域网可达，供 Android 悬浮歌词 App 在「设置 → 歌词代理」中填入电脑地址，复用电脑持久缓存规避搜索频控），仅允许转发至 `c.y.qq.com` 白名单端点（防 SSRF），无任何写操作。代理未运行时 lyrics-plus 自动回退 LRCLIB 源，仅 QQ 音乐源不可用。
>
> 代理内置磁盘缓存（搜索 7 天 / 歌词 30 天）：已听过的歌重播时零上游请求，可规避 QQ 搜索接口频控；上游异常时自动降级用过期缓存。若搜索接口被频控（500）且缓存缺失，可运行 `node services/seed-cache-from-log.mjs` 从访问日志重建历史搜索缓存。

> Spicetify 更新可能覆盖 lyrics-plus，歌词源失效时重新执行第 3 步即可（脚本幂等）。

## 从源码构建

### Android App

```powershell
cd android
.\gradlew.bat assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

要求：JDK 17+、Android SDK 35。Release 默认使用 debug 密钥签名（便于直接分发），正式分发请自行替换 `app/build.gradle.kts` 中的签名配置。

运行单元测试（LRC 解析 + 繁简转换）：

```powershell
.\gradlew.bat :core:test
```

### Windows 扩展

```powershell
cd windows
npm test          # 单元测试 + 集成测试
npm run build     # esbuild 打包为单文件 IIFE 扩展
```

## 项目结构

```
├── android/                  # Android 悬浮歌词 App
│   ├── app/                  # 应用模块
│   │   └── src/main/kotlin/com/spotifytools/lyrics/
│   │       ├── ui/           # 主界面
│   │       ├── services/     # 播放监听/歌词服务/歌词仓库/缓存
│   │       ├── modules/      # 悬浮窗渲染
│   │       ├── config/       # 配置
│   │       └── utils/        # 日志
│   ├── core/                 # 核心模块（无 Android 依赖，可独立测试）
│   │   └── src/              # LRC 解析 / 繁简转换 / 词典 + 单元测试
│   └── gradlew.bat           # Gradle Wrapper
├── windows/                  # Windows Spicetify 扩展
│   ├── api/                  # 扩展入口（统一入口层）
│   ├── core/                 # 文本转换核心
│   ├── modules/              # DOM 观察器
│   ├── services/             # opencc / spicetify / QQ音乐代理
│   ├── lyrics-plus-patch/    # lyrics-plus 中文歌词增强补丁
│   ├── build/                # 构建/部署脚本
│   ├── config/ ui/ utils/
│   └── tests/                # 测试
└── docs/                     # 设计文档
```

## 已知限制

- Android 端依赖 Spotify 通知/MediaSession，Spotify 未在前台播放列表页时个别机型可能延迟同步
- Windows 端 qqProxy 需保持运行，QQ音乐源才能工作（LRCLIB 源不受影响）
- 繁简转换基于词典，个别生僻词可能未覆盖

## 许可证

[MIT](LICENSE)
