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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.ChannelRepository
import org.knightstalker.app.data.repository.VodRepository

data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val error: String? = null,
    val channelName: String = "",
    val showControls: Boolean = true
)

class PlayerViewModel(
    context: Context,
    private val channelRepository: ChannelRepository,
    private val vodRepository: VodRepository,
    private val authManager: StalkerAuthManager
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.update {
                        it.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(error = error.message) }
                }
            })
        }

    fun playChannel(cmd: String, name: String = "") {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(channelName = name, error = null, isBuffering = true) }

        viewModelScope.launch {
            channelRepository.createLink(portal.url, cmd)
                .onSuccess { url ->
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
        _state.update { it.copy(channelName = name, error = null, isBuffering = true) }

        viewModelScope.launch {
            vodRepository.createLink(portal.url, cmd)
                .onSuccess { url ->
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.play()
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, isBuffering = false) }
                }
        }
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

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    class Factory(
        private val context: Context,
        private val channelRepository: ChannelRepository,
        private val vodRepository: VodRepository,
        private val authManager: StalkerAuthManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(context, channelRepository, vodRepository, authManager) as T
        }
    }
}
