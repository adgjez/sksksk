package io.legado.app.help.ai.skill

import io.legado.app.help.ai.memory.AiMemoryStore

/**
 * Skill 注册表：内置 presets + 用户可"激活/停用"（激活状态写 memory）。
 *
 * Agent 启动时调用 [activeInstructions]，把激活的 skill 拼到 system prompt。
 */
class SkillRegistry(
    private val memory: AiMemoryStore = AiMemoryStore.instance,
) {

    /** 全部 skill。 */
    fun all(): List<Skill> = Skill.presets

    fun byName(name: String): Skill? = Skill.presets.firstOrNull { it.name == name }

    /** 当前激活的 skill 集合。读自 memory。 */
    suspend fun active(): List<Skill> {
        val active = mutableListOf<Skill>()
        for (s in all()) {
            val v = memory.get(Skill.activateMemoryKey(s.name), "global", "")
            if (v?.value == "1") active.add(s)
        }
        return active
    }

    suspend fun activate(name: String) {
        memory.put(
            io.legado.app.help.ai.memory.AiMemoryEntry(
                id = "skill_${name}_on",
                key = Skill.activateMemoryKey(name),
                value = "1",
                scope = "global",
                importance = 30,
            )
        )
    }

    suspend fun deactivate(name: String) {
        memory.put(
            io.legado.app.help.ai.memory.AiMemoryEntry(
                id = "skill_${name}_off",
                key = Skill.activateMemoryKey(name),
                value = "0",
                scope = "global",
                importance = 30,
            )
        )
    }

    /**
     * 拼一个 system prompt 段，列出当前激活的 skill。
     * agent 启动时拼到 system prompt 末尾。
     */
    suspend fun activeInstructions(): String {
        val active = active()
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("## 当前激活的 Skills")
            for (s in active) {
                appendLine("- ${s.name}: ${s.description}")
            }
            appendLine()
            appendLine("## Skill 详情（按名称遵循其指令）")
            for (s in active) {
                appendLine()
                appendLine(s.instructions)
            }
        }
    }

    companion object {
        val instance by lazy { SkillRegistry() }
    }
}
