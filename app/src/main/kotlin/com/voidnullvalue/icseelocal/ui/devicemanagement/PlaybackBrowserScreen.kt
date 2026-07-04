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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recorded-clip browser: pick a date, query the SD card via OPFileQuery, and
 * list what was recorded. Shares the single [DeviceManagementViewModel] (see
 * ConfigEditorScreen). In-app playback of a clip is intentionally not offered --
 * the DVRIP recorded-media stream is the same one still unresolved for live
 * view, which uses RTSP instead (see PROTOCOL_STATUS.md); this lists what's on
 * the card rather than streaming it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackBrowserScreen(
    onBack: () -> Unit,
    viewModel: DeviceManagementViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var date by remember { mutableStateOf(today) }

    Scaffold(topBar = { TopAppBar(title = { Text("Recordings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text("Date (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.queryRecordings(date) },
                enabled = !state.recordingsQuerying,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(if (state.recordingsQuerying) "Searching…" else "Find recordings") }

            if (state.recordingsQuerying) CircularProgressIndicator(Modifier.padding(top = 12.dp))
            state.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            val recordings = state.recordings
            if (recordings != null && !state.recordingsQuerying) {
                if (recordings.isEmpty()) {
                    Text("No recordings found for $date.", modifier = Modifier.padding(top = 12.dp))
                } else {
                    Text("${recordings.size} clip(s):", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                    recordings.forEach { f -> RecordingRow(f) }
                }
            }

            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
        }
    }
}

@Composable
private fun RecordingRow(f: RecordedFile) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("${timeOnly(f.beginTime)} – ${timeOnly(f.endTime)}", style = MaterialTheme.typography.bodyLarge)
            if (f.sizeText.isNotBlank()) {
                Text("size ${f.sizeText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (f.fileName.isNotBlank()) {
                Text(f.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** OPFileQuery times are "YYYY-MM-DD hh:mm:ss"; show just the clock part when present. */
private fun timeOnly(t: String): String = t.substringAfter(' ', t).ifBlank { t }
