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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Friendly, purpose-built controls for the two image configs (Camera.Param and
 * Camera.ParamEx) -- switches, a slider and a mode picker instead of the raw
 * hex/int fields the generic [ConfigEditorScreen] would show. Binds directly to
 * the [EditableJson] leaf [EditableJson.Prim.text] state, so "Save" just writes
 * the whole (mutated) config back through the same path everything else uses.
 * Shares the single [DeviceManagementViewModel] instance (see ConfigEditorScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSettingsScreen(
    onBack: () -> Unit,
    viewModel: DeviceManagementViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val param = state.configValues["Camera.Param"]
    val paramEx = state.configValues["Camera.ParamEx"]

    // Parse once per underlying config so edits accumulate in the leaf states.
    val editableParam = remember(param) { param?.let { EditableJson.from(it) } }
    val editableParamEx = remember(paramEx) { paramEx?.let { EditableJson.from(it) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Image settings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (editableParam == null && editableParamEx == null) {
                Text("Image settings aren't loaded yet, or this camera doesn't expose them.")
                Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
                return@Column
            }

            editableParam?.let { p ->
                SettingsCard("Orientation") {
                    HexSwitch("Flip vertically", "For ceiling-mounted cameras.", p.at(0, "PictureFlip"))
                    HexSwitch("Mirror horizontally", "Flip the picture left-to-right.", p.at(0, "PictureMirror"))
                }
                SettingsCard("Low-light gain") {
                    IntSwitch("Auto gain", "Boost signal automatically in the dark (brighter, noisier).", p.at(0, "GainParam", "AutoGain"))
                    IntSlider("Gain level", 0..100, p.at(0, "GainParam", "Gain"))
                }
            }

            editableParamEx?.let { px ->
                SettingsCard("Day / night") {
                    ModePicker(
                        "Mode",
                        listOf(0 to "Auto", 1 to "Day", 2 to "Night", 3 to "Scheduled"),
                        px.at(0, "DayNightSwitch", "SwitchMode"),
                    )
                    IntSwitch("Night enhancement", "Extra brightening of the night image.", px.at(0, "NightEnhance"))
                    IntSwitch("Low-light mode", "Slower shutter for a brighter picture in very low light.", px.at(0, "LowLuxMode"))
                }
            }

            if (state.busy) CircularProgressIndicator(Modifier.padding(top = 8.dp))
            state.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }

            Button(
                onClick = {
                    editableParam?.let { viewModel.saveConfig("Camera.Param", it.toJsonElement()) }
                    editableParamEx?.let { viewModel.saveConfig("Camera.ParamEx", it.toJsonElement()) }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Save") }
            Button(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
        }
    }
}

/** Walks an [EditableJson] tree by object keys (String) / array indices (Int) to a leaf primitive. */
private fun EditableJson.at(vararg path: Any): EditableJson.Prim? {
    var cur: EditableJson? = this
    for (seg in path) {
        cur = when {
            cur is EditableJson.Obj && seg is String -> cur.entries.firstOrNull { it.first == seg }?.second
            cur is EditableJson.Arr && seg is Int -> cur.items.getOrNull(seg)
            else -> null
        }
    }
    return cur as? EditableJson.Prim
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun LabelBlock(label: String, desc: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (desc != null) Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Hex on/off flag ("0x00000001"/"0x00000000"). */
@Composable
private fun HexSwitch(label: String, desc: String, prim: EditableJson.Prim?) {
    if (prim == null) return
    var t by prim.text
    val on = t.trim().let { it.isNotEmpty() && it != "0x00000000" && it != "0" }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LabelBlock(label, desc, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { t = if (it) "0x00000001" else "0x00000000" })
    }
}

/** Integer on/off flag ("1"/"0"). */
@Composable
private fun IntSwitch(label: String, desc: String, prim: EditableJson.Prim?) {
    if (prim == null) return
    var t by prim.text
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LabelBlock(label, desc, Modifier.weight(1f))
        Switch(checked = t.trim() == "1", onCheckedChange = { t = if (it) "1" else "0" })
    }
}

@Composable
private fun IntSlider(label: String, range: IntRange, prim: EditableJson.Prim?) {
    if (prim == null) return
    var t by prim.text
    val value = t.trim().toIntOrNull()?.coerceIn(range) ?: range.first
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$value", style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { t = it.toInt().toString() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun ModePicker(label: String, options: List<Pair<Int, String>>, prim: EditableJson.Prim?) {
    if (prim == null) return
    var t by prim.text
    val selected = t.trim().toIntOrNull()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth()) {
            options.forEach { (v, name) ->
                FilterChip(
                    selected = selected == v,
                    onClick = { t = v.toString() },
                    label = { Text(name, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
            }
        }
    }
}
