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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.entities.AiProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(vm: AiSettingsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
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
        }

        ExtendedFloatingActionButton(
            onClick = { vm.addNew() },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("添加") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
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
    var typeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider.name.isBlank()) "添加 Provider" else "编辑 Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = it }) {
                    OutlinedTextField(
                        value = type, onValueChange = {}, readOnly = true,
                        label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    androidx.compose.material3.ExposedDropdownMenu(
                        expanded = typeMenu, onDismissRequest = { typeMenu = false }
                    ) {
                        listOf("openai", "ollama").forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = { type = t; typeMenu = false }
                            )
                        }
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
