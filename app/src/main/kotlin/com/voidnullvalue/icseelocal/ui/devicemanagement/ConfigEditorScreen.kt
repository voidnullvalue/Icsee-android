package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidnullvalue.icseelocal.config.ConfigFieldDocs
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

    com.voidnullvalue.icseelocal.ui.components.AppScaffold(title = label, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (value == null) {
                Text("Not available on this camera.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ConfigFieldDocs.configIntro(configName)?.let { intro ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Row(Modifier.padding(14.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(intro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val editable = remember(configName) { EditableJson.from(value) }
                if (configName == "Record") {
                    RecordScheduleEditor(editable)
                } else {
                    JsonEditorNode(name = label, node = editable, metadata = metadata)
                }
                if (state.busy) CircularProgressIndicator(Modifier.padding(top = 16.dp))
                state.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                com.voidnullvalue.icseelocal.ui.components.GradientButton(
                    text = "Save",
                    busy = state.busy,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    onClick = { viewModel.saveConfig(configName, editable.toJsonElement()) },
                )
            }
        }
    }
}
