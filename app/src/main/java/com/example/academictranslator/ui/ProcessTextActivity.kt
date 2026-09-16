package com.example.academictranslator.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.example.academictranslator.overlay.OverlayService

/**
 * 系统文本选择菜单（长按选中后弹出的“复制/全选/…”工具栏）中的自定义入口。
 * 当某些 App 不向无障碍服务上报选区事件时，用户可手动点“翻译（文献助手）”。
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)?.toString()
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        if (text.isNullOrBlank()) {
            finish()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先在应用中授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        // Zotero 等阅读器可通过“分享”交出选中文本；直接显示现有翻译悬浮面板。
        OverlayService.start(applicationContext, text.trim(), null)
        finish()
    }
}
