// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.knightstalker.app.domain.model.EpgEntry
import org.knightstalker.app.ui.components.LoadingIndicator
import org.knightstalker.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EpgScreen(
    viewModel: EpgViewModel,
    onChannelClick: (String, String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val horizontalScroll = rememberScrollState()

    if (state.isLoading && state.channels.isEmpty()) {
        LoadingIndicator()
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Date selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val displaySdf = SimpleDateFormat("EEE dd", Locale.US)
            val cal = Calendar.getInstance()
            repeat(7) { i ->
                val date = sdf.format(cal.time)
                val label = if (i == 0) "Today" else displaySdf.format(cal.time)
                val isSelected = date == state.selectedDate
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Accent else DarkSurfaceVariant)
                        .clickable { viewModel.selectDate(date) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) TextPrimary else TextSecondary
                    )
                }
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        // EPG grid
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.channels, key = { it.id }) { channel ->
                val epgEntries = state.epgData[channel.id] ?: emptyList()

                LaunchedEffect(channel.id) {
                    if (channel.id !in state.epgData) {
                        viewModel.loadEpgForChannel(channel.id)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                ) {
                    // Channel name
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .fillMaxHeight()
                            .background(DarkSurfaceVariant)
                            .clickable { onChannelClick(channel.id, channel.cmd) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // EPG timeline
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScroll)
                    ) {
                        if (epgEntries.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .width(600.dp)
                                    .fillMaxHeight()
                                    .background(CardBackground)
                                    .padding(8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    "No program info",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        } else {
                            epgEntries.forEach { entry ->
                                EpgCell(entry = entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpgCell(entry: EpgEntry) {
    val width = (entry.duration / 60f * 4).dp.coerceIn(80.dp, 400.dp)
    val now = System.currentTimeMillis() / 1000
    val isCurrent = now in entry.startTime..entry.endTime

    val sdf = SimpleDateFormat("HH:mm", Locale.US)

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isCurrent) Accent.copy(alpha = 0.3f) else CardBackground)
            .padding(4.dp)
    ) {
        Column {
            Text(
                text = sdf.format(Date(entry.startTime * 1000)),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
