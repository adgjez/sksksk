package io.legado.app.ui.main.ai

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import io.legado.app.data.entities.AiImage
import java.io.File

@Composable
fun AiImagesScreen(vm: AiImagesViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<AiImage?>(null) }
    var showGenerateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.images.isEmpty() && !state.generating) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Image, contentDescription = null,
                    modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "还没有生成的图片", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = "点击右下角按钮生成图片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.generating) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
                items(state.images, key = { it.id }) { img ->
                    Surface(
                        onClick = { selected = img },
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        @OptIn(ExperimentalGlideComposeApi::class)
                        GlideImage(
                            model = File(img.localPath),
                            contentDescription = img.prompt,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )
                    }
                }
            }
        }

        // Error / success message
        state.error?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            ) {
                Text(
                    text = err,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            LaunchedEffect(err) {
                kotlinx.coroutines.delay(3000)
                vm.clearError()
            }
        }
        state.success?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                vm.clearError()
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showGenerateDialog = true },
            icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
            text = { Text("生成图片") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    if (showGenerateDialog) {
        GenerateImageDialog(
            onDismiss = { showGenerateDialog = false },
            onGenerate = { prompt, size ->
                vm.generateImage(prompt, size)
                showGenerateDialog = false
            },
        )
    }

    selected?.let { img ->
        AiImagePreviewDialog(
            img = img,
            onDismiss = { selected = null },
            onDelete = { vm.deleteImage(img) },
        )
    }
}

@Composable
private fun GenerateImageDialog(
    onDismiss: () -> Unit,
    onGenerate: (prompt: String, size: String) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("1024x1024") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成图片") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                Text("尺寸", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1024x1024" to "方形", "1792x1024" to "横图", "1024x1792" to "竖图").forEach { (key, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = size == key,
                            onClick = { size = key },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGenerate(prompt.trim(), size) },
                enabled = prompt.isNotBlank(),
            ) { Text("生成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
