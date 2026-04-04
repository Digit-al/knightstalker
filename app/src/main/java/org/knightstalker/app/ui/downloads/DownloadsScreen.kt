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
fun DownloadsScreen(downloadRepository: DownloadRepository) {
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
                        onPause = { downloadRepository.pauseDownload(task.id) },
                        onResume = { downloadRepository.resumeDownload(task.id) },
                        onCancel = { downloadRepository.cancelDownload(task.id) },
                        onRetry = { downloadRepository.retryDownload(task.id) },
                        onRemove = { downloadRepository.removeDownload(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItem(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
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
                                append(" \u2022 ${formatBytes(task.bytesDownloaded)}/${formatBytes(task.totalBytes)}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Action buttons
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = TextPrimary)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Accent)
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = SuccessGreen)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Accent)
                        }
                    }
                    DownloadStatus.FAILED -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = WarningOrange)
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Accent)
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = TextSecondary)
                        }
                    }
                    DownloadStatus.CANCELLED, DownloadStatus.PENDING -> {
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = TextSecondary)
                        }
                    }
                }
            }

            // Progress bar
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
                Text(
                    "${(task.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Error message
            task.error?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Accent)
            }
        }
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
