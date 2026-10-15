// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.portal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.knightstalker.app.data.remote.ConnectionState
import org.knightstalker.app.domain.model.Portal
import org.knightstalker.app.domain.model.PortalType
import org.knightstalker.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalListScreen(
    viewModel: PortalViewModel,
    onAddPortal: () -> Unit,
    onEditPortal: (Long) -> Unit,
    onConnected: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()

    // Navigate home only after a genuine successful connect (auto-connect on first
    // launch, or the user tapping Connect). Entering this screen while already
    // connected (e.g. via Settings > "Manage All Portals") must NOT bounce to home.
    LaunchedEffect(state.pendingNavigateHome) {
        if (state.pendingNavigateHome) {
            viewModel.consumeNavigateHome()
            onConnected()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KnightStalker", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddPortal,
                containerColor = Accent,
                contentColor = TextPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Portal")
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        if (state.portals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.SettingsInputAntenna,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No portals configured",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Add a Stalker portal to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(state.portals, key = { it.id }) { portal ->
                    PortalCard(
                        portal = portal,
                        isConnected = state.connectedPortalId == portal.id,
                        isConnecting = state.connectionState == ConnectionState.CONNECTING && state.connectedPortalId == null,
                        onConnect = { viewModel.connect(portal) },
                        onDisconnect = { viewModel.disconnect() },
                        onEdit = { onEditPortal(portal.id) },
                        onDelete = { viewModel.deletePortal(portal) }
                    )
                }

                state.error?.let { error ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = Accent)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(error, color = Accent, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalCard(
    portal: Portal,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SettingsInputAntenna,
                contentDescription = null,
                tint = if (isConnected) SuccessGreen else TextSecondary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(portal.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = TextSecondary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            portal.type.name,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(portal.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                if (portal.type == PortalType.STALKER) {
                    Text("MAC: ${portal.macAddress}", style = MaterialTheme.typography.bodySmall)
                } else if (portal.type == PortalType.XTREAM) {
                    Text("User: ${portal.username}", style = MaterialTheme.typography.bodySmall)
                } else if (portal.epgUrl.isNotBlank()) {
                    Text("EPG: ${portal.epgUrl}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))

            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = Accent,
                    strokeWidth = 2.dp
                )
            } else if (isConnected) {
                FilledTonalButton(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("Connect")
                }
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary)
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextSecondary)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Portal") },
            text = { Text("Delete \"${portal.name}\"?") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Delete", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
