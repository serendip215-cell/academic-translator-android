package com.example.academictranslator.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.academictranslator.data.SettingsStore
import com.example.academictranslator.overlay.OverlayService

/**
 * 监听系统级“文本选择变化”事件：
 * 用户在任意 App（PDF 阅读器、浏览器、文献 App）中选中英文文本后，
 * 防抖 ~450ms 取出选中文本与其屏幕坐标，启动悬浮窗在选区旁显示译文。
 *
 * 注意：
 * - 部分 App 的自绘文本（如少数 PDF 内核）不上报选区事件，此时可用系统选择菜单中的
 *   “翻译（文献助手）”兜底（见 ProcessTextActivity）。
 * - 密码框内容一律忽略。
 */
class TranslationAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val settings by lazy { SettingsStore(this) }
    private var lastSubmitted: String = ""

    private val triggerRunnable = Runnable {
        pendingSelection?.let { (text, rect) ->
            if (text != lastSubmitted) {
                lastSubmitted = text
                OverlayService.start(applicationContext, text, rect)
            }
        }
        pendingSelection = null
    }

    /** 等待提交的选区：文本 + 选区在屏幕中的位置 */
    private var pendingSelection: Pair<String, Rect>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        if (!settings.autoSelectionEnabled) return
        if (!canDrawOverlays()) {
            Log.w(TAG, "Ignoring selection because overlay permission is absent")
            return
        }

        val node = event.source
        try {
            if (node?.isPassword == true) return

            // Chrome/WebView 和部分 PDF 阅读器会将选区放在 event.text，
            // 或仅暴露给当前焦点节点；不能只依赖 event.source。
            val selected = extractSelectedText(event, node) ?: run {
                Log.d(TAG, "Selection event carried no readable text: pkg=${event.packageName}, class=${event.className}")
                return
            }
            if (selected.length < MIN_LEN || selected.length > MAX_LEN) return
            // 纯数字 / 纯符号 / 几乎不含拉丁字母的选区直接忽略
            if (!selected.any { it in 'A'..'Z' || it in 'a'..'z' }) return
            // 与当前面板内容相同则不重复触发
            if (selected == lastSubmitted) return

            val rect = Rect()
            node?.getBoundsInScreen(rect)
            // 某些控件只给出整个控件的 bounds；至少保证 rect 有效
            if (rect.width() <= 0 || rect.height() <= 0) rect.setEmpty()
            val anchor = rect.takeIf { !it.isEmpty }

            pendingSelection = selected to (anchor?.let(::Rect) ?: Rect())
            Log.i(TAG, "Selection captured: ${selected.take(80)}")
            handler.removeCallbacks(triggerRunnable)
            handler.postDelayed(triggerRunnable, DEBOUNCE_MS)
        } finally {
            // 事件源节点由系统管理，recycle 在 API 33+ 为 no-op，旧版本安全调用
            node?.let { runCatching { @Suppress("DEPRECATION") it.recycle() } }
        }
    }

    private fun extractSelectedText(
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo?
    ): String? {
        selectedRange(source?.text, source?.textSelectionStart ?: -1, source?.textSelectionEnd ?: -1)
            ?.let { return it }

        val eventText = event.text.joinToString(separator = "").trim()
        selectedRange(eventText, event.fromIndex, event.toIndex)?.let { return it }
        if (eventText.isNotBlank()) return eventText

        val focused = rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        try {
            return selectedRange(
                focused?.text,
                focused?.textSelectionStart ?: -1,
                focused?.textSelectionEnd ?: -1
            )
        } finally {
            focused?.let { runCatching { @Suppress("DEPRECATION") it.recycle() } }
        }
    }

    private fun selectedRange(text: CharSequence?, start: Int, end: Int): String? {
        if (text.isNullOrEmpty() || start < 0 || end < 0 || start == end) return null
        val first = minOf(start, end)
        val last = maxOf(start, end)
        if (first >= text.length || last > text.length) return null
        return text.subSequence(first, last).toString().trim().takeIf { it.isNotEmpty() }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(triggerRunnable)
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    companion object {
        private const val TAG = "AcademicTranslator"
        private const val DEBOUNCE_MS = 450L
        private const val MIN_LEN = 2
        private const val MAX_LEN = 6000
    }
}
