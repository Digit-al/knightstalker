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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.knightstalker.app.domain.model.EpgEntry
import org.knightstalker.app.ui.components.LoadingIndicator
import org.knightstalker.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Pixels per minute in the timeline. 2dp per minute → 120dp per hour.
 * A 1h programme = 120dp, a 30min show = 60dp.
 */
private const val PX_PER_MINUTE = 2

/** Total width of the timeline: 24 hours × 60 min × PX_PER_MINUTE. */
private val TOTAL_WIDTH_DP = 24 * 60 * PX_PER_MINUTE // 2880dp

/** Row height for each channel. */
private val ROW_HEIGHT_DP = 56.dp

/** Channel name column width. */
private val CHANNEL_COL_WIDTH = 130.dp

/**
 * Computes the epoch seconds of midnight (local) for a given date string.
 */
private fun dayStartEpoch(dateStr: String): Long {
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        fmt.parse(dateStr)?.time?.div(1000) ?: 0L
    } catch (_: Exception) {
        0L
    }
}

@Composable
fun EpgScreen(
    viewModel: EpgViewModel,
    onChannelClick: (String, String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current

    // Shared horizontal scroll state — all timeline rows scroll together.
    val scrollState = rememberScrollState()
    var hasAutoScrolled by remember { mutableStateOf(false) }

    if (state.isLoading && state.channels.isEmpty()) {
        LoadingIndicator()
        return
    }

    // Auto-scroll to current time when today's data first appears.
    val nowSec = System.currentTimeMillis() / 1000
    val isToday = state.selectedDate == SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val dayStart = dayStartEpoch(state.selectedDate)

    LaunchedEffect(isToday, state.epgData.isNotEmpty(), hasAutoScrolled) {
        if (isToday && !hasAutoScrolled && state.epgData.isNotEmpty()) {
            val nowOffsetDp = ((nowSec - dayStart) / 60f * PX_PER_MINUTE).toInt()
                .coerceAtLeast(0)
            // Center the now line: scroll so now is at ~35% of the viewport.
            val targetPx = with(density) { nowOffsetDp.dp.toPx() }.toInt()
            val viewportPx = with(density) { (300).dp.toPx() }.toInt()
            val scrollTarget = (targetPx - viewportPx / 3).coerceAtLeast(0)
            scrollState.scrollTo(scrollTarget)
            hasAutoScrolled = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Category selector
        CategoryRow(
            categories = state.categories,
            selected = state.selectedCategory,
            onSelect = { viewModel.selectCategory(it) }
        )

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

        // Guide status banner
        if (state.epgFeedApplicable) {
            val feedEmpty = state.epgFeedEntries == 0 && state.epgFeedChannels == 0
            val rawKo = state.epgFeedRawSize / 1024
            val xmlMo = state.epgFeedXmlSize / 1024 / 1024
            val feedText = when {
                state.epgFeedLoading -> "Chargement du guide…"
                state.epgFeedError != null -> "Guide: ${state.epgFeedError}"
                feedEmpty -> "Guide: 0 chaîne/programme — brut ${rawKo} Ko → xml ${xmlMo} Mo — début: ${state.epgFeedHead.take(120)}"
                else -> "Guide: ${state.epgFeedChannels} chaînes, ${state.epgFeedEntries} programmes"
            }
            val feedColor = when {
                state.epgFeedLoading -> TextSecondary
                state.epgFeedError != null -> WarningOrange
                feedEmpty -> WarningOrange
                else -> TextSecondary
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = feedText,
                    color = feedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Réessayer",
                    color = Accent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(DarkSurface)
                        .clickable { viewModel.refreshAll() }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        if (state.channels.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.error ?: "No channels found",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return
        }

        // EPG timeline grid
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Time header
            item(key = "time_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(DarkSurface)
                ) {
                    Spacer(Modifier.width(CHANNEL_COL_WIDTH))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(scrollState)
                    ) {
                        // Hour labels — one per hour
                        for (h in 0..23) {
                            Text(
                                text = String.format(Locale.US, "%02d:00", h),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                modifier = Modifier
                                    .width((60 * PX_PER_MINUTE).dp)
                                    .padding(start = 2.dp)
                            )
                        }
                    }
                }
            }

            // Channel rows
            items(state.channels, key = { it.id }) { channel ->
                val epgEntries = state.epgData[channel.id] ?: emptyList()

                LaunchedEffect(channel.id, state.selectedDate) {
                    if (channel.id !in state.epgData) {
                        viewModel.loadEpgForChannel(channel)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT_DP)
                ) {
                    // Channel name
                    Box(
                        modifier = Modifier
                            .width(CHANNEL_COL_WIDTH)
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

                    // Timeline — shared scroll state
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(scrollState)
                    ) {
                        // Fixed-width container = one day
                        Box(
                            modifier = Modifier
                                .width(TOTAL_WIDTH_DP.dp)
                                .fillMaxHeight()
                        ) {
                            // Hour grid lines (subtle)
                            for (h in 1..23) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = (h * 60 * PX_PER_MINUTE).dp)
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(Color.White.copy(alpha = 0.04f))
                                )
                            }

                            // Programme blocks
                            val sdf = SimpleDateFormat("HH:mm", Locale.US)
                            epgEntries.forEach { entry ->
                                val entryStart = entry.startTime
                                val entryEnd = entry.endTime

                                // Only render entries overlapping this day
                                if (entryEnd <= dayStart || entryStart >= dayStart + 86400) return@forEach

                                val clampedStart = entryStart.coerceAtLeast(dayStart)
                                val clampedEnd = entryEnd.coerceAtMost(dayStart + 86400L)
                                val startOffsetMin = ((clampedStart - dayStart) / 60).toInt()
                                val durationMin = ((clampedEnd - clampedStart) / 60).toInt()

                                if (durationMin < 1) return@forEach

                                val isCurrent = isToday && nowSec in entry.startTime..entry.endTime

                                Box(
                                    modifier = Modifier
                                        .offset(x = (startOffsetMin * PX_PER_MINUTE).dp)
                                        .width((durationMin * PX_PER_MINUTE).dp)
                                        .fillMaxHeight()
                                        .padding(vertical = 1.dp, horizontal = 1.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (isCurrent) Accent.copy(alpha = 0.25f)
                                            else CardBackground
                                        )
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = sdf.format(Date(entry.startTime * 1000)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Clip
                                        )
                                        Text(
                                            text = entry.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // "Now" line (red)
                            if (isToday) {
                                val nowOffsetDp = ((nowSec - dayStart) / 60f * PX_PER_MINUTE).toInt()
                                if (nowOffsetDp in 0..(TOTAL_WIDTH_DP)) {
                                    Box(
                                        modifier = Modifier
                                            .offset(x = nowOffsetDp.dp)
                                            .width(2.dp)
                                            .fillMaxHeight()
                                            .background(Color(0xFFE53935))
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

/**
 * Full-width category chip row with a dropdown to pick the active category.
 * `selected == null` means "All".
 */
@Composable
private fun CategoryRow(
    categories: List<org.knightstalker.app.domain.model.ChannelCategory>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = (categories.firstOrNull { it.id == selected }?.title) ?: "All categories"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Category: $label",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = TextSecondary
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            DropdownMenuItem(
                text = { Text("All categories") },
                onClick = {
                    expanded = false
                    if (selected != null) onSelect(null)
                }
            )
            categories.forEach { cat ->
                if (cat.id == "*") return@forEach
                DropdownMenuItem(
                    text = { Text(cat.title) },
                    onClick = {
                        expanded = false
                        onSelect(cat.id)
                    }
                )
            }
        }
    }
}
