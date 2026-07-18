package io.legado.app.help.ai.tool

/**
 * AI 一次 tool_call 调用。从模型返回的 JSON 中解析。
 */
data class AiToolCall(
    val id: String,
    val name: String,
    /** 解析后的参数。模型返回的是 JSON 字符串。 */
    val arguments: Map<String, Any?>,
) {
    companion object {
        /** 把 JSON 字符串解析成 Map<String, Any?>。失败返回空 map 并标 raw 文本。 */
        fun fromJson(raw: String): Map<String, Any?> {
            return runCatching {
                val obj = org.json.JSONObject(raw)
                val out = mutableMapOf<String, Any?>()
                obj.keys().forEach { k -> out[k] = obj.get(k) }
                out
            }.getOrElse { emptyMap() }
        }
    }
}
