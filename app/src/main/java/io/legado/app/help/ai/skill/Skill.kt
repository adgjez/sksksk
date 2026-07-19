package io.legado.app.help.ai.skill

/**
 * Skill 元数据。
 *
 * 评估系统字段：
 * - origin: builtin（系统预设） / user（用户手写） / agent（agent 创建）
 * - status: experimental（新创建，待评估） / stable（评分 ≥ 4） / deprecated（评分 ≤ 2 且用 ≥ 3 次）
 * - useCount / ratingSum / ratingCount: 平均分 = ratingSum / ratingCount
 * - lastUsed: 毫秒时间戳
 * - lastEvaluation: 最近一次 agent 自我评估的总结
 */
data class Skill(
    val name: String,
    val description: String,
    val instructions: String,
    val origin: String = ORIGIN_BUILTIN,
    val status: String = STATUS_STABLE,
    val useCount: Int = 0,
    val ratingSum: Int = 0,
    val ratingCount: Int = 0,
    val lastUsed: Long = 0L,
    val lastEvaluation: String = "",
) {
    val averageRating: Double
        get() = if (ratingCount == 0) 0.0 else ratingSum.toDouble() / ratingCount

    companion object {
        const val ORIGIN_BUILTIN = "builtin"
        const val ORIGIN_USER = "user"
        const val ORIGIN_AGENT = "agent"

        const val STATUS_EXPERIMENTAL = "experimental"
        const val STATUS_STABLE = "stable"
        const val STATUS_DEPRECATED = "deprecated"

        fun activateMemoryKey(name: String) = "active_skill_$name"

        /** 全量预置 skill。 */
        val presets: List<Skill> = listOf(
            Skill(
                name = "summarize_chapter",
                description = "总结章节：用户提供章节正文，输出 100-300 字摘要。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_STABLE,
                instructions = """
                    当前激活 skill: summarize_chapter
                    用户给你一段书籍章节正文（可能用 read_chapter 工具拉取）。
                    你的任务：用 100-300 字中文摘要该章节关键情节、人物行为、伏笔。
                    不要复述细节；只讲推进了什么。
                """.trimIndent()
            ),
            Skill(
                name = "extract_characters",
                description = "从章节正文中抽取人物清单（名字 + 一句话简介）。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_STABLE,
                instructions = """
                    当前激活 skill: extract_characters
                    读到章节正文后，列出所有登场人物。每人一行："名字：一句话简介"。
                    不确定就标注（存疑）。
                """.trimIndent()
            ),
            Skill(
                name = "translate_passage",
                description = "把用户给出的段落翻译成中英日韩（用户说哪种就哪种）。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_STABLE,
                instructions = """
                    当前激活 skill: translate_passage
                    用户给一段外文，翻译成中文或反之。保留文学风格，避免机翻味。
                """.trimIndent()
            ),
            Skill(
                name = "explain_term",
                description = "解释用户提供的术语，可选调用搜索/记忆查询。",
                origin = ORIGIN_BUILTIN,
                status = STATUS_STABLE,
                instructions = """
                    当前激活 skill: explain_term
                    用户给一个词或概念：先查 memory 是否有相关条目；
                    没有就给一个简洁解释（2-5 句），必要时举书中的例子。
                """.trimIndent()
            ),
        )
    }
}
