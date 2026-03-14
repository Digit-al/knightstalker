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
import org.knightstalker.app.data.repository.PortalRepository
import org.knightstalker.app.domain.model.Portal

data class PortalListState(
    val portals: List<Portal> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val error: String? = null,
    val connectedPortalId: Long? = null
)

class PortalViewModel(
    private val portalRepository: PortalRepository,
    private val authManager: StalkerAuthManager,
    private val preferences: UserPreferences
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
        private val preferences: UserPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PortalViewModel(portalRepository, authManager, preferences) as T
        }
    }
}
