// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.knightstalker.app.domain.model.Portal

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

class StalkerAuthManager(private val api: StalkerApi) {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    var currentToken: String? = null
        private set

    var currentPortal: Portal? = null
        private set

    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect(portal: Portal): Boolean {
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        currentPortal = portal

        val handshakeResult = api.handshake(portal.url)
        if (handshakeResult.isFailure) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = handshakeResult.exceptionOrNull()?.message ?: "Handshake failed"
            return false
        }

        val token = handshakeResult.getOrNull()?.js?.token
        if (token.isNullOrEmpty()) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Invalid token received"
            return false
        }
        currentToken = token

        val profileResult = api.getProfile(portal.url)
        if (profileResult.isFailure) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Failed to load profile"
            return false
        }

        val profile = profileResult.getOrNull()?.js
        if (profile != null && profile.blocked == 1) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Account is blocked"
            return false
        }

        _connectionState.value = ConnectionState.CONNECTED
        startWatchdog(portal.url)
        return true
    }

    fun disconnect() {
        watchdogJob?.cancel()
        watchdogJob = null
        currentToken = null
        currentPortal = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _errorMessage.value = null
    }

    private fun startWatchdog(baseUrl: String) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(120_000) // 2 minutes
                try {
                    val result = api.doAuth(baseUrl)
                    if (result.isFailure) {
                        // Try re-handshake
                        val portal = currentPortal ?: break
                        val hs = api.handshake(portal.url)
                        currentToken = hs.getOrNull()?.js?.token
                    }
                } catch (_: CancellationException) {
                    break
                } catch (_: Exception) {
                    // Swallow, will retry next cycle
                }
            }
        }
    }

    fun destroy() {
        watchdogJob?.cancel()
        scope.cancel()
    }
}
