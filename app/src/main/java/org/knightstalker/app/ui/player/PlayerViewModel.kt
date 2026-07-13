// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
    // Start hidden — the player begins playback clean; controls appear only on demand
    // (tap / any remote key), then auto-hide again after a few seconds.
    val showControls: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadComplete: Boolean = false,
    val downloadError: String? = null,
    val currentStreamUrl: String? = null,
    val resumePositionMs: Long = 0,
    val showResumePrompt: Boolean = false,
    // Embedded audio / subtitle tracks (consistent for films, series and downloads).
    val subtitleTracks: List<TrackOption> = emptyList(),
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    // When a series episode finishes and a following one exists.
    val showNextEpisodePrompt: Boolean = false,
    val nextEpisodeNumber: Int? = null
)

/** A selectable audio or subtitle track. [groupIndex] indexes into the player's
 *  current [Tracks.groups]; [trackIndex] the track within that group. */
data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean
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
    // Portal that owns the current item's watch-history row. Local (downloaded)
    // files use LOCAL_PORTAL_ID so their resume position is kept independently of
    // any connected portal — downloads are meant to play offline.
    private var currentPortalId: Long = LOCAL_PORTAL_ID
    private var positionSaveJob: Job? = null
    // Latest track info from the player, used to resolve track selections.
    private var tracksRef: Tracks = Tracks.EMPTY
    // Series-episode context, for "play next episode" when one ends.
    private var currentEpisode: Int = 0
    private var currentEpisodes: List<Int> = emptyList()
    private var currentSeriesName: String = ""

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
                        proposeNextEpisode()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(error = error.message) }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    tracksRef = tracks
                    val subs = mutableListOf<TrackOption>()
                    val auds = mutableListOf<TrackOption>()
                    tracks.groups.forEachIndexed { gi, group ->
                        for (ti in 0 until group.length) {
                            if (!group.isTrackSupported(ti)) continue
                            val fmt = group.getTrackFormat(ti)
                            val selected = group.isTrackSelected(ti)
                            when (group.type) {
                                C.TRACK_TYPE_TEXT ->
                                    subs.add(TrackOption(gi, ti, trackLabel(fmt.language, fmt.label, subs.size, "Subtitle"), selected))
                                C.TRACK_TYPE_AUDIO ->
                                    auds.add(TrackOption(gi, ti, trackLabel(fmt.language, fmt.label, auds.size, "Audio"), selected))
                            }
                        }
                    }
                    _state.update {
                        it.copy(
                            subtitleTracks = subs,
                            audioTracks = auds,
                            subtitlesEnabled = subs.any { s -> s.selected }
                        )
                    }
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
        currentPortalId = portal.id
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

    fun playSeries(cmd: String, name: String = "") {
        val portal = authManager.currentPortal ?: return
        currentItemId = cmd.hashCode().toString()
        currentCmd = cmd
        currentType = "series"
        currentPortalId = portal.id
        _state.update { it.copy(channelName = name, error = null, isBuffering = true) }

        viewModelScope.launch {
            vodRepository.createSeriesLink(portal.url, cmd)
                .onSuccess { url ->
                    _state.update { it.copy(currentStreamUrl = url) }
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
        currentPortalId = portal.id
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

    fun playLocal(path: String, name: String = "") {
        // Local file playback — no portal link needed. Keyed on the file path under
        // LOCAL_PORTAL_ID so the resume position survives offline and across sessions.
        currentItemId = "local:$path"
        currentCmd = path
        currentType = "local"
        currentPortalId = LOCAL_PORTAL_ID
        currentLogo = null
        _state.update { it.copy(channelName = name, error = null, isBuffering = true) }

        val file = java.io.File(path)
        if (!file.exists()) {
            _state.update { it.copy(error = "File not found", isBuffering = false) }
            return
        }

        viewModelScope.launch {
            val history = watchHistoryRepository.getEntry(currentItemId, currentPortalId)
            if (history != null && !history.isFinished && history.positionMs > 5000) {
                _state.update {
                    it.copy(resumePositionMs = history.positionMs, showResumePrompt = true)
                }
            }
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
    }

    /** Play a single series episode, resolved from its season cmd + episode number.
     *  [episodes] is the season's full episode list, used to offer the next one. */
    fun playEpisode(seasonCmd: String, episode: Int, name: String = "", episodes: List<Int> = emptyList()) {
        val portal = authManager.currentPortal ?: return
        currentItemId = "episode:$seasonCmd:$episode"
        currentCmd = seasonCmd
        currentType = "vod"
        currentPortalId = portal.id
        currentLogo = null
        currentEpisode = episode
        if (episodes.isNotEmpty()) currentEpisodes = episodes
        if (name.isNotBlank()) currentSeriesName = name
        val title = if (currentSeriesName.isBlank()) "Episode $episode" else "$currentSeriesName • Ep $episode"
        _state.update {
            it.copy(channelName = title, error = null, isBuffering = true, showNextEpisodePrompt = false, nextEpisodeNumber = null)
        }

        viewModelScope.launch {
            vodRepository.createEpisodeLink(portal.url, seasonCmd, episode)
                .onSuccess { url ->
                    _state.update { it.copy(currentStreamUrl = url) }
                    val history = watchHistoryRepository.getEntry(currentItemId, currentPortalId)
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

    /** Seek to an absolute position (used by the scrubber / progress bar). */
    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    // On an episode ending, surface a prompt to play the next one if it exists.
    private fun proposeNextEpisode() {
        if (!currentItemId.startsWith("episode:")) return
        val next = currentEpisodes.firstOrNull { it > currentEpisode } ?: return
        _state.update { it.copy(showNextEpisodePrompt = true, nextEpisodeNumber = next) }
    }

    fun playNextEpisode() {
        val next = _state.value.nextEpisodeNumber ?: return
        playEpisode(currentCmd, next, currentSeriesName, currentEpisodes)
    }

    fun dismissNextEpisode() {
        _state.update { it.copy(showNextEpisodePrompt = false) }
    }

    /** Select a subtitle track, or null to turn subtitles off. */
    fun selectSubtitle(option: TrackOption?) {
        val params = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            val group = tracksRef.groups.getOrNull(option.groupIndex) ?: return
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = params.build()
    }

    /** Select an audio track. */
    fun selectAudio(option: TrackOption) {
        val group = tracksRef.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    // Human-friendly label: explicit label, else language display name, else index.
    private fun trackLabel(language: String?, label: String?, index: Int, kind: String): String {
        label?.takeIf { it.isNotBlank() }?.let { return it }
        language?.takeIf { it.isNotBlank() && it != "und" }?.let { lang ->
            val display = java.util.Locale(lang).displayLanguage
            return if (display.isNotBlank() && !display.equals(lang, ignoreCase = true)) display
            else lang.uppercase()
        }
        return "$kind ${index + 1}"
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
        if (currentItemId.isEmpty()) return
        val position = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)
        if (position <= 0) return
        val portalId = currentPortalId

        viewModelScope.launch {
            watchHistoryRepository.savePosition(
                itemId = currentItemId,
                portalId = portalId,
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
        if (currentItemId.isEmpty()) return
        val portalId = currentPortalId
        viewModelScope.launch {
            watchHistoryRepository.markFinished(currentItemId, portalId)
        }
    }

    override fun onCleared() {
        saveCurrentPosition()
        positionSaveJob?.cancel()
        player.release()
        super.onCleared()
    }

    companion object {
        /** Sentinel portal id for locally-downloaded files (real portals start at 1). */
        const val LOCAL_PORTAL_ID = 0L
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
