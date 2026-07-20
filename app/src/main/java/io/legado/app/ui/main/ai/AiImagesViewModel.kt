package io.legado.app.ui.main.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.AiImage
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.AiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class AiImagesUiState(
    val images: List<AiImage> = emptyList(),
    val generating: Boolean = false,
    val error: String? = null,
    val success: String? = null,
)

class AiImagesViewModel(
    private val repo: AiRepository = AiRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow(AiImagesUiState())
    val state: StateFlow<AiImagesUiState> = _state.asStateFlow()

    val images get() = _state.value.images

    fun refresh() {
        viewModelScope.launch { _state.update { it.copy(images = repo.listImages()) } }
    }

    fun generateImage(prompt: String, size: String = "1024x1024") {
        viewModelScope.launch {
            val provider = repo.listEnabledProviders().firstOrNull()
            if (provider == null) {
                _state.update { it.copy(error = "请先在设置中配置 AI 提供商") }
                return@launch
            }
            _state.update { it.copy(generating = true, error = null, success = null) }
            val result = repo.generateImage(provider, prompt, size)
            result.onSuccess { newImages ->
                _state.update {
                    it.copy(
                        images = newImages + it.images,
                        generating = false,
                        success = "生成成功",
                    )
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(
                        generating = false,
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null, success = null) }
    }

    fun deleteImage(img: AiImage) {
        viewModelScope.launch {
            runCatching { File(img.localPath).delete() }
            repo.deleteImage(img.id)
            _state.update { it.copy(images = it.images.filterNot { i -> i.id == img.id }) }
        }
    }
}
