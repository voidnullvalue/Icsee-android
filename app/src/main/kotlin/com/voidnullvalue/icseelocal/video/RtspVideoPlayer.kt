package com.voidnullvalue.icseelocal.video

import android.content.Context
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
 * "RTSP video -- LIVE CONFIRMED"). Tries the user's configured
 * credentials first; if the camera rejects those specifically for RTSP
 * (a real, observed possibility -- RTSP has its own account store,
 * separate from the DVRIP login account on this camera), falls back once
 * to the live-confirmed factory-default account.
 *
 * Owns one [ExoPlayer] instance; call [release] when the screen goes
 * away. Not unit-testable in a plain JVM test (requires the Android media
 * framework); the pure URL-construction and fallback-selection logic this
 * depends on is factored out into [RtspUrlBuilder] and is unit tested.
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
                if (!usedFallback) {
                    usedFallback = true
                    playUrl(fallbackUrl ?: return)
                } else {
                    _state.value = RtspPlayerState.Error(error.message ?: "RTSP playback error")
                }
            }
        })
    }

    private var fallbackUrl: String? = null
    private var usedFallback = false

    fun start(host: String, port: Int, username: String, password: String, channel: Int) {
        usedFallback = false
        val primaryUrl = RtspUrlBuilder.build(host, port, username, password, channel)
        fallbackUrl = RtspUrlBuilder.build(host, port, RtspUrlBuilder.FALLBACK_USERNAME, RtspUrlBuilder.FALLBACK_PASSWORD, channel)
        playUrl(primaryUrl)
    }

    private fun playUrl(url: String) {
        _state.value = RtspPlayerState.Connecting
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // matches the transport confirmed working live (TCP-interleaved)
            .createMediaSource(MediaItem.fromUri(url))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun release() {
        exoPlayer.release()
    }
}
