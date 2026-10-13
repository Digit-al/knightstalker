// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.prefs.UserPreferences
import org.knightstalker.app.data.local.sync.MediaKind
import org.knightstalker.app.data.local.sync.SyncEngine
import org.knightstalker.app.data.remote.ConnectionState
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.MediaIndexRepository
import org.knightstalker.app.data.repository.PortalRepository
import org.knightstalker.app.data.repository.VodRepository
import org.knightstalker.app.data.local.prefs.UserPreferences.Companion.DEFAULT_CATEGORY_FALLBACK
import org.knightstalker.app.domain.model.Portal
import org.knightstalker.app.domain.model.PortalType

data class SettingsState(
    val bufferSizeMs: Int = 5000,
    val autoPlay: Boolean = true,
    val defaultCategory: String = DEFAULT_CATEGORY_FALLBACK,
    val categories: List<String> = emptyList(),
    val connectedPortal: Portal? = null,
    val portalUrl: String = "",
    val portalMac: String = "",
    val portalName: String = "",
    val portalType: PortalType = PortalType.STALKER,
    val portalUsername: String = "",
    val portalPassword: String = "",
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val importSuccess: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    // --- Library (local media index) ---
    val autoUpdateOnOpen: Boolean = true,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val movieStatus: String = "IDLE",
    val seriesStatus: String = "IDLE",
    val lastSyncAt: Long = 0L,
    val xtreamRefreshHours: Int = UserPreferences.DEFAULT_XTREAM_REFRESH_HOURS
)

class SettingsViewModel(
    private val preferences: UserPreferences,
    private val authManager: StalkerAuthManager,
    private val portalRepository: PortalRepository,
    private val vodRepository: VodRepository,
    private val mediaIndexRepository: MediaIndexRepository,
    private val syncEngine: SyncEngine
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
            preferences.defaultCategory.collect { cat ->
                _state.update { it.copy(defaultCategory = cat) }
            }
        }
        viewModelScope.launch {
            authManager.connectionState.collect { connState ->
                if (connState == ConnectionState.CONNECTED && _state.value.categories.isEmpty()) {
                    val portal = authManager.currentPortal ?: return@collect
                    vodRepository.getCategories(portal.url).onSuccess { cats ->
                        _state.update { it.copy(categories = cats.map { c -> c.title }) }
                    }
                }
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
                        portalName = portal?.name ?: it.portalName,
                        portalType = portal?.type ?: it.portalType,
                        portalUsername = portal?.username ?: it.portalUsername,
                        portalPassword = portal?.password ?: it.portalPassword
                    )
                }
            }
        }
        observeLibrary()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLibrary() {
        viewModelScope.launch {
            preferences.autoUpdateOnOpen.collect { v ->
                _state.update { it.copy(autoUpdateOnOpen = v) }
            }
        }
        viewModelScope.launch {
            preferences.xtreamRefreshIntervalHours.collect { h ->
                _state.update { it.copy(xtreamRefreshHours = h) }
            }
        }
        // Item counts follow the active portal (re-subscribe when it changes).
        viewModelScope.launch {
            authManager.connectionState.flatMapLatest {
                val pid = authManager.currentPortal?.id ?: return@flatMapLatest flowOf(0)
                mediaIndexRepository.observeMovieCount(pid)
            }.collect { c -> _state.update { it.copy(movieCount = c) } }
        }
        viewModelScope.launch {
            authManager.connectionState.flatMapLatest {
                val pid = authManager.currentPortal?.id ?: return@flatMapLatest flowOf(0)
                mediaIndexRepository.observeSeriesCount(pid)
            }.collect { c -> _state.update { it.copy(seriesCount = c) } }
        }
        // Index status + last full-sync time (sync-state is keyed by kind, not portal).
        viewModelScope.launch {
            syncEngine.status(MediaKind.MOVIE).collect { st ->
                _state.update {
                    it.copy(
                        movieStatus = st?.status ?: "IDLE",
                        lastSyncAt = maxOf(it.lastSyncAt, st?.lastFullSyncAt ?: 0L)
                    )
                }
            }
        }
        viewModelScope.launch {
            syncEngine.status(MediaKind.SERIES).collect { st ->
                _state.update {
                    it.copy(
                        seriesStatus = st?.status ?: "IDLE",
                        lastSyncAt = maxOf(it.lastSyncAt, st?.lastFullSyncAt ?: 0L)
                    )
                }
            }
        }
    }

    fun setAutoUpdateOnOpen(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoUpdateOnOpen(enabled) }
    }

    fun setXtreamRefreshHours(hours: Int) {
        viewModelScope.launch { preferences.setXtreamRefreshIntervalHours(hours) }
    }

    fun rebuildLibrary() {
        syncEngine.rebuildLibrary()
    }

    fun setBufferSize(ms: Int) {
        viewModelScope.launch { preferences.setBufferSizeMs(ms) }
    }

    fun setAutoPlay(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoPlay(enabled) }
    }

    fun setDefaultCategory(name: String) {
        viewModelScope.launch { preferences.setDefaultCategory(name) }
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

    fun updatePortalUsername(username: String) {
        _state.update { it.copy(portalUsername = username, saveSuccess = false) }
    }

    fun updatePortalPassword(password: String) {
        _state.update { it.copy(portalPassword = password, saveSuccess = false) }
    }

    fun updatePortalName(name: String) {
        _state.update { it.copy(portalName = name, saveSuccess = false) }
    }

    fun saveAndReconnect() {
        val current = _state.value
        val portal = current.connectedPortal ?: return
        val newUrl = current.portalUrl.trim()
        val newName = current.portalName.ifBlank { portal.name }

        // Type-specific validation
        when (portal.type) {
            PortalType.STALKER -> {
                val newMac = current.portalMac.trim().uppercase()
                if (newUrl.isBlank() || !newMac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))) return
            }
            PortalType.M3U -> {
                if (newUrl.isBlank()) return
            }
            PortalType.XTREAM -> {
                if (newUrl.isBlank() || current.portalUsername.isBlank() || current.portalPassword.isBlank()) return
            }
        }

        _state.update { it.copy(isSaving = true, saveSuccess = false) }

        viewModelScope.launch {
            val updated = portal.copy(
                url = newUrl,
                macAddress = portal.type.let {
                    if (it == PortalType.STALKER) current.portalMac.trim().uppercase() else portal.macAddress
                },
                name = newName,
                username = if (portal.type == PortalType.XTREAM) current.portalUsername.trim() else portal.username,
                password = if (portal.type == PortalType.XTREAM) current.portalPassword else portal.password
            )
            portalRepository.save(updated)
            authManager.disconnect()
            val success = authManager.connect(updated)
            _state.update { it.copy(isSaving = false, saveSuccess = success) }
        }
    }

    fun importPortal(name: String, url: String, mac: String) {
        viewModelScope.launch {
            val portal = Portal(name = name, url = url, macAddress = mac)
            portalRepository.save(portal)
            _state.update { it.copy(importSuccess = true) }
        }
    }

    class Factory(
        private val preferences: UserPreferences,
        private val authManager: StalkerAuthManager,
        private val portalRepository: PortalRepository,
        private val vodRepository: VodRepository,
        private val mediaIndexRepository: MediaIndexRepository,
        private val syncEngine: SyncEngine
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                preferences, authManager, portalRepository, vodRepository,
                mediaIndexRepository, syncEngine
            ) as T
        }
    }
}
