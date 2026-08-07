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
 *
 * Confirmed wire shape (PROTOCOL_NOTES / talk channel):
 * `00 00 01 FA 0E 02 40 01` + exactly 320 A-law bytes.
 */
internal object XmClipAudio {
    private const val SAMPLE_RATE = 8_000
    private const val CHANNELS = 1
    private const val AAC_BITRATE = 16_000
    const val ALAW_CHUNK = 320 // 40 ms at 8 kHz

    /** Sub-header after the Annex-B start code: FA 0E 02 40 01 */
    private val FA_SUBHEADER = byteArrayOf(0xFA.toByte(), 0x0E, 0x02, 0x40, 0x01)

    /** Concatenated A-law bytes from FA units in [raw], or empty if none. */
    fun extractAlaw(raw: ByteArray): ByteArray {
        val scanned = scanConfirmedFaUnits(raw)
        if (scanned.isNotEmpty()) return scanned

        // Fallback: NAL-split path for slightly variant firmwares.
        val out = ByteArrayOutputStream()
        for (nal in XmClipParser.splitAnnexB(raw)) {
            if (nal.isEmpty()) continue
            if ((nal[0].toInt() and 0xFF) != 0xFA) continue
            if (nal.size >= 5 + ALAW_CHUNK &&
                nal[1] == 0x0E.toByte() &&
                nal[2] == 0x02.toByte()
            ) {
                val len = if (nal.size >= 5 &&
                    nal[3] == 0x40.toByte() && nal[4] == 0x01.toByte()
                ) {
                    ALAW_CHUNK
                } else {
                    ALAW_CHUNK
                }
                val available = (nal.size - 5).coerceAtMost(len)
                if (available >= ALAW_CHUNK) {
                    out.write(nal, 5, ALAW_CHUNK)
                    var off = 5 + ALAW_CHUNK
                    while (off + ALAW_CHUNK <= nal.size) {
                        out.write(nal, off, ALAW_CHUNK)
                        off += ALAW_CHUNK
                    }
                }
                continue
            }
            // Length LE at bytes 3-4 when present
            if (nal.size >= 5) {
                val declared = (nal[3].toInt() and 0xFF) or ((nal[4].toInt() and 0xFF) shl 8)
                if (declared in ALAW_CHUNK..(ALAW_CHUNK * 8) && nal.size >= 5 + declared) {
                    val usable = declared - (declared % ALAW_CHUNK)
                    if (usable >= ALAW_CHUNK) {
                        out.write(nal, 5, usable)
                        continue
                    }
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Byte-scan for the live-confirmed FA audio framing. More reliable than
     * Annex-B NAL split alone when FA units sit between XM video wrappers.
     */
    private fun scanConfirmedFaUnits(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i + 3 + FA_SUBHEADER.size + ALAW_CHUNK <= raw.size) {
            if (raw[i] == 0.toByte() && raw[i + 1] == 0.toByte() && raw[i + 2] == 1.toByte() &&
                matchesAt(raw, i + 3, FA_SUBHEADER)
            ) {
                out.write(raw, i + 3 + FA_SUBHEADER.size, ALAW_CHUNK)
                i += 3 + FA_SUBHEADER.size + ALAW_CHUNK
                continue
            }
            // Also accept 4-byte start code 00 00 00 01 FA …
            if (i + 4 + FA_SUBHEADER.size + ALAW_CHUNK <= raw.size &&
                raw[i] == 0.toByte() && raw[i + 1] == 0.toByte() &&
                raw[i + 2] == 0.toByte() && raw[i + 3] == 1.toByte() &&
                matchesAt(raw, i + 4, FA_SUBHEADER)
            ) {
                out.write(raw, i + 4 + FA_SUBHEADER.size, ALAW_CHUNK)
                i += 4 + FA_SUBHEADER.size + ALAW_CHUNK
                continue
            }
            i++
        }
        return out.toByteArray()
    }

    private fun matchesAt(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > data.size) return false
        for (j in pattern.indices) {
            if (data[offset + j] != pattern[j]) return false
        }
        return true
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
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        try {
            val samples = ArrayList<ByteArray>()
            var csd: ByteArray? = null
            val info = MediaCodec.BufferInfo()
            var pcmIdx = 0
            var inputDone = false
            var outputDone = false
            val frameSamples = 160 // ~20 ms
            while (!outputDone) {
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
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            // Keep draining briefly after EOS input.
                            val retry = codec.dequeueOutputBuffer(info, 50_000)
                            if (retry == MediaCodec.INFO_TRY_AGAIN_LATER) break
                            if (retry >= 0) {
                                handleAacOutput(codec, retry, info, samples) { csd = it }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            } else if (retry == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                csd = readCsd(codec) ?: csd
                            }
                        }
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        csd = readCsd(codec) ?: csd
                    }
                    else -> if (outIx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            handleAacOutput(codec, outIx, info, samples) { csd = it }
                            outputDone = true
                        } else {
                            handleAacOutput(codec, outIx, info, samples) { csd = it }
                        }
                    }
                }
            }
            val config = csd ?: return null
            if (samples.isEmpty()) return null
            val sampleDurUs = 1_000_000L * frameSamples / SAMPLE_RATE
            return AacTrack(config, samples, sampleDurUs)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun readCsd(codec: MediaCodec): ByteArray? {
        val outFmt = codec.outputFormat
        return outFmt.getByteBuffer("csd-0")?.let { b ->
            val a = ByteArray(b.remaining())
            b.get(a)
            a
        }
    }

    private fun handleAacOutput(
        codec: MediaCodec,
        outIx: Int,
        info: MediaCodec.BufferInfo,
        samples: MutableList<ByteArray>,
        onCsd: (ByteArray) -> Unit,
    ) {
        if (info.size > 0) {
            val out = codec.getOutputBuffer(outIx)!!
            val chunk = ByteArray(info.size)
            out.position(info.offset)
            out.get(chunk)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                onCsd(chunk)
            } else {
                samples += chunk
            }
        }
        codec.releaseOutputBuffer(outIx, false)
    }

    fun addToMuxer(muxer: MediaMuxer, aac: AacTrack): Int {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
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
