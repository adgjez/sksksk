package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiConversation
import io.legado.app.data.entities.AiImage
import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import java.util.UUID

/**
 * AI 仓库：把 service（HTTP）和 DAO（Room）合成一个上层 API。
 */
class AiRepository(private val service: AiService = OpenAiService()) {

    fun listProviders() = appDb.aiProviderDao.all()
    fun listEnabledProviders() = appDb.aiProviderDao.enabled()
    fun getProvider(id: String) = appDb.aiProviderDao.get(id)
    fun saveProvider(p: AiProvider) = appDb.aiProviderDao.upsert(p)
    fun deleteProvider(id: String) = appDb.aiProviderDao.delete(id)

    suspend fun testProvider(p: AiProvider): Result<Unit> = service.testConnection(p)

    fun listConversations() = appDb.aiConversationDao.all()
    fun getConversation(id: String) = appDb.aiConversationDao.get(id)
    fun saveConversation(c: AiConversation) = appDb.aiConversationDao.upsert(c)
    fun deleteConversation(id: String) = appDb.aiConversationDao.delete(id)

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
        return service.chat(provider, systemPrompt, history).map { result ->
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
        val userMsg = AiMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = AiMessage.ROLE_USER,
            content = userText
        )
        saveMessage(userMsg)
        val history = messagesOf(conversationId)
        service.chatStream(provider, systemPrompt, history, tools = emptyList(), stream = stream)
    }

    suspend fun generateImage(
        provider: AiProvider,
        prompt: String,
        size: String = "1024x1024",
    ): Result<List<AiImage>> {
        return service.generateImage(provider, prompt, size, n = 1).map { paths ->
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
