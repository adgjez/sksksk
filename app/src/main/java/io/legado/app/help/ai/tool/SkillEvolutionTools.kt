package io.legado.app.help.ai.tool

import io.legado.app.help.ai.skill.Skill
import io.legado.app.help.ai.skill.SkillRegistry

/**
 * agent 自我进化的 Skill 工具集。
 *
 * 流程：
 * 1. agent 调 [CreateSkillTool] 创建新 skill（status=experimental）
 * 2. agent 在多次使用后调 [EvaluateSkillTool] 评分
 * 3. 当评分差时，agent 调 [EvolveSkillTool] 改进 instructions
 * 4. 评分稳定（avg ≥ 4）后，status 升 stable
 */
class ListMySkillsTool(
    private val skills: SkillRegistry,
) : AiTool {
    override val name = "list_my_skills"
    override val description = "列出所有 skill（内置 + 用户/agent 创建），含评分/使用次数。返回文本。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "origin": {"type":"string", "enum": ["all","builtin","user","agent"], "default": "all"},
          "includeDeprecated": {"type":"boolean", "default": false}
        }}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val origin = arguments["origin"]?.toString() ?: "all"
        val includeDeprecated = arguments["includeDeprecated"] as? Boolean ?: false
        val all = skills.all().filter { s ->
            (origin == "all" || s.origin == origin) &&
            (includeDeprecated || s.status != Skill.STATUS_DEPRECATED)
        }
        if (all.isEmpty()) return AiToolResult("(no skills)")
        val text = all.joinToString("\n") { s ->
            "- ${s.name} [${s.origin}/${s.status}] avg=%.2f (n=%d) uses=%d: %s".format(
                s.averageRating, s.ratingCount, s.useCount, s.description
            )
        }
        return AiToolResult(text)
    }
}

/** 创建新 skill。origin 默认 agent。同名已存在则失败。 */
class CreateSkillTool(
    private val skills: SkillRegistry,
) : AiTool {
    override val name = "create_skill"
    override val description = "创建一个新 skill。name 必须小写蛇形命名。origin=agent 默认。" +
            "新创建的 skill status=experimental，需要 evaluate 后才升级 stable。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "name":         {"type":"string",  "description":"小写蛇形, 2-41 字符"},
          "description":  {"type":"string"},
          "instructions": {"type":"string",  "description":"激活后注入 system prompt 的指令文本"},
          "origin":       {"type":"string",  "enum":["agent","user"], "default":"agent"}
        }, "required":["name","description","instructions"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val name = arguments["name"]?.toString()?.trim().orEmpty()
        val desc = arguments["description"]?.toString()?.trim().orEmpty()
        val instr = arguments["instructions"]?.toString().orEmpty()
        val origin = arguments["origin"]?.toString() ?: Skill.ORIGIN_AGENT
        if (name.isEmpty() || desc.isEmpty() || instr.isEmpty()) {
            return AiToolResult("missing required field(s)", isError = true)
        }
        val s = Skill(name = name, description = desc, instructions = instr, origin = origin)
        return skills.createSkill(s).fold(
            onSuccess = { AiToolResult("created skill '${it.name}' (status=${it.status})") },
            onFailure = { AiToolResult("error: ${it.message}", isError = true) }
        )
    }
}

/** 改进已有 skill 的 instructions。 */
class EvolveSkillTool(
    private val skills: SkillRegistry,
) : AiTool {
    override val name = "evolve_skill"
    override val description = "替换 skill 的 instructions 并重置评分（让用户重新评价）。" +
            "适合发现 skill 输出有偏差时调用。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "name":            {"type":"string"},
          "newInstructions": {"type":"string"},
          "reason":          {"type":"string", "description":"为什么改"}
        }, "required":["name","newInstructions","reason"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val name = arguments["name"]?.toString().orEmpty()
        val newInst = arguments["newInstructions"]?.toString().orEmpty()
        val reason = arguments["reason"]?.toString().orEmpty()
        if (name.isEmpty() || newInst.isEmpty()) {
            return AiToolResult("missing required field(s)", isError = true)
        }
        return skills.evolveSkill(name, newInst, reason).fold(
            onSuccess = { AiToolResult("evolved '${it.name}': ${reason.take(80)}") },
            onFailure = { AiToolResult("error: ${it.message}", isError = true) }
        )
    }
}

/** 评估 skill（1-5 分）。多次调用会累计评分；自动改 status。 */
class EvaluateSkillTool(
    private val skills: SkillRegistry,
) : AiTool {
    override val name = "evaluate_skill"
    override val description = "用 1-5 分评价最近一次使用 skill 的效果。1=很差 3=还行 5=很好。" +
            "系统会自动根据评分 + useCount 决定 skill 是 stable / experimental / deprecated。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "name":    {"type":"string"},
          "rating":  {"type":"integer", "minimum": 1, "maximum": 5},
          "comment": {"type":"string", "description":"简短说明哪里好/不好"}
        }, "required":["name","rating"]}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val name = arguments["name"]?.toString().orEmpty()
        val rating = (arguments["rating"] as? Number)?.toInt() ?: 0
        val comment = arguments["comment"]?.toString() ?: ""
        if (name.isEmpty() || rating !in 1..5) {
            return AiToolResult("name and rating(1-5) required", isError = true)
        }
        return skills.evaluate(name, rating, comment).fold(
            onSuccess = {
                val avg = "%.2f".format(it.averageRating)
                AiToolResult("rated '${it.name}': ${rating}★ (avg=$avg, status=${it.status})")
            },
            onFailure = { AiToolResult("error: ${it.message}", isError = true) }
        )
    }
}

/** 列出已 deprecated 的 skill（agent 看到后可以决定是否要重写）。 */
class ListDeprecatedSkillsTool(
    private val skills: SkillRegistry,
) : AiTool {
    override val name = "list_deprecated_skills"
    override val description = "列出所有 status=deprecated 的 skill。agent 可参考这些失败案例改进。"
    override val parametersSchema = """{"type":"object","properties":{}}"""
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult {
        val deprecated = skills.all().filter { it.status == Skill.STATUS_DEPRECATED }
        if (deprecated.isEmpty()) return AiToolResult("(no deprecated skills)")
        val text = deprecated.joinToString("\n") { s ->
            "- ${s.name} [${s.origin}] avg=%.2f (n=%d) uses=%d: %s\n  last eval: %s".format(
                s.averageRating, s.ratingCount, s.useCount, s.description, s.lastEvaluation.take(120)
            )
        }
        return AiToolResult(text)
    }
}
