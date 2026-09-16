package com.example.academictranslator.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全局设置，使用 SharedPreferences 保存在本机。
 * API Key 等敏感信息默认不随备份上传（AndroidManifest 中 allowBackup=true，
 * 如需更严格可改为 EncryptedSharedPreferences）。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("academic_translator", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString(KEY_PROVIDER, PROVIDER_MLKIT) ?: PROVIDER_MLKIT
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** 用户自定义术语，每行 "english=中文" */
    var extraGlossary: String
        get() = prefs.getString(KEY_EXTRA_GLOSSARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTRA_GLOSSARY, value).apply()

    /** 控制无障碍服务是否自动提交选中的文本；不影响手动分享翻译。 */
    var autoSelectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SELECTION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SELECTION_ENABLED, value).apply()

    /** 悬浮译文面板按屏幕比例保存，以便在不同尺寸设备上恢复合适的大小。 */
    var overlayWidthFraction: Float
        get() = prefs.getFloat(KEY_OVERLAY_WIDTH_FRACTION, 0.92f)
        set(value) = prefs.edit().putFloat(KEY_OVERLAY_WIDTH_FRACTION, value).apply()

    var overlayHeightFraction: Float
        get() = prefs.getFloat(KEY_OVERLAY_HEIGHT_FRACTION, 0.32f)
        set(value) = prefs.edit().putFloat(KEY_OVERLAY_HEIGHT_FRACTION, value).apply()

    /** 仅供大模型 API 使用的译文表达风格。 */
    var translationStyle: String
        get() = prefs.getString(KEY_TRANSLATION_STYLE, STYLE_ACADEMIC) ?: STYLE_ACADEMIC
        set(value) = prefs.edit().putString(KEY_TRANSLATION_STYLE, value).apply()

    var customTranslationStyle: String
        get() = prefs.getString(KEY_CUSTOM_TRANSLATION_STYLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_TRANSLATION_STYLE, value).apply()

    data class CustomStylePreset(val name: String, val prompt: String)

    var selectedCustomStyleName: String
        get() = prefs.getString(KEY_SELECTED_CUSTOM_STYLE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_CUSTOM_STYLE_NAME, value).apply()

    val customStylePresets: List<CustomStylePreset>
        get() = runCatching {
            val array = JSONArray(prefs.getString(KEY_CUSTOM_STYLE_PRESETS, "[]"))
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                CustomStylePreset(item.getString("name"), item.getString("prompt"))
            }
        }.getOrDefault(emptyList())

    /** 返回 false 表示目标名称属于另一条已有风格，避免静默覆盖。 */
    fun saveCustomStylePreset(name: String, prompt: String, previousName: String = ""): Boolean {
        val title = name.trim()
        val body = prompt.trim()
        require(title.isNotEmpty() && body.isNotEmpty())
        val presets = customStylePresets.toMutableList()
        if (presets.any { it.name == title && it.name != previousName }) return false
        val index = presets.indexOfFirst { it.name == previousName }
        if (index >= 0) presets[index] = CustomStylePreset(title, body)
        else presets.add(CustomStylePreset(title, body))
        val array = JSONArray()
        presets.forEach { array.put(JSONObject().put("name", it.name).put("prompt", it.prompt)) }
        prefs.edit()
            .putString(KEY_CUSTOM_STYLE_PRESETS, array.toString())
            .putString(KEY_SELECTED_CUSTOM_STYLE_NAME, title)
            .putString(KEY_CUSTOM_TRANSLATION_STYLE, body)
            .apply()
        return true
    }

    /** 删除用户保存的自定义风格；内置风格不在此列表中。 */
    fun deleteCustomStylePreset(name: String): Boolean {
        val target = name.trim()
        if (target.isEmpty()) return false
        val presets = customStylePresets
        if (presets.none { it.name == target }) return false
        val array = JSONArray()
        presets.filterNot { it.name == target }
            .forEach { array.put(JSONObject().put("name", it.name).put("prompt", it.prompt)) }
        val edit = prefs.edit().putString(KEY_CUSTOM_STYLE_PRESETS, array.toString())
        if (selectedCustomStyleName == target) {
            edit.remove(KEY_SELECTED_CUSTOM_STYLE_NAME).remove(KEY_CUSTOM_TRANSLATION_STYLE)
        }
        edit.apply()
        return true
    }

    companion object {
        const val PROVIDER_LLM = "llm"
        const val PROVIDER_MLKIT = "mlkit"
        const val PROVIDER_MYMEMORY = "mymemory"

        const val STYLE_ACADEMIC = "academic"
        const val STYLE_SIMPLE = "simple"
        const val STYLE_PLAYFUL = "playful"
        const val STYLE_LITERAL = "literal"
        const val STYLE_BRUTALLY_HONEST = "brutally_honest"
        const val STYLE_CUSTOM = "custom"

        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o-mini"

        private const val KEY_PROVIDER = "provider"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_EXTRA_GLOSSARY = "extra_glossary"
        private const val KEY_AUTO_SELECTION_ENABLED = "auto_selection_enabled"
        private const val KEY_OVERLAY_WIDTH_FRACTION = "overlay_width_fraction"
        private const val KEY_OVERLAY_HEIGHT_FRACTION = "overlay_height_fraction"
        private const val KEY_TRANSLATION_STYLE = "translation_style"
        private const val KEY_CUSTOM_TRANSLATION_STYLE = "custom_translation_style"
        private const val KEY_SELECTED_CUSTOM_STYLE_NAME = "selected_custom_style_name"
        private const val KEY_CUSTOM_STYLE_PRESETS = "custom_style_presets"
    }
}
