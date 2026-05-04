// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    searchQuery: String? = null,
    onSearchChanged: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val sorted = if (favoriteIds.isNotEmpty()) {
        val (favs, rest) = categories.partition { it.first in favoriteIds }
        favs + rest
    } else {
        categories
    }

    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(sorted.firstOrNull()?.first) {
        if (sorted.isNotEmpty()) {
            // Force initial focus to the first category, not the search field above it.
            // Wrapped in try/catch because the target may not yet be attached on first frame.
            try { firstItemFocus.requestFocus() } catch (_: Throwable) {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(220.dp)
            .background(DarkSurfaceVariant)
            .padding(8.dp)
    ) {
        val searchHandler = onSearchChanged
        if (searchHandler != null) {
            OutlinedTextField(
                value = searchQuery ?: "",
                onValueChange = searchHandler,
                placeholder = {
                    Text("Search", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = if (!searchQuery.isNullOrEmpty()) {
                    {
                        IconButton(
                            onClick = { searchHandler("") },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                } else null,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    cursorColor = Accent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(sorted) { index, (id, title) ->
            val isSelected = id == selectedId
            val isFavorite = id in favoriteIds
            val isSpecial = id == "*" || id == "favs"
            val rowFocusModifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Accent.copy(alpha = 0.2f) else Color.Transparent)
                    .then(rowFocusModifier)
                    .focusable()
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
}
