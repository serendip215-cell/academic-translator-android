package com.example.academictranslator.translate

import android.content.Context
import com.example.academictranslator.data.GlossaryStore
import com.example.academictranslator.data.SettingsStore

/**
 * 根据设置选择翻译引擎。大模型 API 的配置错误必须直接提示，避免用户误以为
 * 请求成功却实际回退到了离线翻译；离线与免费引擎仍保留彼此的回退能力。
 */
class TranslationManager(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val glossaryStore = GlossaryStore(appContext)

    private val mlkit: MlKitTranslator by lazy { MlKitTranslator() }
    private val mymemory: MyMemoryTranslator by lazy { MyMemoryTranslator() }

    fun currentSettings(): SettingsStore = settings

    suspend fun translate(source: String): TranslationResult {
        val cleaned = source.trim().replace(Regex("[\\u00A0\\u200B]"), " ")
        require(cleaned.length >= 2) { "选中文本过短" }
        require(cleaned.length <= MAX_CHARS) { "选中文本过长（超过 $MAX_CHARS 字符），请缩小选区" }

        val preferred = when (settings.provider) {
            SettingsStore.PROVIDER_LLM -> LlmTranslator(settings, glossaryStore)
            SettingsStore.PROVIDER_MLKIT -> mlkit
            SettingsStore.PROVIDER_MYMEMORY -> mymemory
            else -> mlkit
        }

        val errors = mutableListOf<String>()
        val chain = when (settings.provider) {
            SettingsStore.PROVIDER_LLM -> listOf(preferred)
            SettingsStore.PROVIDER_MLKIT -> listOf(preferred, mymemory)
            else -> listOf(preferred, mlkit)
        }

        var lastError: Exception? = null
        for (engine in chain) {
            try {
                return engine.translateEnglishToChinese(cleaned)
            } catch (e: Exception) {
                lastError = e
                errors.add("${engine.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException(
            "所有翻译引擎均不可用：${errors.joinToString(" | ")}", lastError
        )
    }

    companion object {
        const val MAX_CHARS = 6000
    }
}
