package io.legado.app.help.ai.agent

import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.AiService
import io.legado.app.help.ai.ChatResult
import io.legado.app.help.ai.OpenAiService
import io.legado.app.help.ai.memory.AiMemoryStore
import io.legado.app.help.ai.skill.Skill
import io.legado.app.help.ai.skill.SkillRegistry
import io.legado.app.help.ai.tool.AiTool
import io.legado.app.help.ai.tool.AddNoteTool
import io.legado.app.help.ai.tool.CheckBookSourcesTool
import io.legado.app.help.ai.tool.CreateSkillTool
import io.legado.app.help.ai.tool.EvaluateSkillTool
import io.legado.app.help.ai.tool.EvolveSkillTool
import io.legado.app.help.ai.tool.FetchHtmlTool
import io.legado.app.help.ai.tool.ListChaptersTool
import io.legado.app.help.ai.tool.ListDeprecatedSkillsTool
import io.legado.app.help.ai.tool.ListMySkillsTool
import io.legado.app.help.ai.tool.ListSavedBookSourcesTool
import io.legado.app.help.ai.tool.ReadMemoryTool
import io.legado.app.help.ai.tool.ReadStatsTool
import io.legado.app.help.ai.tool.SaveBookSourceTool
import io.legado.app.help.ai.tool.SearchInBookTool
import io.legado.app.help.ai.tool.SearchWebTool
import io.legado.app.help.ai.tool.ValidateBookSourceTool
import io.legado.app.help.ai.tool.WriteMemoryTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Agent：在多步循环里跑 chat + 工具调用 + 工具执行 + 再次 chat。
 *
 * OpenAI 协议约定：模型返回 tool_calls 时，要把结果作为 role=tool 的消息加回
 * messages，再发起下一轮 chat。直到模型返回纯文本为止。
 *
 * 上限 [maxSteps] 防止无限循环。
 *
 * 改进：
 * - chat 请求失败时自动重试（指数退避，最多 [maxRetries] 次）
 * - 工具执行失败时把错误信息回传给模型让它自行恢复
 * - 工具超时保护（[toolTimeoutMs]）
 * - 连续错误计数，超过阈值后优雅退出
 */
