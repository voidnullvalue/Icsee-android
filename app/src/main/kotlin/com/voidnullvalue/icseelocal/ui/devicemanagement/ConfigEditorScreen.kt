package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.config.ConfigMetadataCache

/**
 * Generic editor for one named DVRIP config (Camera.Param, Detect.MotionDetect,
 * General.General, ...) -- see EditableJson.kt for why this is one reusable
 * screen rather than a bespoke UI per config name. This app navigates by
 * swapping Composables in a single Activity (no nested NavHost), so
 * `viewModel()` here resolves to the SAME [DeviceManagementViewModel] instance
 * [DeviceManagementScreen] already loaded (Compose's default ViewModel scoping
 * keys by class against the single Activity-level store) -- no manual
 * instance-passing needed, and this screen reads the config value that
 * screen already fetched rather than reconnecting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(
    configName: String,
    label: String,
    onBack: () -> Unit,
    viewModel: DeviceManagementViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val value = state.configValues[configName]
    var metadata by remember { mutableStateOf<ConfigMetadataCache?>(null) }

    LaunchedEffect(configName) {
        metadata = viewModel.getConfigMetadata(configName)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(label) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (value == null) {
                Text("Not available on this camera.")
            } else {
                val editable = remember(configName) { EditableJson.from(value) }
                JsonEditorNode(name = label, node = editable, metadata = metadata)
                if (state.busy) CircularProgressIndicator(Modifier.padding(top = 16.dp))
                state.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                Button(
                    onClick = { viewModel.saveConfig(configName, editable.toJsonElement()) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Save") }
            }
            Button(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
        }
    }
}
