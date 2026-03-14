// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.knightstalker.app.domain.model.Channel
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

        when {
            state.isLoading -> LoadingIndicator()
            !state.hasSearched -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Type to search", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            }
            state.channels.isEmpty() && state.vod.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results found", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (state.channels.isNotEmpty()) {
                        item {
                            Text(
                                "Channels (${state.channels.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(state.channels, key = { "ch_${it.id}" }) { channel ->
                            ChannelCard(
                                channel = channel,
                                onClick = { onChannelClick(channel.id, channel.cmd) },
                                onToggleFavorite = {}
                            )
                        }
                    }
                    if (state.vod.isNotEmpty()) {
                        item {
                            Text(
                                "Movies (${state.vod.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(state.vod, key = { "vod_${it.id}" }) { vod ->
                            VodCard(
                                vod = vod,
                                onClick = { onVodClick(vod.id, vod.cmd) }
                            )
                        }
                    }
                }
            }
        }
    }
}
