// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.settings

import android.graphics.Bitmap
import org.knightstalker.app.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.knightstalker.app.data.remote.ConnectionState
import org.knightstalker.app.domain.model.PortalType
import org.knightstalker.app.ui.theme.*

@Serializable
private data class QrPortalData(
    val n: String,
    val u: String,
    val m: String
)

private fun formatLastSync(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Never"
    val fmt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochMillis))
}

private fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onPortals: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showQrDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf<QrPortalData?>(null) }
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
                    showImportDialog = data
                    scanError = null
                }
            } catch (_: Exception) {
                scanError = "Invalid QR code format"
            }
        }
    }

    // QR Display Dialog
    if (showQrDialog && state.connectedPortal != null) {
        val portal = state.connectedPortal!!
        val qrData = json.encodeToString(QrPortalData(
            n = portal.name,
            u = portal.url,
            m = portal.macAddress
        ))
        val qrBitmap = remember(qrData) { generateQrBitmap(qrData) }

        Dialog(onDismissRequest = { showQrDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Share Connection", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Scan this QR on another device",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(280.dp)
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(portal.name, style = MaterialTheme.typography.bodyMedium)
                    Text(portal.url, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { showQrDialog = false }) {
                        Text("Close")
                    }
                }
            }
        }
    }

    // Import Confirmation Dialog
    showImportDialog?.let { data ->
        AlertDialog(
            onDismissRequest = { showImportDialog = null },
            title = { Text("Import Portal") },
            text = {
                Column {
                    Text("Name: ${data.n}")
                    Text("URL: ${data.u}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("MAC: ${data.m}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.importPortal(data.n, data.u, data.m)
                        showImportDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = null }) {
                    Text("Cancel")
                }
            },
            containerColor = CardBackground
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(8.dp))

        // Connection settings — inline URL & MAC editing
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    if (state.connectionState == ConnectionState.CONNECTED) {
                        Text(
                            "Connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = SuccessGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.portalName,
                    onValueChange = { viewModel.updatePortalName(it) },
                    label = { Text("Portal Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.portalUrl,
                    onValueChange = { viewModel.updatePortalUrl(it) },
                    label = { Text("Portal URL") },
                    placeholder = { Text("http://example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                when (state.portalType) {
                    PortalType.STALKER -> OutlinedTextField(
                        value = state.portalMac,
                        onValueChange = { viewModel.updatePortalMac(it) },
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

                    PortalType.M3U -> Text(
                        "Remote playlist. Streams are played directly from the playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    PortalType.XTREAM -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.portalUsername,
                            onValueChange = { viewModel.updatePortalUsername(it) },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                cursorColor = Accent
                            )
                        )
                        OutlinedTextField(
                            value = state.portalPassword,
                            onValueChange = { viewModel.updatePortalPassword(it) },
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

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.saveAndReconnect() },
                        enabled = !state.isSaving && state.connectedPortal != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = TextPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Saving...")
                        } else {
                            Icon(
                                if (state.saveSuccess) Icons.Default.Check else Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (state.saveSuccess) "Saved & Reconnected" else "Save & Reconnect")
                        }
                    }
                }
            }
        }

        // QR Code sharing
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Share / Import", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showQrDialog = true },
                        enabled = state.connectedPortal != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Show QR")
                    }
                    OutlinedButton(
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
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan QR")
                    }
                }
                scanError?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Accent)
                }
                if (state.importSuccess) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Portal imported successfully!", style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
                }
            }
        }

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
                    Text("Manage All Portals")
                }
            }
        }

        // Content settings
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Content", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Category shown when you open Movies / Series",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Default category", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(6.dp))
                var categoryMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceVariant)
                            .clickable { categoryMenu = true }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            state.defaultCategory,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary)
                    }
                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = { categoryMenu = false },
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        state.categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    categoryMenu = false
                                    viewModel.setDefaultCategory(cat)
                                },
                                leadingIcon = {
                                    if (cat == state.defaultCategory) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Accent)
                                    }
                                }
                            )
                        }
                    }
                }

                // --- Library (local media index) ---
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = DarkSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))

                Text("Library", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Local index served to Movies / Series browsing",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                val indexing = state.movieStatus == "CRAWLING" || state.movieStatus == "DELTA" ||
                    state.seriesStatus == "CRAWLING" || state.seriesStatus == "DELTA"
                val error = state.movieStatus == "ERROR" || state.seriesStatus == "ERROR"
                val statusLabel = when {
                    indexing -> "Indexing…"
                    error -> "Error"
                    else -> "Idle"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Status", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (error) Accent else if (indexing) Accent else SuccessGreen
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Items", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text(
                        "${state.movieCount} movies · ${state.seriesCount} series",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Last sync", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text(formatLastSync(state.lastSyncAt), style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-update on open", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Refresh the index when Movies / Series opens",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = state.autoUpdateOnOpen,
                        onCheckedChange = { viewModel.setAutoUpdateOnOpen(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.5f))
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.rebuildLibrary() },
                    enabled = !indexing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rebuild library now")
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

                val bufferSec = state.bufferSizeMs / 1000
                val bufferLabel = if (bufferSec >= 60) "${bufferSec / 60}min ${bufferSec % 60}s" else "${bufferSec}s"
                Text("Buffer Size: $bufferLabel", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = state.bufferSizeMs.toFloat(),
                    onValueChange = { viewModel.setBufferSize(it.toInt()) },
                    valueRange = 2000f..600000f,
                    steps = 59,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                )
            }
        }

        // About
        Card(colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("KnightStalker v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("Open source Stalker IPTV client", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("License: GPL-3.0-or-later", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Series ratings by TVmaze (CC BY-SA)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
