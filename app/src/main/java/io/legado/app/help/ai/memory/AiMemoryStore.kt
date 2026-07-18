package io.legado.app.help.ai.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AI 长期记忆。K-V + scope（global/book/session）。
 *
 * MVP 实现：进程内 list + Mutex。重启清空。后续可替换为 Room 持久化。
 *
 * Agent "自我进化" 通过 [WriteMemoryTool] 写入；查询时通过 [forPrompt]
 * 拼成 system prompt 的一段。
 */
class AiMemoryStore {

    private val mutex = Mutex()
    private val entries = mutableListOf<AiMemoryEntry>()

    suspend fun put(entry: AiMemoryEntry) {
        mutex.withLock {
            // 同 (key, scope, bookKey) 则覆盖
            entries.removeAll {
                it.key == entry.key && it.scope == entry.scope && it.bookKey == entry.bookKey
            }
            entries.add(entry)
            // 简单上限：每 scope 最多 200 条
            val perScope = entries.filter { it.scope == entry.scope }
            if (perScope.size > 200) {
                val toRemove = perScope.sortedBy { it.importance }.take(perScope.size - 200)
                entries.removeAll(toRemove.toSet())
            }
        }
    }

    suspend fun get(key: String, scope: String = "global", bookKey: String = ""): AiMemoryEntry? =
        mutex.withLock {
            entries.firstOrNull {
                it.key == key && it.scope == scope && it.bookKey == bookKey
            }
        }

    suspend fun search(query: String, bookKey: String = "", limit: Int = 10): List<AiMemoryEntry> =
        mutex.withLock {
            entries
                .filter { bookKey.isBlank() || it.scope == "global" || it.bookKey == bookKey }
                .filter { e ->
                    e.key.contains(query, ignoreCase = true) ||
                            e.value.contains(query, ignoreCase = true)
                }
                .sortedByDescending { it.importance }
                .take(limit)
        }

    suspend fun forPrompt(bookKey: String = "", maxChars: Int = 1500): String =
        mutex.withLock {
            val relevant = entries
                .filter { bookKey.isBlank() || it.scope == "global" || it.bookKey == bookKey }
                .sortedByDescending { it.importance }
                .take(50)
            if (relevant.isEmpty()) return@withLock ""
            val sb = StringBuilder("## 长期记忆\n")
            for (e in relevant) {
                val line = "- [${e.scope}] ${e.key}: ${e.value}\n"
                if (sb.length + line.length > maxChars) break
                sb.append(line)
            }
            sb.toString()
        }

    suspend fun delete(id: String) {
        mutex.withLock { entries.removeAll { it.id == id } }
    }

    suspend fun size(): Int = mutex.withLock { entries.size }

    companion object {
        val instance by lazy { AiMemoryStore() }
    }
}
