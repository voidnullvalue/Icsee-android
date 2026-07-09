package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.ui.components.AppScaffold
import com.voidnullvalue.icseelocal.ui.components.GradientButton
import com.voidnullvalue.icseelocal.ui.components.NavTile
import com.voidnullvalue.icseelocal.ui.components.SectionCard
import com.voidnullvalue.icseelocal.ui.components.StatusColors
import com.voidnullvalue.icseelocal.ui.components.StatusPill
import com.voidnullvalue.icseelocal.ui.components.RevealablePasswordField

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
    // Connecting/disconnecting is driven centrally by MainActivity (see
    // enterFocus/leaveFocus on the ViewModel) based on which device-management-
    // family screen is actually on screen -- not from here, since this composable
    // also unmounts when navigating to ConfigEditor/ImageSettings/PlaybackBrowser
    // (same family, same session must keep running).
    val state by viewModel.state.collectAsState()
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }

    AppScaffold(title = "Device management", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (label, dot) = when (state.connectionState) {
                    is ConnectionState.Authenticated -> "Connected" to StatusColors.ok
                    is ConnectionState.Failed -> "Connection failed" to StatusColors.bad
                    else -> "Connecting…" to StatusColors.warn
                }
                StatusPill(label, dot)
                Spacer(Modifier.weight(1f))
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            state.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
            state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }

            SectionCard(title = "Device info", icon = Icons.Default.Info) {
                val info = state.systemInfo
                if (info == null) {
                    Text("Not loaded yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SelectionContainer {
                        Column {
                            InfoRow("Model", info.deviceModel)
                            InfoRow("Hardware", "${info.hardware} (rev ${info.hardwareVersion})")
                            InfoRow("Firmware", info.softwareVersion)
                            InfoRow("Build", info.buildTime)
                            InfoRow("Serial", info.serialNo)
                            InfoRow("PID", info.pid)
                            InfoRow("Uptime", formatUptime(info.deviceRunTimeSeconds))
                        }
                    }
                }
            }

            SectionCard(title = "Device time", icon = Icons.Default.AccessTime) {
                Text(state.deviceTime ?: "Not queried yet", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                GradientButton("Query time", onClick = { viewModel.refreshTime() })
            }

            SectionCard(title = "SD card", icon = Icons.Default.SdStorage) {
                val summary = state.storageInfo?.let { storageSummary(it.toString()) }
                if (summary == null) {
                    Text("No card detected, or not loaded yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(summary.first, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { summary.second },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                Spacer(Modifier.height(14.dp))
                NavTile(icon = Icons.Default.Videocam, title = "Recordings", subtitle = "Browse & download clips", onClick = onOpenRecordings)
                Spacer(Modifier.height(8.dp))
                DangerButton("Format card", enabled = state.storageInfo != null) { viewModel.requestFormat() }
            }

            SectionCard(title = "Settings", icon = Icons.Default.Tune) {
                val imageAvailable = state.configValues.containsKey("Camera.Param") || state.configValues.containsKey("Camera.ParamEx")
                NavTile(
                    icon = Icons.Default.Tune,
                    title = "Image settings",
                    subtitle = if (imageAvailable) "Exposure, day/night, white balance…" else "Not available on this camera",
                    enabled = imageAvailable,
                    onClick = onOpenImageSettings,
                )
            }

            SectionCard(title = "Advanced settings", icon = Icons.Default.Tune) {
                AdvancedConfig.entries.forEach { cfg ->
                    val available = state.configValues.containsKey(cfg.configName)
                    NavTile(
                        icon = Icons.Default.Tune,
                        title = cfg.label,
                        subtitle = if (available) null else "Unsupported by this camera",
                        enabled = available,
                        onClick = { onOpenConfig(cfg.configName, cfg.label) },
                    )
                }
            }

            SectionCard(title = "Login", icon = Icons.Default.Lock) {
                NavTile(icon = Icons.Default.Person, title = "Change username", onClick = { showUsernameDialog = true })
                Spacer(Modifier.height(8.dp))
                NavTile(icon = Icons.Default.Password, title = "Change password", onClick = { showPasswordDialog = true })
            }

            SectionCard(title = "Accounts on this camera", icon = Icons.Default.ManageAccounts) {
                Text(
                    "Shows the camera's own account list. The blank-password \"admin\" backdoor is " +
                        "flagged \"factory test account\"; the real per-device admin is a random name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                GradientButton(
                    text = if (state.accountsQuerying) "Reading…" else "Show accounts",
                    busy = state.accountsQuerying,
                    enabled = state.connectionState is ConnectionState.Authenticated && !state.accountsQuerying,
                    onClick = { viewModel.loadAccounts() },
                )
                state.accounts?.let { accounts ->
                    if (accounts.isEmpty()) {
                        Text("No accounts returned.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        SelectionContainer { Column { accounts.forEach { AccountRow(it) } } }
                    }
                }
            }

            SectionCard(title = "Power", icon = Icons.Default.PowerSettingsNew) {
                DangerButton("Reboot camera", icon = Icons.Default.RestartAlt) { viewModel.requestReboot() }
            }
            Spacer(Modifier.height(24.dp))
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

/** Destructive action button (error-tinted, rounded). */
@Composable
private fun DangerButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** Parses TotalSpace/RemainSpace (MB, hex or decimal) into a "X GB free of Y GB" + used fraction. */
private fun storageSummary(json: String): Pair<String, Float>? {
    fun num(key: String) = Regex("\"$key\"\\s*:\\s*\"?(0x[0-9a-fA-F]+|\\d+)").find(json)?.groupValues?.get(1)
    val total = num("TotalSpace") ?: return null
    val remain = num("RemainSpace") ?: return null
    fun parse(s: String) = if (s.startsWith("0x")) s.substring(2).toLong(16) else s.toLong()
    val totalMb = parse(total)
    val remainMb = parse(remain)
    if (totalMb <= 0) return null
    val usedMb = (totalMb - remainMb).coerceIn(0, totalMb)
    val gb = 1024.0
    return "%.1f GB free of %.1f GB".format(remainMb / gb, totalMb / gb) to (usedMb.toFloat() / totalMb.toFloat())
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccountRow(account: DeviceAccount) {
    val isFactoryTest = account.memo.contains("factory test", ignoreCase = true)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row {
            Text(account.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleSmall)
            if (isFactoryTest) {
                Text(
                    "  ⚠ factory test",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (account.memo.isNotBlank()) {
            Text("memo: ${account.memo}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "group: ${account.group.ifBlank { "-" }}   " +
                "password: ${if (account.hasPassword || account.hasPasswordV2) "set" else "blank"}" +
                (if (account.reserved) "   reserved" else "") +
                (if (account.sharable) "   sharable" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
