package io.legado.app.help.ai.skill

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.help.ai.memory.AiMemoryStore
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

/**
 * Skill 注册表：内置 presets + 用户/agent 自定义 skills + 激活状态。
 *
 * 持久化（SharedPreferences "ai_skills"）：
 * - active_set: Set<String> 当前激活的 skill 名
 * - custom_skills_json: 自定义 skill 列表（agent 创建或用户手写）
 *
 * 评估系统：
 * - evaluate(skillName, rating, comment) 累计评分，自动改 status
 *   rating >= 4 → stable；rating <= 2 且 useCount >= 3 → deprecated
 * - recordUsage 每次激活时调用
 */
class SkillRegistry(
    private val memory: AiMemoryStore = AiMemoryStore.instance,
) {
    private val prefs: SharedPreferences =
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- 查询 ---

    fun all(): List<Skill> = presets() + customSkills()
    private fun presets(): List<Skill> = Skill.presets
    private fun customSkills(): List<Skill> {
        val raw = prefs.getString(KEY_CUSTOM, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parseSkill(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    fun byName(name: String): Skill? = all().firstOrNull { it.name == name }

    suspend fun active(): List<Skill> {
        val set = activeNames()
        return all().filter { it.name in set }
    }

    private fun activeNames(): Set<String> = prefs.getStringSet(KEY_ACTIVE, emptySet()) ?: emptySet()

    suspend fun isActive(name: String): Boolean = name in activeNames()

    // --- 激活控制 ---

    suspend fun activate(name: String) {
        val updated = activeNames().toMutableSet().apply { add(name) }
        prefs.edit().putStringSet(KEY_ACTIVE, updated).apply()
        recordUsage(name)
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

    // --- 创建 / 进化 / 评估（agent 自我进化核心） ---

    /**
     * 创建新 skill（agent 或用户调用）。同名则报错。
     * 默认 status=experimental，等待 evaluate 后才升级 stable / deprecated。
     */
    fun createSkill(skill: Skill): Result<Skill> = runCatching {
        require(skill.origin != Skill.ORIGIN_BUILTIN) { "can't create built-in origin" }
        require(skill.name.matches(Regex("^[a-z_][a-z0-9_]{1,40}$"))) {
            "skill name must be lowercase snake_case, 2-41 chars, got: ${skill.name}"
        }
        val existing = byName(skill.name)
        require(existing == null) { "skill '${skill.name}' already exists" }
        val withMeta = skill.copy(
            origin = if (skill.origin.isBlank()) Skill.ORIGIN_AGENT else skill.origin,
            status = Skill.STATUS_EXPERIMENTAL,
            useCount = 0,
            ratingSum = 0,
            ratingCount = 0,
        )
        saveCustom(withMeta)
        withMeta
    }

    /**
     * 进化 skill：替换 instructions（agent 根据评估总结改进）。
     * 重置 useCount / rating 让用户重新评价。
     * origin 保持不变。
     */
    fun evolveSkill(name: String, newInstructions: String, reason: String): Result<Skill> = runCatching {
        val s = byName(name) ?: error("skill not found: $name")
        val evolved = s.copy(
            instructions = newInstructions,
            status = Skill.STATUS_EXPERIMENTAL,  // 重新评估
            useCount = 0,
            ratingSum = 0,
            ratingCount = 0,
            lastEvaluation = "evolved: $reason",
        )
        updateSkill(evolved)
        evolved
    }

    /**
     * 评估 skill（agent 在用过 skill 后调用）。
     * @param rating 1-5 整数
     * @param comment 评估说明（写到 lastEvaluation）
     */
    fun evaluate(name: String, rating: Int, comment: String): Result<Skill> = runCatching {
        require(rating in 1..5) { "rating must be 1-5" }
        val s = byName(name) ?: error("skill not found: $name")
        val newCount = s.ratingCount + 1
        val newSum = s.ratingSum + rating
        val avg = newSum.toDouble() / newCount
        val newStatus = when {
            avg >= 4.0 -> Skill.STATUS_STABLE
            avg <= 2.0 && s.useCount >= 3 -> Skill.STATUS_DEPRECATED
            else -> Skill.STATUS_EXPERIMENTAL
        }
        val evaluated = s.copy(
            ratingSum = newSum,
            ratingCount = newCount,
            status = newStatus,
            lastUsed = System.currentTimeMillis(),
            lastEvaluation = "rating=$rating avg=%.2f: %s".format(avg, comment.take(200)),
        )
        updateSkill(evaluated)
        evaluated
    }

    /** 每次使用 +1。不改 status。 */
    fun recordUsage(name: String) {
        val s = byName(name) ?: return
        val updated = s.copy(useCount = s.useCount + 1, lastUsed = System.currentTimeMillis())
        updateSkill(updated)
    }

    /**
     * 通用更新：builtin skill 存为 custom（覆盖），非 builtin 直接 saveCustom。
     * 这样 builtin 也能被 evaluate / recordUsage 修改状态。
     */
    fun saveSkill(skill: Skill): Result<Skill> = runCatching {
        val existing = byName(skill.name)
        if (existing == null) {
            createSkill(skill).getOrThrow()
        } else {
            updateSkill(skill)
            skill
        }
    }

    private fun updateSkill(skill: Skill) {
        val list = customSkills().toMutableList()
        val idx = list.indexOfFirst { it.name == skill.name }
        if (idx >= 0) list[idx] = skill else list.add(skill)
        prefs.edit().putString(KEY_CUSTOM, serialize(list)).apply()
    }

    // --- 持久化辅助 ---

    private fun saveCustom(skill: Skill) {
        val list = customSkills().toMutableList()
        val idx = list.indexOfFirst { it.name == skill.name }
        if (idx >= 0) list[idx] = skill else list.add(skill)
        prefs.edit().putString(KEY_CUSTOM, serialize(list)).apply()
    }

    fun deleteCustom(name: String) {
        val list = customSkills().filterNot { it.name == name }
        prefs.edit().putString(KEY_CUSTOM, serialize(list)).apply()
    }

    // --- system prompt 段 ---

    suspend fun activeInstructions(): String {
        val active = active()
        if (active.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("## 当前激活的 Skills")
        for (s in active) {
            val origin = if (s.origin == Skill.ORIGIN_BUILTIN) "" else " [${s.origin}]"
            sb.appendLine("- ${s.name}$origin (rating=%.1f, uses=%d): ${s.description}".format(
                s.averageRating, s.useCount))
        }
        sb.appendLine()
        sb.appendLine("## Skill 详情（按名称遵循其指令）")
        for (s in active) {
            sb.appendLine()
            sb.appendLine(s.instructions)
        }
        return sb.toString()
    }

    // --- JSON 序列化 ---

    private fun serialize(list: List<Skill>): String {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("name", s.name)
            o.put("description", s.description)
            o.put("instructions", s.instructions)
            o.put("origin", s.origin)
            o.put("status", s.status)
            o.put("useCount", s.useCount)
            o.put("ratingSum", s.ratingSum)
            o.put("ratingCount", s.ratingCount)
            o.put("lastUsed", s.lastUsed)
            o.put("lastEvaluation", s.lastEvaluation)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun parseSkill(o: JSONObject): Skill = Skill(
        name = o.optString("name"),
        description = o.optString("description"),
        instructions = o.optString("instructions"),
        origin = o.optString("origin", Skill.ORIGIN_USER),
        status = o.optString("status", Skill.STATUS_EXPERIMENTAL),
        useCount = o.optInt("useCount", 0),
        ratingSum = o.optInt("ratingSum", 0),
        ratingCount = o.optInt("ratingCount", 0),
        lastUsed = o.optLong("lastUsed", 0L),
        lastEvaluation = o.optString("lastEvaluation"),
    )

    companion object {
        private const val PREFS_NAME = "ai_skills"
        private const val KEY_ACTIVE = "active_set"
        private const val KEY_CUSTOM = "custom_skills_json"
        val instance by lazy { SkillRegistry() }
    }
}
