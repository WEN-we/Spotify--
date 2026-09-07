# Spotify 繁简转换 + 歌词补充工具 设计文档

- 日期：2026-09-07
- 状态：已与用户确认设计方向
- 平台：Windows 桌面客户端 + Android 手机

## 1. 背景与痛点

用户使用 Spotify 时存在以下痛点：

1. **繁体字显示**：部分歌曲的歌名、歌手、专辑、歌词在界面中显示为繁体中文，阅读不适。
2. **歌词缺失**：部分歌曲在 Spotify 内没有歌词显示。
3. （仅 Android）上述两点在安卓端同样存在，且安卓 Spotify App 为封闭应用，无法像桌面端那样通过 Spicetify 定制。

## 2. 目标与非目标

### 目标

- **G1（Windows）**：Spotify 界面内所有繁体中文（歌名、歌手、专辑、歌词等）实时转换为简体。
- **G2（Windows）**：为无歌词歌曲提供同步滚动歌词（多歌词源自动回退）。
- **G3（Android）**：自研悬浮歌词 App，识别 Spotify 当前播放歌曲，显示同步滚动歌词，歌词与歌名均转换为简体。
- **G4**：歌词已播放过的歌曲本地缓存，断网可再次显示。
- **G5**：全端降级运行——任何模块失败不影响 Spotify/手机正常使用。

### 非目标（明确排除）

- **N1**：不做任何绕过 Spotify 付费限制的功能（顺序播放、单曲循环、切上一首等 Premium 功能）。此类需求违反 Spotify 服务条款，明确拒绝。
- **N2**：不修改 Spotify 安卓 App 本体（无注入、无 root）。
- **N3**：不搭建服务器——纯客户端架构，零服务器成本，听歌数据不经过第三方服务器。

## 3. 总体架构

```
Spotify工具/
 ├── windows/                  # Windows 端
 │    ├── core/                # 繁简转换核心（纯函数，可单测）
 │    ├── modules/             # DOM 观察器模块（可启用/禁用）
 │    ├── services/            # Spicetify API 集成、opencc-js 字典服务
 │    ├── api/                 # 扩展统一入口
 │    ├── ui/                  # 扩展配置面板
 │    ├── config/              # 配置文件
 │    ├── utils/               # 工具库
 │    ├── tests/               # 单元测试
 │    └── build/               # 构建脚本（源码 → Spicetify 扩展产物）
 │
 └── android/                  # Android 端（悬浮歌词 App）
      ├── app/                 # 入口、权限引导（悬浮窗/通知监听授权）
      ├── core/                # 繁简转换核心（与 Windows 端共用词表数据）
      ├── modules/             # 歌词悬浮窗、通知栏歌词、播放识别
      ├── services/            # LRCLIB 歌词服务、通知监听服务
      ├── api/                 # 模块统一接口层
      ├── config/              # 配置
      ├── utils/ tests/
      └── 技术选型：Kotlin，无 root，纯客户端
```

依赖说明：
- Windows 端依赖 Spicetify 框架 + 社区 lyrics-plus 扩展（多歌词源：Musixmatch → NetEase → LRCLIB 等，安装时实测验证可用性）。
- Android 端零第三方运行时依赖（仅标准 Android API + LRCLIB HTTP API）。

## 4. Skill 划分（两端）

| Skill | 层 | 职责 | 输入 → 输出 | 关键规则 |
|---|---|---|---|---|
| TextConversionSkill | core | 繁→简转换 + 是否含繁体检测 | 文本 → 转换后文本/布尔 | 纯函数；转换异常返回原文（降级） |
| DOMObserverSkill | modules (Win) | 监听界面文本变化并调用转换 | DOM 变更 → 更新后 DOM | 防循环：无繁体则跳过，零开销 |
| SpicetifyIntegrationSkill | services (Win) | 注册扩展、读取播放状态 | Spicetify API → 标准化数据 | Spicetify 不可用时静默禁用 |
| LyricFetchSkill | services (双端) | 歌词获取（Win 由 lyrics-plus 承担；Android 走 LRCLIB） | 歌名/歌手 → LRC 歌词 | 缓存优先；源失败返回标准错误结构 |
| NotificationListenerSkill | services (Android) | 系统媒体通知捕获歌名/进度 | 通知 → 播放状态 | 仅监听媒体通知；权限未授予时降级为手动搜索 |
| FloatingLyricsSkill | modules (Android) | 悬浮窗/通知栏歌词渲染 | 播放状态+歌词 → 界面 | 悬浮窗权限未授予时仅通知栏显示 |
| ConfigManagementSkill | config | 开关配置（转换开关、歌词源选择） | 配置键 → 配置值 | 不写死参数，支持热更新 |
| ErrorHandlingSkill | 横切 | 标准错误结构捕获 | 异常 → `{code, message, fallback}` | 任何失败不崩溃宿主 |
| LoggingSkill | 横切 | 调试日志 | 行为 → 日志 | 默认静默，debug 模式开启 |

强制规则：所有功能定义为 Skill；Skill 有输入/输出定义；禁止跨层直接调用（必须走 api 层）；外部依赖全部 Service 化。

## 5. 数据流（单向，禁止循环依赖）

### Windows

```
Spotify 渲染界面 → MutationObserver 捕获文本变化
  → 检测是否含繁体（不含则跳过）
  → opencc-js 转换为简体 → 写回 DOM（标记已处理，避免循环触发）
```

### Android

```
Spotify 播放 → 系统媒体通知 → NotificationListenerService 捕获歌名/歌手/进度
  → 本地歌词缓存查询（命中则直接用）
  → 未命中：LRCLIB 搜索歌词 → 繁转简本地转换 → 写入缓存
  → 悬浮窗/通知栏显示同步滚动歌词
```

## 6. 降级策略（全链路）

| 故障场景 | 降级行为 |
|---|---|
| opencc-js 字典加载失败（Win） | 扩展静默禁用，显示原繁体 |
| 歌词源无结果（双端） | 显示"未找到歌词"提示 |
| NotificationListener 权限未授予（Android） | 降级为手动搜索歌曲 |
| 悬浮窗权限未授予（Android） | 仅通知栏/锁屏显示歌词 |
| Spotify 大版本更新后扩展失效（Win） | 卸载扩展即恢复原版，可随时回退 |
| 网络不可用（Android） | 使用本地缓存歌词；缓存未命中则提示离线 |

## 7. 测试策略

- **core（双端）**：单元测试覆盖繁简转换纯函数——繁体样本、简繁混合文本、边界情况（空串、纯英文、表情符号）。两端词表一致性校验。
- **modules（Win）**：jsdom 模拟 DOM 变化，测试观察器防循环逻辑、无繁体跳过逻辑。
- **modules（Android）**：单元测试 + 手动验收。
- **集成验收（真实环境手动清单）**：
  - 繁体歌名歌曲在 Windows/Android 均显示简体
  - 无歌词歌曲能显示同步滚动歌词
  - 歌词面板中繁体歌词被转换为简体
  - 断网二次播放缓存歌曲仍有歌词
  - 悬浮窗覆盖在 Spotify 界面之上正常显示

## 8. 版本控制规范

commit 类型按用户规则：feat / fix / refactor / module / api / service / config / ai。
