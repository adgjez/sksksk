package io.legado.app.help.ai.tool

/**
 * AI 可以调用的工具。由 Agent 拼到 chat 请求的 `tools` 字段。
 *
 * OpenAI 兼容协议：每个 tool 有 name / description / JSON Schema 的 parameters。
 * AI 返回时给出 tool_calls，我们把参数（Map<String, Any?>）反序列化后调用 [execute]。
 */
interface AiTool {
    val name: String
    val description: String
    val parametersSchema: String
    /** 真正执行工具。结果是一段文本，会被回传 AI 作为下一轮上下文。 */
    suspend fun execute(arguments: Map<String, Any?>): AiToolResult
}

data class AiToolResult(
    val content: String,
    val isError: Boolean = false,
)

/** 当前可用的工具集合。Agent 用它拼 OpenAI 协议的 tools 字段。 */
data class AiToolRegistry(
    val tools: List<AiTool>,
) {
    fun byName(name: String): AiTool? = tools.firstOrNull { it.name == name }
}
