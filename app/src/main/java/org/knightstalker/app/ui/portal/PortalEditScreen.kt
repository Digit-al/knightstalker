// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.portal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.knightstalker.app.domain.model.Portal
import org.knightstalker.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalEditScreen(
    portal: Portal?,
    onSave: (name: String, url: String, mac: String, id: Long) -> Unit,
    onBack: () -> Unit
) {
    var name by remember(portal?.id) { mutableStateOf(portal?.name ?: "") }
    var url by remember(portal?.id) { mutableStateOf(portal?.url ?: "") }
    var mac by remember(portal?.id) { mutableStateOf(portal?.macAddress ?: "00:1A:79:") }

    val isValid = url.isNotBlank() && mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (portal != null) "Edit Portal" else "Add Portal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Portal Name") },
                placeholder = { Text("My IPTV") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    cursorColor = Accent
                )
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Portal URL") },
                placeholder = { Text("http://example.com/stalker_portal") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    cursorColor = Accent
                )
            )

            OutlinedTextField(
                value = mac,
                onValueChange = { input ->
                    // Auto-format MAC address
                    val cleaned = input.uppercase().filter { it.isLetterOrDigit() || it == ':' }
                    if (cleaned.length <= 17) {
                        mac = cleaned
                    }
                },
                label = { Text("MAC Address") },
                placeholder = { Text("00:1A:79:XX:XX:XX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    cursorColor = Accent
                ),
                supportingText = {
                    Text("Format: XX:XX:XX:XX:XX:XX", color = TextSecondary)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onSave(name, url, mac, portal?.id ?: 0) },
                enabled = isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Save Portal", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
