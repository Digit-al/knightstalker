// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.portal

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.knightstalker.app.domain.model.Portal
import org.knightstalker.app.domain.model.PortalType
import org.knightstalker.app.ui.theme.*

@Serializable
private data class QrPortalData(
    val n: String = "",
    val u: String = "",
    val m: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalEditScreen(
    portal: Portal?,
    onSave: (
        name: String, url: String, mac: String, existingId: Long,
        type: PortalType, username: String, password: String, epgUrl: String
    ) -> Unit,
    onBack: () -> Unit
) {
    var name by remember(portal?.id) { mutableStateOf(portal?.name ?: "") }
    var url by remember(portal?.id) { mutableStateOf(portal?.url ?: "") }
    var mac by remember(portal?.id) { mutableStateOf(portal?.macAddress ?: "00:1A:79:") }
    var type by remember(portal?.id) { mutableStateOf(portal?.type ?: PortalType.STALKER) }
    var username by remember(portal?.id) { mutableStateOf(portal?.username ?: "") }
    var password by remember(portal?.id) { mutableStateOf(portal?.password ?: "") }
    var epgUrl by remember(portal?.id) { mutableStateOf(portal?.epgUrl ?: "") }
    var scanError by remember { mutableStateOf<String?>(null) }

    val json = remember { Json { ignoreUnknownKeys = true } }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            try {
                val data = json.decodeFromString<QrPortalData>(contents)
                if (data.u.isBlank()) {
                    scanError = "Invalid QR: missing URL"
                } else {
                    name = data.n
                    url = data.u
                    mac = data.m.ifBlank { mac }
                    type = PortalType.STALKER
                    scanError = null
                }
            } catch (_: Exception) {
                scanError = "Invalid QR code format"
            }
        }
    }

    val isMacValid = mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    val isValid = when (type) {
        PortalType.STALKER -> url.isNotBlank() && isMacValid
        PortalType.M3U -> url.isNotBlank()
        PortalType.XTREAM -> url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (portal != null) "Edit Portal" else "Add Portal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (type == PortalType.STALKER) {
                        IconButton(
                            onClick = {
                                scanError = null
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("Scan a KnightStalker QR code")
                                    setBeepEnabled(false)
                                    setOrientationLocked(false)
                                }
                                scanLauncher.launch(options)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR code",
                                tint = TextPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
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
                .verticalScroll(scrollState)
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

            // Source type
            Text("Source Type", style = MaterialTheme.typography.titleSmall, color = TextSecondary)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PortalType.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = type == option,
                        onClick = { type = option },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index, count = PortalType.entries.size
                        )
                    ) {
                        Text(option.name)
                    }
                }
            }

            when (type) {
                PortalType.STALKER -> {
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
                }
                PortalType.M3U -> {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("M3U / M3U8 URL") },
                        placeholder = { Text("https://example.com/playlist.m3u") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            cursorColor = Accent
                        )
                    )
                    Text(
                        "Remote playlist URL. Streams are played directly from the playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                PortalType.XTREAM -> {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("http://example.com:8080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            cursorColor = Accent
                        ),
                        supportingText = {
                            Text("Panel host:port (player_api.php is appended automatically)", color = TextSecondary)
                        }
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            cursorColor = Accent
                        )
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            cursorColor = Accent
                        )
                    )
                }
            }

            // Optional XMLTV EPG for this portal
            OutlinedTextField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                label = { Text("EPG XMLTV URL (optional)") },
                placeholder = { Text("https://epg.example.com/feed.xml") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    cursorColor = Accent
                ),
                supportingText = {
                    Text(
                        if (type == PortalType.STALKER)
                            "Optional: overrides the portal EPG with this XMLTV feed"
                        else
                            "Programme guide for M3U / Xtream channels (XMLTV)",
                        color = TextSecondary
                    )
                }
            )

            scanError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Accent)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    onSave(name, url, mac, portal?.id ?: 0, type, username, password, epgUrl)
                },
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
