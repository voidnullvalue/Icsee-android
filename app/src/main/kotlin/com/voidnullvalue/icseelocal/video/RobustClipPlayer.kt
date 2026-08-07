package com.voidnullvalue.icseelocal.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * ExoPlayer tuned for our remuxed SD-card clips (often high-res HEVC that HW
 * decoders paint green / corrupt). Prefers software decoders and enables
 * fallback when the primary codec fails.
 */
@UnstableApi
object RobustClipPlayer {
    fun create(context: Context, onError: (String) -> Unit = {}): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(softwarePreferSelector())

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .build()
        }

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
            .apply {
                playWhenReady = true
                // Avoid seamless gaps that can leave a stale green frame on screen.
                videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onError(error.message ?: "Player error ${error.errorCode}")
                    }
                })
            }
    }

    fun play(player: ExoPlayer, uri: String) {
        val media = MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMimeType(guessMime(uri))
            .build()
        player.setMediaItem(media, /* resetPosition = */ true)
        player.prepare()
        player.playWhenReady = true
    }

    private fun guessMime(uri: String): String? = when {
        uri.contains(".mp4", ignoreCase = true) -> "video/mp4"
        uri.contains(".h264", ignoreCase = true) -> "video/avc"
        uri.contains(".h265", ignoreCase = true) || uri.contains(".hevc", ignoreCase = true) -> "video/hevc"
        else -> null
    }

    /** Sort codec infos so software / Google decoders come first. */
    private fun softwarePreferSelector(): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder,
            )
            infos.sortedBy { info ->
                when {
                    info.name.contains("c2.android", ignoreCase = true) -> 0
                    info.name.contains("OMX.google", ignoreCase = true) -> 1
                    info.softwareOnly -> 2
                    else -> 3
                }
            }
        }
}
