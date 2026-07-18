package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 提供商配置。
 *
 * 一个 app 可配置多个 provider（OpenAI / 通义千问 / DeepSeek / Ollama 等），
 * 用户在聊天/生图前选一个。
 */
@Entity(
    tableName = "ai_providers",
    indices = [Index("enabled"), Index("type")]
)
data class AiProvider(
    @PrimaryKey
    val id: String = "",
    @ColumnInfo(defaultValue = "")
    val name: String = "",
    @ColumnInfo(defaultValue = "openai")
    val type: String = TYPE_OPENAI,
    @ColumnInfo(defaultValue = "")
    val baseUrl: String = "",
    @ColumnInfo(defaultValue = "")
    val apiKey: String = "",
    @ColumnInfo(defaultValue = "")
    val model: String = "",
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_OPENAI = "openai"
        const val TYPE_OLLAMA = "ollama"
    }
}
