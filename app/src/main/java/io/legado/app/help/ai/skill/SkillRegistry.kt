package io.legado.app.help.ai.skill

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.help.ai.memory.AiMemoryStore
import splitties.init.appCtx

/**
 * Skill 状态持久化。激活状态存 SharedPreferences，App 重启保留。
 *
 * 同时把激活状态"镜像"到 AiMemoryStore，以便 agent 的 list_skills /
 * activate_skill 工具看到一致视图。
 */
class SkillRegistry(
    private val memory: AiMemoryStore = AiMemoryStore.instance,
) {
    private val prefs: SharedPreferences =
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<Skill> = Skill.presets

    fun byName(name: String): Skill? = Skill.presets.firstOrNull { it.name == name }

    /** 当前激活的 skill 集合。从 prefs 读，**不**从 memory 读（以 prefs 为准）。 */
    suspend fun active(): List<Skill> {
        val set = activeNames()
        return Skill.presets.filter { it.name in set }
    }

    private fun activeNames(): Set<String> = prefs.getStringSet(KEY_ACTIVE, emptySet()) ?: emptySet()

    suspend fun activate(name: String) {
        val updated = activeNames().toMutableSet().apply { add(name) }
        prefs.edit().putStringSet(KEY_ACTIVE, updated).apply()
        // 镜像到 memory（让 list_skills 工具能读）
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
        val updated = activeNames().toMutableSet().apply { remove(name) }
        prefs.edit().putStringSet(KEY_ACTIVE, updated).apply()
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

    suspend fun isActive(name: String): Boolean = name in activeNames()

    /** 拼 system prompt 段。 */
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
        private const val PREFS_NAME = "ai_skills"
        private const val KEY_ACTIVE = "active_set"
        val instance by lazy { SkillRegistry() }
    }
}
