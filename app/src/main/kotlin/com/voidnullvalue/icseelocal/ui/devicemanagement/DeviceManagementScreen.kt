package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.ui.components.RevealablePasswordField
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagementScreen(
    cameraId: String,
    onOpenConfig: (configName: String, label: String) -> Unit,
    onOpenImageSettings: () -> Unit,
    onOpenRecordings: () -> Unit,
    onBack: () -> Unit,
    viewModel: DeviceManagementViewModel = viewModel(),
) {
    LaunchedEffect(cameraId) { viewModel.load(cameraId) }
    val state by viewModel.state.collectAsState()
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Device management") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                when (state.connectionState) {
                    is ConnectionState.Authenticated -> "Connected"
                    is ConnectionState.Failed -> "Connection failed"
                    else -> "Connecting…"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (state.busy) CircularProgressIndicator(Modifier.padding(bottom = 8.dp))
            state.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp)) }

            // -- Device info --
            SectionCard(title = "Device info") {
                val info = state.systemInfo
                if (info == null) {
                    Text("Not loaded yet", style = MaterialTheme.typography.bodySmall)
                } else {
                    InfoRow("Model", info.deviceModel)
                    InfoRow("Hardware", "${info.hardware} (rev ${info.hardwareVersion})")
                    InfoRow("Firmware", info.softwareVersion)
                    InfoRow("Build", info.buildTime)
                    InfoRow("Serial", info.serialNo)
                    InfoRow("PID", info.pid)
                    InfoRow("Uptime", formatUptime(info.deviceRunTimeSeconds))
                }
            }

            // -- Time --
            SectionCard(title = "Device time") {
                Text(state.deviceTime ?: "Not queried yet", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { viewModel.refreshTime() }) { Text("Query time") }
                }
            }

            // -- Storage --
            SectionCard(title = "SD card") {
                val storage = state.storageInfo
                if (storage == null) {
                    Text("No card detected, or not loaded yet", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(storage.toString(), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = onOpenRecordings, modifier = Modifier.padding(end = 8.dp)) { Text("Recordings") }
                    Button(
                        onClick = { viewModel.requestFormat() },
                        enabled = storage != null,
                    ) { Text("Format card") }
                }
            }

            // -- Friendly settings screens --
            SectionCard(title = "Settings") {
                val imageAvailable = state.configValues.containsKey("Camera.Param") ||
                    state.configValues.containsKey("Camera.ParamEx")
                Button(onClick = onOpenImageSettings, enabled = imageAvailable) {
                    Text(if (imageAvailable) "Image settings" else "Image settings (not available)")
                }
            }

            // -- Advanced named configs (generic JSON editor) --
            SectionCard(title = "Advanced settings") {
                AdvancedConfig.entries.forEach { cfg ->
                    val available = state.configValues.containsKey(cfg.configName)
                    TextButton(
                        onClick = { onOpenConfig(cfg.configName, cfg.label) },
                        enabled = available,
                    ) {
                        Text(if (available) cfg.label else "${cfg.label} (unsupported by this camera)")
                    }
                }
            }

            // -- Change credentials --
            SectionCard(title = "Login") {
                Row {
                    Button(onClick = { showUsernameDialog = true }, modifier = Modifier.padding(end = 8.dp)) { Text("Change username") }
                    Button(onClick = { showPasswordDialog = true }) { Text("Change password") }
                }
            }

            // -- Reboot --
            SectionCard(title = "Power") {
                Button(onClick = { viewModel.requestReboot() }) { Text("Reboot camera") }
            }

            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
        }
    }

    if (state.rebootRequested) {
        RebootConfirmDialog(onConfirm = { viewModel.reboot() }, onDismiss = { viewModel.cancelReboot() })
    }
    if (state.formatRequested) {
        FormatConfirmDialog(onConfirm = { viewModel.formatSdCard() }, onDismiss = { viewModel.cancelFormat() })
    }
    if (showPasswordDialog) {
        ChangePasswordDialog(
            busy = state.passwordChangeInFlight,
            onConfirm = { newPassword ->
                viewModel.changePassword(newPassword)
                showPasswordDialog = false
            },
            onDismiss = { showPasswordDialog = false },
        )
    }
    if (showUsernameDialog) {
        ChangeUsernameDialog(
            busy = state.busy,
            onConfirm = { newUsername ->
                viewModel.changeUsername(newUsername)
                showUsernameDialog = false
            },
            onDismiss = { showUsernameDialog = false },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return "${days}d ${hours}h ${minutes}m"
}

@Composable
private fun RebootConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Reboot camera?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The camera will disconnect and be unreachable for about a minute while it restarts.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                Row {
                    Button(onClick = onConfirm, modifier = Modifier.padding(end = 8.dp)) { Text("Reboot") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun FormatConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Format SD card?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This permanently erases ALL recordings on the card. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                Row {
                    Button(onClick = onConfirm, modifier = Modifier.padding(end = 8.dp)) { Text("Erase everything") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ChangePasswordDialog(busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val mismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Change device login password", style = MaterialTheme.typography.titleMedium)
                RevealablePasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = "New password",
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                RevealablePasswordField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = "Confirm new password",
                    isError = mismatch,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (mismatch) {
                    Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.padding(top = 16.dp)) {
                    Button(
                        onClick = { onConfirm(newPassword) },
                        enabled = !busy && newPassword.isNotBlank() && !mismatch,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (busy) "Saving…" else "Save") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ChangeUsernameDialog(busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var newUsername by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Change device login username", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The camera will be reconnected under the new username. The password is unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = newUsername,
                    onValueChange = { newUsername = it },
                    label = { Text("New username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 16.dp)) {
                    Button(
                        onClick = { onConfirm(newUsername) },
                        enabled = !busy && newUsername.isNotBlank(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (busy) "Saving…" else "Save") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
