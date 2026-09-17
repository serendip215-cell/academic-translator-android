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
import com.example.academictranslator.BuildConfig
import com.example.academictranslator.data.GithubRelease
import com.example.academictranslator.data.GithubUpdateChecker
import com.example.academictranslator.data.SettingsStore
import com.example.academictranslator.databinding.ActivityMainBinding
import com.example.academictranslator.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateDialogShown = false
    private var continueFirstRunAfterNotification = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = SettingsStore(this)

        loadSettingsIntoUi()
        b.btnPermissionSetup.setOnClickListener { openMissingPermissionSettings() }
        b.btnCheckUpdate.setOnClickListener { checkForUpdates(manual = true) }
        if (!settings.permissionOnboardingShown) {
            settings.permissionOnboardingShown = true
            b.root.post { requestNotificationThenOverlay() }
        } else if (hasRequiredPermissions()) {
            checkForUpdates(manual = false)
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

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateDialogShown && !manual) return
        if (manual) b.btnCheckUpdate.text = "正在检查更新…"
        activityScope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { GithubUpdateChecker.latestRelease() }.getOrNull()
            }
            if (manual) b.btnCheckUpdate.text = "检查更新"
            if (release == null) {
                if (manual) showUpdateMessage("暂时无法连接 GitHub，请检查网络后重试。")
                return@launch
            }
            if (GithubUpdateChecker.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                updateDialogShown = true
                showUpdateDialog(release)
            } else if (manual) {
                showUpdateMessage("当前已是最新版本（v${BuildConfig.VERSION_NAME}）。")
            }
        }
    }

    private fun showUpdateMessage(message: String) {
        AlertDialog.Builder(this).setTitle("检查更新").setMessage(message).setPositiveButton("确定", null).show()
    }

    private fun showUpdateDialog(release: GithubRelease) {
        val downloadUrl = release.apkUrl.ifBlank { release.releaseUrl }
        val notes = release.notes.ifBlank { "GitHub 发布了新版本。" }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${release.version}")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n\n$notes")
            .setNegativeButton("稍后", null)
            .setPositiveButton("立即更新") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
            .show()
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
        val enabled = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (Settings.canDrawOverlays(this)) enabled += "悬浮窗" else missing += "悬浮窗"
        if (isAccessibilityEnabled()) enabled += "无障碍" else missing += "无障碍（网页自动翻译）"
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) enabled += "通知" else missing += "通知"
        b.tvPermissionTitle.text = if (missing.isEmpty()) "已开启权限" else "权限状态"
        b.tvPermissionStatus.text = buildString {
            if (enabled.isNotEmpty()) append("已开启：${enabled.joinToString("、")}")
            if (missing.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("待开启：${missing.joinToString("、")}")
            }
        }
        b.btnPermissionSetup.text = if (missing.isEmpty()) "权限已完成" else "前往系统授权"
    }

    /** 不在应用内重复说明，直接跳转 Android 系统对应的授权界面。 */
    private fun openMissingPermissionSettings() {
        when {
            Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                requestNotificationThenOverlay()
            }
            !Settings.canDrawOverlays(this) -> openOverlayPermission()
            !isAccessibilityEnabled() -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestNotificationThenOverlay() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            continueFirstRunAfterNotification = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            openOverlayPermission()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && continueFirstRunAfterNotification) {
            continueFirstRunAfterNotification = false
            openOverlayPermission()
        }
    }

    private fun openOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
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
