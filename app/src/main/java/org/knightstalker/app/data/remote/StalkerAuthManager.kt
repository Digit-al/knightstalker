// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.knightstalker.app.data.remote.parser.M3uChannel
import org.knightstalker.app.data.remote.parser.M3uParser
import org.knightstalker.app.domain.model.Portal
import org.knightstalker.app.domain.model.PortalType
import okhttp3.Request

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

/**
 * Manages the active portal connection for ALL source types.
 *
 *  - STALKER: handshake + token + profile (original behaviour).
 *  - M3U:    downloads and parses the playlist once (kept in [m3uChannels]).
 *  - XTREAM: verifies credentials against `player_api.php` and keeps the
 *            stream list / categories in memory (kept in [xtreamStreams] /
 *            [xtreamCategories]).
 *
 * For M3U/XTREAM there is no Stalker token: [currentToken] stays null, which
 * automatically disables the Stalker auth/MAC interceptors' headers' usefulness
 * (they are harmless — no Stalker API calls are made for these portal types).
 */
class StalkerAuthManager(
    private val api: StalkerApi,
    private val xtreamApi: XtreamCodesApi,
    private val m3uParser: M3uParser = M3uParser()
) {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    var currentToken: String? = null
        private set

    var currentPortal: Portal? = null
        private set

    /** Parsed M3U channels when the active portal is an M3U playlist. */
    var m3uChannels: List<M3uChannel> = emptyList()
        private set

    /** Xtream live stream list when the active portal is Xtream Codes. */
    var xtreamStreams: List<XtreamCodesApi.StreamInfo> = emptyList()
        private set

    /** Xtream category list when the active portal is Xtream Codes. */
    var xtreamCategories: List<XtreamCodesApi.CategoryInfo> = emptyList()
        private set

    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect(portal: Portal): Boolean {
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        currentPortal = portal

        // Reset per-type caches
        currentToken = null
        m3uChannels = emptyList()
        xtreamStreams = emptyList()
        xtreamCategories = emptyList()

        val ok = when (portal.type) {
            PortalType.STALKER -> connectStalker(portal)
            PortalType.M3U -> connectM3u(portal)
            PortalType.XTREAM -> connectXtream(portal)
        }

        if (ok) {
            _connectionState.value = ConnectionState.CONNECTED
            Log.d("StalkerAuth", "Connection state set to CONNECTED, portal=${portal.name} (${portal.type})")
            if (portal.type == PortalType.STALKER) startWatchdog(portal.url)
        }
        return ok
    }

    // ------------------------------------------------------------------ STALKER

    private suspend fun connectStalker(portal: Portal): Boolean {
        api.resolveApiBase(portal.url)
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

        // Authenticate before making further API calls
        val authResult = api.doAuth(portal.url)
        Log.d("StalkerAuth", "do_auth result: ${authResult.isSuccess}")
        return true
    }

    // --------------------------------------------------------------------- M3U

    private suspend fun connectM3u(portal: Portal): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = portal.url.trim()
            val request = Request.Builder().url(url).get().build()
            val text = api.executeRaw(request).use { response ->
                if (!response.isSuccessful) {
                    _connectionState.value = ConnectionState.ERROR
                    _errorMessage.value = "M3U: HTTP ${response.code} ${response.message}"
                    return@withContext false
                }
                response.body?.string() ?: ""
            }
            if (text.isBlank()) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "Empty M3U playlist"
                return@withContext false
            }
            val channels = m3uParser.parse(text)
            if (channels.isEmpty()) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "No channels found in M3U playlist"
                return@withContext false
            }
            m3uChannels = channels
            Log.d("StalkerAuth", "M3U connected: ${channels.size} channels from $url")
            true
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "M3U: ${e.message}"
            false
        }
    }

    // -------------------------------------------------------------------- XTREAM

    private suspend fun connectXtream(portal: Portal): Boolean {
        val username = portal.username
        val password = portal.password
        if (username.isBlank() || password.isBlank()) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Xtream: username and password are required"
            return false
        }

        val result = xtreamApi.getLiveStreams(portal.url, username, password)
        result
            .onSuccess { streams ->
                xtreamStreams = streams
                xtreamCategories = xtreamApi.getCategories(portal.url, username, password)
                    .getOrNull() ?: emptyList()
                Log.d("StalkerAuth", "Xtream connected: ${streams.size} streams, ${xtreamCategories.size} categories")
            }
            .onFailure { e ->
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "Xtream: ${e.message}"
            }
        return result.isSuccess
    }

    // --------------------------------------------------------------- lifecycle

    /**
     * Re-perform the handshake for the active STALKER portal and update
     * [currentToken] in place so the existing auth interceptor immediately picks
     * up the fresh token. Used by the sync engine to recover from a token expiry
     * mid-crawl. Returns true on success.
     */
    suspend fun reHandshake(): Boolean {
        val portal = currentPortal ?: return false
        if (portal.type != PortalType.STALKER) return false
        return try {
            val hs = api.handshake(portal.url)
            val token = hs.getOrNull()?.js?.token
            if (token.isNullOrEmpty()) {
                false
            } else {
                currentToken = token
                api.doAuth(portal.url)
                true
            }
        } catch (e: Exception) {
            Log.w("StalkerAuth", "reHandshake failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        watchdogJob?.cancel()
        watchdogJob = null
        currentToken = null
        currentPortal = null
        m3uChannels = emptyList()
        xtreamStreams = emptyList()
        xtreamCategories = emptyList()
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
