package com.voidnullvalue.icseelocal.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.voidnullvalue.icseelocal.audio.MicrophoneSource
import com.voidnullvalue.icseelocal.audio.TalkController
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.model.ConnectionState
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

    private var sessionManager: CameraSessionManager? = null
    private var ptzController: PtzController? = null
    private var videoController: VideoStreamController? = null
    private var talkController: TalkController? = null
    private var videoStatsJob: Job? = null

    // Which SessionID wireControllers() last built controllers for -- a reconnect
    // (manual or automatic, see CameraSessionManager) produces a brand new
    // ConnectionState.Authenticated with a brand new SessionID, and Ptz/Video/Talk
    // controllers all capture their SessionID immutably at construction time, so
    // they must be torn down and rebuilt rather than left pointing at a now-dead
    // session.
    private var wiredSessionId: UInt? = null

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

    fun setSpeedStep(step: Int) {
        _speedStep.value = step.coerceIn(0, 10)
    }

    @UnstableApi
    fun load(cameraId: String) {
        viewModelScope.launch {
            val found = store.cameras.first().firstOrNull { it.id == cameraId } ?: return@launch
            _camera.value = found
            val credentials = store.credentialsFor(cameraId) ?: CameraCredentials("", "")
            // RTSP is independent of the DVRIP session (confirmed live: no DVRIP
            // login needed for RTSP at all), so start it immediately rather than
            // waiting on Authenticated. found.channel is 0-based (DVRIP
            // convention); this camera's RTSP URL convention is 1-based.
            rtspPlayer.start(found.host, found.rtspPort, credentials.username, credentials.password, found.channel + 1)
            val manager = CameraSessionManager(found.host, found.dvripPort)
            sessionManager = manager
            viewModelScope.launch {
                manager.state.collect { state ->
                    _connectionState.value = state
                    if (state is ConnectionState.Authenticated) {
                        wireControllers(found, state)
                    }
                }
            }
            manager.connect(credentials)
        }
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
            sessionManager?.connect(credentials)
        }
    }

    // --- PTZ ---
    fun onPtzDown(command: PtzCommand) = ptzController?.onPointerDown(command, _speedStep.value)
    fun onPtzUp() = ptzController?.onPointerUp()
    fun onPtzCancel() = ptzController?.onPointerCancel()
    fun onPtzDirectionChange(command: PtzCommand) = ptzController?.onDirectionChange(command, _speedStep.value)
    fun setPreset(preset: Int) = ptzController?.setPreset(preset)
    fun gotoPreset(preset: Int) = ptzController?.gotoPreset(preset)
    fun clearPreset(preset: Int) = ptzController?.clearPreset(preset)
    fun startTour() = ptzController?.startTour()
    fun stopTour() = ptzController?.stopTour()

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

    override fun onStop(owner: LifecycleOwner) {
        // App lost foreground: stop anything physically/audibly active on the camera.
        ptzController?.onForegroundLost()
        stopTalk()
    }

    @UnstableApi
    override fun onCleared() {
        ptzController?.onScreenDisposed()
        stopTalk()
        videoController?.stop()
        rtspPlayer.release()
        sessionManager?.shutdown()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
}
