package io.legado.app.help.ai.agent

import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.AiService
import io.legado.app.help.ai.ChatResult
import io.legado.app.help.ai.OpenAiService
import io.legado.app.help.ai.memory.AiMemoryStore
import io.legado.app.help.ai.tool.AiTool
import io.legado.app.help.ai.tool.AiToolCall
import io.legado.app.help.ai.tool.ReadMemoryTool
import io.legado.app.help.ai.tool.WriteMemoryTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Agent：在多步循环里跑 chat + 工具调用 + 工具执行 + 再次 chat。
 *
 * OpenAI 协议约定：模型返回 tool_calls 时，要把结果作为 role=tool 的消息加回
 * messages，再发起下一轮 chat。直到模型返回纯文本为止。
 *
 * 上限 [maxSteps] 防止无限循环。
 */
class Agent(
    private val service: AiService = OpenAiService(),
    private val memory: AiMemoryStore = AiMemoryStore.instance,
    private val maxSteps: Int = 5,
) {

    /** 启动一个带工具的对话。返回最终 Assistant 文本。 */
    suspend fun run(
        provider: AiProvider,
        systemPrompt: String,
        history: List<AiMessage>,
        extraTools: List<AiTool> = emptyList(),
    ): Result<AgentResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 默认带 memory 工具
            val tools = extraTools + listOf(
                ReadMemoryTool(memory),
                WriteMemoryTool(memory),
            )
            // 把 memory 内容注入 system prompt
            val bookKey = history.firstOrNull { it.content.contains("bookKey") }?.id ?: ""
            val memorySection = memory.forPrompt(bookKey = "")
            val fullSystem = buildString {
                append(systemPrompt)
                if (memorySection.isNotBlank()) {
                    append("\n\n")
                    append(memorySection)
                }
            }

            val working = history.toMutableList()
            val toolLog = mutableListOf<ToolCallLog>()
            var lastUsage = ChatResult("", 0, 0)

            for (step in 1..maxSteps) {
                val result = service.chat(provider, fullSystem, working, tools).getOrThrow()
                lastUsage = result
                if (result.toolCalls.isEmpty()) {
                    return@runCatching AgentResult(
                        finalText = result.content,
                        steps = step,
                        toolLog = toolLog,
                        totalPromptTokens = lastUsage.promptTokens,
                        totalCompletionTokens = lastUsage.completionTokens,
                    )
                }
                // 把 assistant 的 tool_calls 消息加回
                val assistantMsg = AiMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = working.lastOrNull()?.conversationId.orEmpty(),
                    role = "assistant",
                    content = result.content,
                )
                working.add(assistantMsg)
                // 执行每个 tool_call
                for (call in result.toolCalls) {
                    val tool = tools.firstOrNull { it.name == call.name }
                    val toolResult = if (tool == null) {
                        AiToolCallResult(name = call.name, content = "unknown tool: ${call.name}", isError = true)
                    } else {
                        try {
                            val r = tool.execute(call.arguments)
                            AiToolCallResult(name = call.name, content = r.content, isError = r.isError)
                        } catch (t: Throwable) {
                            AiToolCallResult(name = call.name, content = "error: ${t.message}", isError = true)
                        }
                    }
                    toolLog.add(ToolCallLog(call = call, result = toolResult))
                    // OpenAI 协议：把 tool 结果作为 role=tool 的消息
                    working.add(toolResult.toMessage(working.last().conversationId))
                }
            }
            // 超过步数：把最后一轮 content 返回
            AgentResult(
                finalText = lastUsage.content.ifBlank { "[agent stopped at maxSteps=$maxSteps]" },
                steps = maxSteps,
                toolLog = toolLog,
                totalPromptTokens = lastUsage.promptTokens,
                totalCompletionTokens = lastUsage.completionTokens,
            )
        }
    }
}

data class AgentResult(
    val finalText: String,
    val steps: Int,
    val toolLog: List<ToolCallLog>,
    val totalPromptTokens: Int,
    val totalCompletionTokens: Int,
)

data class ToolCallLog(
    val call: AiToolCall,
    val result: AiToolCallResult,
)

data class AiToolCallResult(
    val name: String,
    val content: String,
    val isError: Boolean,
) {
    fun toMessage(conversationId: String): AiMessage {
        val argsJson = JSONObject(callArguments).toString()
        return AiMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "tool",
            content = if (isError) "[error] $content" else content,
        )
    }
    // AiToolCall 没有暴露 arguments，这里通过 reflection 拿（仅 build message 用）
    private val callArguments: Map<String, Any?> get() = runCatching {
        val f = AiToolCall::class.java.getDeclaredField("arguments")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        f.get(call) as Map<String, Any?>
    }.getOrElse { emptyMap() }
}
