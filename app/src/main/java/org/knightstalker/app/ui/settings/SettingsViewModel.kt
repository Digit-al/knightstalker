// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.prefs.UserPreferences

data class SettingsState(
    val bufferSizeMs: Int = 5000,
    val autoPlay: Boolean = true
)

class SettingsViewModel(
    private val preferences: UserPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.bufferSizeMs.collect { ms ->
                _state.update { it.copy(bufferSizeMs = ms) }
            }
        }
        viewModelScope.launch {
            preferences.autoPlay.collect { auto ->
                _state.update { it.copy(autoPlay = auto) }
            }
        }
    }

    fun setBufferSize(ms: Int) {
        viewModelScope.launch { preferences.setBufferSizeMs(ms) }
    }

    fun setAutoPlay(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoPlay(enabled) }
    }

    class Factory(
        private val preferences: UserPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(preferences) as T
        }
    }
}
