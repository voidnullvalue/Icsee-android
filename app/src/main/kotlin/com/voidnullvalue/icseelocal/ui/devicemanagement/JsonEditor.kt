package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voidnullvalue.icseelocal.config.ConfigMetadataCache

/**
 * Recursive editor for an [EditableJson] tree -- see that file's doc for why
 * one generic editor covers every named DVRIP config rather than a bespoke
 * screen per config name. Renders each object key as a labeled row; nested
 * objects/arrays indent one level; array items are labeled by index.
 */
@Composable
fun JsonEditorNode(
    name: String,
    node: EditableJson,
    depth: Int = 0,
    metadata: ConfigMetadataCache? = null,
    pathPrefix: String = "",
) {
    val indent = (depth * 12).dp
    when (node) {
        is EditableJson.Obj -> {
            if (depth > 0) {
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = indent, top = 8.dp, bottom = 2.dp))
            }
            Column(Modifier.padding(start = if (depth > 0) 8.dp else 0.dp)) {
                node.entries.forEach { (key, value) ->
                    val newPath = if (pathPrefix.isEmpty()) key else "$pathPrefix/$key"
                    JsonEditorNode(key, value, depth + 1, metadata, newPath)
                }
            }
        }
        is EditableJson.Arr -> {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = indent, top = 8.dp, bottom = 2.dp))
            Column(Modifier.padding(start = 8.dp)) {
                node.items.forEachIndexed { i, item ->
                    val newPath = if (pathPrefix.isEmpty()) "[$i]" else "$pathPrefix/[$i]"
                    JsonEditorNode("[$i]", item, depth + 1, metadata, newPath)
                }
            }
        }
        is EditableJson.Null -> {
            Text("$name: null", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = indent, top = 2.dp, bottom = 2.dp))
        }
        is EditableJson.Prim -> {
            var text by node.text
            val hint = metadata?.fields?.get(pathPrefix)?.hint() ?: ""
            val labelText = if (hint.isNotEmpty()) "$name $hint" else name

            if (node.kind == EditableJson.Prim.Kind.BOOLEAN) {
                Row(Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp)) {
                    Checkbox(checked = text.toBooleanStrictOrNull() ?: false, onCheckedChange = { text = it.toString() })
                    Text(labelText, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(labelText, fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (node.kind) {
                            EditableJson.Prim.Kind.INT, EditableJson.Prim.Kind.LONG -> KeyboardType.Number
                            EditableJson.Prim.Kind.DOUBLE -> KeyboardType.Decimal
                            else -> KeyboardType.Text
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}
