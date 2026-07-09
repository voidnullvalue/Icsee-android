package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voidnullvalue.icseelocal.config.ConfigFieldDocs
import com.voidnullvalue.icseelocal.config.ConfigMetadataCache
import kotlin.math.roundToLong

/**
 * Recursive editor for an [EditableJson] tree. Each leaf is rendered with a
 * control matched to what the setting actually is -- a Switch for on/off flags,
 * a dropdown of friendly options for enums ([ConfigFieldDocs] value maps), a
 * slider for numeric ranges (inferred [ConfigMetadataCache] constraints), and a
 * plain field only as a last resort -- instead of exposing raw protocol values.
 */
@Composable
fun JsonEditorNode(
    name: String,
    node: EditableJson,
    depth: Int = 0,
    metadata: ConfigMetadataCache? = null,
    pathPrefix: String = "",
) {
    // No indentation: every field aligns to the same left edge. Hierarchy is
    // shown with section headers + spacing instead of stair-stepping.
    when (node) {
        is EditableJson.Obj -> {
            if (depth > 0) {
                Text(
                    ConfigFieldDocs.humanize(name),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            node.entries.forEach { (key, value) ->
                val newPath = if (pathPrefix.isEmpty()) key else "$pathPrefix/$key"
                JsonEditorNode(key, value, depth + 1, metadata, newPath)
            }
        }
        is EditableJson.Arr -> {
            node.items.forEachIndexed { i, item ->
                val newPath = if (pathPrefix.isEmpty()) "[$i]" else "$pathPrefix/[$i]"
                // Only number the entries when there's more than one.
                val itemName = if (node.items.size > 1) "${ConfigFieldDocs.humanize(name)} ${i + 1}" else name
                JsonEditorNode(itemName, item, depth, metadata, newPath)
            }
        }
        is EditableJson.Null -> {
            Text(
                "${ConfigFieldDocs.humanize(name)}: —",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
        is EditableJson.Prim -> PrimEditor(name, node, metadata, pathPrefix)
    }
}

@Composable
private fun PrimEditor(
    name: String,
    node: EditableJson.Prim,
    metadata: ConfigMetadataCache?,
    pathPrefix: String,
) {
    var text by node.text
    val doc = ConfigFieldDocs.forKey(name)
    val constraints = metadata?.fields?.get(pathPrefix)
    val labelText = doc?.label ?: ConfigFieldDocs.humanize(name)
    val desc = doc?.description
    val values = doc?.values ?: emptyMap()
    val isOnOff = values.values.toSet() == setOf("Off", "On")

    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
        when {
            node.kind == EditableJson.Prim.Kind.BOOLEAN ->
                SwitchField(labelText, desc, (text.toBooleanStrictOrNull() ?: false)) { text = it.toString() }

            isOnOff ->
                SwitchField(labelText, desc, values[text] == "On") { on ->
                    text = pickOnOffKey(values, on, text.startsWith("0x"))
                }

            values.isNotEmpty() ->
                EnumDropdown(labelText, desc, text, values) { text = it }

            constraints?.kind == "INT" && constraints.minInt != null && constraints.maxInt != null ->
                IntSliderField(labelText, desc, text, constraints.minInt!!, constraints.maxInt!!) { text = it }

            else -> {
                val decoded = doc?.values?.get(text)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(labelText, fontSize = 12.sp) },
                    supportingText = if (decoded != null) ({ Text("= $decoded", fontSize = 11.sp) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (node.kind) {
                            EditableJson.Prim.Kind.INT, EditableJson.Prim.Kind.LONG -> KeyboardType.Number
                            EditableJson.Prim.Kind.DOUBLE -> KeyboardType.Decimal
                            else -> KeyboardType.Text
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                desc?.let { Description(it) }
            }
        }
    }
}

@Composable
private fun SwitchField(label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            desc?.let { Description(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(label: String, desc: String?, current: String, values: Map<String, String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = values[current] ?: current
    Column(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = display,
                onValueChange = {},
                readOnly = true,
                label = { Text(label, fontSize = 12.sp) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { (raw, friendly) ->
                    DropdownMenuItem(
                        text = { Text(friendly) },
                        onClick = { onSelect(raw); expanded = false },
                    )
                }
            }
        }
        desc?.let { Description(it) }
    }
}

@Composable
private fun IntSliderField(label: String, desc: String?, current: String, min: Long, max: Long, onChange: (String) -> Unit) {
    val cur = (current.toLongOrNull() ?: min).coerceIn(min, max)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$cur", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 36.dp))
        }
        Slider(
            value = cur.toFloat(),
            onValueChange = { onChange(it.roundToLong().coerceIn(min, max).toString()) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        desc?.let { Description(it) }
    }
}

@Composable
private fun Description(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
}

/** Picks the raw on/off key matching the current value's format (hex vs decimal). */
private fun pickOnOffKey(values: Map<String, String>, on: Boolean, preferHex: Boolean): String {
    val target = if (on) "On" else "Off"
    val candidates = values.filterValues { it == target }.keys
    return candidates.firstOrNull { it.startsWith("0x") == preferHex } ?: candidates.firstOrNull() ?: (if (on) "1" else "0")
}
