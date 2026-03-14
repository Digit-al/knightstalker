// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.knightstalker.app.data.local.entity.WatchHistoryEntity
import org.knightstalker.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onResume: (type: String, cmd: String, name: String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabRow(
                selectedTabIndex = state.selectedTab,
                containerColor = DarkSurface,
                contentColor = Accent,
                modifier = Modifier.weight(1f)
            ) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text("Continue Watching (${state.continueWatching.size})") }
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text("All History (${state.allHistory.size})") }
                )
            }
            if (state.allHistory.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear History", tint = TextSecondary)
                }
            }
        }

        val items = if (state.selectedTab == 0) state.continueWatching else state.allHistory

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (state.selectedTab == 0) "Nothing to continue" else "No watch history",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Start watching something to build your history",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(items, key = { it.itemId }) { entry ->
                    HistoryItem(
                        entry = entry,
                        onResume = { onResume(entry.type, entry.cmd, entry.name) },
                        onRemove = { viewModel.removeEntry(entry) }
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History") },
            text = { Text("Remove all watch history? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; viewModel.clearAll() }) {
                    Text("Clear All", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HistoryItem(
    entry: WatchHistoryEntity,
    onResume: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onResume),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (!entry.logo.isNullOrEmpty()) {
                    AsyncImage(
                        model = entry.logo,
                        contentDescription = entry.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (entry.type == "itv") Icons.Default.LiveTv else Icons.Default.Movie,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Play icon overlay
                if (!entry.isFinished) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = TextPrimary.copy(alpha = 0.9f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Type badge
                    Text(
                        text = if (entry.type == "itv") "Live TV" else "Movie",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    // Time ago
                    Text(
                        text = formatTimeAgo(entry.lastWatched),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Progress bar for unfinished items
                if (!entry.isFinished && entry.durationMs > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { entry.progressPercent },
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Accent,
                        )
                        Text(
                            text = "${formatDuration(entry.positionMs)} / ${formatDuration(entry.durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                } else if (entry.isFinished) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Watched",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen
                    )
                }
            }

            // Resume / Remove buttons
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!entry.isFinished && entry.positionMs > 0) {
                    FilledTonalButton(
                        onClick = onResume,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Accent)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume", style = MaterialTheme.typography.bodySmall, color = Accent)
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}

private fun formatTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM dd", Locale.US).format(Date(timestamp))
    }
}
