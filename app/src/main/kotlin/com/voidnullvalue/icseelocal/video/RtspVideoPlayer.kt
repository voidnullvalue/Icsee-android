package com.voidnullvalue.icseelocal.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RtspPlayerState {
    data object Idle : RtspPlayerState
    data object Connecting : RtspPlayerState
    data object Playing : RtspPlayerState
    data class Error(val message: String) : RtspPlayerState
}

/**
 * Thin wrapper around ExoPlayer's RTSP extension for this camera's
 * confirmed-live RTSP stream (see [RtspUrlBuilder] and PROTOCOL_NOTES.md
 * "RTSP video -- LIVE CONFIRMED").
 *
 * Attempts, in order:
 * 1. Preferred stream (main/sub) + configured credentials + TCP
 * 2. Same over the factory RTSP account (`admin` / blank)
 * 3. On decoder capability failures (common for 2304×1296 main H.264 on
 *    phone SoCs), the lower-res **sub** stream — then UDP variants
 *
 * Owns one [ExoPlayer] instance; call [release] when the screen goes away.
 */
@UnstableApi
class RtspVideoPlayer(context: Context) {
    private val _state = MutableStateFlow<RtspPlayerState>(RtspPlayerState.Idle)
    val state: StateFlow<RtspPlayerState> = _state.asStateFlow()

    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** Estimated stream bitrate in bits/sec (0 when unknown). */
    private val _bitrateBps = MutableStateFlow(0L)
    val bitrateBps: StateFlow<Long> = _bitrateBps.asStateFlow()

    private val renderersFactory = DefaultRenderersFactory(context)
        // Soft-decode when the primary HW codec rejects the format (e.g.
        // OMX.qcom… avc with NO_EXCEEDS_CAPABILITIES on 2304×1296).
        .setEnableDecoderFallback(true)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, renderersFactory).build().apply {
        volume = 0f // muted by default — user unmutes from live chrome
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && isPlaying) {
                    _state.value = RtspPlayerState.Playing
                } else if (playbackState == Player.STATE_BUFFERING) {
                    _state.value = RtspPlayerState.Connecting
                }
            }

            override fun onPlayerError(error: PlaybackException) {
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
                } else {
                    _state.value = RtspPlayerState.Error(friendlyError(error))
                }
            }
        })
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

    fun setMuted(mute: Boolean) {
        _muted.value = mute
        exoPlayer.volume = if (mute) 0f else 1f
    }

    fun toggleMute() = setMuted(!_muted.value)

    private data class Attempt(
        val url: String,
        val forceTcp: Boolean,
        val mainStream: Boolean,
    )

    private var attempts: List<Attempt> = emptyList()
    private var attemptIndex = 0

    /**
     * @param preferFactoryRtspAccount when true (settings "RTSP fallback"), try the
     *   live-confirmed `admin`/blank RTSP account before the DVRIP credentials —
     *   those often do not work for RTSP on this camera family.
     */
    fun start(
        host: String,
        port: Int,
        username: String,
        password: String,
        channel: Int,
        mainStream: Boolean = true,
        preferFactoryRtspAccount: Boolean = false,
    ) {
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
        playAttempt(attempts.first())
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
        _state.value = RtspPlayerState.Connecting
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(attempt.forceTcp)
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(Uri.parse(attempt.url)))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /**
     * Stops playback and clears the current stream without releasing the underlying
     * [ExoPlayer], so the same instance can be [start]ed again (e.g. when the live
     * screen is re-entered or the app returns to the foreground). Use [release] only
     * when the player is being thrown away for good.
     */
    fun stop() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        attempts = emptyList()
        attemptIndex = 0
        _state.value = RtspPlayerState.Idle
    }

    fun release() {
        exoPlayer.release()
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
        val detail = parts.joinToString(" — ").ifBlank { "RTSP playback error" }
        return when {
            isDecoderCapabilityError(error) ->
                "$detail — main stream resolution exceeds this phone's decoder; " +
                    "set Stream to Sub in camera settings if this persists"
            detail.contains("Source error", ignoreCase = true) && parts.size == 1 ->
                "$detail (auth, SDP/HEVC params, or transport — try RTSP fallback / paste URL into VLC)"
            else -> detail
        }
    }
}
