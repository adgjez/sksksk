package io.legado.app.help.ai

import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider

data class ChatResult(
    val content: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

/** SSE 流式回调。 */
fun interface ChatStream {
    fun onDelta(delta: String, isFinal: Boolean)
    fun onError(t: Throwable) {}
    fun onComplete(result: ChatResult) {}
}

interface AiService {
    suspend fun testConnection(provider: AiProvider): Result<Unit>
    suspend fun chat(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
        temperature: Double = 0.7,
        maxTokens: Int = 2048
    ): Result<ChatResult>
    suspend fun chatStream(
        provider: AiProvider,
        systemPrompt: String,
        messages: List<AiMessage>,
        stream: ChatStream,
        temperature: Double = 0.7,
        maxTokens: Int = 2048
    )
    suspend fun generateImage(
        provider: AiProvider,
        prompt: String,
        size: String = "1024x1024",
        n: Int = 1
    ): Result<List<String>>
}
