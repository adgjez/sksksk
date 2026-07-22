package io.legado.app.ui.main.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.help.ai.agent.Agent
import io.legado.app.help.ai.skill.SkillRegistry
import io.legado.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 书源生成器 Activity。
 *
 * 用户提供：网站 URL + 书源名 + 搜索关键词
 * 后台：先激活 generate_book_source skill + 加 fetch_html / save_book_source 工具，跑 Agent
 * 显示：tool log（agent 在做什么）+ 最终的 BookSource JSON
 * 保存到 filesDir/ai_book_sources/{name}.json
 */
class BookSourceGeneratorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { BookSourceGeneratorScreen(onFinish = { finish() }) }
        }
    }
}

data class GenState(
    val url: String = "",
    val sourceName: String = "",
    val sampleKeyword: String = "",
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val resultJson: String? = null,
    val error: String? = null,
)

class BookSourceGeneratorViewModel : ViewModel() {
    private val _state = MutableStateFlow(GenState())
    val state: StateFlow<GenState> = _state.asStateFlow()

    fun update(url: String = _state.value.url, name: String = _state.value.sourceName, kw: String = _state.value.sampleKeyword) {
        _state.update { it.copy(url = url, sourceName = name, sampleKeyword = kw) }
    }

    fun generate() = viewModelScope.launch {
        val s = _state.value
        if (s.url.isBlank() || s.sourceName.isBlank()) {
            _state.update { it.copy(error = "URL 和书源名都必填") }
            return@launch
        }
        _state.update { it.copy(running = true, log = emptyList(), resultJson = null, error = null) }
        val provider = io.legado.app.help.ai.AiRepository.instance.listEnabledProviders().firstOrNull()
        if (provider == null) {
            _state.update { it.copy(running = false, error = "没配置 AI provider") }
            return@launch
        }
        val skills = SkillRegistry.instance
        // 临时激活 skill，结束后停用（try-finally 确保异常也能停用）
        val wasActive = skills.isActive("generate_book_source")
        if (!wasActive) skills.activate("generate_book_source")

        try {
            val systemPrompt = """
                你的任务：为用户给的网站生成一个 Legado (阅读) 兼容的 BookSource JSON。
                用户输入：
                  - 网站 URL: ${s.url}
                  - 书源显示名: ${s.sourceName}
                  - 示例搜索关键词: ${s.sampleKeyword}

                你可以用 fetch_html 工具抓页面分析，用 save_book_source 工具保存。
                完成后告诉用户：保存路径 + 怎么导入（base 的"书源管理 → 本地导入"）。
            """.trimIndent()

            val userMsg = io.legado.app.data.entities.AiMessage(
                id = "user-1",
                conversationId = "book_source_gen",
                role = io.legado.app.data.entities.AiMessage.ROLE_USER,
                content = "请为这个网站生成书源：${s.url}，书源名 ${s.sourceName}，示例搜索词 ${s.sampleKeyword}",
            )
            // FetchHtmlTool / SaveBookSourceTool 已在 Agent.getDefaultTools() 中，不再重复传入
            val agent = Agent()
            val r = agent.run(provider, systemPrompt, listOf(userMsg), extraTools = emptyList())
            r.onSuccess { ar ->
                val log = ar.toolLog.map { tl -> "-> ${tl.call.name}(${tl.call.arguments.entries.joinToString { "${it.key}=${it.value}" }}) -> ${tl.result.content.take(120)}" }
                _state.update {
                    it.copy(
                        running = false,
                        log = log,
                        resultJson = ar.finalText.takeIf { t -> t.contains("{") && t.contains("}") }
                    )
                }
            }.onFailure { t ->
                _state.update { it.copy(running = false, error = t.message ?: t.javaClass.simpleName) }
            }
        } finally {
            // 结束后停用临时激活的 skill
            if (!wasActive) skills.deactivate("generate_book_source")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSourceGeneratorScreen(onFinish: () -> Unit) {
    val vm: BookSourceGeneratorViewModel = viewModel()
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("AI 生成书源") },
            navigationIcon = {
                IconButton(onClick = onFinish) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.url, onValueChange = { vm.update(url = it) },
                label = { Text("网站 URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.sourceName, onValueChange = { vm.update(name = it) },
                label = { Text("书源显示名（中文）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.sampleKeyword, onValueChange = { vm.update(kw = it) },
                label = { Text("示例搜索关键词") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { scope.launch { vm.generate() } },
                enabled = !state.running && state.url.isNotBlank() && state.sourceName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).height(16.dp))
                }
                Text(if (state.running) "AI 工作中…" else "开始生成")
            }

            state.error?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(err, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            if (state.log.isNotEmpty()) {
                Text("Agent 步骤", style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.log) { line ->
                        Card(elevation = CardDefaults.cardElevation(0.dp)) {
                            Text(line, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            state.resultJson?.let { json ->
                Text("AI 输出（可复制到 base 导入）", style = MaterialTheme.typography.titleSmall)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(json, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
