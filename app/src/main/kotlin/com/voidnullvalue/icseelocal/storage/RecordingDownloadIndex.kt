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

private val Context.recordingIndexStore by preferencesDataStore(name = "recording_downloads")

@Serializable
data class DownloadedClipRecord(
    val cameraId: String,
    val fileName: String,
    val beginTime: String,
    val uri: String,
    val thumbPath: String? = null,
    val savedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Persists which SD-card clips have already been downloaded to the phone,
 * keyed by cameraId + fileName + beginTime.
 */
class RecordingDownloadIndex(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("entries")

    val entries: Flow<List<DownloadedClipRecord>> = context.recordingIndexStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<DownloadedClipRecord>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun all(): List<DownloadedClipRecord> = entries.first()

    suspend fun put(record: DownloadedClipRecord) {
        context.recordingIndexStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<DownloadedClipRecord>>(prefs[key] ?: "[]")
            }.getOrDefault(emptyList())
            val updated = current.filterNot {
                it.cameraId == record.cameraId && it.fileName == record.fileName && it.beginTime == record.beginTime
            } + record
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun find(cameraId: String, fileName: String, beginTime: String): DownloadedClipRecord? =
        all().firstOrNull {
            it.cameraId == cameraId && it.fileName == fileName && it.beginTime == beginTime
        }

    fun thumbDir(): File = File(context.filesDir, "recording_thumbs").also { it.mkdirs() }
}
