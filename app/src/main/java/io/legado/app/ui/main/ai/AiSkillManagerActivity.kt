package io.legado.app.ui.main.ai

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.help.ai.skill.Skill
import io.legado.app.help.ai.skill.SkillRegistry
import io.legado.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Skill 管理屏：列出所有 skill（内置 + 自定义），可评分、激活、编辑、删除。
 * 入口：AI 设置页加按钮。
 */
class AiSkillManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AiSkillManagerScreen(onFinish = { finish() }) } }
    }
}

data class SkillManagerState(
    val active: Set<String> = emptySet(),
    val skills: List<Skill> = emptyList(),
    val editing: Skill? = null,
    val ratingFor: Skill? = null,
)

class SkillManagerViewModel : ViewModel() {
    private val skills = SkillRegistry.instance
    private val _state = MutableStateFlow(SkillManagerState())
    val state: StateFlow<SkillManagerState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val all = skills.all()
        val active = skills.active().map { it.name }.toSet()
        _state.update { it.copy(skills = all, active = active) }
    }

    fun toggleActive(name: String) = viewModelScope.launch {
        if (name in _state.value.active) skills.deactivate(name) else skills.activate(name)
        refresh()
    }

    fun startEdit(skill: Skill) = _state.update { it.copy(editing = skill) }
    fun cancelEdit() = _state.update { it.copy(editing = null) }

    fun save(skill: Skill) = viewModelScope.launch {
        skills.createSkill(skill.copy(origin = Skill.ORIGIN_USER))
        cancelEdit()
        refresh()
    }

    fun startRating(skill: Skill) = _state.update { it.copy(ratingFor = skill) }
    fun cancelRating() = _state.update { it.copy(ratingFor = null) }
    fun submitRating(name: String, rating: Int, comment: String) = viewModelScope.launch {
        skills.evaluate(name, rating, comment)
        cancelRating()
        refresh()
    }

    fun delete(name: String) = viewModelScope.launch {
        skills.deleteCustom(name)
        refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSkillManagerScreen(onFinish: () -> Unit) {
    val vm: SkillManagerViewModel = viewModel()
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("技能管理") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    vm.startEdit(Skill(
                        name = "",
                        description = "",
                        instructions = "",
                        origin = Skill.ORIGIN_USER,
                        status = Skill.STATUS_EXPERIMENTAL,
                    ))
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建") },
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)) {
            Text(
                "已激活的 skill 会注入到 agent 的 system prompt。评分 ≥ 4 自动 stable；评分 ≤ 2 且用 ≥ 3 次自动 deprecated。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.skills, key = { it.name }) { s ->
                    SkillRow(
                        skill = s,
                        active = s.name in state.active,
                        onToggle = { vm.toggleActive(s.name) },
                        onRate = { vm.startRating(s) },
                        onEdit = { if (s.origin != Skill.ORIGIN_BUILTIN) vm.startEdit(s) },
                        onDelete = { if (s.origin != Skill.ORIGIN_BUILTIN) vm.delete(s.name) },
                    )
                }
            }
        }
    }

    state.editing?.let { editing ->
        SkillEditDialog(
            skill = editing,
            onDismiss = { vm.cancelEdit() },
            onSave = { vm.save(it) },
        )
    }
    state.ratingFor?.let { s ->
        RatingDialog(
            skill = s,
            onDismiss = { vm.cancelRating() },
            onSubmit = { rating, comment -> vm.submitRating(s.name, rating, comment) },
        )
    }
}

@Composable
private fun SkillRow(
    skill: Skill,
    active: Boolean,
    onToggle: () -> Unit,
    onRate: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(skill.name, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.size(8.dp))
                        StatusChip(skill.status)
                        Spacer(Modifier.size(8.dp))
                        Text("[${skill.origin}]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "★ %.2f (%d评) · 用 %d 次".format(skill.averageRating, skill.ratingCount, skill.useCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                FilterChip(
                    selected = active,
                    onClick = onToggle,
                    label = { Text(if (active) "已激活" else "激活") }
                )
            }
            if (skill.lastEvaluation.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "上次评估: ${skill.lastEvaluation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRate) {
                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("评分")
                }
                if (onEdit != null) {
                    TextButton(onClick = onEdit) { Text("编辑") }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (color, label) = when (status) {
        Skill.STATUS_STABLE -> MaterialTheme.colorScheme.tertiaryContainer to "stable"
        Skill.STATUS_DEPRECATED -> MaterialTheme.colorScheme.errorContainer to "deprecated"
        else -> MaterialTheme.colorScheme.secondaryContainer to "experimental"
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditDialog(
    skill: Skill,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit,
) {
    var name by remember { mutableStateOf(skill.name) }
    var description by remember { mutableStateOf(skill.description) }
    var instructions by remember { mutableStateOf(skill.instructions) }
    val isNew = skill.name.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建 Skill" else "编辑 ${skill.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.lowercase().replace(" ", "_") },
                    label = { Text("名称（snake_case）") }, singleLine = true, enabled = isNew,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it },
                    label = { Text("描述（agent 看到）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = instructions, onValueChange = { instructions = it },
                    label = { Text("指令（激活后注入 system prompt）") },
                    minLines = 5, maxLines = 12,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && description.isNotBlank() && instructions.isNotBlank(),
                onClick = {
                    onSave(skill.copy(name = name, description = description, instructions = instructions))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RatingDialog(
    skill: Skill,
    onDismiss: () -> Unit,
    onSubmit: (Int, String) -> Unit,
) {
    var rating by remember { mutableStateOf(3) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("评分 ${skill.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 1..5) {
                        IconButton(onClick = { rating = i }) {
                            Icon(
                                imageVector = if (i <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "$i 星",
                                tint = if (i <= rating) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("评语（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(rating, comment) }) { Text("提交") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
