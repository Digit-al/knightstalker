// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.knightstalker.app.data.local.entity.FavoriteEntity
import org.knightstalker.app.ui.theme.*

@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    onChannelClick: (String, String) -> Unit,
    onVodClick: (String, String) -> Unit
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = state.selectedTab,
            containerColor = DarkSurface,
            contentColor = Accent
        ) {
            Tab(
                selected = state.selectedTab == 0,
                onClick = { viewModel.selectTab(0) },
                text = { Text("Channels (${state.channels.size})") }
            )
            Tab(
                selected = state.selectedTab == 1,
                onClick = { viewModel.selectTab(1) },
                text = { Text("Movies (${state.vod.size})") }
            )
        }

        val items = if (state.selectedTab == 0) state.channels else state.vod

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(64.dp), tint = TextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No favorites yet", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                    Text("Long-press an item to add it", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(items, key = { it.channelId }) { fav ->
                    FavoriteItem(
                        favorite = fav,
                        onClick = {
                            if (fav.type == "channel") {
                                onChannelClick(fav.channelId, fav.cmd)
                            } else {
                                onVodClick(fav.channelId, fav.cmd)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteItem(favorite: FavoriteEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (favorite.type == "channel") Icons.Default.LiveTv else Icons.Default.Movie,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = favorite.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
