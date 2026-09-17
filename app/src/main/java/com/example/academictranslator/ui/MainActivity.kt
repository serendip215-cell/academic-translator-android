package com.example.academictranslator.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.content.ComponentName
import android.content.pm.PackageManager
import android.Manifest
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.academictranslator.R
import com.example.academictranslator.data.SettingsStore
import com.example.academictranslator.databinding.ActivityMainBinding
import com.example.academictranslator.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = SettingsStore(this)

        loadSettingsIntoUi()
        b.btnPermissionSetup.setOnClickListener { showPermissionGuide() }
        if (!hasRequiredPermissions()) {
            b.root.post { showPermissionGuide() }
        }

        b.rgTranslationMode.setOnCheckedChangeListener { _, checkedId ->
            val webMode = checkedId == R.id.rbWebMode
            settings.autoSelectionEnabled = webMode
            updateTranslationMode(webMode)
        }
        b.btnTranslationEngine.setOnClickListener { showEngineDialog() }
        b.btnGlossary.setOnClickListener { showGlossaryDialog() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        if (Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        Settings.canDrawOverlays(this) && isAccessibilityEnabled() &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, com.example.academictranslator.accessibility.TranslationAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun updatePermissionStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "✅ 悬浮窗" else "❌ 悬浮窗"
        val accessibility = if (isAccessibilityEnabled()) "✅ 无障碍" else "❌ 无障碍"
        val notification = if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "✅ 通知" else "❌ 通知"
        b.tvPermissionStatus.text = "$overlay　$accessibility　$notification"
        b.btnPermissionSetup.text = if (hasRequiredPermissions()) "权限已完成" else "检查并授权"
    }

    private fun showPermissionGuide() {
        val missing = mutableListOf<String>()
        if (!Settings.canDrawOverlays(this)) missing += "悬浮窗权限"
        if (!isAccessibilityEnabled()) missing += "无障碍服务（自动读取选中文字）"
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += "通知权限"
        if (missing.isEmpty()) {
            AlertDialog.Builder(this).setTitle("权限状态").setMessage("所需权限已经全部开启。").setPositiveButton("确定", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("完成首次授权")
            .setMessage("为了显示翻译悬浮窗和自动读取网页选区，请开启：\n\n${missing.joinToString("\n")}")
            .setPositiveButton("去授权") { _, _ -> openNextPermission(missing.first()) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openNextPermission(permission: String) {
        when {
            permission == "悬浮窗权限" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            permission == "无障碍服务（自动读取选中文字）" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Build.VERSION.SDK_INT >= 33 && permission == "通知权限" -> requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun loadSettingsIntoUi() {
        b.rgTranslationMode.check(if (settings.autoSelectionEnabled) R.id.rbWebMode else R.id.rbZoteroMode)
        updateTranslationMode(settings.autoSelectionEnabled)
        updateEngineButton()
    }

    private fun updateTranslationMode(webMode: Boolean) {
        b.tvTranslationModeStatus.text = if (webMode) {
            "网页自动翻译已启用"
        } else {
            "Zotero 分享翻译已启用"
        }
    }

    private fun updateEngineButton() {
        val provider = when (settings.provider) {
            SettingsStore.PROVIDER_LLM -> "大模型 API"
            SettingsStore.PROVIDER_MYMEMORY -> "MyMemory 免费在线翻译"
            else -> "ML Kit 离线翻译"
        }
        val style = if (settings.provider == SettingsStore.PROVIDER_LLM) {
            " · ${translationStyleLabel(settings.translationStyle)}"
        } else ""
        b.btnTranslationEngine.text = "翻译引擎  ·  $provider$style"
    }

    private fun showEngineDialog() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val group = android.widget.RadioGroup(this)
        val llm = RadioButton(this).apply { id = View.generateViewId(); text = "大模型 API（可配置风格）" }
        val mlKit = RadioButton(this).apply { id = View.generateViewId(); text = "ML Kit 离线翻译" }
        val myMemory = RadioButton(this).apply { id = View.generateViewId(); text = "MyMemory 免费在线翻译" }
        group.addView(llm)
        group.addView(mlKit)
        group.addView(myMemory)
        content.addView(group)

        val endpoint = EditText(this).apply {
            hint = "API Base URL（OpenAI 兼容）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(settings.endpoint)
        }
        val apiKey = EditText(this).apply {
            hint = "API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.apiKey)
        }
        val model = EditText(this).apply {
            hint = "模型名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(settings.model)
        }
        content.addView(endpoint)
        content.addView(apiKey)
        content.addView(model)

        val styleTitle = TextView(this).apply {
            text = "翻译风格"
            textSize = 16f
            setPadding(0, padding / 2, 0, 0)
        }
        val styleGroup = android.widget.RadioGroup(this)
        fun styleOption(text: String) = RadioButton(this).apply {
            id = View.generateViewId()
            this.text = text
        }
        val academic = styleOption("学术严谨（默认）")
        val simple = styleOption("简单易懂")
        val playful = styleOption("俏皮口语")
        val literal = styleOption("忠实直译")
        val brutallyHonest = styleOption("刁钻刻薄")
        val custom = styleOption("自定义")
        listOf(academic, simple, playful, literal, brutallyHonest, custom).forEach(styleGroup::addView)
        val presets = settings.customStylePresets
        val savedStylePicker = Spinner(this)
        val savedStyleNames = listOf("新建自定义风格") + presets.map { it.name }
        val styleAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, savedStyleNames
        )
        savedStylePicker.adapter = styleAdapter
        val customStyleHint = TextView(this).apply {
            text = "选择已保存风格后，在下方查看或编辑提示词"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
        }
        val customStyleName = EditText(this).apply {
            hint = "风格名称（例如：科普讲解）"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val customStyle = EditText(this).apply {
            hint = "输入自定义前置提示词，例如：用面向本科生的清晰中文翻译"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(settings.customTranslationStyle)
        }
        val deleteCustomStyle = Button(this).apply {
            text = "删除当前自定义风格"
            isAllCaps = false
        }
        content.addView(styleTitle)
        content.addView(styleGroup)
        content.addView(savedStylePicker)
        content.addView(customStyleHint)
        content.addView(customStyleName)
        content.addView(customStyle)
        content.addView(deleteCustomStyle)

        var customEditorRevealed = false

        fun showCustomPreset(position: Int) {
            val chosen = settings.customStylePresets.getOrNull(position - 1)
            customEditorRevealed = true
            if (chosen != null) {
                customStyleName.setText(chosen.name)
                customStyle.setText(chosen.prompt)
                deleteCustomStyle.visibility = View.VISIBLE
            } else {
                customStyleName.setText("")
                customStyle.setText("")
                deleteCustomStyle.visibility = View.GONE
            }
            customStyleName.visibility = View.VISIBLE
            customStyle.visibility = View.VISIBLE
        }

        savedStylePicker.setSelection(
            presets.indexOfFirst { it.name == settings.selectedCustomStyleName }
                .let { if (it >= 0) it + 1 else 0 }
        )
        savedStylePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showCustomPreset(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        group.check(
            when (settings.provider) {
                SettingsStore.PROVIDER_LLM -> llm.id
                SettingsStore.PROVIDER_MYMEMORY -> myMemory.id
                else -> mlKit.id
            }
        )
        styleGroup.check(
            when (settings.translationStyle) {
                SettingsStore.STYLE_SIMPLE -> simple.id
                SettingsStore.STYLE_PLAYFUL -> playful.id
                SettingsStore.STYLE_LITERAL -> literal.id
                SettingsStore.STYLE_BRUTALLY_HONEST -> brutallyHonest.id
                SettingsStore.STYLE_CUSTOM -> custom.id
                else -> academic.id
            }
        )
        fun updateApiFields() {
            val visible = if (group.checkedRadioButtonId == llm.id) View.VISIBLE else View.GONE
            endpoint.visibility = visible
            apiKey.visibility = visible
            model.visibility = visible
            styleTitle.visibility = visible
            styleGroup.visibility = visible
            val customVisible = if (visible == View.VISIBLE && styleGroup.checkedRadioButtonId == custom.id) {
                View.VISIBLE
            } else View.GONE
            savedStylePicker.visibility = customVisible
            customStyleHint.visibility = customVisible
            customStyleName.visibility = if (customVisible == View.VISIBLE && customEditorRevealed) View.VISIBLE else View.GONE
            customStyle.visibility = if (customVisible == View.VISIBLE && customEditorRevealed) View.VISIBLE else View.GONE
            deleteCustomStyle.visibility = if (customVisible == View.VISIBLE && customEditorRevealed && savedStylePicker.selectedItemPosition > 0) {
                View.VISIBLE
            } else View.GONE
        }
        updateApiFields()
        group.setOnCheckedChangeListener { _, _ -> updateApiFields() }
        styleGroup.setOnCheckedChangeListener { _, _ -> updateApiFields() }
        deleteCustomStyle.setOnClickListener {
            val position = savedStylePicker.selectedItemPosition
            val name = settings.customStylePresets.getOrNull(position - 1)?.name ?: return@setOnClickListener
            if (settings.deleteCustomStylePreset(name)) {
                styleAdapter.clear()
                styleAdapter.add("新建自定义风格")
                styleAdapter.addAll(settings.customStylePresets.map { it.name })
                styleAdapter.notifyDataSetChanged()
                savedStylePicker.setSelection(0)
                if (settings.translationStyle == SettingsStore.STYLE_CUSTOM) {
                    settings.translationStyle = SettingsStore.STYLE_ACADEMIC
                }
                updateApiFields()
            }
        }

        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("翻译引擎")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (group.checkedRadioButtonId == llm.id && styleGroup.checkedRadioButtonId == custom.id) {
                    val name = customStyleName.text?.toString()?.trim().orEmpty()
                    val prompt = customStyle.text?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) {
                        customStyleName.error = "请输入风格名称"
                        return@setOnClickListener
                    }
                    if (prompt.isEmpty()) {
                        customStyle.error = "请输入风格提示词"
                        return@setOnClickListener
                    }
                    val previousName = presets.getOrNull(savedStylePicker.selectedItemPosition - 1)?.name.orEmpty()
                    if (!settings.saveCustomStylePreset(name, prompt, previousName)) {
                        customStyleName.error = "已有同名风格，请换个名称"
                        return@setOnClickListener
                    }
                }
                settings.provider = when (group.checkedRadioButtonId) {
                    llm.id -> SettingsStore.PROVIDER_LLM
                    myMemory.id -> SettingsStore.PROVIDER_MYMEMORY
                    else -> SettingsStore.PROVIDER_MLKIT
                }
                settings.endpoint = endpoint.text?.toString().orEmpty()
                settings.apiKey = apiKey.text?.toString().orEmpty()
                settings.model = model.text?.toString().orEmpty()
                settings.translationStyle = when (styleGroup.checkedRadioButtonId) {
                    simple.id -> SettingsStore.STYLE_SIMPLE
                    playful.id -> SettingsStore.STYLE_PLAYFUL
                    literal.id -> SettingsStore.STYLE_LITERAL
                    brutallyHonest.id -> SettingsStore.STYLE_BRUTALLY_HONEST
                    custom.id -> SettingsStore.STYLE_CUSTOM
                    else -> SettingsStore.STYLE_ACADEMIC
                }
                updateEngineButton()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showGlossaryDialog() {
        val input = EditText(this).apply {
            hint = "每行一条，例如：\nmixture of experts (MoE)=混合专家模型"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(settings.extraGlossary)
            setPadding(40, 10, 40, 10)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义术语")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                settings.extraGlossary = input.text?.toString().orEmpty()
            }
            .show()
    }

    private fun translationStyleLabel(style: String): String = when (style) {
        SettingsStore.STYLE_SIMPLE -> "简单易懂"
        SettingsStore.STYLE_PLAYFUL -> "俏皮口语"
        SettingsStore.STYLE_LITERAL -> "忠实直译"
        SettingsStore.STYLE_BRUTALLY_HONEST -> "刁钻刻薄"
        SettingsStore.STYLE_CUSTOM -> settings.selectedCustomStyleName.ifBlank { "自定义" }
        else -> "学术严谨"
    }
}
