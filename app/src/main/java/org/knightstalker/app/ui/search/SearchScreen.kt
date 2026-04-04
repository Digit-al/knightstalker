// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.knightstalker.app.ui.components.ChannelCard
import org.knightstalker.app.ui.components.LoadingIndicator
import org.knightstalker.app.ui.components.VodCard
import org.knightstalker.app.ui.theme.*

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onChannelClick: (String, String) -> Unit,
    onVodClick: (String, String) -> Unit
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onQueryChanged(it) },
            placeholder = { Text("Search channels and movies...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                cursorColor = Accent
            )
        )

        // Filter chips
        if (state.hasSearched) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchFilter.entries.forEach { filter ->
                    val count = when (filter) {
                        SearchFilter.All -> state.channels.size + state.vod.size + state.series.size
                        SearchFilter.Channels -> state.channels.size
                        SearchFilter.Movies -> state.vod.size
                        SearchFilter.Series -> state.series.size
                    }
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text("${filter.name} ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = DarkBackground
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        when {
            state.isLoading -> LoadingIndicator()
            !state.hasSearched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Type to search", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            }
            state.channels.isEmpty() && state.vod.isEmpty() && state.series.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results found", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            }
            else -> {
                val showChannels = state.filter == SearchFilter.All || state.filter == SearchFilter.Channels
                val showVod = state.filter == SearchFilter.All || state.filter == SearchFilter.Movies
                val showSeries = state.filter == SearchFilter.All || state.filter == SearchFilter.Series

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (showChannels && state.channels.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Channels (${state.channels.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(state.channels, key = { "ch_${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { channel ->
                            ChannelCard(
                                channel = channel,
                                onClick = { onChannelClick(channel.id, channel.cmd) },
                                onToggleFavorite = {}
                            )
                        }
                    }
                    if (showVod && state.vod.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Movies (${state.vod.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(state.vod, key = { "vod_${it.id}" }) { vod ->
                            VodCard(
                                vod = vod,
                                onClick = { onVodClick(vod.id, vod.cmd) }
                            )
                        }
                    }
                    if (showSeries && state.series.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Series (${state.series.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(state.series, key = { "ser_${it.id}" }) { series ->
                            VodCard(
                                vod = series,
                                onClick = { onVodClick(series.id, series.cmd) }
                            )
                        }
                    }
                }
            }
        }
    }
}
