package com.voidnullvalue.icseelocal.ui.cameralist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.ui.components.AppScaffold
import com.voidnullvalue.icseelocal.ui.components.GradientButton
import com.voidnullvalue.icseelocal.ui.components.IconBadge
import com.voidnullvalue.icseelocal.ui.components.SectionCard

@Composable
fun CameraListScreen(
    onOpenCamera: (String) -> Unit,
    onAddManual: () -> Unit,
    onAddDiscovered: (DiscoveryBeacon) -> Unit,
    onPairBluetooth: () -> Unit,
    onOpenSettings: (String) -> Unit,
    viewModel: CameraListViewModel = viewModel(),
) {
    val saved by viewModel.savedCameras.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val discovering by viewModel.discovering.collectAsState()
    var subnet by remember { mutableStateOf(viewModel.suggestedSubnet()) }

    AppScaffold(
        title = "iCSee Local Control",
        floatingActionButton = {
            FloatingActionButton(onClick = onAddManual, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Add, contentDescription = "Add camera manually")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                SectionCard(title = "Find cameras", icon = Icons.Default.Search) {
                    Text(
                        "Discover cameras on your network, or pair a new one over Bluetooth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        GradientButton(
                            text = if (discovering) "Scanning…" else "Scan network",
                            icon = Icons.Default.Search,
                            busy = discovering,
                            onClick = viewModel::refreshDiscovery,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        FilledTonalButton(onClick = onPairBluetooth) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Bluetooth")
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = subnet,
                            onValueChange = { subnet = it },
                            label = { Text("Scan subnet (VPN), e.g. 192.168.88") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        GradientButton(text = "Scan", enabled = !discovering && subnet.isNotBlank(), onClick = { viewModel.sweepSubnet(subnet) })
                    }
                }
            }

            item {
                Text(
                    "Saved cameras",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                )
            }
            if (saved.isEmpty()) {
                item {
                    Text(
                        "No saved cameras yet. Scan your network or tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(saved, key = { it.id }) { camera ->
                SavedCameraCard(camera, onClick = { onOpenCamera(camera.id) }, onSettings = { onOpenSettings(camera.id) })
            }

            if (discovered.isNotEmpty()) {
                item {
                    Text(
                        "Discovered on LAN",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }
                items(discovered, key = { it.identityKey }) { beacon ->
                    DiscoveredCameraCard(beacon, onAdd = { onAddDiscovered(beacon) })
                }
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun SavedCameraCard(camera: CameraDescriptor, onClick: () -> Unit, onSettings: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.Videocam)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(camera.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${camera.host}:${camera.dvripPort}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                camera.firmwareVersion?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiscoveredCameraCard(beacon: DiscoveryBeacon, onAdd: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.Videocam)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(beacon.hostName.ifBlank { beacon.hostIp }, style = MaterialTheme.typography.titleSmall)
                Text("${beacon.hostIp}:${beacon.tcpPort} · ${beacon.mac}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GradientButton(text = "Add", onClick = onAdd)
        }
    }
}
