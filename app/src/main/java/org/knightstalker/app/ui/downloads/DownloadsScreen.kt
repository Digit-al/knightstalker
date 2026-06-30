// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.knightstalker.app.data.repository.DownloadRepository
import org.knightstalker.app.data.repository.DownloadStatus
import org.knightstalker.app.data.repository.DownloadTask
import org.knightstalker.app.ui.theme.*

@Composable
fun DownloadsScreen(
    downloadRepository: DownloadRepository,
    onPlay: (DownloadTask) -> Unit = {}
) {
    val downloads by downloadRepository.downloads.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Downloads",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp)
        )

        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No downloads", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(downloads, key = { it.id }) { task ->
                    DownloadItem(
                        task = task,
                        onPlay = { onPlay(task) },
                        onPause = { downloadRepository.pauseDownload(task.id) },
                        onResume = { downloadRepository.resumeDownload(task.id) },
                        onRestart = { downloadRepository.retryDownload(task.id) },
                        onDelete = { downloadRepository.removeDownload(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItem(
    task: DownloadTask,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                Icon(
                    imageVector = when (task.status) {
                        DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
                        DownloadStatus.FAILED -> Icons.Default.Error
                        DownloadStatus.DOWNLOADING -> Icons.Default.Download
                        DownloadStatus.PAUSED -> Icons.Default.Pause
                        DownloadStatus.CANCELLED -> Icons.Default.Cancel
                        DownloadStatus.PENDING -> Icons.Default.HourglassTop
                    },
                    contentDescription = null,
                    tint = when (task.status) {
                        DownloadStatus.COMPLETED -> SuccessGreen
                        DownloadStatus.FAILED -> Accent
                        DownloadStatus.DOWNLOADING -> PrimaryBlue
                        DownloadStatus.PAUSED -> WarningOrange
                        else -> TextSecondary
                    },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(task.status.name.lowercase().replaceFirstChar { it.uppercase() })
                            if (task.totalBytes > 0) {
                                append(" • ${formatBytes(task.bytesDownloaded)}/${formatBytes(task.totalBytes)}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Action buttons per state. Commands: play, resume, pause,
                // restart, delete — each with its own icon.
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> {
                        ActionIcon(Icons.Default.Pause, "Pause", TextPrimary, onPause)
                        ActionIcon(Icons.Default.Refresh, "Restart", WarningOrange, onRestart)
                        ActionIcon(Icons.Default.Delete, "Delete", Accent, onDelete)
                    }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                        ActionIcon(Icons.Default.PlayArrow, "Resume", SuccessGreen, onResume)
                        ActionIcon(Icons.Default.Refresh, "Restart", WarningOrange, onRestart)
                        ActionIcon(Icons.Default.Delete, "Delete", Accent, onDelete)
                    }
                    DownloadStatus.COMPLETED -> {
                        ActionIcon(Icons.Default.PlayCircle, "Play", SuccessGreen, onPlay)
                        ActionIcon(Icons.Default.Delete, "Delete", TextSecondary, onDelete)
                    }
                    DownloadStatus.PENDING -> {
                        ActionIcon(Icons.Default.Pause, "Pause", TextPrimary, onPause)
                        ActionIcon(Icons.Default.Delete, "Delete", Accent, onDelete)
                    }
                    DownloadStatus.CANCELLED -> {
                        ActionIcon(Icons.Default.Refresh, "Restart", WarningOrange, onRestart)
                        ActionIcon(Icons.Default.Delete, "Delete", TextSecondary, onDelete)
                    }
                }
            }

            // Progress bar + live speed / ETA
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = if (task.status == DownloadStatus.DOWNLOADING) Accent else WarningOrange,
                    trackColor = DividerColor
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${(task.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    if (task.status == DownloadStatus.DOWNLOADING && task.speedBytesPerSec > 0) {
                        Text(
                            "${formatSpeed(task.speedBytesPerSec)} • ${formatEta(task)} left",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Error message
            task.error?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Accent)
            }
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}

private fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

private fun formatEta(task: DownloadTask): String {
    val remaining = task.totalBytes - task.bytesDownloaded
    if (remaining <= 0 || task.speedBytesPerSec <= 0) return "--"
    val seconds = remaining / task.speedBytesPerSec
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        m > 0 -> "%d:%02d".format(m, s)
        else -> "${s}s"
    }
}
