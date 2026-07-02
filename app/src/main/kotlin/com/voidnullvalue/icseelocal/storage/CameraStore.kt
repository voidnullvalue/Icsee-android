package com.voidnullvalue.icseelocal.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.model.StreamType
import com.voidnullvalue.icseelocal.session.CameraCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

private val Context.cameraDataStore by preferencesDataStore(name = "cameras")

@Serializable
internal data class StoredCamera(
    val id: String,
    val displayName: String,
    val host: String,
    val dvripPort: Int,
    val channel: Int,
    val streamType: String,
    val rtspFallbackEnabled: Boolean,
    val rtspPort: Int,
    val mac: String? = null,
    val serialNumber: String? = null,
    val pid: String? = null,
    val firmwareVersion: String? = null,
    val httpPort: Int? = null,
    val sslPort: Int? = null,
    val udpPort: Int? = null,
    // Keystore-encrypted; never plaintext at rest.
    val credentialIvBase64: String? = null,
    val credentialCiphertextBase64: String? = null,
) {
    fun toDescriptor() = CameraDescriptor(
        id = id, displayName = displayName, host = host, dvripPort = dvripPort, channel = channel,
        streamType = StreamType.valueOf(streamType), rtspFallbackEnabled = rtspFallbackEnabled, rtspPort = rtspPort,
        mac = mac, serialNumber = serialNumber, pid = pid, firmwareVersion = firmwareVersion,
        httpPort = httpPort, sslPort = sslPort, udpPort = udpPort,
    )
}

@Serializable
internal data class StoredCredentials(val username: String, val password: String)

private fun CameraDescriptor.toStored(ivBase64: String?, ciphertextBase64: String?) = StoredCamera(
    id = id, displayName = displayName, host = host, dvripPort = dvripPort, channel = channel,
    streamType = streamType.name, rtspFallbackEnabled = rtspFallbackEnabled, rtspPort = rtspPort,
    mac = mac, serialNumber = serialNumber, pid = pid, firmwareVersion = firmwareVersion,
    httpPort = httpPort, sslPort = sslPort, udpPort = udpPort,
    credentialIvBase64 = ivBase64, credentialCiphertextBase64 = ciphertextBase64,
)

/**
 * Non-sensitive camera fields live in DataStore Preferences as plain JSON;
 * username/password are AES-GCM-encrypted via [KeystoreCipher] first, and
 * only the resulting ciphertext (base64) is ever written to DataStore.
 */
class CameraStore(
    private val context: Context,
    private val cipher: KeystoreCipher = KeystoreCipher(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listKey = stringPreferencesKey("camera_list")

    val cameras: Flow<List<CameraDescriptor>> = context.cameraDataStore.data.map { prefs ->
        readStoredList(prefs[listKey]).map { it.toDescriptor() }
    }

    suspend fun save(descriptor: CameraDescriptor, credentials: CameraCredentials?) {
        context.cameraDataStore.edit { prefs ->
            val current = readStoredList(prefs[listKey])
            val encrypted = credentials?.let {
                val plaintext = json.encodeToString(StoredCredentials(it.username, it.password)).toByteArray()
                cipher.encrypt(plaintext)
            }
            val stored = descriptor.toStored(
                ivBase64 = encrypted?.iv?.let { Base64.getEncoder().encodeToString(it) },
                ciphertextBase64 = encrypted?.ciphertext?.let { Base64.getEncoder().encodeToString(it) },
            )
            val updated = current.filterNot { it.id == descriptor.id } + stored
            prefs[listKey] = json.encodeToString(updated)
        }
    }

    suspend fun delete(id: String) {
        context.cameraDataStore.edit { prefs ->
            prefs[listKey] = json.encodeToString(readStoredList(prefs[listKey]).filterNot { it.id == id })
        }
    }

    /** Decrypts credentials on demand -- never held in the in-memory camera list. */
    suspend fun credentialsFor(id: String): CameraCredentials? {
        val prefs = context.cameraDataStore.data.first()
        val stored = readStoredList(prefs[listKey]).firstOrNull { it.id == id } ?: return null
        val ivB64 = stored.credentialIvBase64 ?: return null
        val ctB64 = stored.credentialCiphertextBase64 ?: return null
        val blob = EncryptedBlob(Base64.getDecoder().decode(ivB64), Base64.getDecoder().decode(ctB64))
        val plaintext = cipher.decrypt(blob)
        val decoded = json.decodeFromString<StoredCredentials>(String(plaintext))
        return CameraCredentials(decoded.username, decoded.password)
    }

    private fun readStoredList(raw: String?): List<StoredCamera> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredCamera>>(raw) }.getOrDefault(emptyList())
    }
}
