package com.voidnullvalue.icseelocal.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Discovers and patches lighting / day-night fields across XM firmware variants.
 *
 * Cameras disagree on shape:
 * - `Camera.ParamEx[0].WhiteLight.WorkMode` ("Close"/"Open"/"Auto"/…) — white LED
 * - `Camera.ParamEx[0].DayNightSwitch` as **object** or **array** with `SwitchMode`
 * - `Camera.Param[0].DayNightColor` — legacy colour/IR mode
 */
data class CameraLightingCaps(
    val whiteLight: Boolean = false,
    val dayNightSwitch: Boolean = false,
    val dayNightColor: Boolean = false,
    val whiteLightMode: String? = null,
    val dayNightMode: Int? = null,
) {
    val hasAnyLightControl: Boolean get() = whiteLight || dayNightSwitch || dayNightColor

    val summary: String
        get() = buildList {
            if (whiteLight) add("white LED")
            if (dayNightSwitch) add("day/night switch")
            if (dayNightColor) add("day/night colour")
            if (isEmpty()) add("none detected")
        }.joinToString(", ")
}

object CameraLighting {
    fun probe(param: JsonElement?, paramEx: JsonElement?): CameraLightingCaps {
        val chEx = channelObj(paramEx)
        val chParam = channelObj(param)
        val white = findWhiteLight(chEx)
        val dns = findDayNightSwitch(chEx)
        val dnc = chParam?.get("DayNightColor")
        return CameraLightingCaps(
            whiteLight = white != null,
            dayNightSwitch = dns != null,
            dayNightColor = dnc != null,
            whiteLightMode = white?.let { readWorkMode(it) },
            dayNightMode = dns?.let { readSwitchMode(it) } ?: dnc?.let { parseIntish(it) },
        )
    }

    /**
     * Cycles white LED when present; otherwise day/night mode.
     * Returns updated root config name + patched element, or null if unsupported.
     */
    fun toggle(
        param: JsonElement?,
        paramEx: JsonElement?,
    ): ToggleResult? {
        val caps = probe(param, paramEx)
        if (caps.whiteLight && paramEx != null) {
            val next = nextWhiteMode(caps.whiteLightMode)
            val patched = patchWhiteLight(paramEx, next) ?: return null
            return ToggleResult("Camera.ParamEx", patched, "Light: $next", caps.copy(whiteLightMode = next))
        }
        if (caps.dayNightSwitch && paramEx != null) {
            val next = nextDayNight(caps.dayNightMode ?: 0)
            val patched = patchDayNightSwitch(paramEx, next) ?: return null
            val label = when (next) { 1 -> "Day mode"; 2 -> "Night mode"; else -> "Auto day/night" }
            return ToggleResult("Camera.ParamEx", patched, label, caps.copy(dayNightMode = next))
        }
        if (caps.dayNightColor && param != null) {
            val next = nextDayNightColor(caps.dayNightMode ?: 0)
            val patched = patchDayNightColor(param, next) ?: return null
            val label = when (next) { 1 -> "Day (colour)"; 2 -> "Night (IR)"; else -> "Auto colour/IR" }
            return ToggleResult("Camera.Param", patched, label, caps.copy(dayNightMode = next))
        }
        return null
    }

    data class ToggleResult(
        val configName: String,
        val patched: JsonElement,
        val statusLabel: String,
        val caps: CameraLightingCaps,
    )

    private fun nextWhiteMode(current: String?): String = when (current?.lowercase()) {
        "open", "on", "keepopen", "1" -> "Close"
        "close", "off", "0", null -> "Open"
        else -> "Open" // Auto/Timing/etc. → force on
    }

    private fun nextDayNight(mode: Int): Int = when (mode) { 0 -> 1; 1 -> 2; else -> 0 }

    /** DayNightColor legacy: 0 often off/auto-ish; map cycle 0→1→2→0. */
    private fun nextDayNightColor(mode: Int): Int = when (mode) { 0 -> 1; 1 -> 2; else -> 0 }

    private fun channelObj(root: JsonElement?): JsonObject? = when (root) {
        is JsonArray -> root.firstOrNull() as? JsonObject
        is JsonObject -> root.values.firstOrNull { it is JsonArray }?.let { (it as JsonArray).firstOrNull() as? JsonObject }
            ?: root
        else -> null
    }

