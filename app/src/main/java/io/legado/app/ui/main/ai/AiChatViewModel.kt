package io.legado.app.ui.main.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.AiConversation
import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.AiRepository
import io.legado.app.help.ai.ChatResult
import io.legado.app.help.ai.ChatStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val activeProvider: AiProvider? = null,
    val conversation: AiConversation? = null,
    val messages: List<AiMessage> = emptyList(),
    val sending: Boolean = false,
    val streaming: String? = null,
    val error: String? = null,
)

class AiChatViewModel(
    private val repo: AiRepository = AiRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val provider = repo.listEnabledProviders().firstOrNull()
            val conversation = ensureConversation(provider)
            val messages = conversation?.let { repo.messagesOf(it.id) } ?: emptyList()
            _state.update {
                it.copy(activeProvider = provider, conversation = conversation, messages = messages)
            }
        }
    }

    fun send(text: String) {
        val provider = _state.value.activeProvider ?: return
        val conversation = _state.value.conversation ?: return
        _state.update { it.copy(sending = true, error = null) }

        viewModelScope.launch {
            val userMsg = AiMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = AiMessage.ROLE_USER,
                content = text,
            )
            repo.saveMessage(userMsg)
            _state.update { it.copy(messages = it.messages + userMsg) }

            val accumulated = StringBuilder()
            val stream = object : ChatStream {
                override fun onDelta(delta: String, isFinal: Boolean) {
                    if (delta.isNotEmpty()) {
                        accumulated.append(delta)
                        _state.update { it.copy(streaming = accumulated.toString()) }
                    }
                }
                override fun onError(t: Throwable) {
                    _state.update { it.copy(error = t.message ?: t.javaClass.simpleName, sending = false, streaming = null) }
                }
                override fun onComplete(result: ChatResult) {
                    val assistant = AiMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversation.id,
                        role = AiMessage.ROLE_ASSISTANT,
                        content = accumulated.toString(),
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                    )
                    viewModelScope.launch {
                        repo.saveMessage(assistant)
                        repo.saveConversation(conversation.copy(updatedAt = System.currentTimeMillis()))
                        _state.update {
                            it.copy(messages = it.messages + assistant, streaming = null, sending = false)
                        }
                    }
                }
            }
            try {
                repo.askStream(provider, conversation.id, text, stream = stream)
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: t.javaClass.simpleName, sending = false, streaming = null) }
            }
        }
    }

    private suspend fun ensureConversation(provider: AiProvider?): AiConversation? {
        if (provider == null) return null
        val existing = repo.listConversations().firstOrNull { it.providerId == provider.id }
        if (existing != null) return existing
        val fresh = AiConversation(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            providerId = provider.id,
            systemPrompt = "你是一个有帮助的助手。",
        )
        repo.saveConversation(fresh)
        return fresh
    }
}
