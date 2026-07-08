package com.voidnullvalue.icseelocal.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.voidnullvalue.icseelocal.app.IcseeApplication
import com.voidnullvalue.icseelocal.audio.FileAudioSource
import com.voidnullvalue.icseelocal.audio.MicrophoneSource
import com.voidnullvalue.icseelocal.audio.TalkController
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.model.ConnectionState
import com.voidnullvalue.icseelocal.ptz.DanceChoreography
import com.voidnullvalue.icseelocal.ptz.KonamiCodeDetector
import com.voidnullvalue.icseelocal.ptz.PtzCommand
import com.voidnullvalue.icseelocal.ptz.PtzController
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.session.CameraSessionManager
import com.voidnullvalue.icseelocal.storage.CameraStore
import com.voidnullvalue.icseelocal.video.RtspPlayerState
import com.voidnullvalue.icseelocal.video.RtspVideoPlayer
import com.voidnullvalue.icseelocal.video.VideoStats
import com.voidnullvalue.icseelocal.video.VideoStreamController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LiveControlViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val store = CameraStore(application)
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
    val rtspPlayer = RtspVideoPlayer(application)

    @UnstableApi
    val rtspState: StateFlow<RtspPlayerState> = rtspPlayer.state

    private val _camera = MutableStateFlow<CameraDescriptor?>(null)
    val camera: StateFlow<CameraDescriptor?> = _camera.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _speedStep = MutableStateFlow(2)
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
                    rtspPlayer.start(found.host, found.rtspPort, credentials.username, credentials.password, found.channel + 1)
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
    fun setPreset(preset: Int) = ptzController?.setPreset(preset)
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

        val source = FileAudioSource(getApplication())
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
                rtspPlayer.start(found.host, found.rtspPort, credentials.username, credentials.password, found.channel + 1)
            } catch (e: Exception) {
                // RTSP restart failure is not fatal
            }
            acquireSession(found, credentials)
        }
    }

    @UnstableApi
    override fun onCleared() {
        releaseSession()
        rtspPlayer.release()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
}
