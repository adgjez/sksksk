package io.legado.app.ui.main.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.theme.AppTheme

class AiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AiRoot(onBack = { finish() })
            }
        }
    }
}

private enum class AiTab(val label: String) {
    Chat("聊天"),
    Images("图片"),
    Settings("设置"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiRoot(onBack: () -> Unit) {
    // rememberSaveable 让 tab 选择在屏幕旋转 / 进程恢复后保持
    var currentOrdinal by rememberSaveable { mutableIntStateOf(AiTab.Chat.ordinal) }
    val current = AiTab.entries.getOrElse(currentOrdinal) { AiTab.Chat }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI · ${current.label}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AiTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { currentOrdinal = tab.ordinal },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AiTab.Chat -> Icons.Filled.Chat
                                    AiTab.Images -> Icons.Filled.Image
                                    AiTab.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (current) {
                AiTab.Chat -> AiChatScreen()
                AiTab.Images -> AiImagesScreen()
                AiTab.Settings -> AiSettingsScreen()
            }
        }
    }
}
