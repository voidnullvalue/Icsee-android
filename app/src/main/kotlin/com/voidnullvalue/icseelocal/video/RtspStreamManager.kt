package com.voidnullvalue.icseelocal.video

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.random.Random

/**
 * Playback lifecycle for live RTSP, separate from DVRIP [com.voidnullvalue.icseelocal.model.ConnectionState].
 */
sealed interface RtspPlayerState {
    data object Idle : RtspPlayerState
    data object Connecting : RtspPlayerState
    data object Buffering : RtspPlayerState
    /** Actively receiving and rendering live media. */
    data object Live : RtspPlayerState
    data object Reconnecting : RtspPlayerState
    data class AuthenticationFailed(val message: String) : RtspPlayerState
    data class Offline(val message: String) : RtspPlayerState
    data class Error(val message: String) : RtspPlayerState

    /** Backward-compatible alias used by existing UI checks. */
    companion object {
        @Deprecated("Use Live", ReplaceWith("Live"))
        val Playing: RtspPlayerState get() = Live
    }
}

/** True when the stream is considered on-air for keep-awake / PiP. */
val RtspPlayerState.isOnAir: Boolean
    get() = this is RtspPlayerState.Live || this is RtspPlayerState.Buffering || this is RtspPlayerState.Reconnecting

/**
 * Media3 RTSP lifecycle manager: low-latency LoadControl, TCP-first cascade,
 * and RTSP-only exponential-backoff reconnect (never re-logins DVRIP).
 *
 * Owns one [ExoPlayer]; call [release] when permanently done.
 */
