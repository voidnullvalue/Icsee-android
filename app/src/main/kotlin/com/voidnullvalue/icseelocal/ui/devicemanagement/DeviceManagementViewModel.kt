package com.voidnullvalue.icseelocal.ui.devicemanagement

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.voidnullvalue.icseelocal.app.IcseeApplication
import com.voidnullvalue.icseelocal.config.ChangeRandomUserClient
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
import com.voidnullvalue.icseelocal.storage.CameraStore
import com.voidnullvalue.icseelocal.video.RecordedClipExporter
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/**
 * One account as reported by `GetAllUser` (msg 1472). [memo] is the telling field
 * on this firmware: the blank-password `admin` backdoor carries `"factory test
 * account"`, while the real per-device admin (a random name like `xkfu`) carries
 * `"admin 's account"`. [hasPassword]/[hasPasswordV2] show whether a real
 * credential is set without exposing it. Used to check whether the login we're
 * using is the transient factory account rather than a real one.
 */
data class DeviceAccount(
    val name: String,
    val group: String,
    val memo: String,
    val reserved: Boolean,
    val sharable: Boolean,
    val hasPassword: Boolean,
    val hasPasswordV2: Boolean,
)

/**
 * Parses a `GetAllUser` (msg 1473) response body into accounts. Tolerant of missing
 * fields and non-object entries. Pure/standalone so it's unit-testable without the
 * Android ViewModel.
 */
fun parseDeviceAccounts(responseText: String): List<DeviceAccount> {
    val root = runCatching { Json.parseToJsonElement(responseText) as? JsonObject }.getOrNull() ?: return emptyList()
    val arr = root["Users"]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
        fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
        DeviceAccount(
            name = s("Name"),
            group = s("Group"),
            memo = s("Memo"),
            reserved = b("Reserved"),
            sharable = b("Sharable"),
            hasPassword = s("Password").isNotEmpty(),
            hasPasswordV2 = s("PasswordV2").isNotEmpty(),
        )
    }
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
    val formatRequested: Boolean = false,
    val recordings: List<RecordedFile>? = null,
    val recordingsQuerying: Boolean = false,
    val downloadingClip: String? = null,
    val downloadProgressBytes: Long = 0,
    val playbackFile: String? = null,
    val accounts: List<DeviceAccount>? = null,
    val accountsQuerying: Boolean = false,
)

class DeviceManagementViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val store = CameraStore(application)
    // Shared session owner -- this ViewModel no longer builds or shuts down managers;
    // it acquires the registry's single session for the camera (shared with live
    // view, for zero extra logins when both are used) and releases it. See
    // [CameraSessionRegistry].
    private val registry = (application as IcseeApplication).sessionRegistry
    private var sessionManager: CameraSessionManager? = null
    private var camera: CameraDescriptor? = null
    private var credentials: CameraCredentials? = null
    private var configChannel: DvripConfigChannel? = null

    // The coroutine observing the shared manager's state. Held so it can be cancelled
    // when we release the session (on re-load or navigation away).
    private var stateJob: Job? = null

    // The camera we currently hold a registry acquire for (null = not holding).
    // Makes releaseSession idempotent so leaveFocus and onStop can't double-release.
    private var held: CameraDescriptor? = null

    private val _state = MutableStateFlow(DeviceManagementUiState())
    val state: StateFlow<DeviceManagementUiState> = _state.asStateFlow()

    // This ViewModel is Activity-scoped and outlives any individual screen --
    // navigating back to Live Control does NOT clear it. A camera's firmware in
    // this class (Xiongmai/XM-derived DVRIP) is known to be touchy about login
    // *rate*, not just failed passwords, so this app only ever logs in while a
    // screen that needs the session is actually the one in view (enterFocus/
    // leaveFocus below, driven by MainActivity) -- never as a standing background
    // service that reconnects on its own schedule.

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Called by MainActivity exactly when a device-management-family screen becomes
     * the one on screen. Fires only on a real transition into the family (see
     * MainActivity's `LaunchedEffect` keyed on the family's camera id), not on every
     * recomposition, so bouncing between DeviceManagement and its sub-screens
     * (ConfigEditor, ImageSettings, PlaybackBrowser) for the same camera does not
     * re-acquire.
     */
    fun enterFocus(cameraId: String) {
        load(cameraId)
    }

    /**
     * Called by MainActivity exactly when navigation leaves the device-management
     * family (anywhere else, including back to Live Control). Releases the shared
     * session back to the registry (which lingers briefly then tears it down);
     * nothing keeps reconnecting for a screen nobody is looking at.
     */
    fun leaveFocus() {
        releaseSession()
    }

    fun load(cameraId: String) {
        viewModelScope.launch {
            val found = store.cameras.first().firstOrNull { it.id == cameraId } ?: return@launch

            // Release any previously-held camera before acquiring the new one.
            releaseSession()

            camera = found
            val creds = store.credentialsFor(cameraId) ?: CameraCredentials("", "")
            credentials = creds
            acquireSession(found, creds)
        }
    }

    /**
     * Acquires the shared session for [found] from the registry (connecting it if
     * it isn't already up -- possibly reusing one live view is holding, for zero
     * extra logins) and observes its state to build the config channel.
     */
    private fun acquireSession(found: CameraDescriptor, creds: CameraCredentials) {
        val manager = registry.acquire(found.host, found.dvripPort, creds)
        sessionManager = manager
        held = found
        stateJob?.cancel()
        stateJob = viewModelScope.launch {
            manager.state.collect { connState ->
                _state.value = _state.value.copy(connectionState = connState)
                if (connState is ConnectionState.Authenticated) {
                    configChannel = DvripConfigChannel(manager.controlTransport!!, manager.commandChannel!!, connState.sessionId, getApplication())
                    refreshAll()
                }
            }
        }
    }

    /** Releases the shared session back to the registry. Idempotent. */
    private fun releaseSession() {
        val h = held ?: return
        held = null
        stateJob?.cancel()
        stateJob = null
        configChannel = null
        sessionManager = null
        registry.release(h.host, h.dvripPort)
    }

    override fun onStop(owner: LifecycleOwner) {
        // App backgrounded: release the shared session. Deliberately NOT re-acquired
        // on onStart, unlike the Live-family session -- device management is an
        // occasional, deliberate task, not something meant to sit connected in the
        // background. Re-entering the screen reconnects via enterFocus -> load(), so
        // nothing is lost by staying released until the user actually comes back.
        releaseSession()
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
     * `ChangeRandomUser` (msg 1660/1661) -- the vendor's `SetDevPsdActivity`
     * mechanism, sent over a throwaway **session-less** connection (no login,
     * no `SessionID`; see `ChangeRandomUserClient`). Two earlier approaches
     * were tried and both confirmed NOT to work against a real (non-`admin`)
     * account: legacy `ModifyPassword` alone, and `ModifyPassword` + a
     * `System.ExUserMap` read-modify-write with the vendor's `u()` password
     * obfuscation (both ACK `Ret:100` but the resulting `PasswordV2` never
     * authenticates the new password -- see PASSWORD_CHANGE_RE.md "live-tested
     * against xkfu"). `ChangeRandomUser` is the one that's **live-verified
     * working** (2026-07-07): old password rejected, new password
     * authenticates, immediately, no factory reset needed. Because it doesn't
     * use the shared session at all, it also doesn't touch the per-camera
     * Ret:205 login-rate budget.
     */
    fun changePassword(newPassword: String) {
        val cam = camera ?: return
        val creds = credentials ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, passwordChangeInFlight = true)
            runCatching {
                val result = ChangeRandomUserClient.changeUser(
                    host = cam.host,
                    port = cam.dvripPort,
                    currentRandomName = creds.username,
                    currentRandomPassword = creds.password,
                    newUsername = creds.username,
                    newPassword = newPassword,
                )
                if (result !is ChangeRandomUserClient.Result.Success) {
                    val detail = (result as? ChangeRandomUserClient.Result.Failure)?.detail
                    val ret = (result as? ChangeRandomUserClient.Result.Failure)?.ret
                    _state.value = _state.value.copy(errorMessage = "Password change rejected by the camera (Ret=$ret${detail?.let { ": $it" } ?: ""})")
                    return@runCatching
                }

                // Ret:100 alone is not proof enough given this firmware's history --
                // verify by actually re-authenticating with the new password before
                // persisting or reporting success.
                val newCreds = CameraCredentials(creds.username, newPassword)
                if (reconnectAndAwait(cam, newCreds)) {
                    store.save(cam, newCreds)
                    credentials = newCreds
                    _state.value = _state.value.copy(statusMessage = "Password changed")
                } else {
                    // New password doesn't actually authenticate -- restore the live
                    // session under the still-working old credentials.
                    reconnectAndAwait(cam, creds)
                    _state.value = _state.value.copy(
                        errorMessage = "The camera accepted the change but the new password still does not " +
                            "authenticate. Password left as-is in local storage.",
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
                val newCreds = CameraCredentials(newUsername, creds.password)
                if (reconnectAndAwait(cam, newCreds)) {
                    store.save(cam, newCreds)
                    credentials = newCreds
                    _state.value = _state.value.copy(statusMessage = "Username changed to '$newUsername'")
                } else {
                    // Restore the live session under the old identity.
                    reconnectAndAwait(cam, creds)
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
     * Reconnects the shared session under [creds] and waits for it to settle,
     * returning true iff it authenticated. This both re-establishes the live session
     * (mandatory after a credential change, which invalidates the old one) and serves
     * as the honest verification that the new credentials actually work -- one login
     * instead of the old throwaway-verify-then-reconnect pair. Routed through the
     * registry so the shared session and its stored credentials update for every
     * consumer, and so it stays under the per-camera login-rate ceiling.
     *
     * connect() tears the old session down synchronously before launching the new
     * attempt, so by the time this awaits, state has already left Authenticated --
     * no risk of reading the pre-reconnect success as if it were the new one.
     */
    private suspend fun reconnectAndAwait(cam: CameraDescriptor, creds: CameraCredentials): Boolean {
        registry.reconnect(cam.host, cam.dvripPort, creds)
        val manager = sessionManager ?: return false
        return withTimeoutOrNull(12_000) {
            manager.state.first {
                it is ConnectionState.Authenticated || it is ConnectionState.Failed
            } is ConnectionState.Authenticated
        } ?: false
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
     * Loads EVERY recorded clip on the SD card as one flat list (no day picker).
     * The camera rejects a wide multi-year `OPFileQuery` range (Ret:119), so we
     * read the recorded span from `StorageInfo` and query each day in it, then
     * merge. Live-confirmed 2026-07-09.
     */
    fun loadAllRecordings() {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        val ch = camera?.channel ?: 0
        viewModelScope.launch {
            _state.value = _state.value.copy(recordingsQuerying = true, recordings = null, errorMessage = null)
            runCatching {
                val storageJson = "{\"Name\":\"StorageInfo\",\"StorageInfo\":{},\"SessionID\":\"0x%08x\"}".format(sid.toLong())
                val storageText = sendAndAwait(transport, channel, DvripMessageIds.INFO_GET, DvripMessageIds.INFO_GET_RESPONSE, storageJson)
                val days = recordedDays(storageText)
                val all = ArrayList<RecordedFile>()
                var lastDiag: String? = null
                for (day in days) {
                    val (files, diag) = queryDay(transport, channel, sid, ch, day)
                    all += files
                    if (diag != null) lastDiag = diag
                }
                all.sortByDescending { it.beginTime }
                val diag = when {
                    all.isNotEmpty() -> null
                    lastDiag != null -> lastDiag
                    days.isEmpty() -> "SD card reports no recorded time span."
                    else -> "No recordings on the SD card."
                }
                _state.value = _state.value.copy(recordings = all, errorMessage = diag)
            }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Query failed", recordings = emptyList()) }
            _state.value = _state.value.copy(recordingsQuerying = false)
        }
    }

    /** Runs one day's OPFileQuery (msg 1440); returns (clips, diagnostic-or-null). */
    private suspend fun queryDay(
        transport: DvripTransport,
        channel: DvripCommandChannel,
        sid: UInt,
        ch: Int,
        date: String,
    ): Pair<List<RecordedFile>, String?> {
        val json = buildJsonObject {
            put("Name", "OPFileQuery")
            put("OPFileQuery", buildJsonObject {
                put("BeginTime", "$date 00:00:00")
                put("EndTime", "$date 23:59:59")
                put("Channel", ch)
                put("DriverTypeMask", "0x0000FFFF")
                // Event="*" is REQUIRED -- omitting it returns Ret:119 (live-confirmed).
                put("Event", "*")
                put("Type", "h264")
                put("StreamType", "0x00000000")
            })
            put("SessionID", "0x%08x".format(sid.toLong()))
        }.toString()
        val text = sendAndAwait(transport, channel, DvripMessageIds.FILE_QUERY, DvripMessageIds.FILE_QUERY + 1, json, timeoutMillis = 15000)
        val files = text?.let { parseRecordings(it) } ?: emptyList()
        val ret = text?.let { Regex("\"Ret\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1) }
        val diag = when {
            text == null -> "No response from camera (timeout)."
            files.isEmpty() && ret != null && ret != "100" -> "Camera rejected the query (Ret=$ret)."
            else -> null
        }
        return files to diag
    }

    /** Extracts the list of days ("yyyy-MM-dd") spanned by the recorded data in a StorageInfo reply. */
    private fun recordedDays(storageText: String?): List<String> {
        if (storageText == null) return emptyList()
        val dates = Regex("\"(?:Old|New)(?:Start|End)Time\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2}) ")
            .findAll(storageText)
            .map { it.groupValues[1] }
            .filter { it != "0000-00-00" }
            .toSortedSet()
        if (dates.isEmpty()) return emptyList()
        return try {
            val start = java.time.LocalDate.parse(dates.first())
            val end = java.time.LocalDate.parse(dates.last())
            generateSequence(start) { if (it < end) it.plusDays(1) else null }
                .map { it.toString() }
                .toList()
        } catch (e: Exception) {
            dates.toList()
        }
    }

    /** Sets the camera clock to the phone's current local time (`OPTimeSetting`, msg 1450). */
    fun setCameraClock() {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            runCatching {
                val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val json = "{\"Name\":\"OPTimeSetting\",\"OPTimeSetting\":\"$now\",\"SessionID\":\"0x%08x\"}".format(sid.toLong())
                val text = sendAndAwait(transport, channel, DvripMessageIds.MACHINE_CONTROL, DvripMessageIds.MACHINE_CONTROL + 1, json)
                val ret = text?.let { Regex("\"Ret\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1) }
                if (ret == "100") _state.value = _state.value.copy(statusMessage = "Camera clock set to $now", deviceTime = now)
                else _state.value = _state.value.copy(errorMessage = "Set clock failed (Ret=$ret)")
            }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Set clock failed") }
            _state.value = _state.value.copy(busy = false)
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

    /**
     * Downloads [clip] off the SD card (DVRIP OPPlayBack), remuxes the XM-framed
     * HEVC to a standard MP4 in the app cache, and exposes its path via
     * [DeviceManagementUiState.playbackFile] for the browser to play in ExoPlayer.
     * See [RecordedClipExporter]; protocol live-confirmed 2026-07-09.
     */
    fun downloadAndPlay(clip: RecordedFile) {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        if (clip.fileName.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                downloadingClip = clip.fileName, downloadProgressBytes = 0,
                errorMessage = null, playbackFile = null,
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    val exporter = RecordedClipExporter(transport, channel, sid)
                    val out = File(getApplication<Application>().cacheDir, "recording_${System.currentTimeMillis()}.mp4")
                    exporter.exportToMp4(clip.fileName, clip.beginTime, clip.endTime, out) { bytes ->
                        _state.value = _state.value.copy(downloadProgressBytes = bytes)
                    }
                    out.absolutePath
                }
            }.onSuccess { path ->
                _state.value = _state.value.copy(playbackFile = path)
            }.onFailure {
                _state.value = _state.value.copy(errorMessage = it.message ?: "Download failed")
            }
            _state.value = _state.value.copy(downloadingClip = null)
        }
    }

    fun clearPlayback() {
        _state.value.playbackFile?.let { runCatching { File(it).delete() } }
        _state.value = _state.value.copy(playbackFile = null)
    }

    /**
     * Reads the camera's account list (`GetAllUser`, msg 1472) so we can see whether
     * the login we're using is the transient blank-password `admin` "factory test
     * account" versus a real per-device account (random name, memo "admin 's
     * account"). This is a diagnostic for the "the factory account idles out after
     * provisioning, which is why login dies after a while" theory -- see [DeviceAccount].
     */
    fun loadAccounts() {
        val manager = sessionManager ?: return
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(accountsQuerying = true, errorMessage = null)
            runCatching {
                val sidHex = "0x%08x".format(sid.toLong())
                val text = sendAndAwait(
                    transport, channel,
                    DvripMessageIds.USER_GET_ALL, DvripMessageIds.USER_GET_ALL_RESPONSE,
                    buildJsonObject { put("Name", "GetAllUser"); put("SessionID", sidHex) }.toString(),
                )
                _state.value = _state.value.copy(accounts = text?.let { parseAccounts(it) } ?: emptyList())
            }.onFailure { _state.value = _state.value.copy(errorMessage = it.message ?: "Account query failed", accounts = emptyList()) }
            _state.value = _state.value.copy(accountsQuerying = false)
        }
    }

    private fun parseAccounts(responseText: String): List<DeviceAccount> = parseDeviceAccounts(responseText)

    suspend fun getConfigMetadata(configName: String) = configChannel?.getCachedMetadata(configName)

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        releaseSession()
    }
}
