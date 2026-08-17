package com.voidnullvalue.icseelocal.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.voidnullvalue.icseelocal.audio.GAlaw
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Pulls G.711 A-law audio out of XM private-framed clips (FA marker units,
 * same shape as talk-channel frames) and AAC-encodes it for MediaMuxer.
 */
internal object XmClipAudio {
    private const val SAMPLE_RATE = 8_000
    private const val CHANNELS = 1
    private const val AAC_BITRATE = 16_000
    private const val ALAW_CHUNK = 320 // 40 ms at 8 kHz

    /** Concatenated A-law bytes from FA units in [raw], or empty if none. */
    fun extractAlaw(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in XmClipParser.splitAnnexB(raw)) {
            if (nal.isEmpty()) continue
            if ((nal[0].toInt() and 0xFF) != 0xFA) continue
            // Talk-shaped: FA 0E 02 40 01 + 320 A-law
            if (nal.size >= 5 + ALAW_CHUNK &&
                nal[1] == 0x0E.toByte() &&
                nal[2] == 0x02.toByte()
            ) {
                out.write(nal, 5, ALAW_CHUNK)
                // Extra trailing chunks on longer FA units
                var off = 5 + ALAW_CHUNK
                while (off + ALAW_CHUNK <= nal.size) {
                    out.write(nal, off, ALAW_CHUNK)
                    off += ALAW_CHUNK
                }
                continue
            }
            // Generic: skip short XM sub-header, take remaining in 320-byte strides
            for (hdr in intArrayOf(1, 5, 8, 9, 15, 16)) {
                if (nal.size <= hdr) continue
                val body = nal.size - hdr
                if (body < ALAW_CHUNK) continue
                if (body % ALAW_CHUNK == 0 || body >= ALAW_CHUNK) {
                    val usable = body - (body % ALAW_CHUNK)
                    if (usable >= ALAW_CHUNK) {
                        out.write(nal, hdr, usable)
                        break
                    }
                }
            }
        }
        return out.toByteArray()
    }

    data class AacTrack(
        val csd: ByteArray,
        val samples: List<ByteArray>,
        val sampleDurUs: Long,
    )

    /** Returns null when no usable audio is present or AAC encode fails. */
    fun encodeAac(alaw: ByteArray): AacTrack? {
        if (alaw.size < ALAW_CHUNK) return null
        val pcm = GAlaw.decodeBuffer(alaw)
        return runCatching { encodePcmToAac(pcm) }.getOrNull()
    }

    private fun encodePcmToAac(pcm: ShortArray): AacTrack? {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val samples = ArrayList<ByteArray>()
        var csd: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        var pcmIdx = 0
        var inputDone = false
        // Feed ~20 ms (160 samples) per input buffer
        val frameSamples = 160
        while (!inputDone || samples.size < 10_000) {
            if (!inputDone) {
                val inIx = codec.dequeueInputBuffer(10_000)
                if (inIx >= 0) {
                    val buf = codec.getInputBuffer(inIx)!!
                    buf.clear()
                    if (pcmIdx >= pcm.size) {
                        codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val n = minOf(frameSamples, pcm.size - pcmIdx)
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            val s = pcm[pcmIdx + i].toInt()
                            bytes[i * 2] = (s and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        buf.put(bytes)
                        val pts = pcmIdx * 1_000_000L / SAMPLE_RATE
                        codec.queueInputBuffer(inIx, 0, bytes.size, pts, 0)
                        pcmIdx += n
                    }
                }
            }
            when (val outIx = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) break
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFmt = codec.outputFormat
                    csd = outFmt.getByteBuffer("csd-0")?.let { b ->
                        val a = ByteArray(b.remaining())
                        b.get(a)
                        a
                    }
                }
                else -> if (outIx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outIx, false)
                        break
                    }
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val out = codec.getOutputBuffer(outIx)!!
                        val chunk = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(chunk)
                        samples += chunk
                    } else if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val out = codec.getOutputBuffer(outIx)!!
                        val chunk = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(chunk)
                        csd = chunk
                    }
                    codec.releaseOutputBuffer(outIx, false)
                }
            }
        }
        codec.stop()
        codec.release()
        val config = csd ?: return null
        if (samples.isEmpty()) return null
        val sampleDurUs = 1_000_000L * frameSamples / SAMPLE_RATE
        return AacTrack(config, samples, sampleDurUs)
    }

    fun addToMuxer(muxer: MediaMuxer, aac: AacTrack): Int {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setByteBuffer("csd-0", ByteBuffer.wrap(aac.csd))
        }
        return muxer.addTrack(format)
    }

    fun writeSamples(muxer: MediaMuxer, trackIdx: Int, aac: AacTrack) {
        val info = MediaCodec.BufferInfo()
        aac.samples.forEachIndexed { i, sample ->
            info.set(0, sample.size, i * aac.sampleDurUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(trackIdx, ByteBuffer.wrap(sample), info)
        }
    }
}
