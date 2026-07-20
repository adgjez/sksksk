package io.legado.app.ui.main.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import io.legado.app.data.entities.AiImage
import java.io.File

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AiImagePreviewDialog(
    img: AiImage,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("图片") },
        text = {
            Box(modifier = Modifier.fillMaxWidth()) {
                GlideImage(
                    model = File(img.localPath),
                    contentDescription = img.prompt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (onDelete != null) {
                IconButton(onClick = { onDelete(); onDismiss() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
        }
    )
}
