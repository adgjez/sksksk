package io.legado.app.help.ai

import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.tool.AiTool
import io.legado.app.help.ai.tool.AiToolCall
import io.legado.app.help.ai.tool.AiToolResult
import okhttp3.sse.EventSource

data class ChatResult(
    val content: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 模型决定要调的若干工具调用（一个 message 可能含多个）。 */
    val toolCalls: List<AiToolCall> = emptyList(),
)

/** SSE 流式回调。 */
fun interface ChatStream {
    fun onDelta(delta: String, isFinal: Boolean)
    fun onError(t: Throwable) {}
    fun onComplete(result: ChatResult) {}
}

interface AiService {
    suspend fun testConnection(provider: AiProvider): Result<Unit>

    /**
     * 单次 chat。
     * @param tools 模型可以调用的工具列表（可空）
     */
    suspend fun chat(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<AiTool> = emptyList(),
        temperature: Double = 0.7,
        maxTokens: Int = 2048
    ): Result<ChatResult>

    /**
     * 流式 chat。返回 EventSource 引用以便调用方可以 cancel。
     */
    suspend fun chatStream(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
        tools: List<AiTool> = emptyList(),
        stream: ChatStream,
        temperature: Double = 0.7,
        maxTokens: Int = 2048
    ): EventSource?

    suspend fun generateImage(
        provider: AiProvider,
        prompt: String,
        size: String = "1024x1024",
        n: Int = 1
    ): Result<List<String>>
}
