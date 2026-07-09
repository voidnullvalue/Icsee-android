package com.voidnullvalue.icseelocal.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voidnullvalue.icseelocal.ble.BlePairedCamera
import com.voidnullvalue.icseelocal.config.DvrIpClient
import com.voidnullvalue.icseelocal.config.XiongmaiCrypto
import com.voidnullvalue.icseelocal.discovery.DiscoveryBeacon
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.model.StreamType
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.session.DvripLoginNegotiator
import com.voidnullvalue.icseelocal.session.LoginNegotiationBlockedException
import com.voidnullvalue.icseelocal.storage.CameraStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val saveError: String? = null,
    val saving: Boolean = false,
    // Retrieved real account credentials (xkfu or other)
    val retrievedUsername: String? = null,
    val retrievedPassword: String? = null,
    val retrievingCreds: Boolean = false,
    val retrieveCredsError: String? = null,
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
            username = "admin",
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
        // Prefer xkfu (real account) over admin if available
        val username = paired.xkfuUsername ?: paired.username
        val password = paired.xkfuPassword ?: paired.password

        _state.value = CameraSettingsUiState(
            id = paired.mac ?: paired.host,
            displayName = paired.host,
            host = paired.host,
            isExisting = false,
            username = username,
            password = password,
            mac = paired.mac,
        )
    }

    fun update(transform: (CameraSettingsUiState) -> CameraSettingsUiState) {
        _state.value = transform(_state.value)
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (s.host.isBlank()) {
            _state.value = s.copy(saveError = "Host / IP is required.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, saveError = null)
            // Persisting encrypts credentials via the Android Keystore, which can
            // fail on some devices/keystore states -- never let that force-close
            // the app; surface it so the user can retry.
            val result = runCatching {
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
            }
            _state.value = _state.value.copy(saving = false)
            result.onSuccess { onSaved() }
                .onFailure { _state.value = _state.value.copy(saveError = "Couldn't save this camera: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val result = runCatching { store.delete(_state.value.id) }
            result.onSuccess { onDeleted() }
                .onFailure { _state.value = _state.value.copy(saveError = "Couldn't delete this camera: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun testConnection() {
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(testing = true, testResult = null)
            // All the socket work runs off the main thread; catching Throwable
            // here guarantees nothing from the connect/login path can escape and
            // force-close the app -- the worst case is a failure message.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeout(10000) {
                        val transport = DvripTransport(s.host, s.dvripPort.toIntOrNull() ?: 34567)
                        transport.connect()
                        try {
                            // A blank password is a real credential for these cameras
                            // (the factory admin/no-password account), so verify the
                            // actual login rather than falling back to a misleading
                            // TCP-only check. Only skip login when no username is set.
                            if (s.username.isBlank()) {
                                "✓ TCP connection to ${s.host}:${s.dvripPort} succeeded (enter a username to verify login)"
                            } else {
                                val credentials = CameraCredentials(s.username, s.password)
                                val negotiator = DvripLoginNegotiator()
                                negotiator.negotiate(transport, credentials)
                                val shown = if (s.password.isBlank()) "${s.username} (no password)" else s.username
                                "✓ Login successful on ${s.host}:${s.dvripPort} as $shown"
                            }
                        } finally {
                            transport.close()
                            // Let the camera fully tear down this session before any
                            // subsequent connection is attempted.
                            kotlinx.coroutines.delay(500)
                        }
                    }
                }.getOrElse { e ->
                    val locked = (e as? LoginNegotiationBlockedException)?.isAccountLocked == true
                    if (locked) {
                        "✗ Login temporarily locked by the camera (Ret:205) after repeated attempts. " +
                            "Wait a few minutes or power-cycle the camera, then test once."
                    } else {
                        "✗ Connection failed: ${e.message}"
                    }
                }
            }
            _state.value = _state.value.copy(testing = false, testResult = result)
        }
    }

    /**
     * Retrieves the real (non-`admin`) account via `GetRandomUser`, which needs a
     * real login first -- but `admin`/blank isn't guaranteed to still work: it
     * time-expires into `Ret:205` some while after provisioning, independent of
     * app/user activity (see SECURITY.md "unremovable blank-password admin
     * backdoor"). So this tries a short list of candidate logins in order rather
     * than assuming `admin`/blank: the classic factory default first (cheapest,
     * most likely to still be open on a recently-provisioned camera), then
     * whatever is currently saved for this camera (which may already be the real
     * account's password from a previous successful retrieval or password
     * change), then whatever's currently typed into the form. Stops at the first
     * one that actually works.
     */
    fun retrieveRealCredentials() {
        val s = _state.value
        if (s.host.isBlank()) {
            _state.value = _state.value.copy(retrieveCredsError = "Host required.")
            return
        }

        _state.value = _state.value.copy(retrievingCreds = true, retrieveCredsError = null, retrievedUsername = null, retrievedPassword = null)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val saved = if (s.isExisting) store.credentialsFor(s.id) else null
                    val candidates = listOf(
                        "admin" to "",
                        saved?.username to saved?.password,
                        s.username to s.password,
                    ).mapNotNull { (u, p) -> if (!u.isNullOrBlank()) u to (p ?: "") else null }
                        .distinct()

                    Log.d("CameraSettingsViewModel", "Retrieving real credentials from ${s.host}, trying ${candidates.size} candidate login(s)")
                    val client = DvrIpClient(s.host, s.dvripPort.toIntOrNull() ?: 34567)
                    val creds = candidates.firstNotNullOfOrNull { (username, password) ->
                        client.queryRandomUserCredentials(username, password)
                    } ?: throw Exception(
                        "Could not log in with any known credentials (tried ${candidates.size}), or " +
                            "could not decrypt the provisioned account. The admin/blank factory login " +
                            "may have timed out -- see SECURITY.md.",
                    )
                    Log.d("CameraSettingsViewModel", "Successfully retrieved ${creds.first} credentials")
                    creds
                }.onSuccess { (username, password) ->
                    _state.value = _state.value.copy(
                        retrievingCreds = false,
                        retrievedUsername = username,
                        retrievedPassword = password,
                        retrieveCredsError = null
                    )
                }.onFailure { e ->
                    Log.e("CameraSettingsViewModel", "Failed to retrieve: ${e.message}", e)
                    _state.value = _state.value.copy(
                        retrievingCreds = false,
                        retrieveCredsError = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }
}
