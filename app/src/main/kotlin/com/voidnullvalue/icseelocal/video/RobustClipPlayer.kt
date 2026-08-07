package com.voidnullvalue.icseelocal.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExoPlayer for remuxed SD-card clips.
 *
 * Speeds are signed: negative values scrub reverse (ExoPlayer has no native
 * reverse rate). Default is **1×** forward. Volume defaults to full (1f).
 */
@UnstableApi
object RobustClipPlayer {
    /**
     * −4× … −0.5× (reverse scrub) then 1× … 4× forward.
     * Index of 1× is [DEFAULT_SPEED_INDEX].
     */
    val SPEED_STEPS = floatArrayOf(-4f, -3f, -2f, -1f, -0.5f, 1f, 1.5f, 2f, 3f, 4f)
    const val DEFAULT_SPEED_INDEX = 5 // 1×

    fun create(
        context: Context,
        pendingStart: AtomicBoolean = AtomicBoolean(false),
        onError: (String) -> Unit = {},
    ): ExoPlayer {
        val appContext = context.applicationContext
        val renderers = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(appContext, renderers)
            .build()
            .apply {
                playWhenReady = false
                volume = 1f
                setPlaybackSpeed(1f)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        pendingStart.set(false)
                        onError(error.message ?: "Player error ${error.errorCode}")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && pendingStart.compareAndSet(true, false)) {
                            playWhenReady = true
                        }
                    }
                })
            }
    }

    fun play(player: ExoPlayer, uri: String, pendingStart: AtomicBoolean) {
        pendingStart.set(true)
        player.playWhenReady = false
        player.setPlaybackSpeed(1f)
        player.volume = player.volume.coerceIn(0f, 1f).let { if (it <= 0f) 1f else it }
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)), /* resetPosition = */ true)
        player.prepare()
    }

    /** Applies forward ExoPlayer rate. Reverse is handled by the UI scrub loop. */
    fun applyForwardSpeed(player: ExoPlayer, speed: Float) {
        require(speed > 0f)
        player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
        if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
            player.playWhenReady = true
        }
    }

    fun formatSpeed(speed: Float): String = when {
        speed == 1f -> "1×"
        speed == -0.5f -> "−½×"
        speed == 0.5f -> "½×"
        speed == 1.5f -> "1.5×"
        speed == speed.toInt().toFloat() -> {
            val n = speed.toInt()
            if (n < 0) "−${-n}×" else "${n}×"
        }
        speed < 0f -> "−${"%.1f".format(-speed)}×"
        else -> "${"%.1f".format(speed)}×"
    }
}
