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
import io.legado.app.help.ai.tool.ReadMemoryTool
import io.legado.app.help.ai.tool.WriteMemoryTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val skills: SkillRegistry = SkillRegistry.instance,
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
            // 默认带 memory 工具 + skill 工具（让 agent 自我进化）
            val tools = extraTools + listOf(
                ReadMemoryTool(memory),
                WriteMemoryTool(memory),
                ListSkillsTool(skills),
                ActivateSkillTool(skills),
            )
            // 把 memory 内容 + 当前激活 skills 拼到 system prompt
            val memorySection = memory.forPrompt()
            val skillsSection = skills.activeInstructions()
            val fullSystem = buildString {
                append(systemPrompt)
                if (memorySection.isNotBlank()) { append("\n\n"); append(memorySection) }
                if (skillsSection.isNotBlank()) { append("\n\n"); append(skillsSection) }
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
}

/** 让 agent 看自己激活了哪些 skill 的工具。 */
class ListSkillsTool(
    private val skills: SkillRegistry,
) : AiTool {
    override val name = "list_skills"
    override val description = "查看所有可用 skill 及其说明（不需参数）。"
    override val parametersSchema = """{"type":"object","properties":{}}"""
    override suspend fun execute(arguments: Map<String, Any?>) = runCatching {
        io.legado.app.help.ai.tool.AiToolResult(
            skills.all().joinToString("\n") { "- ${it.name}: ${it.description}" }
        )
    }.getOrElse { io.legado.app.help.ai.tool.AiToolResult("error: ${it.message}", isError = true) }
}

/** 激活 skill：写 memory，之后会被自动注入到 system prompt。 */
class ActivateSkillTool(
    private val skills: SkillRegistry,
) : AiTool {
    override val name = "activate_skill"
    override val description = "激活一个 skill。下次 agent 启动会自动按 skill 的指令行事。"
    override val parametersSchema = """
        {"type":"object","properties":{"name":{"type":"string","description":"skill 名称"}}, "required":["name"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): io.legado.app.help.ai.tool.AiToolResult {
        val name = arguments["name"]?.toString()
            ?: return io.legado.app.help.ai.tool.AiToolResult("missing 'name'", isError = true)
        val s = skills.byName(name) ?: return io.legado.app.help.ai.tool.AiToolResult("unknown skill: $name", isError = true)
        skills.activate(s.name)
        return io.legado.app.help.ai.tool.AiToolResult("activated: ${s.name}")
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
