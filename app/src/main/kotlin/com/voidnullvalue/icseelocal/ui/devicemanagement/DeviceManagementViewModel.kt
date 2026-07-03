package com.voidnullvalue.icseelocal.ui.devicemanagement

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voidnullvalue.icseelocal.config.ConfigResult
import com.voidnullvalue.icseelocal.config.DvripConfigChannel
import com.voidnullvalue.icseelocal.config.SystemInfo
import com.voidnullvalue.icseelocal.crypto.SofiaHash
import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.session.CameraSessionManager
import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import com.voidnullvalue.icseelocal.session.DvripLoginNegotiator
import com.voidnullvalue.icseelocal.storage.CameraStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Named configs this screen offers as generic advanced editors -- each answers on [DvripConfigChannel.getConfig]/[DvripConfigChannel.setConfig]. */
enum class AdvancedConfig(val configName: String, val label: String) {
    CAMERA_PARAM("Camera.Param", "Image settings"),
    CAMERA_PARAM_EX("Camera.ParamEx", "Image settings (extended)"),
    MOTION_DETECT("Detect.MotionDetect", "Motion detection"),
    GENERAL("General.General", "General"),
    NET_COMMON("NetWork.NetCommon", "Network"),
    RECORD("Record", "Recording schedule"),
}

data class DeviceManagementUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val systemInfo: SystemInfo? = null,
    val storageInfo: JsonElement? = null,
    val deviceTime: String? = null,
    val configValues: Map<String, JsonElement> = emptyMap(),
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val rebootRequested: Boolean = false,
    val passwordChangeInFlight: Boolean = false,
)

class DeviceManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CameraStore(application)
    private var sessionManager: CameraSessionManager? = null
    private var camera: CameraDescriptor? = null
    private var credentials: CameraCredentials? = null
    private var configChannel: DvripConfigChannel? = null

    private val _state = MutableStateFlow(DeviceManagementUiState())
    val state: StateFlow<DeviceManagementUiState> = _state.asStateFlow()

    fun load(cameraId: String) {
        viewModelScope.launch {
            val found = store.cameras.first().firstOrNull { it.id == cameraId } ?: return@launch
            camera = found
            val creds = store.credentialsFor(cameraId) ?: CameraCredentials("", "")
            credentials = creds

            val manager = CameraSessionManager(found.host, found.dvripPort)
            sessionManager = manager
            viewModelScope.launch {
                manager.state.collect { connState ->
                    _state.value = _state.value.copy(connectionState = connState)
                    if (connState is ConnectionState.Authenticated) {
                        configChannel = DvripConfigChannel(manager.controlTransport!!, manager.commandChannel!!, connState.sessionId, getApplication())
                        refreshAll()
                    }
                }
            }
            manager.connect(creds)
        }
    }

    private fun withChannel(action: suspend (DvripConfigChannel) -> Unit) {
        val channel = configChannel
        if (channel == null) {
            _state.value = _state.value.copy(errorMessage = "Not connected yet")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            runCatching { action(channel) }
                .onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Request failed") }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun refreshAll() {
        withChannel { channel ->
            when (val r = channel.getInfo("SystemInfo")) {
                is ConfigResult.Success -> _state.value = _state.value.copy(systemInfo = SystemInfo.fromJson(r.value))
                is ConfigResult.Failure -> _state.value = _state.value.copy(errorMessage = "SystemInfo failed: Ret=${r.ret}")
            }
            when (val r = channel.getInfo("StorageInfo")) {
                is ConfigResult.Success -> _state.value = _state.value.copy(storageInfo = r.value)
                is ConfigResult.Failure -> { /* SD card may not be present -- not an error worth surfacing */ }
            }
            AdvancedConfig.entries.forEach { cfg ->
                when (val r = channel.getConfig(cfg.configName)) {
                    is ConfigResult.Success -> _state.value = _state.value.copy(
                        configValues = _state.value.configValues + (cfg.configName to r.value),
                    )
                    is ConfigResult.Failure -> { /* this camera may not support every config name -- skip silently, editor won't offer it */ }
                }
            }
        }
    }

    /**
     * Sends a raw (non-`{"Name":X,"X":{...}}`-catalog) command and awaits its
     * reply by message id, matching the same race-avoidance pattern
     * [DvripConfigChannel] and [com.voidnullvalue.icseelocal.session.DvripLoginNegotiator]
     * use: [DvripCommandChannel.sendJson] only sends -- it never waits for or
     * returns the camera's reply -- so the response must be subscribed to on
     * [DvripTransport.incomingFrames] *before* sending.
     */
    private suspend fun sendAndAwait(
        transport: DvripTransport,
        channel: DvripCommandChannel,
        requestMessageId: Int,
        responseMessageId: Int,
        json: String,
        timeoutMillis: Long = 8000,
    ): String? = withTimeoutOrNull(timeoutMillis) {
        coroutineScope {
            val responseDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                transport.incomingFrames.filter { it.header.messageId == responseMessageId }.first()
            }
            channel.sendJson(requestMessageId, json)
            val frame: DvripFrame = responseDeferred.await()
            channel.decodeResponse(frame)
        }
    }

    /**
     * OPTimeQuery returns a bare string (`"OPTimeQuery":"2026-07-03 05:04:51"`),
     * not the `{"Name":X,"X":{...}}` per-config envelope, so it goes through
     * the command channel directly rather than [DvripConfigChannel].
     */
    fun refreshTime() {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching {
                val json = """{"Name":"OPTimeQuery","SessionID":"0x%08x"}""".format(sid.toLong())
                val text = sendAndAwait(transport, channel, DvripMessageIds.TIME_QUERY, DvripMessageIds.TIME_QUERY + 1, json)
                val time = text?.let { Regex("\"OPTimeQuery\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                _state.value = _state.value.copy(deviceTime = time ?: _state.value.deviceTime, errorMessage = if (time == null) "No time in response" else null)
            }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Time query failed") }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun saveConfig(name: String, updated: JsonElement) {
        withChannel { channel ->
            when (val r = channel.setConfig(name, updated)) {
                is ConfigResult.Success -> _state.value = _state.value.copy(
                    statusMessage = "Saved",
                    configValues = _state.value.configValues + (name to updated),
                )
                is ConfigResult.Failure -> {
                    if (r.ret == 100) {
                        _state.value = _state.value.copy(
                            statusMessage = "Saved",
                            configValues = _state.value.configValues + (name to updated),
                        )
                    } else {
                        _state.value = _state.value.copy(errorMessage = "Save failed: Ret=${r.ret}")
                    }
                }
            }
        }
    }

    /**
     * `{"Name":"OPMachine","OPMachine":{"Action":"Reboot"},"SessionID":"0x..."}`
     * on [DvripMessageIds.MACHINE_CONTROL]. The `{"Action":"Reboot"}` body is
     * confirmed from the vendor app's `DevAboutSettingActivity` (its reboot
     * button handler); the outer `{"Name":X,"X":{...},"SessionID":...}`
     * envelope matches every other confirmed-live named command in this app,
     * but this specific command has not itself been sent live (rebooting the
     * camera mid-development would interrupt further testing) -- see
     * PROTOCOL_STATUS.md. The camera drops the connection as it reboots, so a
     * response timeout here is treated as expected, not an error.
     */
    fun reboot() {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, rebootRequested = false)
            runCatching {
                val json = """{"Name":"OPMachine","OPMachine":{"Action":"Reboot"},"SessionID":"0x%08x"}""".format(sid.toLong())
                sendAndAwait(transport, channel, DvripMessageIds.MACHINE_CONTROL, DvripMessageIds.MACHINE_CONTROL + 1, json, timeoutMillis = 3000)
                _state.value = _state.value.copy(statusMessage = "Reboot sent -- the camera will be unreachable for about a minute")
            }.onFailure { _state.value = _state.value.copy(statusMessage = "Reboot sent -- the camera will be unreachable for about a minute") }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun requestReboot() {
        _state.value = _state.value.copy(rebootRequested = true)
    }

    fun cancelReboot() {
        _state.value = _state.value.copy(rebootRequested = false)
    }

    /**
     * `ModifyPassword` has no `@ConfigJsonNameLink` annotation in the
     * decompiled vendor source (it's invoked with a native-resolved cmdId of
     * -1), but every other confirmed-live named command uses the identical
     * `{"Name":X,"X":{...},"SessionID":...}` envelope over
     * [DvripMessageIds.CONFIG_SET] (the same id confirmed live for
     * General.General), so `ModifyPassword` is sent the same way here. Field
     * names (`EncryptType`/`UserName`/`PassWord`/`NewPassWord`) come directly
     * from the vendor's `DevPsdManageActivity`. On `Ret:100`, persists the new
     * credentials to [CameraStore] so the rest of the app keeps working.
     */
    fun changePassword(newPassword: String) {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        val cam = camera ?: return
        val creds = credentials ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, passwordChangeInFlight = true)
            runCatching {
                // Passwords are ALWAYS SofiaHash-ed, including an empty one --
                // see the note in DvripLoginNegotiator.buildLoginRequestJson.
                // (Verified live: the no-password admin account authenticates
                // with SofiaHash.hash(""), not a literal empty string.)
                val hashOld = SofiaHash.hash(creds.password)
                val hashNew = SofiaHash.hash(newPassword)
                val body = buildJsonObject {
                    put("EncryptType", "MD5")
                    put("UserName", creds.username)
                    put("PassWord", hashOld)
                    put("NewPassWord", hashNew)
                }
                val json = buildJsonObject {
                    put("Name", "ModifyPassword")
                    put("ModifyPassword", body)
                    put("SessionID", "0x%08x".format(sid.toLong()))
                }.toString()
                val text = sendAndAwait(transport, channel, DvripMessageIds.CONFIG_SET, DvripMessageIds.CONFIG_SET_RESPONSE, json)
                val ret = text?.let { Regex("\"Ret\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (ret != 100) {
                    _state.value = _state.value.copy(errorMessage = "Password change rejected by the camera (Ret=$ret)")
                    return@runCatching
                }
                // Ret:100 alone is NOT proof the change applied. Some firmware
                // (this test camera included) ACKs the legacy MD5 ModifyPassword
                // with Ret:100 but validates login against a separate PasswordV2
                // blob, so the password never actually changes. Verify by opening
                // a fresh login with the new password before claiming success --
                // never trust the ACK. See PROTOCOL_NOTES.md "Account management".
                val applied = verifyLogin(cam.host, cam.dvripPort, creds.username, newPassword)
                if (applied) {
                    val newCreds = CameraCredentials(creds.username, newPassword)
                    store.save(cam, newCreds)
                    credentials = newCreds
                    sessionManager?.connect(newCreds)
                    _state.value = _state.value.copy(statusMessage = "Password changed")
                } else {
                    _state.value = _state.value.copy(
                        errorMessage = "The camera accepted the request but the new password does not " +
                            "work -- this firmware likely requires an encrypted password scheme this app " +
                            "can't produce yet. Password unchanged.",
                    )
                }
            }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Password change failed") }
            _state.value = _state.value.copy(busy = false, passwordChangeInFlight = false)
        }
    }

    /**
     * Changes the device account's username via `ModifyUser` (msg 1484):
     * fetches the full account object with `GetAllUser` (msg 1472), rewrites
     * its `Name`, and submits it back. Live-confirmed working 2026-07-03.
     * The password is unaffected (it rides along in the object unchanged).
     *
     * Renaming invalidates the current control session, so on success we
     * verify a fresh login under the new name, persist the new credentials,
     * and reconnect the live session under the new identity.
     */
    fun changeUsername(newUsername: String) {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        val cam = camera ?: return
        val creds = credentials ?: return
        if (newUsername.isBlank() || newUsername == creds.username) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching {
                val sidHex = "0x%08x".format(sid.toLong())
                val listText = sendAndAwait(
                    transport, channel,
                    DvripMessageIds.USER_GET_ALL, DvripMessageIds.USER_GET_ALL_RESPONSE,
                    buildJsonObject { put("Name", "GetAllUser"); put("SessionID", sidHex) }.toString(),
                )
                val users = listText?.let { Json.parseToJsonElement(it) as? JsonObject }
                    ?.get("Users")?.jsonArray
                    ?: run {
                        _state.value = _state.value.copy(errorMessage = "Could not read the account list from the camera")
                        return@runCatching
                    }
                val currentUser = users.map { it as JsonObject }
                    .firstOrNull { it["Name"]?.jsonPrimitive?.content == creds.username }
                    ?: run {
                        _state.value = _state.value.copy(errorMessage = "Account '${creds.username}' not found on the camera")
                        return@runCatching
                    }
                val renamed = JsonObject(currentUser.toMutableMap().apply { put("Name", JsonPrimitive(newUsername)) })
                val request = buildJsonObject {
                    put("Name", "ModifyUser")
                    put("UserName", creds.username)
                    put("User", renamed)
                    put("SessionID", sidHex)
                }.toString()
                val text = sendAndAwait(transport, channel, DvripMessageIds.USER_MODIFY, DvripMessageIds.USER_MODIFY_RESPONSE, request)
                val ret = text?.let { Regex("\"Ret\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (ret != 100) {
                    _state.value = _state.value.copy(errorMessage = "Username change rejected by the camera (Ret=$ret)")
                    return@runCatching
                }
                val applied = verifyLogin(cam.host, cam.dvripPort, newUsername, creds.password)
                if (applied) {
                    val newCreds = CameraCredentials(newUsername, creds.password)
                    store.save(cam, newCreds)
                    credentials = newCreds
                    sessionManager?.connect(newCreds)
                    _state.value = _state.value.copy(statusMessage = "Username changed to '$newUsername'")
                } else {
                    _state.value = _state.value.copy(
                        errorMessage = "The camera accepted the request but login under '$newUsername' failed. " +
                            "Username may be unchanged.",
                    )
                }
            }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Username change failed") }
            _state.value = _state.value.copy(busy = false)
        }
    }

    /**
     * Opens a throwaway DVRIP login with the given credentials purely to check
     * whether they authenticate, then closes it. Used to verify a
     * password/username change actually took effect rather than trusting the
     * command's Ret:100 ACK. Returns false on any connection/auth failure.
     */
    private suspend fun verifyLogin(host: String, port: Int, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val probe = DvripTransport(host, port)
                try {
                    probe.connect()
                    DvripLoginNegotiator().negotiate(probe, CameraCredentials(username, password))
                    true
                } finally {
                    probe.close()
                }
            }.getOrDefault(false)
        }

    fun clearStatus() {
        _state.value = _state.value.copy(statusMessage = null, errorMessage = null)
    }

    suspend fun getConfigMetadata(configName: String) = configChannel?.getCachedMetadata(configName)

    override fun onCleared() {
        sessionManager?.shutdown()
    }
}
