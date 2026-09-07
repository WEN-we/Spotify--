package com.spotifytools.lyrics.modules

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.spotifytools.lyrics.config.AppConfig
import com.spotifytools.lyrics.services.LyricRepository

/**
 * FloatingLyricsSkill：悬浮窗歌词渲染（QQ音乐桌面歌词级）
 *
 * 双窗口架构：
 *  - 歌词窗口：解锁态全宽多行卡片（可拖动/双击）；锁定态单行纯文字、
 *    宽度自适应、FLAG_NOT_TOUCHABLE（触摸完全穿透，零遮挡零拦截）
 *  - 锁按钮窗口：歌词右上角的小锁 🔓/🔒，始终可点击，
 *    点击直接锁定/解锁（无需回主界面）——与 QQ音乐桌面歌词交互一致
 *
 * 位置记忆：拖动后保存 y 坐标，重启/重建后恢复。
 * 规则：所有 UI 更新在主线程；权限未授予时由 LyricsService 决定不创建。
 */
@SuppressLint("ClickableViewAccessibility")
class FloatingLyricsView private constructor(
    private val context: Context,
    private val windowManager: WindowManager,
) : LinearLayout(context) {

    private val lyricLines = ArrayList<TextView>()
    private var expanded = true
    private var locked = AppConfig.floatingLocked

    // 锁按钮（独立小窗口）
    private var lockButton: TextView? = null
    private var lockParams: WindowManager.LayoutParams? = null

    // 最近一次渲染数据（双击切换模式时立即重绘，不等下一 tick）
    private var lastLines: List<LyricRepository.LrcLine> = emptyList()
    private var lastIndex = -1

    companion object {
        private const val CONTEXT_LINES = 2        // 当前行上下各显示行数
        private const val MAX_LINES = CONTEXT_LINES * 2 + 1
        private const val TAP_INTERVAL = 300L      // 双击判定间隔
        private const val DRAG_SLOP = 8            // 拖动触发阈值（px）
        private const val GLOW_COLOR = 0xCC1DB954.toInt() // 当前行光晕（Spotify 绿）
        private const val LOCK_SIZE = 84           // 锁按钮尺寸（px）
        private const val LOCK_MARGIN = 24         // 锁按钮与歌词间距（px）

        private val BG_DRAWABLE = GradientDrawable().apply {
            setColor(0x99000000.toInt()) // 半透明黑卡片
            cornerRadius = 28f
        }

        private val LOCK_BG_UNLOCKED = GradientDrawable().apply {
            setColor(0xCC2E2E30.toInt())
            cornerRadius = LOCK_SIZE / 2f
        }
        private val LOCK_BG_LOCKED = GradientDrawable().apply {
            setColor(0xCC1DB954.toInt())
            cornerRadius = LOCK_SIZE / 2f
        }

        /** 创建并添加到窗口（须已持有悬浮窗权限） */
        fun create(context: Context): FloatingLyricsView? = try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = FloatingLyricsView(context, wm)
            val savedY = AppConfig.floatingY
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                if (savedY >= 0) {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = savedY
                } else {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = 200
                }
            }
            wm.addView(view, params)
            view.setupLockButton()
            view.applyLockState()
            view
        } catch (e: Exception) {
            null
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(40, 28, 40, 28)
        background = BG_DRAWABLE

        // 预建 5 行 TextView
        for (i in 0 until MAX_LINES) {
            val tv = TextView(context).apply {
                textSize = AppConfig.fontSize
                setLineSpacing(8f, 1f)
                gravity = Gravity.CENTER
                setPadding(0, 6, 0, 6)
            }
            lyricLines.add(tv)
            addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        setupTouch()
    }

    // ── 锁按钮（独立小窗口，始终可点击） ──

    private fun setupLockButton() {
        try {
            lockButton = TextView(context).apply {
                text = if (locked) "🔒" else "🔓"
                textSize = 16f
                gravity = Gravity.CENTER
                background = if (locked) LOCK_BG_LOCKED else LOCK_BG_UNLOCKED
                setOnClickListener { toggleLock() }
            }
            lockParams = WindowManager.LayoutParams(
                LOCK_SIZE,
                LOCK_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            lockParams?.let { windowManager.addView(lockButton, it) }
            positionLockButton()
        } catch (_: Exception) { /* 锁按钮创建失败不影响歌词 */ }
    }

    /** 锁按钮跟随歌词窗口位置（右上角外挂） */
    private fun positionLockButton() {
        val params = lockParams ?: return
        val lp = layoutParams as? WindowManager.LayoutParams
        if (lp != null && lp.gravity and Gravity.TOP != 0) {
            // 歌词在任意自定义位置：锁钮在歌词上方右侧
            params.gravity = Gravity.TOP or Gravity.END
            params.y = (lp.y - LOCK_SIZE - LOCK_MARGIN).coerceAtLeast(0)
            params.x = LOCK_MARGIN
        } else {
            // 歌词在底部：锁钮在歌词正上方右侧
            val screenH = context.resources.displayMetrics.heightPixels
            params.gravity = Gravity.TOP or Gravity.END
            params.y = (screenH - 400).coerceAtLeast(0)
            params.x = LOCK_MARGIN
        }
        try {
            windowManager.updateViewLayout(lockButton, params)
        } catch (_: Exception) { /* 已移除 */ }
    }

    /** 锁定/解锁切换（锁按钮点击入口） */
    fun toggleLock() {
        locked = !locked
        AppConfig.floatingLocked = locked
        applyLockState()
    }

    /** 应用锁定状态到歌词窗口与锁按钮（主界面开关也走这里） */
    fun setLocked(lock: Boolean) {
        if (locked == lock) return
        locked = lock
        applyLockState()
    }

    private fun applyLockState() {
        post {
            val params = layoutParams as? WindowManager.LayoutParams ?: return@post

            // 1. 歌词窗口：锁定 = 触摸穿透 + 宽度自适应 + 无背景单行
            params.flags = if (locked) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            params.width = if (locked) {
                WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                WindowManager.LayoutParams.MATCH_PARENT
            }
            if (locked) {
                background = null
                setPadding(8, 4, 8, 4)
            } else {
                background = BG_DRAWABLE
                setPadding(40, 28, 40, 28)
            }

            // 2. 锁按钮外观：锁定 = 绿底 🔒；解锁 = 深灰底 🔓
            lockButton?.apply {
                text = if (locked) "🔒" else "🔓"
                background = if (locked) LOCK_BG_LOCKED else LOCK_BG_UNLOCKED
            }

            // 3. 重绘（锁定态自动切单行）
            if (lastIndex >= 0) renderInternal(lastLines, lastIndex)

            try {
                windowManager.updateViewLayout(this, params)
            } catch (_: Exception) { /* 窗口已移除 */ }
            positionLockButton()
        }
    }

    // ── 歌词渲染 ──

    /** 更新歌词显示：传入当前行下标与全部歌词行 */
    fun updateLyrics(lines: List<LyricRepository.LrcLine>, currentIndex: Int) {
        lastLines = lines
        lastIndex = currentIndex
        post { renderInternal(lines, currentIndex) }
    }

    /** 显示状态提示（加载中/无歌词/未播放） */
    fun showHint(hint: String) {
        post {
            for (i in 0 until MAX_LINES) {
                lyricLines[i].visibility = if (i == CONTEXT_LINES) VISIBLE else GONE
            }
            lyricLines[CONTEXT_LINES].apply {
                textSize = AppConfig.fontSize
                text = hint
                setTextColor(Color.WHITE)
                alpha = 0.6f
                paint.isFakeBoldText = false
                setShadowLayer(if (locked) 6f else 0f, 0f, 0f, if (locked) Color.BLACK else Color.TRANSPARENT)
            }
        }
    }

    /** 内部渲染（主线程）：当前行高亮光晕，上下文行半透明 */
    private fun renderInternal(lines: List<LyricRepository.LrcLine>, currentIndex: Int) {
        // 锁定态强制单行（只显示当前行）
        val showExpanded = expanded && !locked
        for (i in 0 until MAX_LINES) {
            val lineIdx = if (showExpanded) {
                currentIndex + (i - CONTEXT_LINES)
            } else {
                if (i == CONTEXT_LINES) currentIndex else -1
            }

            if (lineIdx < 0 || lineIdx >= lines.size) {
                lyricLines[i].visibility = if (showExpanded) INVISIBLE else GONE
                continue
            }
            lyricLines[i].visibility = VISIBLE
            lyricLines[i].textSize = AppConfig.fontSize  // 字号实时生效
            lyricLines[i].text = lines[lineIdx].text
            if (lineIdx == currentIndex) {
                lyricLines[i].paint.isFakeBoldText = true
                lyricLines[i].alpha = 1f
                if (locked) {
                    // 锁定态：绿字 + 黑描边（QQ音乐桌面歌词风格，任意背景可读）
                    lyricLines[i].setTextColor(0xFF1DB954.toInt())
                    lyricLines[i].setShadowLayer(6f, 0f, 0f, Color.BLACK)
                } else {
                    // 解锁态：白字 + 绿色光晕
                    lyricLines[i].setTextColor(Color.WHITE)
                    lyricLines[i].setShadowLayer(10f, 0f, 0f, GLOW_COLOR)
                }
            } else {
                // 上下文行：半透明无光晕
                lyricLines[i].setTextColor(Color.WHITE)
                lyricLines[i].paint.isFakeBoldText = false
                lyricLines[i].alpha = 0.45f
                lyricLines[i].setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
    }

    // ── 触摸交互（仅解锁态；锁定态触摸已穿透不会到这） ──

    /** 单指拖动（半透明+边界限制+位置保存）+ 双击切换展开模式 */
    private fun setupTouch() {
        var downX = 0f
        var downY = 0f
        var lastTapTime = 0L
        var moved = false

        setOnTouchListener { _, event ->
            val params = layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < TAP_INTERVAL) {
                        expanded = !expanded
                        lastTapTime = 0
                        if (lastIndex >= 0) renderInternal(lastLines, lastIndex)
                    } else {
                        lastTapTime = now
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > DRAG_SLOP || kotlin.math.abs(dy) > DRAG_SLOP) moved = true
                    if (moved) {
                        alpha = 0.6f
                        val maxTop = context.resources.displayMetrics.heightPixels - height
                        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        params.y = (event.rawY - downY).toInt().coerceIn(0, maxTop)
                        windowManager.updateViewLayout(this, params)
                        positionLockButton()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        animate().alpha(1f).setDuration(150).start()
                        AppConfig.floatingY = params.y  // 位置记忆
                        positionLockButton()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun destroy() {
        try {
            windowManager.removeView(this)
        } catch (_: Exception) { /* 已移除 */ }
        try {
            lockButton?.let { windowManager.removeView(it) }
        } catch (_: Exception) { /* 已移除 */ }
        lockButton = null
    }
}
