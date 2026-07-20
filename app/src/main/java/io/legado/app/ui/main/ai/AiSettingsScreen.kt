package io.legado.app.ui.main.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(vm: AiSettingsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("AI 提供商", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "兼容 OpenAI Chat Completions 协议。OpenAI / 通义千问 / DeepSeek / 智谱 / Ollama 都能用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.AssistChip(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(context, io.legado.app.ui.main.ai.BookSourceGeneratorActivity::class.java)
                        )
                    },
                    label = { Text("AI 生成书源") }
                )
            }
            Spacer(Modifier.height(12.dp))

            if (state.providers.isEmpty() && !state.loading) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("点右下角 + 添加第一个 provider。", modifier = Modifier.padding(16.dp))
                }
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.providers, key = { it.id }) { p ->
                    ProviderRow(
                        provider = p,
                        onEdit = { vm.edit(p) },
                        onDelete = { scope.launch { vm.delete(p) } },
                        onSetActive = { scope.launch { vm.setActive(p) } },
                    )
                }
            }

            state.testResult?.let { msg ->
                val isError = msg.startsWith("✗")
                Surface(
                    color = if (isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            GlobalConfigSection()
        }

        ExtendedFloatingActionButton(
            onClick = { vm.addNew() },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("添加") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
        ExtendedFloatingActionButton(
            onClick = {
                context.startActivity(
                    android.content.Intent(context, io.legado.app.ui.main.ai.AiSkillManagerActivity::class.java)
                )
            },
            icon = { Icon(Icons.Filled.Build, contentDescription = null) },
            text = { Text("技能") },
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
        )
    }

    state.editing?.let { editing ->
        ProviderEditDialog(
            provider = editing,
            onDismiss = { vm.cancelEdit() },
            onSave = { p -> scope.launch { vm.save(p) } },
            onTest = { p -> scope.launch { vm.test(p) } },
            testing = state.testing,
        )
    }
}

@Composable
private fun GlobalConfigSection() {
    var showConfig by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Text("全局配置", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showConfig = !showConfig }) {
                Text(if (showConfig) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showConfig) {
            Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                var temp by remember { mutableStateOf(AppConfig.aiTemperature.toString()) }
                var maxTok by remember { mutableStateOf(AppConfig.aiMaxTokens.toString()) }
                var sysPrompt by remember { mutableStateOf(AppConfig.aiGlobalSystemPrompt) }
                var agentDefault by remember { mutableStateOf(AppConfig.aiAgentModeDefault) }
                var persist by remember { mutableStateOf(AppConfig.aiChatPersist) }

                OutlinedTextField(
                    value = temp,
                    onValueChange = {
                        temp = it
                        it.toFloatOrNull()?.let { v -> AppConfig.aiTemperature = v }
                    },
                    label = { Text("Temperature (-1=默认, 0.0~2.0)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = maxTok,
                    onValueChange = {
                        maxTok = it
                        it.toIntOrNull()?.let { v -> AppConfig.aiMaxTokens = v }
                    },
                    label = { Text("Max Tokens (0=默认)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = sysPrompt,
                    onValueChange = {
                        sysPrompt = it
                        AppConfig.aiGlobalSystemPrompt = it
                    },
                    label = { Text("全局 System Prompt 前缀") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Agent 模式默认开启", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = agentDefault, onCheckedChange = {
                        agentDefault = it
                        AppConfig.aiAgentModeDefault = it
                    })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("聊天记录持久化", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = persist, onCheckedChange = {
                        persist = it
                        AppConfig.aiChatPersist = it
                    })
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: AiProvider,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.name.ifBlank { "(未命名)" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${provider.model} · ${provider.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSetActive) {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = "设为默认",
                    tint = if (provider.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onEdit) { Text("编辑", style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditDialog(
    provider: AiProvider,
    onDismiss: () -> Unit,
    onSave: (AiProvider) -> Unit,
    onTest: (AiProvider) -> Unit,
    testing: Boolean,
) {
    var name by remember { mutableStateOf(provider.name) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    var model by remember { mutableStateOf(provider.model) }
    var type by remember { mutableStateOf(provider.type) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider.name.isBlank()) "添加 Provider" else "编辑 Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "openai" to "OpenAI 兼容",
                        "ollama" to "Ollama",
                        "anthropic" to "Claude",
                        "gemini" to "Gemini",
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = type == key,
                            onClick = {
                                // 切类型时若 URL/Model 仍是空/默认，填入对应默认
                                if (baseUrl.isBlank() || baseUrl == io.legado.app.data.entities.AiProvider.defaultBaseUrl(type)) {
                                    baseUrl = io.legado.app.data.entities.AiProvider.defaultBaseUrl(key)
                                }
                                if (model.isBlank()) {
                                    model = io.legado.app.data.entities.AiProvider.defaultModel(key)
                                }
                                type = key
                            },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("显示名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("测试中…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(provider.copy(name = name, baseUrl = baseUrl, apiKey = apiKey, model = model, type = type))
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onTest(provider.copy(name = name, baseUrl = baseUrl, apiKey = apiKey, model = model, type = type))
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("测试")
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
