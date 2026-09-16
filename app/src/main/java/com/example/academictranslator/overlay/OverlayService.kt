package com.example.academictranslator.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.academictranslator.R
import com.example.academictranslator.data.SettingsStore
import com.example.academictranslator.databinding.OverlayPanelBinding
import com.example.academictranslator.translate.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮窗服务：在选中文本旁边显示“原文 | 译文”对照面板（平板横屏左右并排）。
 * 可通过标题栏拖动；每次新的选区事件复用同一个面板。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var binding: OverlayPanelBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val manager by lazy { TranslationManager(this) }
    private val settings by lazy { SettingsStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var translateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(EXTRA_SOURCE)
        val anchor: Rect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_ANCHOR, Rect::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_ANCHOR)
        }
        if (!source.isNullOrBlank()) showTranslation(source, anchor)
        return START_STICKY
    }

    private fun ensurePanel() {
        if (binding != null) return
        val b = OverlayPanelBinding.inflate(LayoutInflater.from(this), null, false)
        binding = b

        val metrics = screenMetrics()
        val panelWidth = savedPanelWidth(metrics)
        val panelHeight = savedPanelHeight(metrics)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            type,
            // 不拦截面板外的触摸；面板内可选择文本
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        layoutParams = params

        b.btnClosePanel.setOnClickListener { hidePanel() }
        b.btnCopyTranslation.setOnClickListener {
            val text = b.tvTranslation.text?.toString().orEmpty()
            if (text.isNotBlank() && text != getString(R.string.translating_placeholder)) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("translation", text))
                b.tvPanelStatus.text = "译文已复制到剪贴板"
            }
        }
        enableDrag(b, params)
        enableResize(b, params)
        wm.addView(b.root, params)
    }

    /** 标题栏拖动悬浮窗。 */
    private fun enableDrag(b: OverlayPanelBinding, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        val listener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(b.root, params)
                    true
                }
                else -> false
            }
        }
        b.tvPanelTitle.setOnTouchListener(listener)
    }

    /** 右下角手柄用于缩放，尺寸按屏幕比例保存，换设备后仍会自适应。 */
    private fun enableResize(b: OverlayPanelBinding, params: WindowManager.LayoutParams) {
        var startWidth = 0
        var startHeight = 0
        var startX = 0f
        var startY = 0f
        b.tvResizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = params.width
                    startHeight = params.height
                    startX = event.rawX
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = screenMetrics()
                    params.width = (startWidth + (event.rawX - startX).toInt())
                        .coerceIn(dp(300), metrics.widthPixels - WINDOW_MARGIN * 2)
                    params.height = (startHeight + (event.rawY - startY).toInt())
                        .coerceIn(dp(180), metrics.heightPixels - WINDOW_MARGIN * 2)
                    wm.updateViewLayout(b.root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePanelSize(params, screenMetrics())
                    true
                }
                else -> false
            }
        }
    }

    private fun showTranslation(source: String, anchor: Rect?) {
        ensurePanel()
        val b = binding ?: return
        b.tvOriginal.text = source
        b.tvTranslation.setText(R.string.translating_placeholder)
        b.tvPanelStatus.text = "翻译中…"
        positionPanel(anchor)

        translateJob?.cancel()
        translateJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { manager.translate(source) }
                b.tvTranslation.text = result.translatedText
                b.tvPanelStatus.text = "引擎：${result.providerLabel}　|　标题移动，右下角调整大小"
            } catch (e: Exception) {
                b.tvTranslation.text = "翻译失败：${e.message}"
                b.tvPanelStatus.text = "请检查网络 / API Key，或在主应用切换翻译引擎"
            }
        }
    }

    /** 把面板放到选区旁边：优先选区下方，空间不足则放到上方，并限制在屏幕内。 */
    private fun positionPanel(anchor: Rect?) {
        val params = layoutParams ?: return
        val b = binding ?: return
        val metrics = screenMetrics()
        val h = params.height
        val margin = WINDOW_MARGIN

        params.x = if (anchor != null) {
            // 让面板以选区中心水平对齐，避免只贴着选区左边缘而遮挡正文。
            (anchor.centerX() - params.width / 2).coerceIn(
                margin, max(margin, metrics.widthPixels - params.width - margin)
            )
        } else {
            (metrics.widthPixels - params.width) / 2
        }
        params.y = if (anchor != null) {
            // 优先放在选区上方，避免压住用户刚选中的内容；上方不足时再放到下方。
            val above = anchor.top - h - margin
            if (above >= margin) above
            else (anchor.bottom + margin).coerceAtMost(metrics.heightPixels - h - margin)
        } else margin * 2

        runCatching { wm.updateViewLayout(b.root, params) }
    }

    private fun hidePanel() {
        // 仅移除悬浮面板，服务保持常驻（通知栏低优先级提示），以便随时响应新的选区
        binding?.let { runCatching { wm.removeView(it.root) } }
        binding = null
    }

    private fun screenMetrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(it)
    }

    private fun savedPanelWidth(metrics: DisplayMetrics): Int =
        (metrics.widthPixels * settings.overlayWidthFraction).toInt()
            .coerceIn(dp(300), metrics.widthPixels - WINDOW_MARGIN * 2)

    private fun savedPanelHeight(metrics: DisplayMetrics): Int =
        (metrics.heightPixels * settings.overlayHeightFraction).toInt()
            .coerceIn(dp(180), metrics.heightPixels - WINDOW_MARGIN * 2)

    private fun savePanelSize(params: WindowManager.LayoutParams, metrics: DisplayMetrics) {
        settings.overlayWidthFraction = min(0.98f, params.width.toFloat() / metrics.widthPixels)
        settings.overlayHeightFraction = min(0.90f, params.height.toFloat() / metrics.heightPixels)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startForegroundCompat() {
        val channelId = "overlay_translation"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        binding?.let { runCatching { wm.removeView(it.root) } }
        binding = null
        super.onDestroy()
    }

    /** 兼容老版本 SDK 的 startForeground 包装。 */
    private object ServiceCompat {
        fun startForeground(
            service: Service, id: Int, notification: Notification, type: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(id, notification, type)
            } else {
                service.startForeground(id, notification)
            }
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val WINDOW_MARGIN = 16
        private const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_ANCHOR = "extra_anchor"

        fun start(context: Context, source: String, anchor: Rect? = null) {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_ANCHOR, anchor as? android.os.Parcelable)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