    private fun findWhiteLight(ch: JsonObject?): JsonObject? =
        ch?.get("WhiteLight") as? JsonObject

    private fun findDayNightSwitch(ch: JsonObject?): JsonElement? {
        val raw = ch?.get("DayNightSwitch") ?: return null
        return when (raw) {
            is JsonObject -> raw
            is JsonArray -> raw.firstOrNull()
            else -> null
        }
    }

    private fun readWorkMode(white: JsonObject): String? =
        (white["WorkMode"] as? JsonPrimitive)?.contentOrNull

    private fun readSwitchMode(dns: JsonElement): Int? = when (dns) {
        is JsonObject -> dns["SwitchMode"]?.let { parseIntish(it) }
        else -> null
    }

    private fun parseIntish(el: JsonElement): Int? {
        val p = el as? JsonPrimitive ?: return null
        p.contentOrNull?.removePrefix("0x")?.toIntOrNull(16)?.let { return it }
        return p.contentOrNull?.toIntOrNull() ?: p.contentOrNull?.toDoubleOrNull()?.toInt()
    }

    private fun patchWhiteLight(root: JsonElement, workMode: String): JsonElement? =
        mapChannel(root) { ch ->
            val white = ch["WhiteLight"] as? JsonObject ?: return@mapChannel null
            val newWhite = buildJsonObject {
                white.forEach { (k, v) -> if (k != "WorkMode") put(k, v) }
                put("WorkMode", JsonPrimitive(workMode))
            }
            buildJsonObject {
                ch.forEach { (k, v) -> if (k != "WhiteLight") put(k, v) }
                put("WhiteLight", newWhite)
            }
        }

    private fun patchDayNightSwitch(root: JsonElement, mode: Int): JsonElement? =
        mapChannel(root) { ch ->
            when (val dns = ch["DayNightSwitch"]) {
                is JsonObject -> {
                    val newDns = buildJsonObject {
                        dns.forEach { (k, v) -> if (k != "SwitchMode") put(k, v) }
                        put("SwitchMode", JsonPrimitive(mode))
                    }
                    buildJsonObject {
                        ch.forEach { (k, v) -> if (k != "DayNightSwitch") put(k, v) }
                        put("DayNightSwitch", newDns)
                    }
                }
                is JsonArray -> {
                    if (dns.isEmpty()) return@mapChannel null
                    val dns0 = dns[0] as? JsonObject ?: return@mapChannel null
                    val newDns0 = buildJsonObject {
                        dns0.forEach { (k, v) -> if (k != "SwitchMode") put(k, v) }
                        put("SwitchMode", JsonPrimitive(mode))
                    }
                    val newDns = buildJsonArray {
                        add(newDns0)
                        for (i in 1 until dns.size) add(dns[i])
                    }
                    buildJsonObject {
                        ch.forEach { (k, v) -> if (k != "DayNightSwitch") put(k, v) }
                        put("DayNightSwitch", newDns)
                    }
                }
                else -> null
            }
        }

    private fun patchDayNightColor(root: JsonElement, mode: Int): JsonElement? =
        mapChannel(root) { ch ->
            if (ch["DayNightColor"] == null) return@mapChannel null
            buildJsonObject {
                ch.forEach { (k, v) -> if (k != "DayNightColor") put(k, v) }
                put("DayNightColor", JsonPrimitive(mode))
            }
        }

    /** Rewrite channel 0 inside a Param / ParamEx array (or single object). */
    private fun mapChannel(root: JsonElement, transform: (JsonObject) -> JsonObject?): JsonElement? {
        when (root) {
            is JsonArray -> {
                if (root.isEmpty()) return null
                val first = root[0] as? JsonObject ?: return null
                val newFirst = transform(first) ?: return null
                return buildJsonArray {
                    add(newFirst)
                    for (i in 1 until root.size) add(root[i])
                }
            }
            is JsonObject -> {
                // Unusual envelope with nested array
                val key = root.keys.firstOrNull { root[it] is JsonArray }
                if (key != null) {
                    val arr = root[key] as JsonArray
                    val mapped = mapChannel(arr, transform) as? JsonArray ?: return null
                    return buildJsonObject {
                        root.forEach { (k, v) -> if (k != key) put(k, v) }
                        put(key, mapped)
                    }
                }
                return transform(root)
            }
            else -> return null
        }
    }
}
