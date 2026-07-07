package com.voidnullvalue.icseelocal.ui.blepairing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.ble.BleCameraBeacon
import com.voidnullvalue.icseelocal.ui.components.RevealablePasswordField
import com.voidnullvalue.icseelocal.ble.BlePairedCamera
import com.voidnullvalue.icseelocal.ble.CameraBlePairingClient

private val requiredBlePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlePairingScreen(
    onPaired: (BlePairedCamera) -> Unit,
    onCancel: () -> Unit,
    viewModel: BlePairingViewModel = viewModel(),
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(hasAllPermissions(context, requiredBlePermissions))
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermission = results.values.all { it }
    }

    val beacons by viewModel.beacons.collectAsState()
    val scanError by viewModel.scanError.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.startScan() else permissionLauncher.launch(requiredBlePermissions)
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair camera via Bluetooth") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            when (val state = pairingState) {
                is BlePairingUiState.Idle -> {
                    if (!hasPermission) {
                        Text("Bluetooth permission is required to scan for cameras.")
                    } else {
                        Text("Scanning for cameras in pairing mode…", style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            beacons.forEach { beacon ->
                                BeaconRow(beacon, selected = beacon.address == selectedAddress, onClick = { selectedAddress = beacon.address })
                            }
                            if (beacons.isEmpty()) {
                                val err = scanError
                                if (err != null) {
                                    Text("Scan error: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text("No cameras found yet -- make sure it's powered on and in pairing mode.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = ssid,
                            onValueChange = { ssid = it },
                            label = { Text("WiFi network name (SSID)") },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                        RevealablePasswordField(
                            value = password,
                            onValueChange = { password = it },
                            label = "WiFi password",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Button(
                                onClick = { selectedAddress?.let { viewModel.pair(it, ssid, password) } },
                                enabled = selectedAddress != null && ssid.isNotBlank(),
                                modifier = Modifier.padding(end = 8.dp),
                            ) { Text("Pair") }
                            Button(onClick = onCancel) { Text("Cancel") }
                        }
                    }
                }
                is BlePairingUiState.Pairing -> {
                    Row(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Pairing -- sending WiFi credentials over Bluetooth…")
                    }
                }
                is BlePairingUiState.Success -> {
                    Text("✓ Paired", style = MaterialTheme.typography.titleMedium)
                    Text("Camera joined the network at ${state.camera.host}", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)

                    // Highlight the provisioned credentials prominently so they're not lost.
                    // These come from the camera's ACK and will be saved when the user continues.
                    if (state.camera.xkfuUsername != null && state.camera.xkfuPassword != null) {
                        // Show real provisioned account (not factory admin)
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Your login credentials (created during provisioning):", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "Username: ${state.camera.xkfuUsername}",
                                    modifier = Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Password: ${state.camera.xkfuPassword}",
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "⚠ Save these credentials! You'll need them to access the camera.",
                                    modifier = Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                state.camera.mac?.let {
                                    Text("MAC: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    } else {
                        // Fallback: show admin account from ACK
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Your login credentials (from provisioning ACK):", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "Username: ${state.camera.username}",
                                    modifier = Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Password: ${state.camera.password.ifBlank { "(no password)" }}",
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                state.camera.mac?.let {
                                    Text("MAC: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                    }

                    Button(onClick = { onPaired(state.camera) }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Text("Continue to camera settings")
                    }
                }
                is BlePairingUiState.Failed -> {
                    if (state.errorCode == CameraBlePairingClient.ERROR_PROVISIONED_NO_ACK) {
                        // Not a real failure: the credentials were sent and the camera is
                        // joining WiFi; it just didn't report back over Bluetooth.
                        Text("✓ Credentials sent to camera", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The camera is joining your WiFi network. Bluetooth dropped before it could report back, but the provisioning was successful.",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("⚠ Unable to retrieve assigned credentials", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "Use the factory login to add the camera by IP address. Once added, you can change the password.",
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Factory login: admin / (no password)",
                                    modifier = Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                    } else {
                        val errorMessage = when (state.errorCode) {
                            CameraBlePairingClient.ERROR_BLUETOOTH_DISABLED -> "Bluetooth is disabled. Please enable Bluetooth and try again."
                            else -> "Pairing failed (code ${state.errorCode}). Make sure the camera is in pairing mode and try again."
                        }
                        Text(errorMessage, style = MaterialTheme.typography.titleMedium)
                    }
                    state.detail?.let {
                        Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Button(onClick = { viewModel.reset(); viewModel.startScan() }, modifier = Modifier.padding(end = 8.dp)) { Text("Retry") }
                        Button(onClick = onCancel) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeaconRow(beacon: BleCameraBeacon, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(beacon.name ?: beacon.address, style = MaterialTheme.typography.titleSmall)
                Text(beacon.address, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
