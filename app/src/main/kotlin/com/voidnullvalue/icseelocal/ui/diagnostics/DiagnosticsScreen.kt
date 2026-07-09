package com.voidnullvalue.icseelocal.ui.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.ui.live.LiveControlViewModel

/**
 * Sanitized diagnostics only -- never plaintext passwords, AES keys, RSA
 * blocks, mic contents, or full video frames, per the task brief.
 *
 * Reuses the same [LiveControlViewModel] instance as the live control
 * screen (default Compose `viewModel()` scoping is per-Activity here, since
 * this app does not use Navigation-Compose) so the numbers shown are for
 * whichever camera is currently open, live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: LiveControlViewModel = viewModel(),
) {
    val camera by viewModel.camera.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val videoStats by viewModel.videoStats.collectAsState()

    com.voidnullvalue.icseelocal.ui.components.AppScaffold(title = "Diagnostics", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            DiagnosticRow("Connection state", state.label)
            DiagnosticRow("Camera address", camera?.let { "${it.host}:${it.dvripPort}" } ?: "-")
            DiagnosticRow(
                "Authenticated session ID",
                when (state) {
                    is ConnectionState.Authenticated -> "0x%08X".format((state as ConnectionState.Authenticated).sessionId.toLong())
                    is ConnectionState.Streaming -> "0x%08X".format((state as ConnectionState.Streaming).sessionId.toLong())
                    else -> "-"
                },
            )
            DiagnosticRow(
                "Keepalive interval",
                when (state) {
                    is ConnectionState.Authenticated -> "${(state as ConnectionState.Authenticated).aliveIntervalSeconds}s"
                    else -> "-"
                },
            )
            DiagnosticRow(
                "Last error",
                when (state) {
                    is ConnectionState.Failed -> (state as ConnectionState.Failed).reason
                    else -> "-"
                },
            )
            DiagnosticRow("Video bytes received", videoStats.videoBytesReceived.toString())
            DiagnosticRow("DVRIP media frames", videoStats.dvripMediaFrames.toString())
            DiagnosticRow("Detected codec", videoStats.detectedCodec.toString())
            DiagnosticRow("Frames decoded", videoStats.framesDecoded.toString())
            DiagnosticRow("Frames dropped", videoStats.framesDropped.toString())
            DiagnosticRow("Decoder errors", videoStats.decoderErrors.toString())
            DiagnosticRow("Video last error", videoStats.lastError ?: "-")
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        Text(value, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    }
}
