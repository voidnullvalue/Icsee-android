package com.voidnullvalue.icseelocal.ui.devicemanagement

import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A Compose-editable mirror of a [JsonElement] tree. Many DVRIP config values
 * (Camera.Param, Detect.MotionDetect, ...) are deeply nested and heterogeneous
 * -- rather than a bespoke Composable per config name, this lets one generic
 * recursive editor (see JsonEditor.kt) handle every named config the camera
 * exposes. Primitive leaves are individually mutable Compose state; edits are
 * flattened back to a real [JsonElement] via [toJsonElement] before sending a
 * `setConfig` request, and the camera has only been confirmed to accept a
 * full round-trip of its own shape (see DvripConfigChannel doc), so structure
 * (keys, array length, value types) is preserved -- only leaf values change.
 */
sealed class EditableJson {
    class Obj(val entries: List<Pair<String, EditableJson>>) : EditableJson()
    class Arr(val items: List<EditableJson>) : EditableJson()

    /** [kind] remembers the original JSON type so the value round-trips as the same type even if edited as text. */
    class Prim(initial: String, val kind: Kind) : EditableJson() {
        enum class Kind { STRING, INT, LONG, DOUBLE, BOOLEAN }
        val text = mutableStateOf(initial)
    }

    object Null : EditableJson()

    companion object {
        fun from(element: JsonElement): EditableJson = when (element) {
            is JsonObject -> Obj(element.entries.map { (k, v) -> k to from(v) })
            is JsonArray -> Arr(element.map { from(it) })
            is JsonNull -> Null
            is JsonPrimitive -> {
                val kind = when {
                    element.isString -> Prim.Kind.STRING
                    element.booleanOrNull != null -> Prim.Kind.BOOLEAN
                    element.intOrNull != null -> Prim.Kind.INT
                    element.longOrNull != null -> Prim.Kind.LONG
                    element.doubleOrNull != null -> Prim.Kind.DOUBLE
                    else -> Prim.Kind.STRING
                }
                Prim(element.content, kind)
            }
        }
    }
}

fun EditableJson.toJsonElement(): JsonElement = when (this) {
    is EditableJson.Obj -> buildJsonObject { entries.forEach { (k, v) -> put(k, v.toJsonElement()) } }
    is EditableJson.Arr -> buildJsonArray { items.forEach { add(it.toJsonElement()) } }
    is EditableJson.Null -> JsonNull
    is EditableJson.Prim -> when (kind) {
        EditableJson.Prim.Kind.STRING -> JsonPrimitive(text.value)
        EditableJson.Prim.Kind.BOOLEAN -> JsonPrimitive(text.value.toBooleanStrictOrNull() ?: false)
        EditableJson.Prim.Kind.INT -> JsonPrimitive(text.value.toIntOrNull() ?: 0)
        EditableJson.Prim.Kind.LONG -> JsonPrimitive(text.value.toLongOrNull() ?: 0L)
        EditableJson.Prim.Kind.DOUBLE -> JsonPrimitive(text.value.toDoubleOrNull() ?: 0.0)
    }
}
