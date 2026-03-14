// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.player

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.knightstalker.app.ui.theme.Accent
import org.knightstalker.app.ui.theme.SuccessGreen
import org.knightstalker.app.ui.theme.TextPrimary
import org.knightstalker.app.ui.theme.TextSecondary

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    type: String,
    cmd: String,
    name: String,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(cmd) {
        if (type == "vod") {
            viewModel.playVod(cmd, name)
        } else {
            viewModel.playChannel(cmd, name)
        }
    }

    // Auto-hide controls
    LaunchedEffect(state.showControls) {
        if (state.showControls) {
            delay(5000)
            viewModel.setShowControls(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                viewModel.setShowControls(!state.showControls)
            }
    ) {
        // Video surface
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.player
                    useController = false
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
            }
        }
    }
}
