// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.knightstalker.app.ui.theme.*

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onPortals: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        // Portal management
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Portal Management", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onPortals,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage Portals")
                }
            }
        }

        // Playback settings
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Playback", style = MaterialTheme.typography.titleMedium)

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto Play", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = state.autoPlay,
                        onCheckedChange = { viewModel.setAutoPlay(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.5f))
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Buffer Size: ${state.bufferSizeMs / 1000}s", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = state.bufferSizeMs.toFloat(),
                    onValueChange = { viewModel.setBufferSize(it.toInt()) },
                    valueRange = 2000f..30000f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                )
            }
        }

        // About
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("KnightStalker v1.0.0", style = MaterialTheme.typography.bodyMedium)
                Text("Open source Stalker IPTV client", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("License: GPL-3.0-or-later", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}
