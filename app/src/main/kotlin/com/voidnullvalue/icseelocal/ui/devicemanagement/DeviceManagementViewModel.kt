package com.voidnullvalue.icseelocal.ui.devicemanagement

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
import kotlinx.coroutines.Job
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

/** One recorded clip returned by OPFileQuery (msg 1440). */
data class RecordedFile(
    val beginTime: String,
    val endTime: String,
    val fileName: String,
    val sizeText: String,
)

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
    val formatRequested: Boolean = false,
    val recordings: List<RecordedFile>? = null,
    val recordingsQuerying: Boolean = false,
)

class DeviceManagementViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val store = CameraStore(application)
    private var sessionManager: CameraSessionManager? = null
    private var camera: CameraDescriptor? = null
    private var credentials: CameraCredentials? = null
    private var configChannel: DvripConfigChannel? = null

    // The coroutine collecting the current manager's state. Held so a reload can
    // cancel it -- otherwise each load() leaked a collector bound to a manager we
    // were about to replace.
    private var stateJob: Job? = null

    // Set in onStop() when we tear the session down for backgrounding, so onStart()
    // knows whether to bring it back. See the onStop/onStart rationale below.
    private var resumeSessionOnForeground = false

    private val _state = MutableStateFlow(DeviceManagementUiState())
    val state: StateFlow<DeviceManagementUiState> = _state.asStateFlow()

    init {
        // This ViewModel is Activity-scoped (see MainActivity: default viewModel()
        // scoping, shared across the whole device-management screen family), so it --
        // and its DVRIP control session, keepalive, and auto-reconnect loop -- outlive
        // the visible screen and keep running in the background indefinitely. Left
        // unmanaged, a session opened once (right after associating a camera, to
        // configure it) would keep silently re-logging-in on every socket hiccup for
        // hours or days, which the camera's firmware counts toward its Ret:205 rate
        // lockout -- an automatic auth "spam" independent of any flaky link or button.
        // LiveControlViewModel already guards against this; observe the process
        // lifecycle here too so the session is torn down while backgrounded.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun load(cameraId: String) {
        viewModelScope.launch {
            val found = store.cameras.first().firstOrNull { it.id == cameraId } ?: return@launch
            camera = found
            val creds = store.credentialsFor(cameraId) ?: CameraCredentials("", "")
            credentials = creds

            // Tear down any session/collector from a previous load() before starting a
            // new one. Without this, re-opening device management stacked another
            // immortal CameraSessionManager (each with its own keepalive + reconnect
            // loop and its own fresh rate limiter) on top of the old one, multiplying
            // the background login rate every time the screen was reopened.
            stateJob?.cancel()
            sessionManager?.shutdown()

            val manager = CameraSessionManager(found.host, found.dvripPort)
            sessionManager = manager
            stateJob = viewModelScope.launch {
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

    override fun onStop(owner: LifecycleOwner) {
        // App backgrounded: there is nothing to show and no reason to keep a control
        // session (and its keepalive/auto-reconnect) alive, so drop it. Remember
        // whether it was actually live so onStart can bring it back -- but never
        // resume a session that was already Failed (e.g. a real credential rejection),
        // which would just re-spam a login the camera already refused.
        val state = sessionManager?.state?.value
        resumeSessionOnForeground = state is ConnectionState.Authenticated ||
            state is ConnectionState.Reconnecting ||
            state is ConnectionState.Connecting ||
            state is ConnectionState.NegotiatingCrypto ||
            state is ConnectionState.Authenticating
        sessionManager?.disconnect()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!resumeSessionOnForeground) return
        resumeSessionOnForeground = false
        val creds = credentials ?: return
        sessionManager?.connect(creds)
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

    fun requestFormat() { _state.value = _state.value.copy(formatRequested = true) }
    fun cancelFormat() { _state.value = _state.value.copy(formatRequested = false) }

    /**
     * Formats the SD card via `OPStorageManager` (msg 1460). Body taken verbatim
     * from the vendor's `DevStorageSettingActivity.U1()`
     * (`Action:"Clear", Type:"Data"`). SerialNo/PartNo identify the disk/partition;
     * 0/0 targets the (single) card on an IPC. Destructive -- gated behind a
     * confirm dialog in the UI. Built from decompiled spec; not yet run live.
     */
    fun formatSdCard() {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, formatRequested = false)
            runCatching {
                val json = buildJsonObject {
                    put("Name", "OPStorageManager")
                    put("OPStorageManager", buildJsonObject {
                        put("Action", "Clear")
                        put("Type", "Data")
                        put("SerialNo", 0)
                        put("PartNo", 0)
                    })
                    put("SessionID", "0x%08x".format(sid.toLong()))
                }.toString()
                val text = sendAndAwait(transport, channel, DvripMessageIds.STORAGE_MANAGER, DvripMessageIds.STORAGE_MANAGER + 1, json, timeoutMillis = 60000)
                val ret = text?.let { Regex("\"Ret\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (ret == 100) _state.value = _state.value.copy(statusMessage = "SD card formatted")
                else _state.value = _state.value.copy(errorMessage = "Format failed: Ret=$ret")
            }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Format failed") }
            _state.value = _state.value.copy(busy = false)
        }
    }

    /**
     * Lists recorded clips for [date] via `OPFileQuery` (msg 1440). Request shape
     * is the standard XM DVRIP form (BeginTime/EndTime/Channel/DriverTypeMask/
     * Type/StreamType); the response is an `OPFileQuery` array of
     * BeginTime/EndTime/FileName/FileLength. Built from decompiled spec; parsing
     * is tolerant and needs live confirmation. In-app playback of a clip is not
     * wired -- the DVRIP media-byte stream is the same one still unresolved for
     * live view (see PROTOCOL_STATUS.md), so this browser lists what's recorded
     * rather than streaming it.
     */
    fun queryRecordings(date: String) {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        val ch = camera?.channel ?: 0
        viewModelScope.launch {
            _state.value = _state.value.copy(recordingsQuerying = true, recordings = null, errorMessage = null)
            runCatching {
                val json = buildJsonObject {
                    put("Name", "OPFileQuery")
                    put("OPFileQuery", buildJsonObject {
                        put("BeginTime", "$date 00:00:00")
                        put("EndTime", "$date 23:59:59")
                        put("Channel", ch)
                        put("DriverTypeMask", "0x0000FFFF")
                        put("Type", "h264")
                        put("StreamType", "0x00000000")
                    })
                    put("SessionID", "0x%08x".format(sid.toLong()))
                }.toString()
                val text = sendAndAwait(transport, channel, DvripMessageIds.FILE_QUERY, DvripMessageIds.FILE_QUERY + 1, json, timeoutMillis = 15000)
                val files = text?.let { parseRecordings(it) } ?: emptyList()
                _state.value = _state.value.copy(recordings = files)
            }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Query failed", recordings = emptyList()) }
            _state.value = _state.value.copy(recordingsQuerying = false)
        }
    }

    private fun parseRecordings(responseText: String): List<RecordedFile> {
        val root = runCatching { Json.parseToJsonElement(responseText) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = (root["OPFileQuery"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            RecordedFile(
                beginTime = s("BeginTime"),
                endTime = s("EndTime"),
                fileName = s("FileName"),
                sizeText = s("FileLength"),
            )
        }
    }

    suspend fun getConfigMetadata(configName: String) = configChannel?.getCachedMetadata(configName)

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        stateJob?.cancel()
        sessionManager?.shutdown()
    }
}
