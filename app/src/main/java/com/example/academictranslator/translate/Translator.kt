package com.example.academictranslator.translate

/** 一次翻译的结果。[providerLabel] 用于在悬浮窗底部显示实际使用的引擎。 */
data class TranslationResult(
    val translatedText: String,
    val providerLabel: String
)

interface Translator {
    /** 英文 -> 简体中文。实现需自行处理网络/模型异常并抛出带可读信息的异常。 */
    suspend fun translateEnglishToChinese(source: String): TranslationResult

    /** 引擎是否已就绪（例如离线语言包是否已下载、Key 是否已填写）。 */
    suspend fun ensureReady(): Unit = Unit
}
