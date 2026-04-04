// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.ChannelRepository
import org.knightstalker.app.data.repository.DownloadRepository
import org.knightstalker.app.data.repository.DownloadStatus
import org.knightstalker.app.data.repository.VodRepository
import org.knightstalker.app.data.repository.WatchHistoryRepository

data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val error: String? = null,
    val channelName: String = "",
    val showControls: Boolean = true,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadComplete: Boolean = false,
    val downloadError: String? = null,
    val currentStreamUrl: String? = null,
    val resumePositionMs: Long = 0,
    val showResumePrompt: Boolean = false
)

class PlayerViewModel(
    context: Context,
    private val channelRepository: ChannelRepository,
    private val vodRepository: VodRepository,
    private val authManager: StalkerAuthManager,
    private val downloadRepository: DownloadRepository,
    private val watchHistoryRepository: WatchHistoryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var currentItemId: String = ""
    private var currentCmd: String = ""
    private var currentType: String = ""
    private var currentLogo: String? = null
    private var positionSaveJob: Job? = null

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                    if (!isPlaying) {
                        saveCurrentPosition()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.update {
                        it.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        markFinished()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(error = error.message) }
                }
            })
        }

    init {
        viewModelScope.launch {
            downloadRepository.downloads.collect { downloads ->
                val current = downloads.find { it.name == _state.value.channelName }
                if (current != null) {
                    _state.update {
                        it.copy(
                            isDownloading = current.status == DownloadStatus.DOWNLOADING,
                            downloadProgress = current.progress,
                            downloadComplete = current.status == DownloadStatus.COMPLETED,
                            downloadError = current.error
                        )
                    }
                }
            }
        }
        // Periodically save position while playing
        startPositionTracker()
    }

    fun playChannel(cmd: String, name: String = "") {
        val portal = authManager.currentPortal ?: return
        currentItemId = cmd.hashCode().toString()
        currentCmd = cmd
        currentType = "itv"
        _state.update { it.copy(channelName = name, error = null, isBuffering = true) }

        viewModelScope.launch {
            channelRepository.createLink(portal.url, cmd)
                .onSuccess { url ->
                    _state.update { it.copy(currentStreamUrl = url) }
                    // Check for resume position
                    val history = watchHistoryRepository.getEntry(currentItemId, portal.id)
                    if (history != null && !history.isFinished && history.positionMs > 5000) {
                        _state.update {
                            it.copy(resumePositionMs = history.positionMs, showResumePrompt = true)
                        }
                    }
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.play()
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, isBuffering = false) }
                }
        }
    }

    fun playVod(cmd: String, name: String = "") {
        val portal = authManager.currentPortal ?: return
        currentItemId = cmd.hashCode().toString()
        currentCmd = cmd
        currentType = "vod"
        _state.update { it.copy(channelName = name, error = null, isBuffering = true) }

        viewModelScope.launch {
            vodRepository.createLink(portal.url, cmd)
                .onSuccess { url ->
                    _state.update { it.copy(currentStreamUrl = url) }
                    // Check for resume position
                    val history = watchHistoryRepository.getEntry(currentItemId, portal.id)
                    if (history != null && !history.isFinished && history.positionMs > 5000) {
                        _state.update {
                            it.copy(resumePositionMs = history.positionMs, showResumePrompt = true)
                        }
                    }
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.play()
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, isBuffering = false) }
                }
        }
    }

    fun resumeFromHistory() {
        val pos = _state.value.resumePositionMs
        if (pos > 0) {
            player.seekTo(pos)
        }
        _state.update { it.copy(showResumePrompt = false) }
    }

    fun startFromBeginning() {
        player.seekTo(0)
        _state.update { it.copy(showResumePrompt = false) }
    }

    fun downloadCurrent() {
        val url = _state.value.currentStreamUrl ?: return
        val name = _state.value.channelName.ifEmpty { "download_${System.currentTimeMillis()}" }
        _state.update { it.copy(isDownloading = true, downloadError = null, downloadComplete = false) }

        downloadRepository.startDownload(
            id = "${name}_${System.currentTimeMillis()}",
            name = name,
            streamUrl = url
        )
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekForward() {
        player.seekTo(player.currentPosition + 15000)
    }

    fun seekBackward() {
        player.seekTo((player.currentPosition - 15000).coerceAtLeast(0))
    }

    fun setShowControls(show: Boolean) {
        _state.update { it.copy(showControls = show) }
    }

    fun retry() {
        player.prepare()
        player.play()
        _state.update { it.copy(error = null) }
    }

    private fun startPositionTracker() {
        positionSaveJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000) // Save every 10 seconds
                if (player.isPlaying && player.currentPosition > 0) {
                    saveCurrentPosition()
                }
            }
        }
    }

    private fun saveCurrentPosition() {
        val portal = authManager.currentPortal ?: return
        if (currentItemId.isEmpty()) return
        val position = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)
        if (position <= 0) return

        viewModelScope.launch {
            watchHistoryRepository.savePosition(
                itemId = currentItemId,
                portalId = portal.id,
                name = _state.value.channelName,
                logo = currentLogo,
                cmd = currentCmd,
                type = currentType,
                positionMs = position,
                durationMs = duration
            )
        }
    }

    private fun markFinished() {
        val portal = authManager.currentPortal ?: return
        if (currentItemId.isEmpty()) return
        viewModelScope.launch {
            watchHistoryRepository.markFinished(currentItemId, portal.id)
        }
    }

    override fun onCleared() {
        saveCurrentPosition()
        positionSaveJob?.cancel()
        player.release()
        super.onCleared()
    }

    class Factory(
        private val context: Context,
        private val channelRepository: ChannelRepository,
        private val vodRepository: VodRepository,
        private val authManager: StalkerAuthManager,
        private val downloadRepository: DownloadRepository,
        private val watchHistoryRepository: WatchHistoryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(context, channelRepository, vodRepository, authManager, downloadRepository, watchHistoryRepository) as T
        }
    }
}
