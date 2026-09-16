package com.example.academictranslator.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MyMemory 免费翻译接口（无需 API Key），作为无 Key / 无 Google 服务时的兜底。
 * 注意：免费额度有限（匿名约每天 5000 词），学术长句质量一般，仅建议临时测试。
 * 单次请求长度限制约 500 字节，超长自动按句切分后拼接。
 */
class MyMemoryTranslator : Translator {

    override suspend fun translateEnglishToChinese(source: String): TranslationResult =
        withContext(Dispatchers.IO) {
            val chunks = splitBySentence(source, 450)
            val sb = StringBuilder()
            for (chunk in chunks) {
                val q = URLEncoder.encode(chunk, "UTF-8")
                val url = "https://api.mymemory.translated.net/get?q=$q&langpair=en%7Czh-CN"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "AcademicTranslator/1.0 (Android)")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IllegalStateException("MyMemory 返回 HTTP $code")
                    val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                        .use { it.readText() }
                    sb.append(JSONObject(text).getJSONObject("responseData").getString("translatedText"))
                } finally {
                    conn.disconnect()
                }
            }
            TranslationResult(sb.toString().trim(), "MyMemory 免费")
        }

    private fun splitBySentence(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        text.split(Regex("(?<=[.!?])\\s+")).forEach { sentence ->
            if (cur.length + sentence.length + 1 > maxLen) {
                if (cur.isNotEmpty()) parts.add(cur.toString())
                if (sentence.length > maxLen) {
                    sentence.chunked(maxLen).forEach { parts.add(it) }
                    cur.clear()
                } else {
                    cur.clear().append(sentence)
                }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(sentence)
            }
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        return parts
    }
}
