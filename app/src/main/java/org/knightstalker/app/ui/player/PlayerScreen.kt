// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.player

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.knightstalker.app.domain.model.EpisodeRef
import org.knightstalker.app.ui.theme.Accent
import org.knightstalker.app.ui.theme.SuccessGreen
import org.knightstalker.app.ui.theme.TextPrimary

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    type: String,
    cmd: String,
    name: String,
    ext: String = "",
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    // Live playback position for the seek bar. Polled from the player rather than
    // pushed through state so scrubbing stays smooth and cheap.
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var seekable by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf(0L) }
    var showSubsMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    val menuOpen = showSubsMenu || showAudioMenu
    // Bumped on every remote interaction so the auto-hide timer restarts.
    var interactionNudge by remember { mutableStateOf(0) }
    val wake: () -> Unit = { viewModel.setShowControls(true); interactionNudge++ }

    // Focus targets: the root grabs keys while controls are hidden; on reveal we
    // move focus to the seek bar (so LEFT/RIGHT scrubs) or play/pause otherwise.
    val rootFocus = remember { FocusRequester() }
    val sliderFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(cmd) {
        when (type) {
            "local" -> viewModel.playLocal(cmd, name)
            "vod" -> viewModel.playVod(cmd, name, ext)
            "episode" -> {
                // cmd is "<seasonCmd>|<episode>|<ep1,ep2,...>[|<episodeId>|<ext>]"
                // (season cmd is base64, no pipe). The last two parts are optional so
                // legacy history entries ("<seasonCmd>|<episode>") still resolve via
                // the repository's episode ref cache.
                val parts = cmd.split("|")
                if (parts.size >= 2) {
                    val eps = parts.getOrNull(2)?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
                    val epId = parts.getOrNull(3)
                    val epExt = parts.getOrNull(4)
                    val episodeRef = if (epId.isNullOrBlank()) null
                    else EpisodeRef(episodeId = epId, ext = (epExt ?: "").ifBlank { "mp4" }, title = "")
                    viewModel.playEpisode(parts[0], parts[1].toIntOrNull() ?: 1, name, eps, episodeRef)
                }
            }
            "series" -> viewModel.playSeries(cmd, name)
            else -> viewModel.playChannel(cmd, name)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (!scrubbing) {
                positionMs = viewModel.player.currentPosition.coerceAtLeast(0)
                val d = viewModel.player.duration
                durationMs = if (d > 0) d else 0
                seekable = viewModel.player.isCurrentMediaItemSeekable
            }
            delay(500)
        }
    }

    // Focus management: root when hidden, seek bar / play when the controls show.
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(state.showControls) {
        if (state.showControls) {
            delay(80)
            val target = if (seekable && durationMs > 0) sliderFocus else playFocus
            runCatching { target.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Auto-hide controls (but not while scrubbing or a menu is open); restarts on
    // each interaction.
    LaunchedEffect(state.showControls, scrubbing, menuOpen, interactionNudge) {
        if (state.showControls && !scrubbing && !menuOpen) {
            delay(5000)
            viewModel.setShowControls(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev -> handlePlayerKey(ev, state.showControls, viewModel, wake) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (state.showControls) viewModel.setShowControls(false) else wake()
            }
    ) {
        // Video surface (renders embedded subtitle cues via its SubtitleView).
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.player
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Resume prompt
        if (state.showResumePrompt) {
            AlertDialog(
                onDismissRequest = { viewModel.startFromBeginning() },
                title = { Text("Resume Playback") },
                text = {
                    val minutes = state.resumePositionMs / 60000
                    val seconds = (state.resumePositionMs % 60000) / 1000
                    Text("Resume from ${minutes}m ${seconds}s?")
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resumeFromHistory() }) {
                        Text("Resume", color = Accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.startFromBeginning() }) {
                        Text("Start Over")
                    }
                }
            )
        }

        // Next-episode prompt (series): appears when an episode ends.
        if (state.showNextEpisodePrompt && state.nextEpisodeNumber != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissNextEpisode() },
                title = { Text("Episode finished") },
                text = { Text("Play the next episode (Ep ${state.nextEpisodeNumber})?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.playNextEpisode() }) {
                        Text("Play next", color = Accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissNextEpisode() }) {
                        Text("Not now")
                    }
                }
            )
        }

        // Buffering indicator
        if (state.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center),
                color = Accent
            )
        }

        // Error overlay
        state.error?.let { error ->
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Error, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(error, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.retry() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("Retry")
                }
            }
        }

        // Controls overlay
        AnimatedVisibility(
            visible = state.showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.channelName,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    // Subtitles
                    if (state.subtitleTracks.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showSubsMenu = true; wake() }) {
                                Icon(
                                    Icons.Default.ClosedCaption,
                                    contentDescription = "Subtitles",
                                    tint = if (state.subtitlesEnabled) Accent else TextPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            DropdownMenu(expanded = showSubsMenu, onDismissRequest = { showSubsMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Off") },
                                    leadingIcon = { if (!state.subtitlesEnabled) Icon(Icons.Default.Check, null, tint = Accent) },
                                    onClick = { viewModel.selectSubtitle(null); showSubsMenu = false }
                                )
                                state.subtitleTracks.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.label) },
                                        leadingIcon = { if (t.selected) Icon(Icons.Default.Check, null, tint = Accent) },
                                        onClick = { viewModel.selectSubtitle(t); showSubsMenu = false }
                                    )
                                }
                            }
                        }
                    }

                    // Audio tracks
                    if (state.audioTracks.size > 1) {
                        Box {
                            IconButton(onClick = { showAudioMenu = true; wake() }) {
                                Icon(
                                    Icons.Default.Audiotrack,
                                    contentDescription = "Audio",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            DropdownMenu(expanded = showAudioMenu, onDismissRequest = { showAudioMenu = false }) {
                                state.audioTracks.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.label) },
                                        leadingIcon = { if (t.selected) Icon(Icons.Default.Check, null, tint = Accent) },
                                        onClick = { viewModel.selectAudio(t); showAudioMenu = false }
                                    )
                                }
                            }
                        }
                    }

                    // Download button
                    if (state.currentStreamUrl != null) {
                        when {
                            state.downloadComplete -> {
                                IconButton(onClick = {}) {
                                    Icon(
                                        Icons.Default.DownloadDone,
                                        contentDescription = "Downloaded",
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            state.isDownloading -> {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        progress = { state.downloadProgress },
                                        modifier = Modifier.size(36.dp),
                                        color = Accent,
                                        strokeWidth = 3.dp,
                                    )
                                    Text(
                                        "${(state.downloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary
                                    )
                                }
                            }
                            else -> {
                                IconButton(onClick = { viewModel.downloadCurrent() }) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = "Download",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Download error toast
                state.downloadError?.let { err ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 72.dp),
                        colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.9f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Download failed: $err",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Center controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.seekBackward() },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = TextPrimary, modifier = Modifier.size(32.dp))
                    }

                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Accent.copy(alpha = 0.8f))
                            .focusRequester(playFocus)
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = TextPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.seekForward() },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = TextPrimary, modifier = Modifier.size(32.dp))
                    }
                }

                // Bottom seek bar / progress — for seekable content (VOD, series,
                // downloaded files); live TV is not seekable so it's hidden there.
                if (seekable && durationMs > 0) {
                    val shownMs = if (scrubbing) scrubMs else positionMs
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                    ) {
                        Slider(
                            value = shownMs.coerceIn(0L, durationMs).toFloat(),
                            onValueChange = {
                                scrubbing = true
                                scrubMs = it.toLong()
                                viewModel.setShowControls(true)
                                interactionNudge++
                            },
                            onValueChangeFinished = {
                                viewModel.seekTo(scrubMs)
                                positionMs = scrubMs
                                scrubbing = false
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent
                            ),
                            // D-pad Left/Right must seek reliably on Android TV. Material3's
                            // built-in Slider key handling is eaten by the focus system there,
                            // so seek explicitly (and keep the event, so focus stays on the bar).
                            // Preview (not onKeyEvent) so we intercept before Material3's own
                            // Slider key handling, which on Android TV either eats the DPAD or
                            // nudges by an imperceptible step.
                            modifier = Modifier
                                .focusRequester(sliderFocus)
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (ev.key) {
                                        Key.DirectionRight, Key.MediaFastForward -> {
                                            viewModel.seekForward()
                                            positionMs = viewModel.player.currentPosition.coerceAtLeast(0)
                                            interactionNudge++; true
                                        }
                                        Key.DirectionLeft, Key.MediaRewind -> {
                                            viewModel.seekBackward()
                                            positionMs = viewModel.player.currentPosition.coerceAtLeast(0)
                                            interactionNudge++; true
                                        }
                                        else -> false
                                    }
                                }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                formatTime(shownMs),
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                formatTime(durationMs),
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * TV-remote key handling. While controls are hidden, any nav/OK/media key just
 * reveals them. While visible, dedicated media keys act immediately and every
 * other key falls through to Compose focus navigation (so the seek bar scrubs and
 * the buttons/menus are reachable). BACK is never consumed, so it exits.
 */
private fun handlePlayerKey(
    ev: KeyEvent,
    showControls: Boolean,
    viewModel: PlayerViewModel,
    wake: () -> Unit
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    if (ev.key == Key.Back) return false
    if (!showControls) {
        return when (ev.key) {
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
            Key.MediaFastForward, Key.MediaRewind -> { wake(); true }
            else -> false
        }
    }
    return when (ev.key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { viewModel.togglePlayPause(); wake(); true }
        Key.MediaFastForward -> { viewModel.seekForward(); wake(); true }
        Key.MediaRewind -> { viewModel.seekBackward(); wake(); true }
        else -> { wake(); false }
    }
}

/** Format a millisecond duration as H:MM:SS (or M:SS under an hour). */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
