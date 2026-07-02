package com.voidnullvalue.icseelocal.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Thin wrapper around [MediaCodec] for rendering an Annex-B elementary
 * stream directly to a [Surface]. Only instantiated once [CodecProbe] (or a
 * future confirmed source) has actually identified a codec -- see
 * PROTOCOL_NOTES.md: this app has not yet observed real H.264/H.265 start
 * codes in the target camera's stream, so this class exists and is
 * structurally correct but is not wired to fabricated codec parameters.
 */
class HardwareVideoDecoder(
    private val codec: DetectedCodec,
    private val surface: Surface,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private var mediaCodec: MediaCodec? = null

    fun start() {
        require(codec != DetectedCodec.UNKNOWN) { "cannot start a decoder for an unknown codec" }
        val mime = when (codec) {
            DetectedCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            DetectedCodec.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            DetectedCodec.UNKNOWN -> error("unreachable")
        }
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val mc = MediaCodec.createDecoderByType(mime)
        mc.configure(format, surface, null, 0)
        mc.start()
        mediaCodec = mc
    }

    /** Feeds one Annex-B access unit (a full frame's worth of NAL units) to the decoder. Returns true if accepted. */
    fun submitFrame(data: ByteArray, presentationTimeUs: Long): Boolean {
        val mc = mediaCodec ?: return false
        val index = mc.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return false
        val buffer: ByteBuffer = mc.getInputBuffer(index) ?: return false
        buffer.clear()
        buffer.put(data)
        mc.queueInputBuffer(index, 0, data.size, presentationTimeUs, 0)
        return true
    }

    /** Drains any decoded output to the surface; returns the number of frames released for rendering. */
    fun drainOutput(): Int {
        val mc = mediaCodec ?: return 0
        val info = MediaCodec.BufferInfo()
        var released = 0
        while (true) {
            val index = mc.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            if (index < 0) break
            mc.releaseOutputBuffer(index, true)
            released++
        }
        return released
    }

    fun release() {
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
    }
}
