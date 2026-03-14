// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.prefs.UserPreferences
import org.knightstalker.app.data.remote.ConnectionState
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.PortalRepository
import org.knightstalker.app.domain.model.Portal

data class SettingsState(
    val bufferSizeMs: Int = 5000,
    val autoPlay: Boolean = true,
    val connectedPortal: Portal? = null,
    val portalUrl: String = "",
    val portalMac: String = "",
    val portalName: String = "",
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED
)

class SettingsViewModel(
    private val preferences: UserPreferences,
    private val authManager: StalkerAuthManager,
    private val portalRepository: PortalRepository
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
        viewModelScope.launch {
            authManager.connectionState.collect { connState ->
                val portal = authManager.currentPortal
                _state.update {
                    it.copy(
                        connectionState = connState,
                        connectedPortal = portal,
                        portalUrl = portal?.url ?: it.portalUrl,
                        portalMac = portal?.macAddress ?: it.portalMac,
                        portalName = portal?.name ?: it.portalName
                    )
                }
            }
        }
    }

    fun setBufferSize(ms: Int) {
        viewModelScope.launch { preferences.setBufferSizeMs(ms) }
    }

    fun setAutoPlay(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoPlay(enabled) }
    }

    fun updatePortalUrl(url: String) {
        _state.update { it.copy(portalUrl = url, saveSuccess = false) }
    }

    fun updatePortalMac(mac: String) {
        val cleaned = mac.uppercase().filter { it.isLetterOrDigit() || it == ':' }
        if (cleaned.length <= 17) {
            _state.update { it.copy(portalMac = cleaned, saveSuccess = false) }
        }
    }

    fun updatePortalName(name: String) {
        _state.update { it.copy(portalName = name, saveSuccess = false) }
    }

    fun saveAndReconnect() {
        val current = _state.value
        val portal = current.connectedPortal ?: return
        val newUrl = current.portalUrl.trim()
        val newMac = current.portalMac.trim().uppercase()
        val newName = current.portalName.ifBlank { portal.name }

        if (newUrl.isBlank() || !newMac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))) return

        _state.update { it.copy(isSaving = true, saveSuccess = false) }

        viewModelScope.launch {
            val updated = portal.copy(url = newUrl, macAddress = newMac, name = newName)
            portalRepository.save(updated)
            authManager.disconnect()
            val success = authManager.connect(updated)
            _state.update { it.copy(isSaving = false, saveSuccess = success) }
        }
    }

    class Factory(
        private val preferences: UserPreferences,
        private val authManager: StalkerAuthManager,
        private val portalRepository: PortalRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(preferences, authManager, portalRepository) as T
        }
    }
}
