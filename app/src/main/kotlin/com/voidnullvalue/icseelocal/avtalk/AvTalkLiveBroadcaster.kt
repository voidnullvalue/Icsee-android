package com.voidnullvalue.icseelocal.avtalk

import android.content.Context
import android.view.TextureView
import com.voidnullvalue.icseelocal.audio.MicrophoneSource
import com.voidnullvalue.icseelocal.model.CameraDescriptor
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.storage.CameraStore
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI-facing state for the live phone -> camera-screen AVTalk broadcaster. */
data class AvTalkLiveState(
    val lens: AvTalkLens = AvTalkLens.FRONT,
    val micEnabled: Boolean = true,
    val previewStarting: Boolean = false,
    val previewReady: Boolean = false,
    val starting: Boolean = false,
    val broadcasting: Boolean = false,
    val stopping: Boolean = false,
    val videoFramesSent: Long = 0,
    val audioFramesSent: Long = 0,
    val error: String? = null,
)

/**
 * Owns the ephemeral phone-camera/mic resources for one AVTalk modal.
 * Nothing is persisted and closing the modal tears the entire pipeline down.
 */
class AvTalkLiveBroadcaster(
    context: Context,
    private val camera: CameraDescriptor,
) {
    private data class QueuedVideoFrame(
        val generation: Long,
        val frame: EncodedHevcFrame,
    )

    private val appContext = context.applicationContext
    private val store = CameraStore(appContext)
    private val microphone = MicrophoneSource(appContext)
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + Dispatchers.Main.immediate)
    private val lifecycleMutex = Mutex()
    private val closed = AtomicBoolean(false)

    private val _state = MutableStateFlow(AvTalkLiveState())
    val state: StateFlow<AvTalkLiveState> = _state.asStateFlow()

    private var previewView: TextureView? = null
    private var source: PhoneCameraHevcSource? = null
    private var client: AvTalkClient? = null
    private var videoChannel: Channel<QueuedVideoFrame>? = null
    private var videoJob: Job? = null
    private var micJob: Job? = null
    private var videoGeneration: Long = 0

    fun attachPreview(textureView: TextureView) {
        if (closed.get()) return
        previewView = textureView
        scope.launch {
            lifecycleMutex.withLock {
                restartPreviewLocked()
            }
        }
    }

    fun detachPreview(textureView: TextureView? = null) {
        if (textureView != null && previewView !== textureView) return
        previewView = null
        scope.launch {
            lifecycleMutex.withLock {
                source?.stop()
                source = null
                updateState { it.copy(previewStarting = false, previewReady = false) }
                if (client != null) {
                    stopBroadcastLocked("Phone camera preview was closed")
                }
            }
        }
    }

    fun switchLens() {
        if (closed.get()) return
        val next = if (_state.value.lens == AvTalkLens.FRONT) AvTalkLens.BACK else AvTalkLens.FRONT
        updateState { it.copy(lens = next, error = null) }
        scope.launch {
            lifecycleMutex.withLock {
                restartPreviewLocked()
            }
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        if (closed.get()) return
        updateState { it.copy(micEnabled = enabled, error = null) }
        scope.launch {
            lifecycleMutex.withLock {
                if (client != null) {
                    if (enabled) startMicLocked() else stopMicLocked()
                }
            }
        }
    }

    fun startBroadcast() {
        if (closed.get()) return
        scope.launch {
            lifecycleMutex.withLock {
                if (client != null || _state.value.starting) return@withLock
                if (source?.isRunning != true) {
                    updateState { it.copy(error = "Phone camera preview is not ready") }
                    return@withLock
                }
                updateState {
                    it.copy(
                        starting = true,
                        stopping = false,
                        error = null,
                        videoFramesSent = 0,
                        audioFramesSent = 0,
                    )
                }
                val credentials = try {
                    store.credentialsFor(camera.id) ?: CameraCredentials("", "")
                } catch (t: Throwable) {
                    updateState { it.copy(starting = false, error = "Could not read camera credentials: ${t.message}") }
                    return@withLock
                }
                val nextClient = AvTalkClient(
                    host = camera.host,
                    port = camera.dvripPort,
                    credentials = credentials,
                    profile = AvTalkProfile(channel = camera.channel),
                )
                try {
                    nextClient.start()
                    client = nextClient
                    startVideoSenderLocked(nextClient)
                    if (_state.value.micEnabled) startMicLocked()
                    source?.requestKeyFrame()
                    updateState { it.copy(starting = false, broadcasting = true, error = null) }
                } catch (t: Throwable) {
                    runCatching { nextClient.stop() }
                    client = null
                    stopVideoSenderLocked()
                    stopMicLocked()
                    updateState {
                        it.copy(
                            starting = false,
                            broadcasting = false,
                            error = "AVTalk start failed: ${t.message ?: t::class.java.simpleName}",
                        )
                    }
                }
            }
        }
    }

    fun stopBroadcast() {
        if (closed.get()) return
        scope.launch {
            lifecycleMutex.withLock {
                stopBroadcastLocked(null)
            }
        }
    }

    fun clearError() = updateState { it.copy(error = null) }

    /** Idempotent. Best-effort Stop is sent before this controller cancels itself. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            lifecycleMutex.withLock {
                stopBroadcastLocked(null)
                source?.stop()
                source = null
                previewView = null
                updateState { it.copy(previewStarting = false, previewReady = false) }
            }
            rootJob.cancel()
        }
    }

    private suspend fun restartPreviewLocked() {
        val view = previewView ?: return
        source?.stop()
        source = null
        videoGeneration++
        updateState { it.copy(previewStarting = true, previewReady = false, error = null) }
        val generation = videoGeneration
        val nextSource = PhoneCameraHevcSource(
            appContext,
            AvTalkProfile(channel = camera.channel),
            onFrame = { encoded -> offerVideoFrame(generation, encoded) },
            onError = { failure ->
                scope.launch { handleFatalSourceError(failure) }
            },
        )
        try {
            nextSource.start(view, _state.value.lens)
            source = nextSource
            updateState { it.copy(previewStarting = false, previewReady = true, error = null) }
            if (client != null) nextSource.requestKeyFrame()
        } catch (t: Throwable) {
            nextSource.stop()
            updateState {
                it.copy(
                    previewStarting = false,
                    previewReady = false,
                    error = "Phone camera failed: ${t.message ?: t::class.java.simpleName}",
                )
            }
            if (client != null) stopBroadcastLocked("Phone camera failed")
        }
    }

    private fun offerVideoFrame(generation: Long, encoded: EncodedHevcFrame) {
        val channel = videoChannel ?: return
        val result = channel.trySend(QueuedVideoFrame(generation, encoded))
        if (result.isFailure && client != null) {
            scope.launch {
                lifecycleMutex.withLock {
                    if (client != null) stopBroadcastLocked("Video uplink could not keep up")
                }
            }
        }
    }

    private fun startVideoSenderLocked(activeClient: AvTalkClient) {
        stopVideoSenderLocked()
        val channel = Channel<QueuedVideoFrame>(capacity = 120)
        videoChannel = channel
        videoJob = scope.launch(Dispatchers.IO) {
            var generation = Long.MIN_VALUE
            var haveKeyFrame = false
            try {
                for (queued in channel) {
                    if (queued.generation != generation) {
                        generation = queued.generation
                        haveKeyFrame = false
                    }
                    if (!haveKeyFrame && !queued.frame.keyFrame) continue
                    if (queued.frame.keyFrame) haveKeyFrame = true
                    if (queued.frame.keyFrame) {
                        activeClient.sendH265KeyFrame(queued.frame.annexB, LocalDateTime.now())
                    } else {
                        activeClient.sendH265InterFrame(queued.frame.annexB)
                    }
                    incrementVideoFrames()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                scope.launch { handleNetworkFailure("Video send failed", t) }
            }
        }
    }

    private fun stopVideoSenderLocked() {
        videoChannel?.close()
        videoChannel = null
        videoJob?.cancel()
        videoJob = null
    }

    private fun startMicLocked() {
        if (micJob != null || !_state.value.micEnabled) return
        val activeClient = client ?: return
        micJob = scope.launch(Dispatchers.IO) {
            try {
                microphone.captureAlawChunks().collect { samples ->
                    activeClient.sendAlawAudio(samples)
                    incrementAudioFrames()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                scope.launch { handleNetworkFailure("Microphone/audio send failed", t) }
            }
        }
    }

    private fun stopMicLocked() {
        micJob?.cancel()
        micJob = null
    }

    private suspend fun stopBroadcastLocked(reason: String?) {
        val activeClient = client
        if (activeClient == null && !_state.value.starting) {
            if (reason != null) updateState { it.copy(error = reason) }
            return
        }
        updateState { it.copy(starting = false, stopping = true, broadcasting = false) }
        client = null
        stopMicLocked()
        stopVideoSenderLocked()
        val failure = if (activeClient != null) runCatching { activeClient.stop() }.exceptionOrNull() else null
        updateState {
            it.copy(
                stopping = false,
                broadcasting = false,
                error = reason ?: failure?.let { error -> "AVTalk stop failed: ${error.message}" },
            )
        }
    }

    private suspend fun handleFatalSourceError(failure: Throwable) {
        lifecycleMutex.withLock {
            source?.stop()
            source = null
            updateState {
                it.copy(
                    previewStarting = false,
                    previewReady = false,
                    error = "Phone camera failed: ${failure.message ?: failure::class.java.simpleName}",
                )
            }
            if (client != null) stopBroadcastLocked("Phone camera failed")
        }
    }

    private suspend fun handleNetworkFailure(prefix: String, failure: Throwable) {
        lifecycleMutex.withLock {
            if (client == null) return@withLock
            stopBroadcastLocked("$prefix: ${failure.message ?: failure::class.java.simpleName}")
        }
    }

    private fun incrementVideoFrames() {
        updateState { it.copy(videoFramesSent = it.videoFramesSent + 1) }
    }

    private fun incrementAudioFrames() {
        updateState { it.copy(audioFramesSent = it.audioFramesSent + 1) }
    }

    private inline fun updateState(crossinline transform: (AvTalkLiveState) -> AvTalkLiveState) {
        _state.update { current -> transform(current) }
    }
}
