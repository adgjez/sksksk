package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_messages",
    indices = [Index("conversationId"), Index("createdAt")],
    foreignKeys = [
        ForeignKey(
            entity = AiConversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AiMessage(
    @PrimaryKey
    val id: String = "",
    @ColumnInfo(defaultValue = "")
    val conversationId: String = "",
    @ColumnInfo(defaultValue = ROLE_USER)
    val role: String = ROLE_USER,
    @ColumnInfo(defaultValue = "")
    val content: String = "",
    @ColumnInfo(defaultValue = "0")
    val promptTokens: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val completionTokens: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    /** tool 角色消息的关联 call ID（仅 role=tool 时使用） */
    @ColumnInfo(defaultValue = "")
    val toolCallId: String = "",
    /** assistant 消息中的 tool_calls JSON（仅 role=assistant 且含工具调用时使用） */
    @ColumnInfo(defaultValue = "")
    val toolCallsJson: String = "",
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
        const val ROLE_TOOL = "tool"
    }
}