class Agent(
    private val service: AiService = OpenAiService(),
    private val memory: AiMemoryStore = AiMemoryStore.instance,
    private val skills: SkillRegistry = SkillRegistry.instance,
    private val maxSteps: Int = 8,
    private val maxRetries: Int = 3,
    private val toolTimeoutMs: Long = 30_000L,
    private val maxConsecutiveErrors: Int = 3,
) {

    /** 启动一个带工具的对话。返回最终 Assistant 文本。 */
    suspend fun run(
        provider: AiProvider,
        systemPrompt: String,
        history: List<AiMessage>,
        extraTools: List<AiTool> = emptyList(),
    ): Result<AgentResult> = withContext(Dispatchers.IO) {
        runCatching {
            val tools = extraTools + getDefaultTools()
            val fullSystem = buildSystemPrompt(systemPrompt)
            val working = history.toMutableList()
            val toolLog = mutableListOf<ToolCallLog>()
            var lastUsage = ChatResult("", 0, 0)
            var consecutiveErrors = 0

            for (step in 1..maxSteps) {
                // chat with retry
                val result = try {
                    chatWithRetry(provider, fullSystem, working, tools)
                } catch (t: Throwable) {
                    consecutiveErrors++
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        return@runCatching AgentResult(
                            finalText = "[agent stopped: $maxConsecutiveErrors consecutive errors. Last: ${t.message}]",
                            steps = step,
                            toolLog = toolLog,
                            totalPromptTokens = lastUsage.promptTokens,
                            totalCompletionTokens = lastUsage.completionTokens,
                        )
                    }
                    // Tell the model about the error and let it try again
                    working.add(AiMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = working.lastOrNull()?.conversationId.orEmpty(),
                        role = "system",
                        content = "API error: ${t.message}. Please try a different approach.",
                    ))
                    continue
                }
                lastUsage = result
                consecutiveErrors = 0  // reset on success

                if (result.toolCalls.isEmpty()) {
                    return@runCatching AgentResult(
                        finalText = result.content,
                        steps = step,
                        toolLog = toolLog,
                        totalPromptTokens = lastUsage.promptTokens,
                        totalCompletionTokens = lastUsage.completionTokens,
                    )
                }

                val assistantMsg = AiMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = working.lastOrNull()?.conversationId.orEmpty(),
                    role = "assistant",
                    content = result.content,
                )
                working.add(assistantMsg)

                for (call in result.toolCalls) {
                    val tool = tools.firstOrNull { it.name == call.name }
                    val toolResult = if (tool == null) {
                        AiToolCallResult(
                            name = call.name,
                            content = "unknown tool: ${call.name}. Available: ${tools.map { it.name }}",
                            isError = true
                        )
                    } else {
                        executeToolWithTimeout(tool, call.arguments)
                    }
                    toolLog.add(ToolCallLog(call = call, result = toolResult))
                    working.add(toolResult.toMessage(call.id, working.last().conversationId))
                }
            }

            AgentResult(
                finalText = lastUsage.content.ifBlank { "[agent stopped at maxSteps=$maxSteps]" },
                steps = maxSteps,
                toolLog = toolLog,
                totalPromptTokens = lastUsage.promptTokens,
                totalCompletionTokens = lastUsage.completionTokens,
            )
        }
    }

    /**
     * Chat with exponential backoff retry.
     * Retries on network errors, rate limits (429), and server errors (5xx).
     */
    private suspend fun chatWithRetry(
        provider: AiProvider,
        systemPrompt: String,
        messages: MutableList<AiMessage>,
        tools: List<AiTool>,
    ): ChatResult {
        var lastError: Throwable? = null
        for (attempt in 0..maxRetries) {
            val result = service.chat(provider, systemPrompt, messages, tools)
            if (result.isSuccess) return result.getOrThrow()
            lastError = result.exceptionOrNull()
            // Don't retry on client errors (4xx except 429)
            val msg = lastError?.message.orEmpty()
            if (msg.contains("40") && !msg.contains("429") && !msg.contains("429")) {
                throw lastError!!
            }
            if (attempt < maxRetries) {
                val delayMs = (1000L shl attempt)  // 1s, 2s, 4s
                delay(delayMs)
            }
        }
        throw lastError ?: RuntimeException("chat failed after $maxRetries retries")
    }

    /**
     * Execute a tool with timeout protection.
     * If the tool times out, return an error result instead of hanging.
     */
    private suspend fun executeToolWithTimeout(
        tool: AiTool,
        arguments: Map<String, Any?>
    ): AiToolCallResult {
        return try {
            kotlinx.coroutines.withTimeout(toolTimeoutMs) {
                val r = tool.execute(arguments)
                AiToolCallResult(name = tool.name, content = r.content, isError = r.isError)
            }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            AiToolCallResult(
                name = tool.name,
                content = "tool timeout after ${toolTimeoutMs}ms",
                isError = true
            )
        } catch (t: Throwable) {
            AiToolCallResult(
                name = tool.name,
                content = "error: ${t.message}",
                isError = true
            )
        }
    }

    private fun getDefaultTools(): List<AiTool> = listOf(
        ReadMemoryTool(memory),
        WriteMemoryTool(memory),
        AddNoteTool(memory),
        ListMySkillsTool(skills),
        CreateSkillTool(skills),
        EvolveSkillTool(skills),
        EvaluateSkillTool(skills),
        ListDeprecatedSkillsTool(skills),
        ListChaptersTool(),
        SearchInBookTool(),
        SearchWebTool(),
        FetchHtmlTool(),
        SaveBookSourceTool(),
        ListSavedBookSourcesTool(),
        // New tools: book source analysis + reading stats
        CheckBookSourcesTool(),
        ReadStatsTool(),
        ValidateBookSourceTool(),
    )

    private suspend fun buildSystemPrompt(base: String): String {
        val memorySection = memory.forPrompt()
        val skillsSection = skills.activeInstructions()
        return buildString {
            append(base)
            if (memorySection.isNotBlank()) { append("\n\n"); append(memorySection) }
            if (skillsSection.isNotBlank()) { append("\n\n"); append(skillsSection) }
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
    val call: io.legado.app.help.ai.tool.AiToolCall,
    val result: AiToolCallResult,
)

data class AiToolCallResult(
    val name: String,
    val content: String,
    val isError: Boolean,
) {
    fun toMessage(callId: String, conversationId: String): AiMessage = AiMessage(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        role = "tool",
        content = if (isError) "[error] $content" else content,
    )
}
