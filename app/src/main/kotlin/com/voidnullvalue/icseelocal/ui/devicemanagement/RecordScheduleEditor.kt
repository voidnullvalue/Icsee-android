package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.voidnullvalue.icseelocal.ui.components.SectionCard

private val DAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private val REC_TYPES = listOf(0 to "Off", 1 to "Continuous", 2 to "On motion", 4 to "On alarm", 6 to "Motion + alarm")

/**
 * Friendly weekly recording-schedule editor for the XM `Record` config. Binds
 * directly to the [EditableJson] `TimeSection` primitives (7 days × 6 windows,
 * each "<type> HH:MM:SS-HH:MM:SS"), so the existing Save path round-trips
 * unchanged. Editing only TimeSection is enough -- the parallel hex `Mask` field
 * is left as-is (live-confirmed the camera honours TimeSection-only edits).
 */
@Composable
fun RecordScheduleEditor(root: EditableJson) {
    val timeSection = findTimeSection(root)
    if (timeSection == null || timeSection.items.isEmpty()) {
        Text("This camera didn't return a recognisable recording schedule.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val days: List<List<EditableJson.Prim>> = timeSection.items.map { day ->
        (day as? EditableJson.Arr)?.items?.filterIsInstance<EditableJson.Prim>() ?: emptyList()
    }

    Column(Modifier.fillMaxWidth()) {
        days.forEachIndexed { d, sections ->
            DayCard(
                name = DAY_NAMES.getOrElse(d) { "Day ${d + 1}" },
                sections = sections,
                onCopyToAll = {
                    val source = sections.map { it.text.value }
                    days.forEach { target ->
                        target.forEachIndexed { i, prim -> source.getOrNull(i)?.let { prim.text.value = it } }
                    }
                },
            )
        }
    }
}

@Composable
private fun DayCard(name: String, sections: List<EditableJson.Prim>, onCopyToAll: () -> Unit) {
    SectionCard(title = name) {
        val active = sections.filter { parse(it.text.value).type != 0 }
        if (active.isEmpty()) {
            Text("Not recording on $name.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        active.forEach { SectionRow(it) }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val firstOff = sections.firstOrNull { parse(it.text.value).type == 0 }
            if (firstOff != null) {
                TextButton(onClick = { firstOff.text.value = format(Window(1, "00:00", "24:00")) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add window")
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCopyToAll) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy to all days")
            }
        }
    }
}

@Composable
private fun SectionRow(prim: EditableJson.Prim) {
    val current by prim.text
    val win = parse(current)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TypeDropdown(win.type, Modifier.weight(1f)) { newType ->
            prim.text.value = format(win.copy(type = newType))
        }
        TimeField(win.start, Modifier.width(72.dp)) { prim.text.value = format(win.copy(start = it)) }
        Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TimeField(win.end, Modifier.width(72.dp)) { prim.text.value = format(win.copy(end = it)) }
        IconButton(onClick = { prim.text.value = format(win.copy(type = 0)) }) {
            Icon(Icons.Default.Close, contentDescription = "Remove window", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(type: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = REC_TYPES.firstOrNull { it.first == type }?.second ?: "Type $type"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            REC_TYPES.filter { it.first != 0 }.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@Composable
private fun TimeField(value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private data class Window(val type: Int, val start: String, val end: String)

private fun parse(s: String): Window {
    val sp = s.trim().split(Regex("\\s+"), limit = 2)
    val type = sp.getOrNull(0)?.toIntOrNull() ?: 0
    val range = (sp.getOrNull(1) ?: "00:00:00-24:00:00").split("-")
    fun hm(t: String?) = (t ?: "00:00:00").trim().let { if (it.length >= 5) it.substring(0, 5) else it }
    return Window(type, hm(range.getOrNull(0)), hm(range.getOrNull(1)))
}

private fun format(w: Window): String {
    fun sec(t: String): String {
        val cleaned = t.trim()
        return when {
            cleaned.count { it == ':' } >= 2 -> cleaned
            cleaned.contains(':') -> "$cleaned:00"
            else -> "$cleaned:00:00"
        }
    }
    return "${w.type} ${sec(w.start)}-${sec(w.end)}"
}

private fun findTimeSection(root: EditableJson): EditableJson.Arr? {
    fun EditableJson.get(key: String): EditableJson? =
        (this as? EditableJson.Obj)?.entries?.firstOrNull { it.first == key }?.second
    val record = root.get("Record") as? EditableJson.Arr ?: return null
    val ch0 = record.items.firstOrNull() as? EditableJson.Obj ?: return null
    return ch0.entries.firstOrNull { it.first == "TimeSection" }?.second as? EditableJson.Arr
}
