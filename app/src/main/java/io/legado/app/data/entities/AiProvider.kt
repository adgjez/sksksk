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
        /** OpenAI / 通义千问 / DeepSeek / 智谱 GLM / 任何 OpenAI 兼容服务。 */
        const val TYPE_OPENAI = "openai"
        /** Ollama（本地 LLM，OpenAI 模式略有差异）。 */
        const val TYPE_OLLAMA = "ollama"
        /** Anthropic Claude（API 路径 /v1/messages，header x-api-key，不兼容 OpenAI）。 */
        const val TYPE_ANTHROPIC = "anthropic"
        /** Google Gemini（API 路径 /v1beta/...，用 ?key=API_KEY，不兼容 OpenAI）。 */
        const val TYPE_GEMINI = "gemini"

        /** 该 provider 类型对应的默认 base URL（用户在 UI 可改）。 */
        fun defaultBaseUrl(type: String): String = when (type) {
            TYPE_OPENAI -> "https://api.openai.com/v1"
            TYPE_OLLAMA -> "http://localhost:11434"
            TYPE_ANTHROPIC -> "https://api.anthropic.com"
            TYPE_GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
            else -> "https://api.openai.com/v1"
        }

        /** 该类型对应的默认 model 占位（用户可改）。 */
        fun defaultModel(type: String): String = when (type) {
            TYPE_OPENAI -> "gpt-4o-mini"
            TYPE_OLLAMA -> "llama3"
            TYPE_ANTHROPIC -> "claude-3-5-sonnet-20241022"
            TYPE_GEMINI -> "gemini-1.5-flash"
            else -> "gpt-4o-mini"
        }
    }
}
