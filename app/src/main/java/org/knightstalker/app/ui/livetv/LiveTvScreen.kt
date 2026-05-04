// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.livetv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.knightstalker.app.ui.components.CategorySidebar
import org.knightstalker.app.ui.components.ChannelCard
import org.knightstalker.app.ui.components.LoadingIndicator
import org.knightstalker.app.ui.theme.TextSecondary

@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel,
    onChannelClick: (String, String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Load more when reaching end of list
    val reachedEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= state.channels.size - 5
        }
    }

    LaunchedEffect(reachedEnd, state.hasMore, state.isLoading) {
        if (reachedEnd && state.hasMore && !state.isLoading) {
            viewModel.loadMore()
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Category sidebar
        if (state.categories.isNotEmpty()) {
            CategorySidebar(
                categories = state.categories.map { it.id to it.title },
                selectedId = state.selectedCategory,
                onSelect = { viewModel.selectCategory(it) },
                favoriteIds = state.favoriteCategoryIds,
                onToggleFavorite = { viewModel.toggleFavoriteCategory(it) },
                searchQuery = state.searchQuery,
                onSearchChanged = { viewModel.setSearchQuery(it) }
            )
        }

        // Channel list
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.channels.isEmpty() -> {
                    LoadingIndicator()
                }
                state.error != null && state.channels.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            state.error ?: "Error loading channels",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                state.channels.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No channels found",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(state.channels, key = { it.id }) { channel ->
                            ChannelCard(
                                channel = channel,
                                onClick = { onChannelClick(channel.id, channel.cmd) },
                                onToggleFavorite = { viewModel.toggleFavorite(channel) }
                            )
                        }
                        if (state.isLoading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LoadingIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