@UnstableApi
class RtspStreamManager(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<RtspPlayerState>(RtspPlayerState.Idle)
    val state: StateFlow<RtspPlayerState> = _state.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _bitrateBps = MutableStateFlow(0L)
    val bitrateBps: StateFlow<Long> = _bitrateBps.asStateFlow()

    private val _mainStream = MutableStateFlow(true)
    val mainStream: StateFlow<Boolean> = _mainStream.asStateFlow()

    private val renderersFactory = DefaultRenderersFactory(appContext)
        .setEnableDecoderFallback(true)

    /** Low target buffers for live IP cams — reduces glass-to-glass latency.
     *  Constraints: minBufferMs >= bufferForPlaybackMs and
     *  minBufferMs >= bufferForPlaybackAfterRebufferMs (DefaultLoadControl asserts). */
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 750,
            /* maxBufferMs = */ 2_500,
            /* bufferForPlaybackMs = */ 250,
            /* bufferForPlaybackAfterRebufferMs = */ 500,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    reconnectAttempt = 0
                    if (exoPlayer.playWhenReady) {
                        _state.value = RtspPlayerState.Live
                    }
                }
                Player.STATE_BUFFERING -> {
                    val current = _state.value
                    if (current is RtspPlayerState.Live || current is RtspPlayerState.Buffering) {
                        _state.value = RtspPlayerState.Buffering
                    } else if (current !is RtspPlayerState.Reconnecting) {
                        _state.value = RtspPlayerState.Connecting
                    }
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> {
                    // Handled by stop() / error path
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && exoPlayer.playbackState == Player.STATE_READY) {
                reconnectAttempt = 0
                _state.value = RtspPlayerState.Live
            }
        }
    }

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .setLoadControl(loadControl)
        .build()
        .apply {
            volume = 1f
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            addListener(playerListener)
            addAnalyticsListener(object : AnalyticsListener {
                override fun onBandwidthEstimate(
                    eventTime: AnalyticsListener.EventTime,
                    totalLoadTimeMs: Int,
                    totalBytesLoaded: Long,
                    bitrateEstimate: Long,
                ) {
                    if (bitrateEstimate > 0) _bitrateBps.value = bitrateEstimate
                }
            })
        }

    private data class Attempt(
        val url: String,
        val forceTcp: Boolean,
        val mainStream: Boolean,
    )

    private data class SessionParams(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val channel: Int,
        val mainStream: Boolean,
        val preferFactoryRtspAccount: Boolean,
    )

    private var attempts: List<Attempt> = emptyList()
    private var attemptIndex = 0
    private var session: SessionParams? = null
    private var reconnectAttempt = 0
    private var released = false
    private var reconnectRunnable: Runnable? = null

    fun setMuted(mute: Boolean) {
        _muted.value = mute
        exoPlayer.volume = if (mute) 0f else 1f
    }

    fun setVolume(level: Float) {
        val v = level.coerceIn(0f, 1f)
        if (v > 0.01f) _muted.value = false
        exoPlayer.volume = if (_muted.value) 0f else v
    }

    fun toggleMute() = setMuted(!_muted.value)

    fun setPlayWhenReady(play: Boolean) {
        exoPlayer.playWhenReady = play
    }

    /**
     * Switch HD (main) / SD (sub) and restart the cascade with the same session.
     */
    fun setMainStream(main: Boolean) {
        val s = session ?: return
        if (_mainStream.value == main) return
        _mainStream.value = main
        cancelReconnect()
        start(
            host = s.host,
            port = s.port,
            username = s.username,
            password = s.password,
            channel = s.channel,
            mainStream = main,
            preferFactoryRtspAccount = s.preferFactoryRtspAccount,
        )
    }

    fun toggleStreamQuality() = setMainStream(!_mainStream.value)

    fun start(
        host: String,
        port: Int,
        username: String,
        password: String,
        channel: Int,
        mainStream: Boolean = true,
        preferFactoryRtspAccount: Boolean = false,
    ) {
        if (released) return
        cancelReconnect()
        reconnectAttempt = 0
        session = SessionParams(host, port, username, password, channel, mainStream, preferFactoryRtspAccount)
        _mainStream.value = mainStream
        val creds = credentialOrder(username, password, preferFactoryRtspAccount)
        val streamOrder = if (mainStream) listOf(true, false) else listOf(false)
        attempts = buildList {
            for (forceTcp in listOf(true, false)) {
                for (useMain in streamOrder) {
                    for ((user, pass) in creds) {
                        add(
                            Attempt(
                                url = RtspUrlBuilder.build(host, port, user, pass, channel, useMain),
                                forceTcp = forceTcp,
                                mainStream = useMain,
                            ),
                        )
                    }
                }
            }
        }.distinctBy { it.url to it.forceTcp }
        attemptIndex = 0
        playAttempt(attempts.firstOrNull() ?: return)
    }

    /** Manual reconnect from UI — resets backoff and restarts cascade. */
    fun reconnect() {
        val s = session ?: return
        cancelReconnect()
        reconnectAttempt = 0
        start(
            host = s.host,
            port = s.port,
            username = s.username,
            password = s.password,
            channel = s.channel,
            mainStream = s.mainStream,
            preferFactoryRtspAccount = s.preferFactoryRtspAccount,
        )
    }

    private fun credentialOrder(
        username: String,
        password: String,
        preferFactory: Boolean,
    ): List<Pair<String, String>> {
        val user = username to password
        val factory = RtspUrlBuilder.FALLBACK_USERNAME to RtspUrlBuilder.FALLBACK_PASSWORD
        return when {
            preferFactory || username.isBlank() -> listOf(factory, user).distinct()
            else -> listOf(user, factory).distinct()
        }
    }

    private fun playAttempt(attempt: Attempt) {
        if (released) return
        if (_state.value !is RtspPlayerState.Reconnecting) {
            _state.value = RtspPlayerState.Connecting
        }
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(attempt.forceTcp)
            .setTimeoutMs(TCP_FALLBACK_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(Uri.parse(attempt.url)))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        if (!_muted.value) exoPlayer.volume = 1f
    }

    private fun handlePlaybackError(error: PlaybackException) {
        if (released) return

        if (isDecoderCapabilityError(error)) {
            val subIdx = attempts.indexOfFirst { !it.mainStream }
            if (subIdx >= 0 && attemptIndex < subIdx) {
                attemptIndex = subIdx
                playAttempt(attempts[subIdx])
                return
            }
        }

        val next = attempts.getOrNull(attemptIndex + 1)
        if (next != null) {
            attemptIndex++
            playAttempt(next)
            return
        }

        if (isAuthError(error)) {
            _state.value = RtspPlayerState.AuthenticationFailed(friendlyError(error))
            return
        }

        scheduleReconnect(friendlyError(error))
    }

    private fun scheduleReconnect(lastError: String) {
        if (session == null || released) {
            _state.value = RtspPlayerState.Offline(lastError)
            return
        }
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _state.value = RtspPlayerState.Offline(lastError)
            return
        }
        _state.value = RtspPlayerState.Reconnecting
        val delayMs = reconnectDelayMs(reconnectAttempt)
        val nextAttempt = reconnectAttempt + 1
        cancelReconnect()
        val runnable = Runnable {
            reconnectRunnable = null
            val s = session ?: return@Runnable
            if (released) return@Runnable
            reconnectAttempt = nextAttempt
            _state.value = RtspPlayerState.Reconnecting
            val creds = credentialOrder(s.username, s.password, s.preferFactoryRtspAccount)
            val streamOrder = if (s.mainStream) listOf(true, false) else listOf(false)
            attempts = buildList {
                for (forceTcp in listOf(true, false)) {
                    for (useMain in streamOrder) {
                        for ((user, pass) in creds) {
                            add(
                                Attempt(
                                    url = RtspUrlBuilder.build(s.host, s.port, user, pass, s.channel, useMain),
                                    forceTcp = forceTcp,
                                    mainStream = useMain,
                                ),
                            )
                        }
                    }
                }
            }.distinctBy { it.url to it.forceTcp }
            attemptIndex = 0
            playAttempt(attempts.firstOrNull() ?: return@Runnable)
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val exp = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L shl attempt.coerceAtMost(6)))
        val jitter = Random.nextLong(0, JITTER_MS)
        return exp + jitter
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    fun stop() {
        cancelReconnect()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        attempts = emptyList()
        attemptIndex = 0
        reconnectAttempt = 0
        _state.value = RtspPlayerState.Idle
    }

    fun release() {
        released = true
        cancelReconnect()
        session = null
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        _state.value = RtspPlayerState.Idle
    }

    private fun isAuthError(error: PlaybackException): Boolean {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 6) {
            val m = t.message.orEmpty()
            if (m.contains("401", ignoreCase = true) ||
                m.contains("403", ignoreCase = true) ||
                m.contains("Unauthorized", ignoreCase = true) ||
                m.contains("authentication", ignoreCase = true)
            ) {
                return true
            }
            t = t.cause
            depth++
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    }

    private fun isDecoderCapabilityError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        ) {
            return true
        }
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 6) {
            val m = t.message.orEmpty()
            if (m.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) ||
                m.contains("Decoder init failed", ignoreCase = true) ||
                m.contains("EXCEEDS_CAPABILITIES", ignoreCase = true)
            ) {
                return true
            }
            t = t.cause
            depth++
        }
        return false
    }

    private fun friendlyError(error: PlaybackException): String {
        val parts = ArrayList<String>()
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 4) {
            val m = t.message?.trim().orEmpty()
            if (m.isNotEmpty() && parts.none { it.equals(m, ignoreCase = true) }) {
                parts += m
            }
            t = t.cause
            depth++
        }
        val detail = RtspUrlRedactor.redact(parts.joinToString(" — ").ifBlank { "RTSP playback error" })
        return when {
            isDecoderCapabilityError(error) ->
                "$detail — main stream resolution exceeds this phone's decoder; " +
                    "set Stream to Sub in camera settings if this persists"
            detail.contains("Source error", ignoreCase = true) && parts.size == 1 ->
                "$detail (auth, SDP/HEVC params, or transport — try RTSP fallback / paste URL into VLC)"
            else -> detail
        }
    }

    companion object {
        private const val TCP_FALLBACK_TIMEOUT_MS = 8_000L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val JITTER_MS = 750L
        private const val MAX_RECONNECT_ATTEMPTS = 12
    }
}

/**
 * Backward-compatible name used throughout the live ViewModel.
 * Prefer [RtspStreamManager] for new call sites.
 */
@Deprecated("Use RtspStreamManager", ReplaceWith("RtspStreamManager"))
typealias RtspVideoPlayer = RtspStreamManager
