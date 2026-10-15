// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.knightstalker.app.domain.model.EpisodeProgress
import org.knightstalker.app.domain.model.NextEpisode
import org.knightstalker.app.domain.model.Season
import org.knightstalker.app.domain.model.VodItem
import org.knightstalker.app.ui.theme.*

@Composable
fun SeriesDetailScreen(
    series: VodItem,
    seasons: List<Season>,
    loading: Boolean,
    error: String?,
    episodeProgress: Map<String, Map<Int, EpisodeProgress>>,
    nextEpisode: NextEpisode?,
    onPlay: (seasonCmd: String, episode: Int, episodes: List<Int>, episodeId: String?, episodeExt: String?, episodeTitle: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSeason by remember(seasons) { mutableStateOf(0) }

    // Focus the "continue / next unseen" action once it's available (episodes load async),
    // rather than leaving focus on the Back button.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(nextEpisode) {
        if (nextEpisode != null) playFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Header: back, poster, title, and a "continue / next unseen" action.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                if (!series.logo.isNullOrEmpty()) {
                    AsyncImage(
                        model = series.logo,
                        contentDescription = series.name,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(width = 56.dp, height = 84.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    text = series.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                if (nextEpisode != null) {
                    Button(
                        onClick = { onPlay(nextEpisode.seasonCmd, nextEpisode.episode, nextEpisode.episodes, nextEpisode.episodeRef?.episodeId, nextEpisode.episodeRef?.ext, null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.focusRequester(playFocus)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text(nextEpisode.label, color = TextPrimary)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Couldn't load episodes: $error", color = TextSecondary)
                }
                seasons.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No episodes found", color = TextSecondary)
                }
                else -> {
                    val season = seasons[selectedSeason.coerceIn(0, seasons.size - 1)]
                    val progress = episodeProgress[season.cmd].orEmpty()

                    // Season selector
                    if (seasons.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(seasons) { i, s ->
                                val selected = i == selectedSeason
                                Surface(
                                    onClick = { selectedSeason = i },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (selected) Accent else CardBackground
                                ) {
                                    Text(
                                        text = s.name.ifBlank { "Season ${i + 1}" },
                                        color = TextPrimary,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Episodes, with watched / in-progress marks.
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(200.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(season.episodes) { ep ->
                            val ref = season.episodeStreams[ep]
                            val epTitle = ref?.title?.ifBlank { null } ?: "Episode $ep"
                            EpisodeCard(
                                episode = ep,
                                episodeTitle = epTitle,
                                seasonName = season.name.ifBlank { "Season ${selectedSeason + 1}" },
                                poster = season.poster,
                                progress = progress[ep],
                                onClick = { onPlay(season.cmd, ep, season.episodes, ref?.episodeId, ref?.ext, epTitle) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Int,
    episodeTitle: String,
    seasonName: String,
    poster: String?,
    progress: EpisodeProgress?,
    onClick: () -> Unit
) {
    val finished = progress?.finished == true
    val inProgress = progress != null && !progress.finished && progress.fraction > 0.02f
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = CardBackground
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Season poster as the episode thumbnail (portal gives no per-episode
                // image), with a finished/play badge overlaid.
                Box {
                    if (!poster.isNullOrEmpty()) {
                        AsyncImage(
                            model = poster,
                            contentDescription = null,
                            modifier = Modifier
                                .size(width = 44.dp, height = 62.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 62.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(DarkBackground)
                        )
                    }
                    Icon(
                        if (finished) Icons.Default.Check else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (finished) SuccessGreen else Accent,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        episodeTitle,
                        color = if (finished) TextSecondary else TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        seasonName,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (inProgress) {
                    Text(
                        "${(progress!!.fraction * 100).toInt()}%",
                        color = Accent,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // Resume progress bar for a partially-watched episode.
            if (inProgress) {
                LinearProgressIndicator(
                    progress = { progress!!.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Accent
                )
            }
        }
    }
}
