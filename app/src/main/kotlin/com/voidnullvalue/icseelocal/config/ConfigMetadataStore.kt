package com.voidnullvalue.icseelocal.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.metadataDataStore by preferencesDataStore(name = "config_metadata")

class ConfigMetadataStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val metadataKey = stringPreferencesKey("metadata_cache")

    suspend fun get(configName: String): ConfigMetadataCache? {
        val prefs = context.metadataDataStore.data.first()
        val raw = prefs[metadataKey] ?: return null
        return runCatching {
            val caches = json.decodeFromString<List<ConfigMetadataCache>>(raw)
            caches.firstOrNull { it.configName == configName }
        }.getOrNull()
    }

    suspend fun put(cache: ConfigMetadataCache) {
        context.metadataDataStore.edit { prefs ->
            val current = (prefs[metadataKey]?.let {
                runCatching { json.decodeFromString<List<ConfigMetadataCache>>(it) }.getOrNull()
            } ?: emptyList()).toMutableList()

            current.removeAll { it.configName == cache.configName }
            current.add(cache)
            prefs[metadataKey] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ConfigMetadataCache.serializer()), current)
        }
    }
}
