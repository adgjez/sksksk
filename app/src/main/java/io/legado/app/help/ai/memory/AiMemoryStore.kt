package io.legado.app.help.ai.memory

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiMemoryEntity

/**
 * AI 长期记忆。K-V + scope（global/book/session）。
 *
 * Room 持久化实现：数据存储在 `ai_memories` 表，重启不丢失。
 *
 * Agent "自我进化" 通过 [WriteMemoryTool] 写入；查询时通过 [forPrompt]
 * 拼成 system prompt 的一段。
 */
class AiMemoryStore {

    private val dao get() = appDb.aiMemoryDao

    suspend fun put(entry: AiMemoryEntry) {
        val entity = entry.toEntity()
        // 同 (key, scope, bookKey) 则覆盖
        val existing = dao.get(entry.key, entry.scope, entry.bookKey)
        if (existing != null) {
            dao.upsert(entity.copy(id = existing.id))
        } else {
            dao.upsert(entity)
        }
        // 简单上限：每 scope 最多 200 条
        val perScope = dao.getByScope(entry.scope)
        if (perScope.size > 200) {
            dao.deleteLeastImportant(entry.scope, perScope.size - 200)
        }
    }

    suspend fun get(key: String, scope: String = "global", bookKey: String = ""): AiMemoryEntry? =
        dao.get(key, scope, bookKey)?.toEntry()

    suspend fun search(query: String, bookKey: String = "", limit: Int = 10): List<AiMemoryEntry> =
        dao.search(query, bookKey, limit).map { it.toEntry() }

    suspend fun forPrompt(bookKey: String = "", maxChars: Int = 1500): String {
        val relevant = dao.topByImportance(bookKey, 50).map { it.toEntry() }
        if (relevant.isEmpty()) return ""
        val sb = StringBuilder("## 长期记忆\n")
        for (e in relevant) {
            val line = "- [${e.scope}] ${e.key}: ${e.value}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString()
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }

    suspend fun size(): Int = dao.count()

    companion object {
        val instance by lazy { AiMemoryStore() }
    }
}

// ===== Entity ↔ Entry 转换 =====

private fun AiMemoryEntry.toEntity(): AiMemoryEntity = AiMemoryEntity(
    id = id,
    key = key,
    value = value,
    scope = scope,
    bookKey = bookKey,
    importance = importance,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun AiMemoryEntity.toEntry(): AiMemoryEntry = AiMemoryEntry(
    id = id,
    key = key,
    value = value,
    scope = scope,
    bookKey = bookKey,
    importance = importance,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
