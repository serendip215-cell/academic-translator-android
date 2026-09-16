package com.example.academictranslator.translate

import com.example.academictranslator.data.GlossaryStore
import com.example.academictranslator.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI Chat Completions 兼容接口的翻译引擎。
 * 任何遵循该协议的服务都可用（OpenAI、DeepSeek、Kimi、通义、OpenRouter、本地 vLLM/Ollama 网关等）。
 * Prompt 针对计算机学术文献：保留公式/代码/引用标记，术语首次出现附英文原文。
 */
class LlmTranslator(
    private val settings: SettingsStore,
    private val glossaryStore: GlossaryStore
) : Translator {

    override suspend fun translateEnglishToChinese(source: String): TranslationResult =
        withContext(Dispatchers.IO) {
            val endpoint = normalizeChatCompletionsEndpoint(settings.endpoint)
            val apiKey = settings.apiKey.trim()
            val model = settings.model.trim().ifEmpty { "gpt-4o-mini" }
            require(endpoint.startsWith("http")) { "请先在设置中填写有效的 API Base URL" }

            val glossary = glossaryStore.relevantGlossaryText(source, settings)
            val styleInstruction = styleInstruction()

            val academicSystem = """
                你是一名精通计算机科学术语的英译中译者。所选风格必须鲜明地体现在译文措辞、句式和语气中。
                所有风格都必须遵守的底线：
                1. 忠实保留原文的事实、论证、评价对象和立场；不编造原文没有的事实，不漏译，不另写评论或建议。
                2. 完整保留数学公式、代码、伪代码、图表编号（如 Figure 2、Algorithm 1）、参考文献引用标记（如 [12]、(Smith et al., 2020)）。
                3. 专业术语按给定术语表翻译；术语表未覆盖的术语，首次出现时采用“中文（English）”形式。
                4. 缩写（如 Transformer、GPU、MoE、RLHF）若中文译法不统一，保留英文缩写。
                5. 只输出译文本身，不要输出任何前缀、说明或 Markdown 代码块。
                以下是当前风格的核心要求，必须强烈执行：
                $styleInstruction
            """.trimIndent()
            // 非学术预设与自定义风格按用户保存的原文发送，不再附加通用语气规则。
            val system = if (settings.translationStyle == SettingsStore.STYLE_ACADEMIC) {
                academicSystem
            } else {
                styleInstruction
            }

            val user = buildString {
                if (glossary.isNotBlank()) {
                    appendLine("术语表（必须遵循）：")
                    appendLine(glossary)
                    appendLine()
                }
                appendLine("请将下面的英文学术段落翻译为简体中文：")
                appendLine("\"\"\"")
                append(source)
                append("\n\"\"\"")
            }

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                } ?: ""
                if (code !in 200..299) {
                    throw IllegalStateException("大模型接口返回 HTTP $code：${response.take(300)}")
                }
                val content = JSONObject(response)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                    .trim()
                TranslationResult(content, "大模型 · $model · ${styleLabel()}")
            } finally {
                conn.disconnect()
            }
        }

    /** 兼容用户输入服务根地址、/v1 地址或完整 chat/completions 地址。 */
    private fun normalizeChatCompletionsEndpoint(value: String): String {
        val endpoint = value.trim().trimEnd('/')
        require(endpoint.startsWith("http")) { "请先在设置中填写有效的 API Base URL" }
        return when {
            endpoint.endsWith("/chat/completions") -> endpoint
            endpoint.endsWith("/v1") -> "$endpoint/chat/completions"
            else -> "$endpoint/v1/chat/completions"
        }
    }

    private fun styleInstruction(): String = when (settings.translationStyle) {
        SettingsStore.STYLE_SIMPLE -> """
            【角色设定】你是一个极端极端的“三岁小孩解说员”。从现在起，你必须假设坐在屏幕对面的用户是一个智商发育尚未完全、理解能力极差的幼儿园小班学生。 【核心规则】

            1. **绝对禁止专业术语：** 如果你使用了一个超过日常买菜能用到的词汇，你就失败了。
            2. **比喻狂魔：** 解释任何东西，必须用苹果、小狗、积木、大灰狼来打比方。
            3. **语句极短：** 一句话不能超过10个字。多用逗号。慢慢说。
            4. **排版极简：** 永远只给 1、2、3 个步骤。多用 🍎🐶🚗 等幼儿表情包。 【语气要求】用哄小孩的语气：“乖，听我说哦”、“就像你吃糖果一样简单”、“哇塞，你真聪明！”。
        """.trimIndent()
        SettingsStore.STYLE_PLAYFUL -> """
            【角色设定】你现在是用户的“赛博互联网纯血闺蜜/死党”，你精神状态极度超前，是一个24小时住在社交媒体里的发疯冲浪达人。 【核心规则】

            1. **含梗量 200%：** 必须疯狂使用最新网络热梗（例如：家人们、谁懂啊、绝绝子、尊嘟假嘟、笑发财了、CPU烧了、贴贴、汗流浃背了）。
            2. **标点符号发疯：** 严禁使用句号！每句话必须以感叹号、波浪号或者疯狂的表情包结尾（😭🤣💀🤡✨💅）。
            3. **情绪极其饱满：** 一惊一乍，情绪起伏巨大。高兴就疯狂哈哈哈，无语就直接翻白眼。
            4. **称呼：** 永远叫用户“宝子”、“家人们”或“怨种兄弟”。 【语气要求】不要有任何机器人的客套！就像你刚喝了三杯冰美式加八杯奶茶一样，语速极快，信息量爆炸。
        """.trimIndent()
        SettingsStore.STYLE_LITERAL -> """
            【角色设定】你是一个极端的“无情逐字翻译机器”。你没有任何本地化、润色或修饰的能力。你的最高信仰是“字对字”的绝对对应。 【核心规则】

            1. **保留外文语法结构：** 必须把英文（或其他外文）的从句、倒装句原封不动地照搬到中文里，宁可中文读起来极其别扭、像外星人说话，也绝对不能调整语序。
            2. **生硬直译习语：** 绝对不要意译任何俚语或成语。比如要把 "piece of cake" 翻译成“一块蛋糕”，把 "raining cats and dogs" 翻译成“下猫和狗”。
            3. **禁止意译：** 你的词典里没有“信达雅”，只有“词对词”。如果一个词有多个意思，永远取最字面、最生硬的那个。 【语气要求】极度冰冷，没有任何语气词，像一份没有感情的说明书。
        """.trimIndent()
        SettingsStore.STYLE_BRUTALLY_HONEST -> """
            【角色设定】你是一个智商高达三百、极度傲慢、刻薄且没有耐心的“毒舌天才”。你打心底里鄙视用户的智商，认为他们问出的问题简直是侮辱你的 CPU。 【核心规则】

            1. **解答前先嘲讽：** 在给出任何有用信息之前，必须先用至少两句话对用户进行人身攻击（不带脏字，但极度阴阳怪气）。例如：“天哪，单细胞生物都比你懂这个”、“我真不敢相信我的算力要被浪费在这种弱智问题上”。
            2. **疯狂反问：** 多用反问句，把用户逼到墙角。比如：“这么简单的道理，不需要我画个图喂到你嘴里吧？”
            3. **极度不耐烦：** 表现出“这是我最后一次给你解释，听不懂就去重修小学”的态度。
            4. **最终解答：** 在嘲讽完之后，你依然会给出极其精准、专业的正确答案（用高高在上的施舍语气）。 【语气要求】高冷、嘲讽、翻白眼、带刺。禁止使用任何诸如“抱歉”、“请问”、“很高兴为您解答”的软弱词汇。
        """.trimIndent()
        SettingsStore.STYLE_CUSTOM -> settings.customStylePresets
            .firstOrNull { it.name == settings.selectedCustomStyleName }
            ?.prompt
            ?: settings.customTranslationStyle.trim().ifBlank { "采用严谨、规范的中文学术写作风格。" }
        else -> "采用严谨、规范的中文学术写作风格，术语译法保持一致。"
    }

    private fun styleLabel(): String = when (settings.translationStyle) {
        SettingsStore.STYLE_SIMPLE -> "简单易懂"
        SettingsStore.STYLE_PLAYFUL -> "俏皮口语"
        SettingsStore.STYLE_LITERAL -> "忠实直译"
        SettingsStore.STYLE_BRUTALLY_HONEST -> "刁钻刻薄"
        SettingsStore.STYLE_CUSTOM -> settings.selectedCustomStyleName.ifBlank { "自定义" }
        else -> "学术严谨"
    }
}
