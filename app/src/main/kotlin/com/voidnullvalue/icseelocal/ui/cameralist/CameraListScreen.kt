package com.voidnullvalue.icseelocal.ui.cameralist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon
import com.voidnullvalue.icseelocal.model.CameraDescriptor

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("iCSee Local Control") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddManual) { Icon(Icons.Default.Add, contentDescription = "Add camera manually") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Saved cameras", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row {
                    Button(onClick = viewModel::refreshDiscovery, enabled = !discovering, modifier = Modifier.padding(end = 8.dp)) {
                        if (discovering) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        }
                        Text(if (discovering) "Scanning…" else "Refresh discovery")
                    }
                    Button(onClick = onPairBluetooth) { Text("Pair via Bluetooth") }
                }
            }

            // Broadcast discovery (above) can't cross a VPN; sweep the camera's
            // subnet by unicast instead. Prefilled from a saved camera's IP.
            var subnet by remember { mutableStateOf(viewModel.suggestedSubnet()) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = subnet,
                    onValueChange = { subnet = it },
                    label = { Text("Scan subnet (VPN), e.g. 192.168.88") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Button(onClick = { viewModel.sweepSubnet(subnet) }, enabled = !discovering && subnet.isNotBlank()) {
                    Text("Scan")
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(saved, key = { it.id }) { camera ->
                    SavedCameraRow(camera, onClick = { onOpenCamera(camera.id) }, onSettings = { onOpenSettings(camera.id) })
                }
                if (discovered.isNotEmpty()) {
                    item { Text("Discovered on LAN", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                    items(discovered, key = { it.identityKey }) { beacon ->
                        DiscoveredCameraRow(beacon, onAdd = { onAddDiscovered(beacon) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedCameraRow(camera: CameraDescriptor, onClick: () -> Unit, onSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(camera.displayName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text("${camera.host}:${camera.dvripPort}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                camera.firmwareVersion?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            }
            Row {
                Button(onClick = onClick, modifier = Modifier.padding(end = 8.dp)) { Text("Open") }
                Button(onClick = onSettings) { Text("Settings") }
            }
        }
    }
}

@Composable
private fun DiscoveredCameraRow(beacon: com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(beacon.hostName.ifBlank { beacon.hostIp })
                Text("${beacon.hostIp}:${beacon.tcpPort} · ${beacon.mac}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onAdd) { Text("Add") }
        }
    }
}
