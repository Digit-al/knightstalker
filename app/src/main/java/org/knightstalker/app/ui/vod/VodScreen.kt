// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.vod

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.knightstalker.app.ui.components.CategorySidebar
import org.knightstalker.app.ui.components.LoadingIndicator
import org.knightstalker.app.ui.components.VodCard
import org.knightstalker.app.ui.theme.TextSecondary

@Composable
fun VodScreen(
    viewModel: VodViewModel,
    onVodClick: (String, String) -> Unit,
    onPlayVod: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()

    val reachedEnd by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= state.items.size - 6
        }
    }

    LaunchedEffect(reachedEnd) {
        if (reachedEnd && state.hasMore) {
            viewModel.loadMore()
        }
    }

    // Detail overlay
    state.selectedVod?.let { vod ->
        VodDetailScreen(
            vod = vod,
            onPlay = { onPlayVod(vod.cmd) },
            onToggleFavorite = { viewModel.toggleFavorite(vod) },
            onDismiss = { viewModel.selectVod(null) }
        )
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (state.categories.isNotEmpty()) {
            CategorySidebar(
                categories = state.categories.map { it.id to it.title },
                selectedId = state.selectedCategory,
                onSelect = { viewModel.selectCategory(it) }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.items.isEmpty() -> LoadingIndicator()
                state.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No content found", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
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
                        items(state.items, key = { it.id }) { vod ->
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
