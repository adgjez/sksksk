package io.legado.app.help.ai.memory

/**
 * 一条记忆。
 *
 * - key: 标识（同 scope+bookKey 下唯一）
 * - value: 内容
 * - scope: global / book / session
 * - importance: 0-100，用于淘汰和排序
 */
data class AiMemoryEntry(
    val id: String = "",
    val key: String = "",
    val value: String = "",
    val scope: String = "global",
    val bookKey: String = "",
    val importance: Int = 50,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_BOOK = "book"
        const val SCOPE_SESSION = "session"
    }
}
