package com.voidnullvalue.icseelocal.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voidnullvalue.icseelocal.ble.BlePairedCamera
import com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.model.StreamType
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.storage.CameraStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class CameraSettingsUiState(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String = "",
    val host: String = "",
    val dvripPort: String = "34567",
    val channel: String = "0",
    val streamType: StreamType = StreamType.MAIN,
    val rtspFallbackEnabled: Boolean = false,
    val rtspPort: String = "554",
    val username: String = "",
    val password: String = "",
    val isExisting: Boolean = false,
    val testResult: String? = null,
    val testing: Boolean = false,
    // Carried through from a discovery beacon (if this camera came from a LAN
    // scan) so "Add" doesn't throw away identifying metadata the discovery
    // client already found.
    val mac: String? = null,
    val serialNumber: String? = null,
    val pid: String? = null,
    val firmwareVersion: String? = null,
    val httpPort: Int? = null,
    val sslPort: Int? = null,
    val udpPort: Int? = null,
)

class CameraSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CameraStore(application)

    private val _state = MutableStateFlow(CameraSettingsUiState())
    val state: StateFlow<CameraSettingsUiState> = _state.asStateFlow()

    fun load(cameraId: String?) {
        if (cameraId == null) {
            _state.value = CameraSettingsUiState()
            return
        }
        viewModelScope.launch {
            // Fetch once from the current snapshot rather than collecting continuously.
            val list = store.cameras.first()
            val found = list.firstOrNull { it.id == cameraId } ?: return@launch
            val creds = store.credentialsFor(cameraId)
            _state.value = CameraSettingsUiState(
                id = found.id,
                displayName = found.displayName,
                host = found.host,
                dvripPort = found.dvripPort.toString(),
                channel = found.channel.toString(),
                streamType = found.streamType,
                rtspFallbackEnabled = found.rtspFallbackEnabled,
                rtspPort = found.rtspPort.toString(),
                username = creds?.username ?: "",
                password = creds?.password ?: "",
                isExisting = true,
                mac = found.mac,
                serialNumber = found.serialNumber,
                pid = found.pid,
                firmwareVersion = found.firmwareVersion,
                httpPort = found.httpPort,
                sslPort = found.sslPort,
                udpPort = found.udpPort,
            )
        }
    }

    /**
     * Prefills the form from a freshly-discovered LAN beacon, but does NOT
     * save anything yet -- the user must fill in and confirm real
     * credentials (see "Save") rather than the camera being added silently
     * with no/blank credentials.
     */
    fun loadFromDiscovery(beacon: DiscoveryBeacon) {
        _state.value = CameraSettingsUiState(
            id = beacon.identityKey,
            displayName = beacon.hostName.ifBlank { beacon.hostIp },
            host = beacon.hostIp,
            dvripPort = (beacon.tcpPort.takeIf { it > 0 } ?: 34567).toString(),
            isExisting = false,
            mac = beacon.mac.ifBlank { null },
            serialNumber = beacon.serialNumber.ifBlank { null },
            pid = beacon.pid.ifBlank { null },
            firmwareVersion = beacon.version.ifBlank { null },
            httpPort = beacon.httpPort.takeIf { it > 0 },
            sslPort = beacon.sslPort.takeIf { it > 0 },
            udpPort = beacon.udpPort.takeIf { it > 0 },
        )
    }

    /**
     * Prefills from a just-completed BLE pairing handshake: the camera has
     * already joined the WiFi network and handed back the LAN IP plus the
     * random username/password it assigned itself (see
     * [[project-icsee-ble-pairing]]). Same "review before saving" pattern as
     * [loadFromDiscovery] -- nothing is persisted until the user hits Save.
     */
    fun loadFromBlePairing(paired: BlePairedCamera) {
        _state.value = CameraSettingsUiState(
            id = paired.mac ?: paired.host,
            displayName = paired.host,
            host = paired.host,
            isExisting = false,
            username = paired.username,
            password = paired.password,
            mac = paired.mac,
        )
    }

    fun update(transform: (CameraSettingsUiState) -> CameraSettingsUiState) {
        _state.value = transform(_state.value)
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            store.save(
                CameraDescriptor(
                    id = s.id,
                    displayName = s.displayName.ifBlank { s.host },
                    host = s.host,
                    dvripPort = s.dvripPort.toIntOrNull() ?: 34567,
                    channel = s.channel.toIntOrNull() ?: 0,
                    streamType = s.streamType,
                    rtspFallbackEnabled = s.rtspFallbackEnabled,
                    rtspPort = s.rtspPort.toIntOrNull() ?: 554,
                    mac = s.mac,
                    serialNumber = s.serialNumber,
                    pid = s.pid,
                    firmwareVersion = s.firmwareVersion,
                    httpPort = s.httpPort,
                    sslPort = s.sslPort,
                    udpPort = s.udpPort,
                ),
                credentials = if (s.username.isNotBlank() || s.password.isNotBlank()) {
                    CameraCredentials(s.username, s.password)
                } else {
                    null
                },
            )
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            store.delete(_state.value.id)
            onDeleted()
        }
    }

    fun testConnection() {
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(testing = true, testResult = null)
            val result = try {
                withTimeout(10000) {
                    val transport = DvripTransport(s.host, s.dvripPort.toIntOrNull() ?: 34567)
                    transport.connect()
                    try {
                        // Perform full DVRIP login verification with the provided credentials
                        if (s.username.isNotBlank() && s.password.isNotBlank()) {
                            val credentials = CameraCredentials(s.username, s.password)
                            val negotiator = com.voidnullvalue.icseelocal.session.DvripLoginNegotiator()
                            val session = negotiator.negotiate(transport, credentials)
                            // Send a keepalive to verify the session is active
                            transport.send(
                                session = session.sessionId,
                                messageId = 1006,
                                payload = byteArrayOf(),
                            )
                            "✓ Login successful on ${s.host}:${s.dvripPort} as ${s.username}"
                        } else {
                            // No credentials provided, just verify TCP connectivity
                            "✓ TCP connection to ${s.host}:${s.dvripPort} succeeded (provide username/password for full login verification)"
                        }
                    } finally {
                        transport.close()
                    }
                }
            } catch (e: Exception) {
                "✗ Connection failed: ${e.message}"
            }
            _state.value = _state.value.copy(testing = false, testResult = result)
        }
    }
}
