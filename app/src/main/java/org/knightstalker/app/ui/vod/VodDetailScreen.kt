// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.knightstalker.app.domain.model.VodItem
import org.knightstalker.app.ui.theme.*

@Composable
fun VodDetailScreen(
    vod: VodItem,
    onPlay: () -> Unit,
    onDownload: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    /** True while the TMDB details (synopsis / cast / genres) are being fetched. */
    loading: Boolean = false
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { playFocus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Poster
            Column(
                modifier = Modifier.width(240.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.Start)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                if (!vod.logo.isNullOrEmpty()) {
                    AsyncImage(
                        model = vod.logo,
                        contentDescription = vod.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(64.dp), tint = TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(vod.name, style = MaterialTheme.typography.headlineMedium)

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    vod.year?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    vod.duration?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    vod.rating?.let { rating ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = WarningOrange)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(rating, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                vod.genre?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }

                // While the TMDB details are being fetched, show a hint in the
                // description area so the user knows more info is on its way.
                if (loading && vod.description == null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading details…", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPlay,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier
                            .height(48.dp)
                            .focusRequester(playFocus)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play")
                    }
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = TextSecondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download")
                    }
                    OutlinedButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(
                            if (vod.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (vod.isFavorite) Accent else TextSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (vod.isFavorite) "Unfavorite" else "Favorite")
                    }
                }

                vod.description?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }

                vod.director?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Director: $it", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }

                vod.actors?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Cast: $it", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
        }
    }
}
