package com.example.academictranslator.data

import android.content.Context
import org.json.JSONObject

/**
 * 术语表：内置 assets/cs_glossary.json（计算机领域常用术语），
 * 叠加用户在设置页填写的自定义术语。
 */
class GlossaryStore(private val context: Context) {

    @Volatile
    private var builtin: Map<String, String> = emptyMap()

    private fun ensureLoaded() {
        if (builtin.isNotEmpty()) return
        synchronized(this) {
            if (builtin.isNotEmpty()) return
            val json = context.assets.open("cs_glossary.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val terms = JSONObject(json).getJSONObject("terms")
            val map = LinkedHashMap<String, String>()
            terms.keys().forEach { en ->
                map[en.lowercase()] = terms.getString(en)
            }
            builtin = map
        }
    }

    /** 返回 英文 -> 中文 的完整术语映射（内置 + 自定义，自定义优先）。 */
    fun allTerms(settings: SettingsStore): Map<String, String> {
        ensureLoaded()
        val merged = LinkedHashMap(builtin)
        settings.extraGlossary.lineSequence()
            .map { it.trim() }
            .filter { it.contains("=") && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.indexOf('=')
                val en = line.substring(0, idx).trim().lowercase()
                val zh = line.substring(idx + 1).trim()
                if (en.isNotEmpty() && zh.isNotEmpty()) merged[en] = zh
            }
        return merged
    }

    /** 供大模型 Prompt 使用的术语表文本，只保留与原文相关的条目，避免 token 浪费。 */
    fun relevantGlossaryText(source: String, settings: SettingsStore, maxItems: Int = 40): String {
        val lower = source.lowercase()
        val matched = allTerms(settings).filterKeys { lower.contains(it) }
        return matched.entries
            .take(maxItems)
            .joinToString("\n") { "- ${it.key} = ${it.value}" }
    }
}
