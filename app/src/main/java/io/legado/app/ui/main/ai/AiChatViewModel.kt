package io.legado.app.ui.main.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.AiConversation
import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.AiRepository
import io.legado.app.help.ai.ChatResult
import io.legado.app.help.ai.ChatStream
import io.legado.app.help.ai.agent.Agent
import io.legado.app.help.ai.agent.AgentResult
import io.legado.app.help.ai.agent.ToolCallLog
import io.legado.app.help.ai.memory.AiMemoryStore
import io.legado.app.help.ai.skill.SkillRegistry
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.util.UUID

data class ChatUiState(
    val activeProvider: AiProvider? = null,
    val conversation: AiConversation? = null,
    val conversations: List<AiConversation> = emptyList(),
    val messages: List<AiMessage> = emptyList(),
    val sending: Boolean = false,
    val streaming: String? = null,
    val error: String? = null,
    val toolLog: List<ToolCallLog> = emptyList(),
    val agentMode: Boolean = AppConfig.aiAgentModeDefault,
    val showConversationList: Boolean = false,
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
)

class AiChatViewModel(
    private val repo: AiRepository = AiRepository.instance,
    private val agent: Agent = Agent(
        memory = AiMemoryStore.instance,
        skills = SkillRegistry.instance,
    ),
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var currentEventSource: EventSource? = null

    override fun onCleared() {
        super.onCleared()
        currentEventSource?.cancel()
    }

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val provider = repo.listEnabledProviders().firstOrNull()
            val allConversations = repo.listConversations()
            val conversations = if (provider != null) {
                allConversations.filter { it.providerId == provider.id }
            } else {
                allConversations
            }
            val conversation = conversations.firstOrNull()
                ?: ensureConversation(provider)
            val messages = conversation?.let { repo.messagesOf(it.id) } ?: emptyList()
            _state.update {
                it.copy(
                    activeProvider = provider,
                    conversations = conversations,
                    conversation = conversation,
                    messages = messages,
                )
            }
        }
    }

    fun toggleAgentMode() {
        _state.update { it.copy(agentMode = !it.agentMode) }
    }

    fun deleteMessage(msg: AiMessage) {
        if (io.legado.app.help.config.AppConfig.aiChatPersist) {
            repo.deleteMessage(msg.id)
        }
        _state.update { it.copy(messages = it.messages.filterNot { m -> m.id == msg.id }) }
    }

    fun retry() {
        val lastUser = _state.value.messages.lastOrNull { it.role == AiMessage.ROLE_USER } ?: return
        // 删除 lastUser 之后的所有消息（toMutableList 避免子列表视图问题）
        val idx = _state.value.messages.indexOfLast { it.id == lastUser.id }
        val kept = _state.value.messages.subList(0, idx).toList()
        _state.update { it.copy(messages = kept, error = null, streaming = null, sending = true, toolLog = emptyList()) }
        val provider = _state.value.activeProvider ?: return
        val conversation = _state.value.conversation ?: return
        if (_state.value.agentMode) {
            viewModelScope.launch { sendViaAgent(provider, conversation, lastUser.content) }
        } else {
            viewModelScope.launch { sendViaStream(provider, conversation, lastUser.content) }
        }
    }

    fun toggleConversationList() {
        _state.update { it.copy(showConversationList = !it.showConversationList) }
    }

    fun newConversation() {
        val provider = _state.value.activeProvider ?: return
        viewModelScope.launch {
            val fresh = AiConversation(
                id = UUID.randomUUID().toString(),
                title = "新会话",
                providerId = provider.id,
                systemPrompt = "你是一个有帮助的助手。",
            )
            repo.saveConversation(fresh)
            val conversations = repo.listConversations()
            _state.update {
                it.copy(
                    conversations = conversations,
                    conversation = fresh,
                    messages = emptyList(),
                    showConversationList = false,
                    error = null,
                    streaming = null,
                )
            }
        }
    }

    fun switchConversation(conv: AiConversation) {
        viewModelScope.launch {
            val messages = repo.messagesOf(conv.id)
            _state.update {
                it.copy(
                    conversation = conv,
                    messages = messages,
                    showConversationList = false,
                    error = null,
                    streaming = null,
                )
            }
        }
    }

    fun deleteConversation(conv: AiConversation) {
        viewModelScope.launch {
            repo.deleteConversation(conv.id)
            val conversations = repo.listConversations()
            val current = if (_state.value.conversation?.id == conv.id) {
                conversations.firstOrNull { it.providerId == _state.value.activeProvider?.id }
                    ?: ensureConversation(_state.value.activeProvider)
            } else {
                _state.value.conversation
            }
            val messages = current?.let { repo.messagesOf(it.id) } ?: emptyList()
            _state.update {
                it.copy(
                    conversations = conversations,
                    conversation = current,
                    messages = messages,
                )
            }
        }
    }

    fun send(text: String) {
        val provider = _state.value.activeProvider ?: return
        val conversation = _state.value.conversation ?: return
        _state.update { it.copy(sending = true, error = null, toolLog = emptyList()) }

        viewModelScope.launch {
            val userMsg = AiMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = AiMessage.ROLE_USER,
                content = text,
            )
            if (AppConfig.aiChatPersist) {
                repo.saveMessage(userMsg)
            }
            // 第一条消息时自动更新会话标题
            if (_state.value.messages.isEmpty()) {
                val title = text.take(20).replace("\n", " ")
                val updated = conversation.copy(title = title, updatedAt = System.currentTimeMillis())
                repo.saveConversation(updated)
                _state.update { it.copy(conversation = updated, conversations = repo.listConversations()) }
            }
            _state.update { it.copy(messages = it.messages + userMsg) }

            if (_state.value.agentMode) {
                sendViaAgent(provider, conversation, text)
            } else {
                sendViaStream(provider, conversation, text)
            }
        }
    }

    private suspend fun sendViaAgent(
        provider: AiProvider,
        conversation: AiConversation,
        text: String,
    ) {
        _state.update { it.copy(streaming = "思考中…") }
        // 从内存 state 读历史（aiChatPersist=false 时 Room 中可能没有最新消息）
        val history = _state.value.messages
        val result = agent.run(
            provider = provider,
            systemPrompt = conversation.systemPrompt.ifBlank { "你是一个有帮助的助手。" },
            history = history,
        )
        result.onSuccess { agentResult: AgentResult ->
            val assistant = AiMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = AiMessage.ROLE_ASSISTANT,
                content = agentResult.finalText,
            )
            if (io.legado.app.help.config.AppConfig.aiChatPersist) {
                repo.saveMessage(assistant)
                repo.saveConversation(conversation.copy(updatedAt = System.currentTimeMillis()))
            }
            _state.update {
                it.copy(
                    messages = it.messages + assistant,
                    streaming = null,
                    sending = false,
                    toolLog = agentResult.toolLog,
                    totalPromptTokens = it.totalPromptTokens + agentResult.totalPromptTokens,
                    totalCompletionTokens = it.totalCompletionTokens + agentResult.totalCompletionTokens,
                )
            }
        }.onFailure { t ->
            _state.update {
                it.copy(
                    error = t.message ?: t.javaClass.simpleName,
                    sending = false,
                    streaming = null,
                )
            }
        }
    }

    private suspend fun sendViaStream(
        provider: AiProvider,
        conversation: AiConversation,
        text: String,
    ) {
        currentEventSource?.cancel()
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
                // 直接在回调线程更新 state，不依赖 viewModelScope（避免 ViewModel 已清除时丢失）
                if (AppConfig.aiChatPersist) {
                    try {
                        repo.saveMessage(assistant)
                        repo.saveConversation(conversation.copy(updatedAt = System.currentTimeMillis()))
                    } catch (_: Exception) { }
                }
                _state.update {
                    it.copy(
                        messages = it.messages + assistant,
                        streaming = null,
                        sending = false,
                        totalPromptTokens = it.totalPromptTokens + result.promptTokens,
                        totalCompletionTokens = it.totalCompletionTokens + result.completionTokens,
                    )
                }
            }
        }
        try {
            val history = _state.value.messages
            currentEventSource = repo.askStream(provider, history, conversation.systemPrompt.ifBlank { "你是一个有帮助的助手。" }, stream = stream)
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.message ?: t.javaClass.simpleName, sending = false, streaming = null) }
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
