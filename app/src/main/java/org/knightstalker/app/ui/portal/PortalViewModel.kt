// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.portal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.prefs.UserPreferences
import org.knightstalker.app.data.remote.ConnectionState
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.MediaIndexRepository
import org.knightstalker.app.data.repository.PortalRepository
import org.knightstalker.app.domain.model.Portal

data class PortalListState(
    val portals: List<Portal> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val error: String? = null,
    val connectedPortalId: Long? = null,
    val autoConnectTriggered: Boolean = false
)

class PortalViewModel(
    private val portalRepository: PortalRepository,
    private val authManager: StalkerAuthManager,
    private val preferences: UserPreferences,
    private val mediaIndexRepository: MediaIndexRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PortalListState())
    val state: StateFlow<PortalListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            portalRepository.portals.collect { portals ->
                _state.update { it.copy(portals = portals) }
            }
        }
        viewModelScope.launch {
            authManager.connectionState.collect { connState ->
                _state.update {
                    it.copy(
                        connectionState = connState,
                        connectedPortalId = if (connState == ConnectionState.CONNECTED) authManager.currentPortal?.id else null
                    )
                }
            }
        }
        viewModelScope.launch {
            authManager.errorMessage.collect { err ->
                _state.update { it.copy(error = err) }
            }
        }
        // Auto-connect to last used portal
        viewModelScope.launch {
            val lastId = preferences.lastPortalId.first()
            if (lastId != null && lastId > 0 && authManager.connectionState.value == ConnectionState.DISCONNECTED) {
                portalRepository.portals.first().find { it.id == lastId }?.let { portal ->
                    _state.update { it.copy(autoConnectTriggered = true) }
                    connect(portal)
                }
            }
        }
    }

    fun connect(portal: Portal) {
        viewModelScope.launch {
            val success = authManager.connect(portal)
            if (success) {
                portalRepository.markConnected(portal.id)
                preferences.setLastPortalId(portal.id)
            }
        }
    }

    fun disconnect() {
        authManager.disconnect()
    }

    fun deletePortal(portal: Portal) {
        viewModelScope.launch {
            if (authManager.currentPortal?.id == portal.id) {
                authManager.disconnect()
            }
            portalRepository.delete(portal)
            // Drop the deleted portal's local media index rows (rebuildable cache).
            mediaIndexRepository.clearPortal(portal.id)
        }
    }

    fun savePortal(name: String, url: String, mac: String, existingId: Long = 0) {
        viewModelScope.launch {
            val portal = Portal(
                id = existingId,
                name = name.ifEmpty { "Portal" },
                url = url.trim(),
                macAddress = mac.trim().uppercase()
            )
            portalRepository.save(portal)
        }
    }

    class Factory(
        private val portalRepository: PortalRepository,
        private val authManager: StalkerAuthManager,
        private val preferences: UserPreferences,
        private val mediaIndexRepository: MediaIndexRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PortalViewModel(portalRepository, authManager, preferences, mediaIndexRepository) as T
        }
    }
}
