package com.voidnullvalue.icseelocal.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.voidnullvalue.icseelocal.ble.BlePairedCamera
import com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon
import com.voidnullvalue.icseelocal.model.StreamType
import com.voidnullvalue.icseelocal.video.RtspUrlBuilder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsScreen(
    cameraId: String?,
    prefillBeacon: DiscoveryBeacon? = null,
    prefillBle: BlePairedCamera? = null,
    onDone: () -> Unit,
    viewModel: CameraSettingsViewModel = viewModel(),
) {
    LaunchedEffect(cameraId, prefillBeacon, prefillBle) {
        when {
            prefillBeacon != null -> viewModel.loadFromDiscovery(prefillBeacon)
            prefillBle != null -> viewModel.loadFromBlePairing(prefillBle)
            else -> viewModel.load(cameraId)
        }
    }
    val state by viewModel.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text(if (state.isExisting) "Camera settings" else "Add camera") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = { v -> viewModel.update { it.copy(displayName = v) } },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = state.host,
                onValueChange = { v -> viewModel.update { it.copy(host = v) } },
                label = { Text("Host / IP address") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = state.dvripPort,
                onValueChange = { v -> viewModel.update { it.copy(dvripPort = v.filter { c -> c.isDigit() }) } },
                label = { Text("DVRIP port") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = state.channel,
                onValueChange = { v -> viewModel.update { it.copy(channel = v.filter { c -> c.isDigit() }) } },
                label = { Text("Channel") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                label = { Text("Username") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.password,
                onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                label = { Text("Password") },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("RTSP fallback", modifier = Modifier.padding(top = 12.dp, end = 8.dp))
                Switch(
                    checked = state.rtspFallbackEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(rtspFallbackEnabled = v) } },
                )
            }
            if (state.rtspFallbackEnabled) {
                OutlinedTextField(
                    value = state.rtspPort,
                    onValueChange = { v -> viewModel.update { it.copy(rtspPort = v.filter { c -> c.isDigit() }) } },
                    label = { Text("RTSP port") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            if (state.host.isNotBlank()) {
                // Convenience: the exact RTSP URL this app plays, so it can be pasted
                // into VLC/ffmpeg/another NVR. Channel is descriptor 0-based + 1 (the
                // camera's RTSP path is 1-based); stream 0 = main, 1 = sub. Mirrors
                // RtspVideoPlayer.start(). Username falls back to the factory "admin".
                val clipboard = LocalClipboardManager.current
                val rtspUrl = remember(state.host, state.rtspPort, state.username, state.password, state.channel, state.streamType) {
                    RtspUrlBuilder.build(
                        host = state.host,
                        port = state.rtspPort.toIntOrNull() ?: 554,
                        username = state.username.ifBlank { RtspUrlBuilder.FALLBACK_USERNAME },
                        password = state.password,
                        channel = (state.channel.toIntOrNull() ?: 0) + 1,
                        mainStream = state.streamType == StreamType.MAIN,
                    )
                }
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }

                Text(
                    "RTSP stream URL",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                SelectionContainer {
                    Text(
                        rtspUrl,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                    Button(onClick = { clipboard.setText(AnnotatedString(rtspUrl)); copied = true }) {
                        Text(if (copied) "Copied!" else "Copy URL")
                    }
                }
            }

            Text(
                "Default credentials: admin / admin",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 4.dp),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Button(onClick = viewModel::testConnection, enabled = !state.testing && state.host.isNotBlank()) {
                    if (state.testing) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text(if (state.testing) "Testing..." else "Test connection")
                }
            }
            state.testResult?.let {
                Text(it, modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }

            Row(Modifier.fillMaxWidth()) {
                Button(onClick = { viewModel.save(onDone) }, modifier = Modifier.padding(end = 8.dp)) { Text("Save") }
                if (state.isExisting) {
                    Button(onClick = { viewModel.delete(onDone) }) { Text("Delete") }
                }
            }
        }
    }
}
