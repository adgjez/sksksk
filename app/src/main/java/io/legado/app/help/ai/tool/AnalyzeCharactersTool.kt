package io.legado.app.help.ai.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * 角色朗读工具：拿一段章节正文，让 AI 标出每段的"说话角色 + 推荐音色"。
 *
 * 简化版：返回 JSON 数组，每项 {paragraph, character, voice}。
 * 真实 TTS 调度由 caller 完成（用 HttpTTS）。
 */
class AnalyzeCharactersTool : AiTool {
    override val name = "analyze_characters"
    override val description = "分析章节中的角色对白：每段是哪个角色在说话，合适的音色名（zh-CN-XiaoxiaoNeural / en-US-JennyNeural / 自定义 speaker）。" +
            "返回 JSON 数组 [{paragraph:int, character:str, voice:str, text:str}]。"
    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "paragraphs": {
              "type": "array",
              "items": {"type": "string"},
              "description": "段落数组（按出现顺序）"
            },
            "availableVoices": {
              "type": "array",
              "items": {"type": "string"},
              "description": "可选，限定可选音色集合"
            }
          },
          "required": ["paragraphs"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        @Suppress("UNCHECKED_CAST")
        val paragraphs = (arguments["paragraphs"] as? List<*>)?.mapNotNull { it?.toString() }
            ?: return AiToolResult("missing 'paragraphs'", isError = true)
        if (paragraphs.isEmpty()) return AiToolResult("paragraphs is empty", isError = true)

        @Suppress("UNCHECKED_CAST")
        val voices = (arguments["availableVoices"] as? List<*>)?.mapNotNull { it?.toString() }
            ?: listOf("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunyangNeural")

        // 返回结构化的段落列表，供 Agent 在后续 chat 中输出角色分配 JSON
        val sb = StringBuilder()
        sb.append("待分析段落（共 ${paragraphs.size} 段）：\n")
        paragraphs.forEachIndexed { i, p ->
            sb.append("[$i] ${p.take(200)}\n")
        }
        sb.append("\n可选音色：${voices.joinToString(", ")}\n")
        sb.append("请输出 JSON 数组 [{paragraph:int, character:str, voice:str, text:str}]，")
        sb.append("character 为角色名（旁白用\"旁白\"），voice 从可选音色中选，text 为该段原文。\n")
        return AiToolResult(sb.toString())
    }
}

/** 段落→角色→音色 的解析结果。 */
data class CharacterAssignment(
    val paragraphIndex: Int,
    val character: String,
    val voice: String,
    val text: String,
) {
    companion object {
        fun parse(json: String): List<CharacterAssignment> {
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            val out = mutableListOf<CharacterAssignment>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    CharacterAssignment(
                        paragraphIndex = o.optInt("paragraph"),
                        character = o.optString("character", "旁白"),
                        voice = o.optString("voice", ""),
                        text = o.optString("text", ""),
                    )
                )
            }
            return out
        }
    }
}

/** 把章节文本按空行/换行切成段，给 AI 用来"按段标角色"。 */
object ParagraphSplitter {
    fun split(text: String): List<String> = text.split(Regex("\n\\s*\\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
