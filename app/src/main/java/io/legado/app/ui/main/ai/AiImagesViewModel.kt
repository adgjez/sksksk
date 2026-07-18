package io.legado.app.ui.main.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.AiImage
import io.legado.app.help.ai.AiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AiImagesViewModel(
    private val repo: AiRepository = AiRepository.instance,
) : ViewModel() {

    private val _images = MutableStateFlow<List<AiImage>>(emptyList())
    val images: StateFlow<List<AiImage>> = _images.asStateFlow()

    fun refresh() { viewModelScope.launch { _images.value = repo.listImages() } }
}
