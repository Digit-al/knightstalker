// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import org.knightstalker.app.ui.components.CategorySidebar
import org.knightstalker.app.ui.components.LoadingIndicator
import org.knightstalker.app.ui.components.VodCard
import org.knightstalker.app.ui.theme.TextSecondary

@Composable
fun VodScreen(
    viewModel: VodViewModel,
    onVodClick: (String, String) -> Unit,
    onPlayVod: (String) -> Unit,
    onOpenDownloads: () -> Unit = {},
    isSeries: Boolean = false,
    onPlayEpisode: (seasonCmd: String, episode: Int, episodes: List<Int>, seriesName: String) -> Unit = { _, _, _, _ -> }
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    val pagingItems = state.items.collectAsLazyPagingItems()

    // Detail overlay
    state.selectedVod?.let { vod ->
        BackHandler { viewModel.selectVod(null) }
        if (isSeries) {
            // Series: pick a season/episode instead of playing the (cmd-less) series.
            SeriesDetailScreen(
                series = vod,
                seasons = state.seasons,
                loading = state.seasonsLoading,
                error = state.seasonsError,
                episodeProgress = state.episodeProgress,
                nextEpisode = state.nextEpisode,
                onPlay = { seasonCmd, ep, eps -> onPlayEpisode(seasonCmd, ep, eps, vod.name) },
                onDismiss = { viewModel.selectVod(null) }
            )
        } else {
            VodDetailScreen(
                vod = vod,
                onPlay = { onPlayVod(vod.cmd) },
                onDownload = {
                    viewModel.downloadVod(vod)
                    viewModel.selectVod(null)
                    onOpenDownloads()
                },
                onToggleFavorite = { viewModel.toggleFavorite(vod) },
                onDismiss = { viewModel.selectVod(null) }
            )
        }
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (state.categories.isNotEmpty()) {
            CategorySidebar(
                categories = state.categories.map { it.id to it.title },
                selectedId = state.selectedCategory,
                onSelect = { viewModel.selectCategory(it) },
                favoriteIds = state.favoriteCategoryIds,
                onToggleFavorite = { viewModel.toggleFavoriteCategory(it) },
                searchQuery = state.searchQuery,
                onSearchChanged = { viewModel.setSearchQuery(it) },
                sortOptions = VodSort.values().map { it.api to it.label },
                selectedSortId = state.sortBy.api,
                onSortSelected = { api ->
                    VodSort.values().firstOrNull { it.api == api }?.let { viewModel.setSortBy(it) }
                }
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            // Non-blocking indexing banner (label + progress bar) while the local
            // index is being built/refreshed. CRAWLING shows determinate progress;
            // the short DELTA refresh shows an indeterminate bar.
            if ((state.syncStatus == "CRAWLING" || state.syncStatus == "DELTA") &&
                state.totalItemsRemote > 0
            ) {
                val crawling = state.syncStatus == "CRAWLING"
                val fraction = (state.itemsIndexed.toFloat() / state.totalItemsRemote)
                    .coerceIn(0f, 1f)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (crawling)
                            "Indexing ${state.itemsIndexed} / ${state.totalItemsRemote} · ${(fraction * 100).toInt()}%"
                        else
                            "Refreshing library…",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    if (crawling) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val refresh = pagingItems.loadState.refresh
                when {
                    pagingItems.itemCount == 0 && refresh is LoadState.Loading -> LoadingIndicator()
                    pagingItems.itemCount == 0 -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No content found",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(160.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(
                                count = pagingItems.itemCount,
                                key = pagingItems.itemKey { it.id }
                            ) { index ->
                                pagingItems[index]?.let { vod ->
                                    VodCard(
                                        vod = vod,
                                        onClick = { viewModel.selectVod(vod) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
