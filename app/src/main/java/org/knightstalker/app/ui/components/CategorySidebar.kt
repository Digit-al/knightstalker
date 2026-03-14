// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.knightstalker.app.ui.theme.Accent
import org.knightstalker.app.ui.theme.DarkSurfaceVariant
import org.knightstalker.app.ui.theme.TextSecondary

@Composable
fun CategorySidebar(
    categories: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val sorted = if (favoriteIds.isNotEmpty()) {
        val (favs, rest) = categories.partition { it.first in favoriteIds }
        favs + rest
    } else {
        categories
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .width(220.dp)
            .background(DarkSurfaceVariant)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(sorted) { (id, title) ->
            val isSelected = id == selectedId
            val isFavorite = id in favoriteIds
            val isSpecial = id == "*" || id == "favs"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Accent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) Accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!isSpecial && onToggleFavorite != null) {
                    IconButton(
                        onClick = { onToggleFavorite(id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isFavorite) "Unstar" else "Star",
                            modifier = Modifier.size(16.dp),
                            tint = if (isFavorite) Accent else TextSecondary
                        )
                    }
                }
            }
        }
    }
}
