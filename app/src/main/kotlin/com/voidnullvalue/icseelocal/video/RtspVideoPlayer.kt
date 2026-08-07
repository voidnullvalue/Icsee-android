package com.voidnullvalue.icseelocal.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
 * 1. Configured credentials + TCP interleaved (live-confirmed transport)
 * 2. Factory-default RTSP account (`admin` / blank) + TCP — RTSP often has a
 *    separate credential store from DVRIP
 * 3. Same URLs again over UDP (some firmwares reject interleaved TCP)
 *
 * Owns one [ExoPlayer] instance; call [release] when the screen goes away.
 */
@UnstableApi
class RtspVideoPlayer(context: Context) {
    private val _state = MutableStateFlow<RtspPlayerState>(RtspPlayerState.Idle)
    val state: StateFlow<RtspPlayerState> = _state.asStateFlow()

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && isPlaying) {
                    _state.value = RtspPlayerState.Playing
                } else if (playbackState == Player.STATE_BUFFERING) {
                    _state.value = RtspPlayerState.Connecting
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val next = attempts.getOrNull(attemptIndex + 1)
                if (next != null) {
                    attemptIndex++
                    playAttempt(next)
                } else {
                    _state.value = RtspPlayerState.Error(friendlyError(error))
                }
            }
        })
    }

    private data class Attempt(val url: String, val forceTcp: Boolean, val label: String)

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
        val userUrl = RtspUrlBuilder.build(host, port, username, password, channel, mainStream)
        val factoryUrl = RtspUrlBuilder.build(
            host, port,
            RtspUrlBuilder.FALLBACK_USERNAME, RtspUrlBuilder.FALLBACK_PASSWORD,
            channel, mainStream,
        )
        val orderedUrls = if (preferFactoryRtspAccount || username.isBlank()) {
            listOf(factoryUrl, userUrl).distinct()
        } else {
            listOf(userUrl, factoryUrl).distinct()
        }
        attempts = buildList {
            for (url in orderedUrls) {
                add(Attempt(url, forceTcp = true, label = "TCP"))
            }
            for (url in orderedUrls) {
                add(Attempt(url, forceTcp = false, label = "UDP"))
            }
        }
        attemptIndex = 0
        playAttempt(attempts.first())
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
        // Media3 often stops at the opaque "Source error"; point at the usual causes.
        return if (detail.contains("Source error", ignoreCase = true) && parts.size == 1) {
            "$detail (auth, SDP/HEVC params, or transport — try RTSP fallback / paste URL into VLC)"
        } else {
            detail
        }
    }
}
