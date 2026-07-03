package com.voidnullvalue.icseelocal.config

import kotlinx.serialization.Serializable

/**
 * Runtime-discovered constraints for a config field (inferred from camera
 * responses, not from schema). Cached locally to avoid re-polling.
 */
@Serializable
data class FieldConstraints(
    val fieldPath: String,  // e.g. "Camera.Param/Brightness"
    val kind: String,       // BOOLEAN, INT, LONG, DOUBLE, STRING, ARRAY, OBJ, NULL
    val minInt: Long? = null,
    val maxInt: Long? = null,
    val minDouble: Double? = null,
    val maxDouble: Double? = null,
    val observedValues: Set<String> = emptySet(),  // for enums
    val isReadOnly: Boolean = false,
) {
    fun hint(): String = when {
        kind == "BOOLEAN" -> "(true/false)"
        minInt != null && maxInt != null -> "($minInt-$maxInt)"
        minDouble != null && maxDouble != null -> "(${minDouble}-${maxDouble})"
        observedValues.isNotEmpty() -> "(${observedValues.sorted().joinToString(", ")})"
        else -> "($kind)"
    }
}

@Serializable
data class ConfigMetadataCache(
    val configName: String,
    val fields: Map<String, FieldConstraints> = emptyMap(),
    val discoveredAt: Long = System.currentTimeMillis(),
)

object ConfigMetadataAnalyzer {
    /**
     * Analyze a JsonElement recursively to infer field constraints.
     * Returns a flat map of "path" -> FieldConstraints.
     */
    fun analyze(configName: String, value: kotlinx.serialization.json.JsonElement): Map<String, FieldConstraints> {
        val constraints = mutableMapOf<String, FieldConstraints>()
        analyzeRecursive(configName, "", value, constraints)
        return constraints
    }

    private fun analyzeRecursive(
        configName: String,
        path: String,
        value: kotlinx.serialization.json.JsonElement,
        constraints: MutableMap<String, FieldConstraints>,
    ) {
        val fullPath = if (path.isEmpty()) configName else "$configName/$path"

        when {
            value is kotlinx.serialization.json.JsonPrimitive -> {
                val kind = when {
                    value.isString -> "STRING"
                    value.content == "true" || value.content == "false" -> "BOOLEAN"
                    value.content.toLongOrNull() != null -> "INT"
                    value.content.toDoubleOrNull() != null -> "DOUBLE"
                    else -> "STRING"
                }

                val constraint = when {
                    kind == "INT" -> {
                        val num = value.content.toLongOrNull() ?: return
                        // Heuristic: if it's 0-255, likely a byte; 0-100, a percentage
                        val (min, max) = when {
                            num in 0..255 -> 0L to 255L
                            num in 0..100 -> 0L to 100L
                            else -> null to null
                        }
                        FieldConstraints(fullPath, kind, minInt = min, maxInt = max)
                    }
                    kind == "DOUBLE" -> {
                        val num = value.content.toDoubleOrNull() ?: return
                        val (min, max) = when {
                            num in 0.0..1.0 -> 0.0 to 1.0
                            num in 0.0..100.0 -> 0.0 to 100.0
                            else -> null to null
                        }
                        FieldConstraints(fullPath, kind, minDouble = min, maxDouble = max)
                    }
                    else -> FieldConstraints(fullPath, kind)
                }
                constraints[fullPath] = constraint
            }
            value is kotlinx.serialization.json.JsonObject -> {
                constraints[fullPath] = FieldConstraints(fullPath, "OBJ")
                value.forEach { (key, v) ->
                    analyzeRecursive(configName, if (path.isEmpty()) key else "$path/$key", v, constraints)
                }
            }
            value is kotlinx.serialization.json.JsonArray -> {
                constraints[fullPath] = FieldConstraints(fullPath, "ARRAY")
                value.forEachIndexed { idx, item ->
                    analyzeRecursive(configName, if (path.isEmpty()) "[$idx]" else "$path/[$idx]", item, constraints)
                }
            }
            else -> {
                constraints[fullPath] = FieldConstraints(fullPath, "NULL")
            }
        }
    }
}
