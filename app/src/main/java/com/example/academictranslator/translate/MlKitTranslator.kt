package com.example.academictranslator.translate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google ML Kit 设备端离线翻译（EN -> ZH）。
 * 首次使用会下载约 30MB 语言包；需要设备上有 Google Play 服务。
 * 无 GMS 的国产平板上初始化会失败，由 TranslationManager 回退到其他引擎。
 */
class MlKitTranslator : Translator {

    private val client by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
        Translation.getClient(options)
    }

    override suspend fun ensureReady() = withContext(Dispatchers.IO) {
        val conditions = DownloadConditions.Builder().build()
        Tasks.await(client.downloadModelIfNeeded(conditions))
        Unit
    }

    override suspend fun translateEnglishToChinese(source: String): TranslationResult =
        withContext(Dispatchers.IO) {
            Tasks.await(client.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            val out = Tasks.await(client.translate(source))
            TranslationResult(out, "ML Kit 离线")
        }
}
