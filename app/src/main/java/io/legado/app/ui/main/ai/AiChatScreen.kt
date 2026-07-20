package io.legado.app.ui.main.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.AiMessage
import io.legado.app.help.ai.agent.ToolCallLog
import kotlinx.coroutines.launch

@Composable
fun AiChatScreen(vm: AiChatViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState(0)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(state.messages.size, state.streaming) {
        val totalItems = state.messages.size + (if (state.streaming != null) 1 else 0)
        if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.activeProvider == null) {
            EmptyHint("还没有 AI 提供商", "切换到「设置」标签，添加一个 OpenAI 兼容的 provider。")
            return@Column
        }

        // Top bar: conversation switch + agent mode toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.toggleConversationList() }) {
                Icon(Icons.Filled.List, contentDescription = "会话列表")
            }
            Text(
                text = state.conversation?.title ?: "新会话",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.newConversation() }) {
                Icon(Icons.Filled.Add, contentDescription = "新会话")
            }
            IconButton(onClick = { vm.toggleAgentMode() }) {
                Icon(
                    if (state.agentMode) Icons.Filled.AutoAwesome else Icons.Filled.Stream,
                    contentDescription = if (state.agentMode) "Agent 模式" else "流式模式",
                    tint = if (state.agentMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }

        // Token usage
        if (state.totalPromptTokens > 0 || state.totalCompletionTokens > 0) {
            val context = androidx.compose.ui.platform.LocalContext.current
            Text(
                text = "Tokens: ↑${state.totalPromptTokens} ↓${state.totalCompletionTokens}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val text = state.messages.joinToString("\n\n") { m ->
                                "${if (m.role == "user") "我" else "AI"}: ${m.content}"
                            }
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "导出会话"))
                        }
                    ),
            )
        }

        // Conversation list drawer
        AnimatedVisibility(visible = state.showConversationList) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.conversations, key = { it.id }) { conv ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (conv.id == state.conversation?.id)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { vm.switchConversation(conv) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = conv.title.ifBlank { "新会话" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(
                                onClick = { vm.deleteConversation(conv) },
                                modifier = Modifier.padding(0.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除会话",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(0.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.messages.isEmpty() && !state.sending) {
                item { EmptyHint("开始聊天", "输入消息，按下右箭头发送。\nAgent 模式支持工具调用。") }
            }
            items(state.messages, key = { it.id }) { m -> MessageBubble(m, onDelete = { vm.deleteMessage(m) }) }
            state.streaming?.let { stream ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.sending && stream.length < 20) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        MessageBubble(
                            AiMessage(id = "_stream", role = AiMessage.ROLE_ASSISTANT, content = stream)
                        )
                    }
                }
            }
        }

        // Tool log
        if (state.toolLog.isNotEmpty()) {
            ToolLogSection(state.toolLog)
        }

        // Error
        state.error?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = err,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = { vm.retry() }) { Text("重试") }
                }
            }
        }

        // Input
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么…") },
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

@Composable
private fun ToolLogSection(toolLog: List<ToolCallLog>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "工具调用 (${toolLog.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            toolLog.takeLast(5).forEach { log ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        text = log.call.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (log.result.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = log.result.content.take(80) + if (log.result.content.length > 80) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: AiMessage, onDelete: (AiMessage) -> Unit = {}) {
    val isUser = m.role == AiMessage.ROLE_USER
    val bg = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val context = androidx.compose.ui.platform.LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Surface(
                color = bg,
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp,
                ),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
            ) {
                Text(text = m.content, color = fg, modifier = Modifier.padding(12.dp), textAlign = TextAlign.Start)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("AI Message", m.content))
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("分享") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, m.content)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "分享"))
                        showMenu = false
                    }
                )
                if (!isUser) {
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            onDelete(m)
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
        }
    }
}
