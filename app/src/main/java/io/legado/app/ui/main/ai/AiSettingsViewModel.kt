package io.legado.app.ui.main.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.AiProvider
import io.legado.app.help.ai.AiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SettingsUiState(
    val providers: List<AiProvider> = emptyList(),
    val editing: AiProvider? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val loading: Boolean = false,
)

class AiSettingsViewModel(
    private val repo: AiRepository = AiRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            _state.update { it.copy(providers = repo.listProviders(), loading = false) }
        }
    }

    fun addNew() {
        _state.update { it.copy(editing = AiProvider(id = UUID.randomUUID().toString()), testResult = null) }
    }

    fun edit(p: AiProvider) {
        _state.update { it.copy(editing = p, testResult = null) }
    }

    fun cancelEdit() {
        _state.update { it.copy(editing = null, testing = false) }
    }

    fun save(p: AiProvider) {
        viewModelScope.launch {
            repo.saveProvider(p)
            _state.update { it.copy(editing = null, providers = repo.listProviders()) }
        }
    }

    fun delete(p: AiProvider) {
        viewModelScope.launch {
            repo.deleteProvider(p.id)
            _state.update { it.copy(providers = repo.listProviders()) }
        }
    }

    fun setActive(p: AiProvider) {
        viewModelScope.launch {
            val all = repo.listProviders()
            for (other in all) {
                if (other.id != p.id && other.enabled) {
                    repo.saveProvider(other.copy(enabled = false))
                }
            }
            repo.saveProvider(p.copy(enabled = true))
            _state.update { it.copy(providers = repo.listProviders()) }
        }
    }

    fun test(p: AiProvider) {
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testResult = null) }
            val r = repo.testProvider(p)
            _state.update {
                it.copy(
                    testing = false,
                    testResult = if (r.isSuccess) "✓ 连接成功" else "✗ ${r.exceptionOrNull()?.message}"
                )
            }
        }
    }
}
