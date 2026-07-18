package io.legado.app.ui.main.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.agent.Agent
import io.legado.app.help.ai.agent.AgentResult
import io.legado.app.help.ai.AiRepository
import io.legado.app.help.ai.tool.AiTool
import io.legado.app.help.ai.tool.AiToolResult
import io.legado.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 阅读助手 Activity。入口：ReadBookActivity 里点"问 AI" → 启动本 Activity。
 *
 * 接收 Intent extras：bookUrl / bookName / chapterIndex / selectedText。
 * 用 Agent 跑多步 chat（带 read_chapter / search_books 等阅读工具）。
 */
class AiReadingAssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL).orEmpty()
        val bookName = intent.getStringExtra(EXTRA_BOOK_NAME).orEmpty()
        val chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, -1)
        val selectedText = intent.getStringExtra(EXTRA_SELECTED_TEXT).orEmpty()

        setContent {
            AppTheme {
                AiReadingAssistantScreen(
                    bookUrl = bookUrl,
                    bookName = bookName,
                    chapterIndex = chapterIndex,
                    selectedText = selectedText,
                    onDismiss = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
        const val EXTRA_BOOK_NAME = "bookName"
        const val EXTRA_CHAPTER_INDEX = "chapterIndex"
        const val EXTRA_SELECTED_TEXT = "selectedText"

        fun launch(
            context: Context,
            bookUrl: String,
            bookName: String,
            chapterIndex: Int,
            selectedText: String,
        ) {
            context.startActivity(
                Intent(context, AiReadingAssistantActivity::class.java).apply {
                    putExtra(EXTRA_BOOK_URL, bookUrl)
                    putExtra(EXTRA_BOOK_NAME, bookName)
                    putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                    putExtra(EXTRA_SELECTED_TEXT, selectedText)
                }
            )
        }
    }
}

data class ReadingAssistantState(
    val provider: AiProvider? = null,
    val messages: List<UiMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val lastToolLog: List<String> = emptyList(),
)

data class UiMessage(
    val id: String,
    val role: String,  // user / assistant / system
    val content: String,
)

class ReadingAssistantViewModel(
    private val bookUrl: String,
    private val bookName: String,
    private val chapterIndex: Int,
    private val selectedText: String,
) : ViewModel() {

    private val repo = AiRepository.instance
    private val _state = MutableStateFlow(ReadingAssistantState())
    val state: StateFlow<ReadingAssistantState> = _state.asStateFlow()

    init { viewModelScope.launch {
        val provider = repo.listEnabledProviders().firstOrNull()
        _state.update { it.copy(provider = provider) }
        // 首条 system 消息：注入上下文
        if (selectedText.isNotBlank()) {
            _state.update { it.copy(
                messages = it.messages + UiMessage(
                    id = UUID.randomUUID().toString(),
                    role = "user",
                    content = "（已选中文本）\n$selectedText"
                )
            )}
        }
    }}

    fun send(text: String) {
        val provider = _state.value.provider ?: return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            // 拼 system prompt
            val sys = buildString {
                appendLine("你正在帮用户阅读《${bookName}》")
                if (chapterIndex >= 0) appendLine("当前章节索引：$chapterIndex")
                if (bookUrl.isNotBlank()) appendLine("书籍 URL：$bookUrl")
                appendLine()
                appendLine("回答时优先调用工具（read_chapter 读章节、search_books 搜书、write_memory 记偏好）再答；保持简洁。")
            }
            val history = _state.value.messages.map { ui ->
                AiMessage(
                    id = ui.id,
                    conversationId = "reading_assistant_$bookUrl",
                    role = if (ui.role == "user") AiMessage.ROLE_USER else AiMessage.ROLE_ASSISTANT,
                    content = ui.content,
                )
            }
            val userMsg = UiMessage(
                id = UUID.randomUUID().toString(),
                role = "user",
                content = text
            )
            _state.update { it.copy(messages = it.messages + userMsg) }
            val allHistory = history + userMsg.toAiMessage()

            val tools: List<AiTool> = listOf(ReadChapterContextTool(bookUrl, chapterIndex))
            val agent = Agent()
            val r: Result<AgentResult> = agent.run(provider, sys, allHistory, tools)

            r.onSuccess { ar ->
                _state.update {
                    it.copy(
                        sending = false,
                        messages = it.messages + UiMessage(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = ar.finalText
                        ),
                        lastToolLog = ar.toolLog.map { tl ->
                            "${tl.call.name}: ${tl.result.content.take(80)}"
                        }
                    )
                }
            }.onFailure { t ->
                _state.update { it.copy(sending = false, error = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    private fun UiMessage.toAiMessage() = AiMessage(
        id = id, conversationId = "reading_assistant_$bookUrl",
        role = if (role == "user") AiMessage.ROLE_USER else AiMessage.ROLE_ASSISTANT,
        content = content
    )
}

/** 当前书的 read_chapter 工具实现：从 base 的 BookChapter 表读正文。 */
class ReadChapterContextTool(
    private val bookUrl: String,
    private val chapterIndex: Int,
) : AiTool {
    override val name = "read_chapter"
    override val description = "读取当前书的指定章节正文。"
    override val parametersSchema = """
        {"type":"object","properties":{
          "chapterIndex":{"type":"integer","default":-1,"description":"-1 表示当前章节"},
          "maxChars":{"type":"integer","default":4000}
        }}
    """.trimIndent()
    override suspend fun execute(arguments: Map<String, Any?>): AiToolResult = runCatching {
        val idx = (arguments["chapterIndex"] as? Number)?.toInt() ?: chapterIndex
        val maxChars = (arguments["maxChars"] as? Number)?.toInt() ?: 4000
        val chapter = appDb.bookChapterDao.getChapter(bookUrl, idx)
            ?: return AiToolResult("chapter not found (bookUrl=$bookUrl, idx=$idx)", isError = true)
        val text = chapter.content.take(maxChars)
        AiToolResult("title=${chapter.title}\n\n$text")
    }.getOrElse { AiToolResult("error: ${it.message}", isError = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiReadingAssistantScreen(
    bookUrl: String,
    bookName: String,
    chapterIndex: Int,
    selectedText: String,
    onDismiss: () -> Unit,
) {
    val vm: ReadingAssistantViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ReadingAssistantViewModel(bookUrl, bookName, chapterIndex, selectedText) as T
        }
    )
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI 阅读助手")
                        if (bookName.isNotBlank()) {
                            Text(bookName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.provider == null) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没配置 AI provider。回 AI 设置页面添加。",
                        textAlign = TextAlign.Center
                    )
                }
                return@Column
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages, key = { it.id }) { m -> MessageBubble(m) }
            }
            state.lastToolLog.takeIf { it.isNotEmpty() }?.let { log ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "🛠 " + log.joinToString(" | "),
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            state.error?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(err, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("问点什么…") },
                        maxLines = 4,
                        enabled = !state.sending,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty() && !state.sending) {
                                input = ""
                                scope.launch { vm.send(text) }
                            }
                        },
                        enabled = !state.sending && input.isNotBlank()
                    ) { Icon(Icons.Filled.Send, contentDescription = "发送") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: UiMessage) {
    val isUser = m.role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier
        ) {
            Text(text = m.content, color = fg, modifier = Modifier.padding(12.dp))
        }
    }
}
