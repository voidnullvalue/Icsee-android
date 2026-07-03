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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.ble.BleCameraBeacon
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
    val credentialChangeState by viewModel.credentialChangeState.collectAsState()
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showSetCredentialsDialog by remember { mutableStateOf(false) }

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
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("WiFi password") },
                            visualTransformation = PasswordVisualTransformation(),
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
                    Text("Paired!", style = MaterialTheme.typography.titleMedium)
                    Text("Camera joined the network at ${state.camera.host}", modifier = Modifier.padding(top = 4.dp))
                    // Credentials the camera reported back in its provisioning ACK -- these
                    // are what you log in with (username defaults to admin if the camera
                    // didn't assign one). Surface them so they aren't lost.
                    Text(
                        "Login: ${state.camera.username} / ${state.camera.password.ifBlank { "(no password)" }}",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.camera.mac?.let {
                        Text("MAC: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    Button(onClick = { onPaired(state.camera) }, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Continue to camera settings")
                    }
                }
                is BlePairingUiState.Failed -> {
                    if (state.errorCode == CameraBlePairingClient.ERROR_PROVISIONED_NO_ACK) {
                        // Not a real failure: the credentials were sent and the camera is
                        // joining WiFi; it just didn't report back over Bluetooth.
                        Text("Credentials sent", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The camera is joining your WiFi. It didn't report its details back " +
                                "over Bluetooth, so use its factory login below once it's on the network:",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Login: admin / (no password)",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "You can set a password afterwards in camera settings.",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
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

    if (pairingState is BlePairingUiState.Success) {
        val camera = (pairingState as BlePairingUiState.Success).camera
        when {
            credentialChangeState == CredentialChangeState.Idle -> {
                // Offer to set custom credentials right after pairing succeeds
                SetCredentialsPrompt(
                    camera.username,
                    camera.password,
                    onSetCredentials = { newUsername, newPassword ->
                        viewModel.changeRandomUserCredentials(camera.host, camera.username, camera.password, newUsername, newPassword)
                    },
                    onSkip = { onPaired(camera) },
                )
            }
            credentialChangeState == CredentialChangeState.InProgress -> {
                SetCredentialsWaitingDialog()
            }
            credentialChangeState == CredentialChangeState.Success -> {
                // Auto-proceed with updated credentials
                LaunchedEffect(Unit) { onPaired(camera) }
            }
            credentialChangeState is CredentialChangeState.Failed -> {
                val failedState = credentialChangeState as CredentialChangeState.Failed
                SetCredentialsErrorDialog(
                    errorMessage = failedState.detail,
                    onContinueAnyway = { onPaired(camera) },
                )
            }
        }
    }
}

@Composable
private fun SetCredentialsPrompt(currentUsername: String, currentPassword: String, onSetCredentials: (String, String) -> Unit, onSkip: () -> Unit) {
    var newUsername by remember { mutableStateOf(currentUsername) }
    var newPassword by remember { mutableStateOf(currentPassword) }
    Dialog(onDismissRequest = onSkip) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Set device login password", style = MaterialTheme.typography.titleMedium)
                Text("The camera currently has a random login assigned. You can set a custom one now, or skip and do it later.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = newUsername,
                    onValueChange = { newUsername = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Button(
                        onClick = { onSetCredentials(newUsername, newPassword) },
                        enabled = newUsername.isNotBlank(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Set") }
                    TextButton(onClick = onSkip) { Text("Skip") }
                }
            }
        }
    }
}

@Composable
private fun SetCredentialsWaitingDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp))
                    Text("Setting credentials…", style = MaterialTheme.typography.bodyMedium)
                }
                Text("(This may take a moment.)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SetCredentialsErrorDialog(errorMessage: String, onContinueAnyway: () -> Unit) {
    Dialog(onDismissRequest = onContinueAnyway) {
        Card {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Failed to set credentials", style = MaterialTheme.typography.titleMedium)
                Text(errorMessage, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Text("You can try again later in camera settings.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onContinueAnyway, modifier = Modifier.padding(top = 16.dp).align(Alignment.End)) { Text("Continue with random credentials") }
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
