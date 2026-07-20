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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.CharacterTtsPlayer
import io.legado.app.help.ai.CharacterTtsService
import io.legado.app.help.ai.tool.CharacterAssignment
import io.legado.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 角色朗读 Activity。读章节 → AI 标每段角色+音色 → 显示结果。
 *
 * 入口：从 AI 设置页加按钮，传入 bookUrl + chapterIndex 即可。
 * 调用：CharacterTtsReadAloudActivity.launch(context, bookUrl, chapterIndex)
 */
class CharacterTtsReadAloudActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL).orEmpty()
        val chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)
        setContent {
            AppTheme {
                CharacterTtsScreen(bookUrl, chapterIndex, onFinish = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
        const val EXTRA_CHAPTER_INDEX = "chapterIndex"
        fun launch(context: android.content.Context, bookUrl: String, chapterIndex: Int) {
            context.startActivity(
                android.content.Intent(context, CharacterTtsReadAloudActivity::class.java).apply {
                    putExtra(EXTRA_BOOK_URL, bookUrl)
                    putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                }
            )
        }
    }
}

data class CharacterTtsState(
    val provider: AiProvider? = null,
    val loading: Boolean = false,
    val assignments: List<CharacterAssignment> = emptyList(),
    val error: String? = null,
    val bookName: String = "",
    val chapterTitle: String = "",
    val currentParagraph: Int = -1,
)

class CharacterTtsViewModel(
    private val bookUrl: String,
    private val chapterIndex: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(CharacterTtsState())
    val state: StateFlow<CharacterTtsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val provider = io.legado.app.help.ai.AiRepository.instance.listEnabledProviders().firstOrNull()
            val book = appDb.bookDao.getBook(bookUrl)
            _state.update { it.copy(provider = provider, bookName = book?.name.orEmpty()) }
        }
    }

    fun analyze() {
        val provider = _state.value.provider ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val book = appDb.bookDao.getBook(bookUrl)
            if (book == null) {
                _state.update { it.copy(loading = false, error = "book not found") }
                return@launch
            }
            val r = CharacterTtsService.assign(book, chapterIndex, provider)
            r.onSuccess { ar ->
                _state.update { it.copy(
                    loading = false,
                    assignments = ar.assignments,
                    chapterTitle = ar.chapterTitle,
                ) }
            }.onFailure { t ->
                _state.update { it.copy(loading = false, error = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    fun setCurrent(idx: Int) {
        _state.update { it.copy(currentParagraph = idx) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterTtsScreen(
    bookUrl: String,
    chapterIndex: Int,
    onFinish: () -> Unit,
) {
    val vm: CharacterTtsViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                CharacterTtsViewModel(bookUrl, chapterIndex) as T
        }
    )
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("角色朗读")
                        if (state.bookName.isNotBlank()) {
                            Text(state.bookName, style = MaterialTheme.typography.labelSmall)
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
                .padding(16.dp)
        ) {
            Text(state.chapterTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (state.provider == null) {
                Text("没配置 AI provider。先去 AI 设置添加。")
                return@Column
            }
            if (state.assignments.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.loading) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("AI 正在分析每段对白（首次会读章节，需要点时间）…")
                        } else {
                            Text("点击下方按钮让 AI 标每段角色 + 推荐音色。")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { scope.launch { vm.analyze() } }) {
                                Text("开始分析")
                            }
                        }
                    }
                }
                return@Column
            }
            // 已分析完
            state.error?.let { err ->
                Text("✗ $err", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "已识别 ${state.assignments.size} 段对白。点一段可在系统 TTS 用对应音色播放（需在系统设置装好对应语音包）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.assignments, key = { it.paragraphIndex }) { a ->
                    Card(
                        elevation = CardDefaults.cardElevation(1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("第 ${a.paragraphIndex} 段 · ${a.character}", style = MaterialTheme.typography.titleSmall)
                                Text(a.text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text("音色: ${a.voice}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                vm.setCurrent(a.paragraphIndex)
                                scope.launch {
                                    CharacterTtsPlayer.play(listOf(a.text to a.voice))
                                }
                            }) { Text("播放") }
                        }
                    }
                }
            }
        }
    }
}
