package com.voidnullvalue.icseelocal.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.presetThumbStore by preferencesDataStore(name = "preset_thumbs")

@Serializable
data class PresetThumbRecord(
    val cameraId: String,
    val preset: Int,
    val thumbPath: String,
    val savedAtMs: Long = System.currentTimeMillis(),
)

/** Persists JPEG paths for PTZ presets 1–4, keyed by camera + preset number. */
class PresetThumbStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("entries")

    val entries: Flow<List<PresetThumbRecord>> = context.presetThumbStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<PresetThumbRecord>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun all(): List<PresetThumbRecord> = entries.first()

    suspend fun forCamera(cameraId: String): Map<Int, String> =
        all().filter { it.cameraId == cameraId && File(it.thumbPath).exists() }
            .associate { it.preset to it.thumbPath }

    suspend fun put(cameraId: String, preset: Int, thumbPath: String) {
        context.presetThumbStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<PresetThumbRecord>>(prefs[key] ?: "[]")
            }.getOrDefault(emptyList())
            val updated = current.filterNot { it.cameraId == cameraId && it.preset == preset } +
                PresetThumbRecord(cameraId, preset, thumbPath)
            prefs[key] = json.encodeToString(updated)
        }
    }

    fun thumbFile(cameraId: String, preset: Int): File {
        val dir = File(context.filesDir, "preset_thumbs").also { it.mkdirs() }
        val safe = cameraId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "${safe}_p$preset.jpg")
    }
}
