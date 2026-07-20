package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiConversation
import io.legado.app.data.entities.AiImage
import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.config.AppConfig
import java.util.UUID

/**
 * AI 仓库：把 service（HTTP）和 DAO（Room）合成一个上层 API。
 * 根据 Provider 类型自动选择对应的 Service 实现。
 */
class AiRepository(
    private val defaultService: AiService = OpenAiService(),
    private val anthropicService: AiService = AnthropicService(),
) {

    private fun serviceFor(provider: AiProvider): AiService =
        if (provider.type == AiProvider.TYPE_ANTHROPIC) anthropicService else defaultService

    private fun globalTemp(): Double = AppConfig.aiTemperature.let { if (it < 0) 0.7 else it.toDouble() }
    private fun globalMaxTokens(): Int = AppConfig.aiMaxTokens.let { if (it <= 0) 2048 else it }
    private fun globalSystemPrefix(): String = AppConfig.aiGlobalSystemPrompt

    fun listProviders() = appDb.aiProviderDao.all()
    fun listEnabledProviders() = appDb.aiProviderDao.enabled()
    fun getProvider(id: String) = appDb.aiProviderDao.get(id)
    fun saveProvider(p: AiProvider) = appDb.aiProviderDao.upsert(p)
    fun deleteProvider(id: String) = appDb.aiProviderDao.delete(id)

    suspend fun testProvider(p: AiProvider): Result<Unit> = serviceFor(p).testConnection(p)

    fun listConversations() = appDb.aiConversationDao.all()
    fun getConversation(id: String) = appDb.aiConversationDao.get(id)
    fun saveConversation(c: AiConversation) = appDb.aiConversationDao.upsert(c)
    fun deleteConversation(id: String) {
        appDb.aiConversationDao.deleteMessages(id)
        appDb.aiConversationDao.delete(id)
    }
    fun conversationsByProvider(providerId: String) = appDb.aiConversationDao.byProvider(providerId)

    fun messagesOf(conversationId: String) = appDb.aiConversationDao.messages(conversationId)
    fun saveMessage(m: AiMessage) = appDb.aiConversationDao.upsertMessage(m)
    fun deleteMessages(conversationId: String) = appDb.aiConversationDao.deleteMessages(conversationId)

    fun listImages() = appDb.aiImageDao.all()
    fun getImage(id: String) = appDb.aiImageDao.get(id)
    fun saveImage(img: AiImage) = appDb.aiImageDao.upsert(img)
    fun deleteImage(id: String) = appDb.aiImageDao.delete(id)

    suspend fun ask(
        provider: AiProvider,
        conversationId: String,
        userText: String,
        systemPrompt: String = "你是一个有帮助的助手。",
    ): Result<String> {
        val userMsg = AiMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = AiMessage.ROLE_USER,
            content = userText
        )
        saveMessage(userMsg)

        val history = messagesOf(conversationId)
        val fullSystem = globalSystemPrefix().let { if (it.isBlank()) systemPrompt else "$it\n\n$systemPrompt" }
        return serviceFor(provider).chat(provider, fullSystem, history, temperature = globalTemp(), maxTokens = globalMaxTokens()).map { result ->
            val assistantMsg = AiMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = AiMessage.ROLE_ASSISTANT,
                content = result.content,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
            )
            saveMessage(assistantMsg)
            getConversation(conversationId)?.let { c ->
                saveConversation(c.copy(updatedAt = System.currentTimeMillis()))
            }
            result.content
        }
    }

    suspend fun askStream(
        provider: AiProvider,
        conversationId: String,
        userText: String,
        systemPrompt: String = "你是一个有帮助的助手。",
        stream: ChatStream,
    ) {
        askStream(provider, messagesOf(conversationId), systemPrompt, stream)
    }

    suspend fun askStream(
        provider: AiProvider,
        history: List<AiMessage>,
        systemPrompt: String,
        stream: ChatStream,
    ) {
        val fullSystem = globalSystemPrefix().let { if (it.isBlank()) systemPrompt else "$it\n\n$systemPrompt" }
        serviceFor(provider).chatStream(provider, fullSystem, history, tools = emptyList(), stream = stream, temperature = globalTemp(), maxTokens = globalMaxTokens())
    }

    suspend fun generateImage(
        provider: AiProvider,
        prompt: String,
        size: String = "1024x1024",
    ): Result<List<AiImage>> {
        return serviceFor(provider).generateImage(provider, prompt, size, n = 1).map { paths ->
            paths.map { p ->
                val img = AiImage(
                    id = UUID.randomUUID().toString(),
                    providerId = provider.id,
                    prompt = prompt,
                    model = provider.model,
                    localPath = p,
                    createdAt = System.currentTimeMillis(),
                )
                saveImage(img)
                img
            }
        }
    }

    companion object {
        val instance by lazy { AiRepository() }
    }
}
