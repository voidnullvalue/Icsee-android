package com.voidnullvalue.icseelocal.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.voidnullvalue.icseelocal.app.IcseeApplication
import com.voidnullvalue.icseelocal.audio.FileAudioSource
import com.voidnullvalue.icseelocal.audio.MicrophoneSource
import com.voidnullvalue.icseelocal.audio.TalkController
import com.voidnullvalue.icseelocal.config.CameraLighting
import com.voidnullvalue.icseelocal.config.CameraLightingCaps
import com.voidnullvalue.icseelocal.config.ConfigResult
import com.voidnullvalue.icseelocal.config.DvripConfigChannel
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.model.StreamType
import com.voidnullvalue.icseelocal.ptz.DanceChoreography
import com.voidnullvalue.icseelocal.ptz.KonamiCodeDetector
import com.voidnullvalue.icseelocal.ptz.PtzCommand
import com.voidnullvalue.icseelocal.ptz.PtzController
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.session.CameraSessionManager
import com.voidnullvalue.icseelocal.storage.CameraStore
import com.voidnullvalue.icseelocal.storage.CameraThumbStore
import com.voidnullvalue.icseelocal.storage.PresetThumbStore
import com.voidnullvalue.icseelocal.video.LiveStreamRecorder
import com.voidnullvalue.icseelocal.video.RecordedVideoStore
import com.voidnullvalue.icseelocal.video.RtspPlayerState
import com.voidnullvalue.icseelocal.video.RtspStreamManager
import com.voidnullvalue.icseelocal.video.SavedVideo
import com.voidnullvalue.icseelocal.video.SnapshotCapture
import com.voidnullvalue.icseelocal.video.SnapshotResult
import com.voidnullvalue.icseelocal.video.VideoStats
import com.voidnullvalue.icseelocal.video.VideoStreamController
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LiveControlViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val store = CameraStore(application)
    private val presetThumbs = PresetThumbStore(application)
    private val cameraThumbs = CameraThumbStore(application)
    private val microphone = MicrophoneSource(application)
    // The app-wide session owner. This ViewModel no longer creates or shuts down
    // CameraSessionManagers -- it acquires the shared one for the camera it's showing
    // and releases it, so live view and device management share one login per camera.
    private val registry = (application as IcseeApplication).sessionRegistry

    private var sessionManager: CameraSessionManager? = null
    private var ptzController: PtzController? = null
    private var videoController: VideoStreamController? = null
    private var talkController: TalkController? = null
    private var videoStatsJob: Job? = null

    // Easter egg: Konami code on the PTZ pad unlocks "dance mode" -- see
    // KonamiCodeDetector, DanceChoreography, FileAudioSource.
    private val konamiDetector = KonamiCodeDetector()
    private var danceChoreography: DanceChoreography? = null
    private var danceAudioController: TalkController? = null
    private val _danceModeTriggered = MutableStateFlow(false)
    val danceModeTriggered: StateFlow<Boolean> = _danceModeTriggered.asStateFlow()
    // The coroutine observing the shared manager's state (wires video/talk on
    // Authenticated). Held so it can be cancelled when we release the session.
    private var sessionStateJob: Job? = null

    // Which SessionID wireControllers() last built controllers for -- a reconnect
    // (manual or automatic, see CameraSessionManager) produces a brand new
    // ConnectionState.Authenticated with a brand new SessionID, and Ptz/Video/Talk
    // controllers all capture their SessionID immutably at construction time, so
    // they must be torn down and rebuilt rather than left pointing at a now-dead
    // session.
    private var wiredSessionId: UInt? = null

    // True exactly while a Live-family screen (LiveControl or Diagnostics -- see
    // MainActivity, which drives this) is the one actually on screen. This
    // ViewModel is Activity-scoped and outlives any individual screen, so without
    // this flag it has no way to know whether anyone is still looking at the camera.
    // The session is held only while inFocus is true AND the app is foregrounded.
    private var inFocus = false

    // The camera we currently hold a registry acquire for (null = not holding).
    // Makes releaseSession idempotent so leaveFocus and onStop can't double-release.
    private var held: CameraDescriptor? = null

    // Real video: RTSP (confirmed live, see PROTOCOL_NOTES.md "RTSP video --
    // LIVE CONFIRMED"). videoController above (DVRIP OPMonitor) is kept
    // running for stats/diagnostics -- its media-byte gap is still open,
    // see PROTOCOL_STATUS.md -- but the RTSP player is what's actually shown.
    @UnstableApi
    val rtspPlayer = RtspStreamManager(application)

    @UnstableApi
    val rtspState: StateFlow<RtspPlayerState> = rtspPlayer.state

    @UnstableApi
    val mainStream: StateFlow<Boolean> = rtspPlayer.mainStream

    private val _camera = MutableStateFlow<CameraDescriptor?>(null)
    val camera: StateFlow<CameraDescriptor?> = _camera.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _speedStep = MutableStateFlow(7)
    val speedStep: StateFlow<Int> = _speedStep.asStateFlow()

    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking.asStateFlow()

    // Last talk failure, surfaced in the UI so a talk problem is visible instead
    // of silently doing nothing (null = no error).
    private val _talkError = MutableStateFlow<String?>(null)
    val talkError: StateFlow<String?> = _talkError.asStateFlow()

    // Count of mic frames actually captured+sent this talk session. Climbing = the
    // mic->network path works (so any silence is camera/format); stuck at 0 = capture
    // itself is failing. Purely diagnostic.
    private val _talkFrames = MutableStateFlow(0)
    val talkFrames: StateFlow<Int> = _talkFrames.asStateFlow()

    private val _videoStats = MutableStateFlow(VideoStats())
    val videoStats: StateFlow<VideoStats> = _videoStats.asStateFlow()

    private val _cruiseActive = MutableStateFlow(false)
    val cruiseActive: StateFlow<Boolean> = _cruiseActive.asStateFlow()

    private val _dayNightMode = MutableStateFlow(0) // 0 Auto, 1 Day, 2 Night
    val dayNightMode: StateFlow<Int> = _dayNightMode.asStateFlow()

    private val _lightOn = MutableStateFlow(false)
    val lightOn: StateFlow<Boolean> = _lightOn.asStateFlow()

    private val _lightingCaps = MutableStateFlow(CameraLightingCaps())
    val lightingCaps: StateFlow<CameraLightingCaps> = _lightingCaps.asStateFlow()

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _recordElapsedMs = MutableStateFlow(0L)
    val recordElapsedMs: StateFlow<Long> = _recordElapsedMs.asStateFlow()

    /** Local JPEG paths for PTZ presets 1–4 (last frame when the preset was saved). */
    private val _presetThumbPaths = MutableStateFlow<Map<Int, String>>(emptyMap())
    val presetThumbPaths: StateFlow<Map<Int, String>> = _presetThumbPaths.asStateFlow()
    /** Bumps when a preset thumb is rewritten so Compose reloads the JPEG. */
    private val _presetThumbEpoch = MutableStateFlow(0L)
    val presetThumbEpoch: StateFlow<Long> = _presetThumbEpoch.asStateFlow()

    private var liveRecorder: LiveStreamRecorder? = null
    private var recordOutFile: File? = null
    private var recordTicker: Job? = null
    private var playerViewRef: WeakReference<PlayerView>? = null

    @UnstableApi
    val muted: StateFlow<Boolean> = rtspPlayer.muted

    @UnstableApi
    val bitrateBps: StateFlow<Long> = rtspPlayer.bitrateBps

    fun bindPlayerView(view: PlayerView?) {
        playerViewRef = view?.let { WeakReference(it) }
    }

    fun clearStatusToast() {
        _statusToast.value = null
    }

    @UnstableApi
    fun toggleMute() = rtspPlayer.toggleMute()

    @UnstableApi
    fun toggleStreamQuality() {
        val found = _camera.value ?: return
        val nextMain = !rtspPlayer.mainStream.value
        setStreamType(if (nextMain) StreamType.MAIN else StreamType.SUB)
    }

    @UnstableApi
    fun reconnectRtsp() = rtspPlayer.reconnect()

    @UnstableApi
    fun setStreamType(type: StreamType) {
        val found = _camera.value ?: return
        if (found.streamType == type) return
        viewModelScope.launch {
            val updated = found.copy(streamType = type)
            val creds = store.credentialsFor(found.id)
            store.save(updated, creds)
            _camera.value = updated
            val credentials = creds ?: CameraCredentials("", "")
            rtspPlayer.start(
                host = updated.host,
                port = updated.rtspPort,
                username = credentials.username,
                password = credentials.password,
                channel = updated.channel + 1,
                mainStream = type == StreamType.MAIN,
                preferFactoryRtspAccount = updated.rtspFallbackEnabled,
            )
        }
    }

    fun toggleCruise() {
        if (_cruiseActive.value) {
            stopTour()
            _cruiseActive.value = false
        } else {
            startTour()
            _cruiseActive.value = true
        }
    }

    fun zoomIn() = ptzController?.onPointerDown(PtzCommand.ZOOM_TILE, _speedStep.value)
    fun zoomOut() = ptzController?.onPointerDown(PtzCommand.ZOOM_WIDE, _speedStep.value)
    fun zoomStop() = ptzController?.onPointerUp()

    fun toggleLight() {
        val manager = sessionManager ?: run {
            _statusToast.value = "Connect to camera to change lights"
            return
        }
        val transport = manager.controlTransport ?: return
        val channel = manager.commandChannel ?: return
        val sid = (manager.state.value as? ConnectionState.Authenticated)?.sessionId ?: return
        viewModelScope.launch {
            val config = DvripConfigChannel(transport, channel, sid, getApplication())
            val param = (config.getConfig("Camera.Param") as? ConfigResult.Success)?.value
            val paramEx = (config.getConfig("Camera.ParamEx") as? ConfigResult.Success)?.value
            val caps = CameraLighting.probe(param, paramEx)
            _lightingCaps.value = caps
            if (!caps.hasAnyLightControl) {
                _statusToast.value = "This camera has no light controls (checked WhiteLight, DayNightSwitch, DayNightColor)"
                return@launch
            }
            val result = CameraLighting.toggle(param, paramEx)
            if (result == null) {
                _statusToast.value = "Could not update light settings"
                return@launch
            }
            when (config.setConfig(result.configName, result.patched)) {
                is ConfigResult.Success -> {
                    _lightingCaps.value = result.caps
                    _lightOn.value = result.caps.whiteLightMode?.equals("Open", ignoreCase = true) == true
                    result.caps.dayNightMode?.let { _dayNightMode.value = it }
                    _statusToast.value = result.statusLabel
                }
                is ConfigResult.Failure -> _statusToast.value = "Light set failed"
            }
        }
    }

    fun reportLightingCaps() {
        val caps = _lightingCaps.value
        _statusToast.value = if (caps.hasAnyLightControl) {
            "Supported: ${caps.summary}"
        } else {
            "No light features detected yet — connect and try Light again"
        }
    }

    @UnstableApi
    fun takeSnapshot() {
        val view = playerViewRef?.get() ?: run {
            _statusToast.value = "Video not ready for snapshot"
            return
        }
        val name = _camera.value?.displayName ?: "camera"
        val cameraId = _camera.value?.id
        viewModelScope.launch {
            val capture = SnapshotCapture(getApplication())
            val surface = findSurfaceView(view)
            val rememberThumb: (android.graphics.Bitmap) -> Unit = { bmp ->
                if (cameraId != null) cameraThumbs.saveJpeg(cameraId, bmp)
            }
            val result = if (surface != null) {
                capture.captureFromSurfaceView(surface, name, onFrame = rememberThumb)
            } else {
                // TextureView path: grab bitmap directly
                val texture = findTextureView(view)
                val bmp = texture?.bitmap
                if (bmp == null) SnapshotResult.Failure("no frame")
                else {
                    rememberThumb(bmp)
                    saveBitmapSnapshot(bmp, name).also { bmp.recycle() }
                }
            }
            capture.release()
            _statusToast.value = when (result) {
                is SnapshotResult.Success -> "Snapshot saved"
                is SnapshotResult.Failure -> "Snapshot failed: ${result.reason}"
            }
        }
    }

    @UnstableApi
    fun toggleRecording() {
        if (_recording.value) stopRecording() else startRecording()
    }

    @UnstableApi
    private fun startRecording() {
        val view = playerViewRef?.get() ?: run {
            _statusToast.value = "Video not ready to record"
            return
        }
        val app = getApplication<Application>()
        val file = File(app.cacheDir, "live_${System.currentTimeMillis()}.mp4")
        val recorder = LiveStreamRecorder(app)
        if (!recorder.start(view, file)) {
            _statusToast.value = "Already recording"
            return
        }
        liveRecorder = recorder
        recordOutFile = file
        _recording.value = true
        _recordElapsedMs.value = 0
        recordTicker = viewModelScope.launch {
            while (_recording.value) {
                _recordElapsedMs.value = recorder.elapsedMs
                kotlinx.coroutines.delay(250)
            }
        }
        _statusToast.value = "Recording…"
    }

    @UnstableApi
    private fun stopRecording() {
        val recorder = liveRecorder
        val file = recordOutFile
        liveRecorder = null
        recordOutFile = null
        recordTicker?.cancel()
        recordTicker = null
        _recording.value = false
        recorder?.stop()
        if (file == null || !file.exists() || file.length() < 1000) {
            runCatching { file?.delete() }
            _statusToast.value = "Recording failed (too short)"
            return
        }
        viewModelScope.launch {
            val name = "icsee_live_${System.currentTimeMillis()}.mp4"
            when (val saved = RecordedVideoStore.save(getApplication(), file, name)) {
                is SavedVideo.Success -> _statusToast.value = "Saved ${saved.label}"
                is SavedVideo.Failure -> _statusToast.value = "Save failed: ${saved.reason}"
            }
            runCatching { file.delete() }
        }
    }

    private fun saveBitmapSnapshot(bitmap: android.graphics.Bitmap, cameraName: String): SnapshotResult {
        val capture = SnapshotCapture(getApplication())
        // SnapshotCapture only exposes SurfaceView path; write via a one-off MediaStore copy.
        return try {
            val tmp = File.createTempFile("snap", ".jpg", getApplication<Application>().cacheDir)
            java.io.FileOutputStream(tmp).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            // Re-read through RecordedVideoStore-style image save
            val result = SnapshotCapture(getApplication()).let { c ->
                // Use internal pattern: Pictures via MediaStore — duplicate minimal save
                saveJpegToGallery(tmp, cameraName).also { tmp.delete(); c.release() }
            }
            capture.release()
            result
        } catch (e: Exception) {
            capture.release()
            SnapshotResult.Failure(e.message ?: "save failed")
        }
    }

    private fun saveJpegToGallery(file: File, cameraName: String): SnapshotResult {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val safe = cameraName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val filename = "icsee_${safe}_$timestamp.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/iCSeeLocalControl")
        }
        val resolver = getApplication<Application>().contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SnapshotResult.Failure("MediaStore insert failed")
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: return SnapshotResult.Failure("write failed")
            SnapshotResult.Success(uri.toString())
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            SnapshotResult.Failure(e.message ?: "save failed")
        }
    }

    private fun findSurfaceView(root: android.view.View): android.view.SurfaceView? {
        if (root is android.view.SurfaceView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findSurfaceView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findTextureView(root: android.view.View): android.view.TextureView? {
        if (root is android.view.TextureView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextureView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    companion object {
        /**
         * Dance easter-egg media: the Funkytown video on the Internet Archive, a
         * plain DRM-free MP4 (H.264 + AAC). The on-screen ExoPlayer streams it
         * for the visual; [FileAudioSource] decodes its audio for the camera
         * speaker + PTZ beats. Not a YouTube embed (blocked, error 152) and not a
         * stream-rip -- a direct read of a publicly-hosted file.
         */
        const val DANCE_MEDIA_URL =
            "https://archive.org/download/Lipps_Inc_Funky_Town/Lipps_Inc_Funky_Town.mp4"
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Called by MainActivity exactly when a Live-family screen (LiveControl or
     * Diagnostics) becomes the one on screen -- never while merely backgrounded.
     * Fires only on a real transition into the family (see MainActivity's
     * `LaunchedEffect` keyed on the family's camera id), not on every recomposition,
     * so bouncing between LiveControl and Diagnostics for the same camera does not
     * re-acquire.
     */
    @UnstableApi
    fun enterFocus(cameraId: String) {
        inFocus = true
        load(cameraId)
    }

    /**
     * Called by MainActivity exactly when navigation leaves the Live family (any
     * other screen). Releases the shared session back to the registry -- which
     * keeps it alive for a short grace window in case we come right back, then tears
     * it down. Nothing is left running unattended.
     */
    @UnstableApi
    fun leaveFocus() {
        inFocus = false
        releaseSession()
    }

    fun setSpeedStep(step: Int) {
        _speedStep.value = step.coerceIn(0, 10)
    }

    @UnstableApi
    fun load(cameraId: String) {
        viewModelScope.launch {
            try {
                val found = store.cameras.first().firstOrNull { it.id == cameraId } ?: return@launch

                // Drop any session/overlays from a previously-shown camera before
                // acquiring the new one (e.g. the user opened a different camera).
                releaseSession()

                _camera.value = found
                _presetThumbPaths.value = presetThumbs.forCamera(found.id)
                val credentials = try {
                    store.credentialsFor(cameraId) ?: CameraCredentials("", "")
                } catch (e: Exception) {
                    // Credential decryption failure -- use empty credentials
                    // (user can re-test to verify actual credentials work)
                    CameraCredentials("", "")
                }

                // RTSP is independent of the DVRIP session (confirmed live: no DVRIP
                // login needed for RTSP at all), so start it immediately rather than
                // waiting on Authenticated. found.channel is 0-based (DVRIP
                // convention); this camera's RTSP URL convention is 1-based.
                try {
                    rtspPlayer.start(
                        host = found.host,
                        port = found.rtspPort,
                        username = credentials.username,
                        password = credentials.password,
                        channel = found.channel + 1,
                        mainStream = found.streamType == StreamType.MAIN,
                        preferFactoryRtspAccount = found.rtspFallbackEnabled,
                    )
                } catch (e: Exception) {
                    // RTSP startup failure is not fatal; DVRIP session can still work
                }

                acquireSession(found, credentials)
            } catch (e: Exception) {
                // Catastrophic failure -- transition to failed state
                _connectionState.value = ConnectionState.Failed("Failed to load camera: ${e.message}")
            }
        }
    }

    /**
     * Acquires the shared session for [found] from the registry (which connects it
     * if it isn't already up -- possibly reusing one device management is holding,
     * for zero extra logins) and starts observing its state to wire video/talk.
     */
    @UnstableApi
    private fun acquireSession(found: CameraDescriptor, credentials: CameraCredentials) {
        val manager = registry.acquire(found.host, found.dvripPort, credentials)
        sessionManager = manager
        held = found
        sessionStateJob?.cancel()
        sessionStateJob = viewModelScope.launch {
            manager.state.collect { state ->
                _connectionState.value = state
                if (state is ConnectionState.Authenticated) {
                    wireControllers(found, state)
                }
            }
        }
    }

    /**
     * Releases the shared session back to the registry and tears down this screen's
     * overlays (video/talk observers, RTSP). Idempotent -- safe to call from both
     * navigation (leaveFocus) and backgrounding (onStop) without double-releasing.
     */
    @UnstableApi
    private fun releaseSession() {
        val camera = held ?: return
        held = null
        sessionStateJob?.cancel()
        sessionStateJob = null
        stopTalk()
        stopDance()
        if (_recording.value) stopRecording()
        _cruiseActive.value = false
        videoStatsJob?.cancel()
        videoController?.stop()
        videoController = null
        ptzController?.onScreenDisposed()
        ptzController = null
        wiredSessionId = null
        rtspPlayer.stop()
        sessionManager = null
        registry.release(camera.host, camera.dvripPort)
    }

    private fun wireControllers(found: CameraDescriptor, state: ConnectionState.Authenticated) {
        if (wiredSessionId == state.sessionId) return
        val channel = sessionManager?.commandChannel ?: return

        // Tear down anything built for a previous session before rebuilding --
        // otherwise e.g. a still-"talking" TalkController from before the
        // reconnect would keep streaming audio tagged with a SessionID the
        // camera no longer recognizes.
        stopTalk()
        videoStatsJob?.cancel()
        videoController?.stop()

        // Secondary connections (video/talk) must continue the session's own
        // sequence counter, not restart at 0, for the camera to route media.
        val seq = sessionManager?.sessionSequence ?: return
        ptzController = PtzController(channel, state.sessionId, viewModelScope, found.channel)
        videoController = VideoStreamController(found.host, found.dvripPort, state.sessionId, found.channel, sessionSequence = seq).also { controller ->
            viewModelScope.launch { controller.start(viewModelScope) }
            videoStatsJob = viewModelScope.launch { controller.stats.collect { _videoStats.value = it } }
        }
        talkController = TalkController(found.host, found.dvripPort, state.sessionId, microphone, seq, channel)
        wiredSessionId = state.sessionId
        val transport = sessionManager?.controlTransport ?: return
        probeLighting(channel, transport, state.sessionId)
    }

    private fun probeLighting(channel: com.voidnullvalue.icseelocal.session.DvripCommandChannel, transport: com.voidnullvalue.icseelocal.dvrip.DvripTransport, sid: UInt) {
        viewModelScope.launch {
            val config = DvripConfigChannel(transport, channel, sid, getApplication())
            val param = (config.getConfig("Camera.Param") as? ConfigResult.Success)?.value
            val paramEx = (config.getConfig("Camera.ParamEx") as? ConfigResult.Success)?.value
            val caps = CameraLighting.probe(param, paramEx)
            _lightingCaps.value = caps
            _lightOn.value = caps.whiteLightMode?.equals("Open", ignoreCase = true) == true
            caps.dayNightMode?.let { _dayNightMode.value = it }
        }
    }

    fun reconnect() {
        val found = _camera.value ?: return
        viewModelScope.launch {
            val credentials = store.credentialsFor(found.id) ?: CameraCredentials("", "")
            // Explicit user action -- reconnects even from a Failed state (unlike the
            // automatic acquire path), still gated by the shared per-camera rate limiter.
            registry.reconnect(found.host, found.dvripPort, credentials)
        }
    }

    // --- PTZ ---
    fun onPtzDown(command: PtzCommand) {
        ptzController?.onPointerDown(command, _speedStep.value)
        if (konamiDetector.onInput(command)) {
            _danceModeTriggered.value = true
        }
    }
    fun onPtzUp() = ptzController?.onPointerUp()
    fun onPtzCancel() = ptzController?.onPointerCancel()
    fun onPtzDirectionChange(command: PtzCommand) = ptzController?.onDirectionChange(command, _speedStep.value)

    /**
     * Saves PTZ preset [preset] and captures the current live frame as its
     * thumbnail (shown on the 1–4 favourite buttons).
     */
    @UnstableApi
    fun setPreset(preset: Int) {
        ptzController?.setPreset(preset)
        val cameraId = _camera.value?.id ?: return
        val view = playerViewRef?.get() ?: run {
            _statusToast.value = "Preset $preset saved (no frame for thumb)"
            return
        }
        viewModelScope.launch {
            val path = capturePresetThumb(view, cameraId, preset)
            if (path != null) {
                presetThumbs.put(cameraId, preset, path)
                withContext(Dispatchers.IO) { cameraThumbs.copyFrom(cameraId, File(path)) }
                _presetThumbPaths.value = _presetThumbPaths.value + (preset to path)
                _presetThumbEpoch.value = System.currentTimeMillis()
                _statusToast.value = "Favourite saved"
            } else {
                _statusToast.value = "Favourite saved (couldn’t capture frame)"
            }
        }
    }

    @UnstableApi
    private suspend fun capturePresetThumb(view: androidx.media3.ui.PlayerView, cameraId: String, preset: Int): String? {
        val out = presetThumbs.thumbFile(cameraId, preset)
        return try {
            val texture = findTextureView(view)
            val bmp = texture?.bitmap
            if (bmp != null) {
                withContext(Dispatchers.IO) {
                    java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                }
                bmp.recycle()
                out.absolutePath
            } else {
                val surface = findSurfaceView(view) ?: return null
                copySurfaceToFile(surface, out)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun copySurfaceToFile(surfaceView: android.view.SurfaceView, out: File): String? {
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return null
        val bitmap = android.graphics.Bitmap.createBitmap(
            surfaceView.width, surfaceView.height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val thread = android.os.HandlerThread("preset-thumb").also { it.start() }
        val handler = android.os.Handler(thread.looper)
        try {
            android.view.PixelCopy.request(
                surfaceView, bitmap,
                { result -> deferred.complete(result == android.view.PixelCopy.SUCCESS) },
                handler,
            )
            if (!deferred.await()) return null
            withContext(Dispatchers.IO) {
                java.io.FileOutputStream(out).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            }
            return out.absolutePath
        } finally {
            bitmap.recycle()
            thread.quitSafely()
        }
    }

    fun gotoPreset(preset: Int) = ptzController?.gotoPreset(preset)
    fun clearPreset(preset: Int) = ptzController?.clearPreset(preset)
    fun startTour() = ptzController?.startTour()
    fun stopTour() = ptzController?.stopTour()

    // --- Dance mode (Konami code easter egg) ---
    fun dismissDanceTrigger() {
        _danceModeTriggered.value = false
    }

    /**
     * Starts relaying a local track (see [FileAudioSource] -- you supply the file
     * yourself, nothing is bundled or downloaded) to the camera speaker via the
     * talk channel, and starts the PTZ dance loop synced to its detected beats.
     *
     * An earlier version of this captured a WebView's own audio playback via
     * Android's MediaProjection-based `AudioPlaybackCaptureConfiguration`
     * ("casting"): it didn't crash, but produced no audio, consistent with that
     * API deliberately refusing to capture DRM-protected content (which embedded
     * YouTube audio typically is). This sidesteps the whole capture layer by
     * decoding a local file directly instead.
     */
    fun startDance() {
        val found = _camera.value ?: return
        val state = _connectionState.value as? ConnectionState.Authenticated ?: return
        val channel = sessionManager?.commandChannel ?: return
        val seq = sessionManager?.sessionSequence ?: return
        val ptz = ptzController ?: return

        stopDance()

        // Same DRM-free archive.org MP4 the on-screen video streams (see
        // DANCE_MEDIA_URL): FileAudioSource decodes its audio track for the
        // camera speaker, and its beats drive the PTZ choreography.
        val source = FileAudioSource(getApplication(), sourceUrl = DANCE_MEDIA_URL)
        val controller = TalkController(found.host, found.dvripPort, state.sessionId, source, seq, channel)
        danceAudioController = controller
        viewModelScope.launch {
            runCatching { controller.start(viewModelScope, onError = { stopDance() }) }
        }
        danceChoreography = DanceChoreography(ptz).also { it.start(viewModelScope, source.beats) }
    }

    fun stopDance() {
        danceChoreography?.stop()
        danceChoreography = null
        danceAudioController?.stop()
        danceAudioController = null
    }

    // --- Push to talk ---
    fun startTalk() {
        val controller = talkController
        if (controller == null) {
            // Talk needs the authenticated DVRIP session; RTSP video works without it,
            // so it's possible to see video but have no session for talk.
            _talkError.value = "Not connected to camera control session yet (state: ${_connectionState.value.label})"
            return
        }
        _talkError.value = null
        _talkFrames.value = 0
        viewModelScope.launch {
            try {
                controller.start(
                    viewModelScope,
                    onError = { e ->
                        _talking.value = false
                        _talkError.value = e.message ?: e.toString()
                    },
                    onFrameSent = { _talkFrames.value = it },
                )
                _talking.value = true
            } catch (e: Exception) {
                // Connection/claim-send failure -- TalkController already cleaned itself
                // up via stop() before rethrowing; surface it instead of crashing.
                _talking.value = false
                _talkError.value = e.message ?: e.toString()
            }
        }
    }

    fun stopTalk() {
        talkController?.stop()
        _talking.value = false
    }

    @UnstableApi
    override fun onStop(owner: LifecycleOwner) {
        // App backgrounded (lock, app switch). Even while inFocus this is the same
        // "nobody is looking" case as navigating away, so release the shared session
        // rather than leave its keepalive/reconnect loop running unattended. The
        // registry's grace window means a quick unlock (re-acquire before it expires)
        // reuses the live socket with zero new logins; a long absence tears it down.
        // inFocus is left as-is so onStart knows whether to bring it back.
        releaseSession()
    }

    @UnstableApi
    override fun onStart(owner: LifecycleOwner) {
        // Re-acquire only if a Live-family screen is still the one in focus -- NOT
        // merely because this (Activity-scoped) ViewModel is still alive. `held`
        // guards against re-acquiring a session we never released.
        if (!inFocus || held != null) return
        val found = _camera.value ?: return
        viewModelScope.launch {
            val credentials = try {
                store.credentialsFor(found.id) ?: CameraCredentials("", "")
            } catch (e: Exception) {
                CameraCredentials("", "")
            }
            try {
                rtspPlayer.start(
                    host = found.host,
                    port = found.rtspPort,
                    username = credentials.username,
                    password = credentials.password,
                    channel = found.channel + 1,
                    mainStream = found.streamType == StreamType.MAIN,
                    preferFactoryRtspAccount = found.rtspFallbackEnabled,
                )
            } catch (e: Exception) {
                // RTSP restart failure is not fatal
            }
            acquireSession(found, credentials)
        }
    }

    @UnstableApi
    override fun onCleared() {
        if (_recording.value) stopRecording()
        releaseSession()
        rtspPlayer.release()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
}
