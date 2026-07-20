package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 长期记忆实体（Room 持久化）。
 *
 * - key: 标识（同 scope+bookKey 下唯一）
 * - value: 内容
 * - scope: global / book / session
 * - importance: 0-100，用于淘汰和排序
 */
@Entity(
    tableName = "ai_memories",
    indices = [
        Index(value = ["scope"]),
        Index(value = ["bookKey"]),
        Index(value = ["importance"]),
    ]
)
data class AiMemoryEntity(
    @PrimaryKey
    val id: String = "",
    val key: String = "",
    val value: String = "",
    val scope: String = "global",
    val bookKey: String = "",
    val importance: Int = 50,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
