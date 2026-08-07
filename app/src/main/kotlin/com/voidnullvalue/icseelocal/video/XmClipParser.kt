package com.voidnullvalue.icseelocal.video

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pulls H.264/H.265 access units out of a Xiongmai private-framed SD-card clip.
 *
 * Clips are typically Annex-B with XM wrapper NAL markers (`0xF9`/`0xFA`/`0xFC`/`0xFD`
 * = reserved HEVC types 124–126) interleaved with real VPS/SPS/PPS/VCL NALs.
 * Some firmware revisions nest the elementary stream *inside* those wrappers
 * (with an 8–16 byte XM sub-header) and/or use length-prefixed NALs — this
 * parser tries each layout before giving up.
 */
internal object XmClipParser {
    data class HevcTrack(
        val vps: ByteArray?,
        val sps: ByteArray,
        val pps: ByteArray?,
        val frames: List<ByteArray>,
    )

    data class AvcTrack(
        val sps: ByteArray,
        val pps: ByteArray?,
        val frames: List<ByteArray>,
    )

    sealed interface Parsed {
        data class Hevc(val track: HevcTrack) : Parsed
        data class Avc(val track: AvcTrack) : Parsed
    }

    fun parse(raw: ByteArray): Parsed {
        require(raw.isNotEmpty()) { "downloaded clip is empty (0 bytes) — Claim/DownloadStart likely failed" }

        // 1) Top-level Annex-B split (the live-confirmed layout).
        parseHevc(splitAnnexB(raw))?.let { return Parsed.Hevc(it) }
        parseAvc(splitAnnexB(raw))?.let { return Parsed.Avc(it) }

        // 2) Peel XM wrappers and look inside their payloads.
        val nested = ArrayList<ByteArray>()
        for (nal in splitAnnexB(raw)) {
            if (nal.isEmpty()) continue
            val hevcType = hevcType(nal)
            if (hevcType in 124..126) {
                nested += extractFromXmWrapper(nal)
            } else {
                nested += nal
            }
        }
        if (nested.isNotEmpty()) {
            parseHevc(nested)?.let { return Parsed.Hevc(it) }
            parseAvc(nested)?.let { return Parsed.Avc(it) }
        }

        // 3) Whole-buffer length-prefixed scan (HVCC/AVCC without start codes).
        val lengthPrefixed = splitLengthPrefixed(raw, offset = 0)
        parseHevc(lengthPrefixed)?.let { return Parsed.Hevc(it) }
        parseAvc(lengthPrefixed)?.let { return Parsed.Avc(it) }

        val types = splitAnnexB(raw)
            .take(32)
            .map { nal -> if (nal.isEmpty()) -1 else hevcType(nal) }
        throw IllegalArgumentException(
            "no H.264/H.265 parameter sets found in clip " +
                "(${raw.size} bytes, first NAL types=$types)",
        )
    }

    private fun parseHevc(nals: List<ByteArray>): HevcTrack? {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val frames = ArrayList<ByteArray>()
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (val type = hevcType(nal)) {
                32 -> vps = nal
                33 -> sps = nal
                34 -> pps = nal
                in 0..31 -> frames.add(nal)
                else -> Unit
            }
        }
        val spsNal = sps ?: return null
        if (frames.isEmpty()) return null
        return HevcTrack(vps, spsNal, pps, frames)
    }

    private fun parseAvc(nals: List<ByteArray>): AvcTrack? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val frames = ArrayList<ByteArray>()
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (val type = nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
                in 1..5 -> frames.add(nal)
                else -> Unit
            }
        }
        val spsNal = sps ?: return null
        if (frames.isEmpty()) return null
        return AvcTrack(spsNal, pps, frames)
    }

    /**
     * XM wrapper body after the 1-byte marker: try a few known sub-header lengths,
     * then Annex-B and length-prefixed splits of the remainder.
     */
    private fun extractFromXmWrapper(wrapperNal: ByteArray): List<ByteArray> {
        // wrapperNal[0] is the marker (F9/FA/FC/FD); payload follows.
        if (wrapperNal.size < 8) return emptyList()
        val out = ArrayList<ByteArray>()
        for (hdrLen in intArrayOf(0, 4, 8, 12, 15, 16)) {
            if (wrapperNal.size <= 1 + hdrLen) continue
            val body = wrapperNal.copyOfRange(1 + hdrLen, wrapperNal.size)
            val annex = splitAnnexB(body)
            if (annex.any { it.isNotEmpty() && (hevcType(it) in 32..34 || (it[0].toInt() and 0x1F) in 7..8) }) {
                out += annex
                return out
            }
            val prefixed = splitLengthPrefixed(body, 0)
            if (prefixed.any { it.isNotEmpty() && (hevcType(it) in 32..34 || (it[0].toInt() and 0x1F) in 7..8) }) {
                out += prefixed
                return out
            }
        }
        // Fallback: treat remainder after a 15-byte sub-header as more Annex-B.
        if (wrapperNal.size > 16) out += splitAnnexB(wrapperNal.copyOfRange(16, wrapperNal.size))
        return out
    }

    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        val out = ArrayList<ByteArray>(starts.size)
        for (k in starts.indices) {
            val s = starts[k]
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else data.size
            // Drop the leading 0x00 of a 4-byte start code (`00 00 00 01`) that
            // was left on the previous NAL by matching the 3-byte form.
            if (e > s && data[e - 1].toInt() == 0) e--
            if (e > s) out.add(data.copyOfRange(s, e))
        }
        return out
    }

    private fun splitLengthPrefixed(data: ByteArray, offset: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = offset
        var consecutive = 0
        while (i + 4 <= data.size) {
            val len = ByteBuffer.wrap(data, i, 4).order(ByteOrder.BIG_ENDIAN).int
            if (len <= 0 || len > data.size || i + 4 + len > data.size) break
            out.add(data.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
            consecutive++
            if (consecutive > 100_000) break
        }
        return out
    }

    private fun hevcType(nal: ByteArray): Int = (nal[0].toInt() shr 1) and 0x3F
}

/** Converts an XM private-framed clip to a standard MP4 via [android.media.MediaMuxer]. */
object XmHevcMuxer {
    /** Fallback when clip Begin/EndTime is missing — many XM recorders are ~12–15 fps. */
    private const val DEFAULT_FPS = 12
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * @param durationUs optional wall-clock length of the recording (from OPFileQuery
     * Begin/EndTime). When set, frame timestamps are spaced across that duration so
     * ExoPlayer 1× matches real time instead of a guessed FPS.
     */
    fun writeMp4(raw: ByteArray, outFile: java.io.File, durationUs: Long? = null, fps: Int = DEFAULT_FPS) {
        val audioAlaw = XmClipAudio.extractAlaw(raw)
        val aac = if (audioAlaw.isNotEmpty()) XmClipAudio.encodeAac(audioAlaw) else null
        when (val parsed = XmClipParser.parse(raw)) {
            is XmClipParser.Parsed.Hevc -> writeHevc(parsed.track, outFile, durationUs, fps, aac)
            is XmClipParser.Parsed.Avc -> writeAvc(parsed.track, outFile, durationUs, fps, aac)
        }
    }

    private fun writeHevc(
        track: XmClipParser.HevcTrack,
        outFile: java.io.File,
        durationUs: Long?,
        fps: Int,
        aac: XmClipAudio.AacTrack?,
    ) {
        val (w, h) = HevcSps.dimensions(track.sps)
        require(w > 0 && h > 0) { "invalid HEVC dimensions ${w}x$h" }
        val frames = dropUntilKey(track.frames) { nal ->
            ((nal[0].toInt() shr 1) and 0x3F) in 16..21
        }
        require(frames.isNotEmpty()) { "no HEVC keyframe in clip yet" }
        val format = android.media.MediaFormat.createVideoFormat("video/hevc", w, h).apply {
            val csd = ByteArrayOutputStream()
            listOfNotNull(track.vps, track.sps, track.pps).forEach {
                csd.write(START_CODE)
                csd.write(it)
            }
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd.toByteArray()))
        }
        muxAnnexB(format, frames, outFile, durationUs, fps, aac) { nal ->
            ((nal[0].toInt() shr 1) and 0x3F) in 16..21
        }
    }

    private fun writeAvc(
        track: XmClipParser.AvcTrack,
        outFile: java.io.File,
        durationUs: Long?,
        fps: Int,
        aac: XmClipAudio.AacTrack?,
    ) {
        val (w, h) = AvcSps.dimensions(track.sps)
        require(w > 0 && h > 0) { "invalid AVC dimensions ${w}x$h" }
        val frames = dropUntilKey(track.frames) { nal -> (nal[0].toInt() and 0x1F) == 5 }
        require(frames.isNotEmpty()) { "no H.264 IDR in clip yet" }
        val format = android.media.MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(START_CODE + track.sps))
            track.pps?.let { setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(START_CODE + it)) }
        }
        muxAnnexB(format, frames, outFile, durationUs, fps, aac) { nal ->
            (nal[0].toInt() and 0x1F) == 5
        }
    }

    private fun dropUntilKey(frames: List<ByteArray>, isKey: (ByteArray) -> Boolean): List<ByteArray> {
        val idx = frames.indexOfFirst(isKey)
        if (idx < 0) return emptyList()
        return frames.subList(idx, frames.size)
    }

    private fun muxAnnexB(
        format: android.media.MediaFormat,
        frames: List<ByteArray>,
        outFile: java.io.File,
        durationUs: Long?,
        fps: Int,
        aac: XmClipAudio.AacTrack?,
        isKey: (ByteArray) -> Boolean,
    ) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()
        val muxer = android.media.MediaMuxer(outFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoIdx = muxer.addTrack(format)
            val audioIdx = aac?.let { XmClipAudio.addToMuxer(muxer, it) }
            muxer.start()
            val info = android.media.MediaCodec.BufferInfo()
            val frameDurUs = when {
                durationUs != null && durationUs > 0 && frames.isNotEmpty() ->
                    (durationUs / frames.size).coerceAtLeast(1_000L)
                else -> 1_000_000L / fps.coerceAtLeast(1)
            }
            frames.forEachIndexed { i, nal ->
                val sample = ByteArray(4 + nal.size).also {
                    it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 1
                    System.arraycopy(nal, 0, it, 4, nal.size)
                }
                info.set(
                    0,
                    sample.size,
                    i * frameDurUs,
                    if (isKey(nal)) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                )
                muxer.writeSampleData(videoIdx, java.nio.ByteBuffer.wrap(sample), info)
            }
            if (audioIdx != null && aac != null) {
                XmClipAudio.writeSamples(muxer, audioIdx, aac)
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }
}

/** Minimal H.264 SPS parser: recovers coded width/height for the muxer. */
internal object AvcSps {
    fun dimensions(spsNal: ByteArray): Pair<Int, Int> {
        val rbsp = unescape(spsNal, from = 1)
        val br = BitReader(rbsp)
        br.u(8) // profile_idc
        br.u(8) // constraint flags + reserved
        br.u(8) // level_idc
        br.ue() // seq_parameter_set_id
        // Baseline/Main/Extended skip; for high profiles there are chroma bits —
        // enough cameras use Main/High that we handle the common High path.
        // Re-read profile from original:
        val profile = spsNal[1].toInt() and 0xFF
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            val chroma = br.ue()
            if (chroma == 3) br.u(1)
            br.ue() // bit_depth_luma_minus8
            br.ue() // bit_depth_chroma_minus8
            br.u(1) // qpprime_y_zero_transform_bypass_flag
            if (br.u(1) == 1) skipScalingList(br, chroma)
        }
        br.ue() // log2_max_frame_num_minus4
        val pocType = br.ue()
        when (pocType) {
            0 -> br.ue()
            1 -> {
                br.u(1)
                br.se()
                br.se()
                val n = br.ue()
                repeat(n) { br.se() }
            }
        }
        br.ue() // max_num_ref_frames
        br.u(1) // gaps_in_frame_num_value_allowed_flag
        val widthInMbs = br.ue() + 1
        val heightInMapUnits = br.ue() + 1
        val frameMbsOnly = br.u(1)
        if (frameMbsOnly == 0) br.u(1)
        br.u(1) // direct_8x8_inference_flag
        var width = widthInMbs * 16
        var height = heightInMapUnits * 16 * (if (frameMbsOnly == 0) 2 else 1)
        if (br.u(1) == 1) { // frame_cropping_flag
            val l = br.ue()
            val r = br.ue()
            val t = br.ue()
            val b = br.ue()
            width -= (l + r) * 2
            height -= (t + b) * 2 * (if (frameMbsOnly == 0) 2 else 1)
        }
        return width to height
    }

    private fun skipScalingList(br: BitReader, chromaFormatIdc: Int) {
        val count = if (chromaFormatIdc != 3) 8 else 12
        repeat(count) { i ->
            if (br.u(1) == 1) {
                val size = if (i < 6) 16 else 64
                var next = 8
                var last = 8
                repeat(size) {
                    if (next != 0) {
                        val delta = br.se()
                        next = (last + delta + 256) % 256
                    }
                    last = if (next == 0) last else next
                }
            }
        }
    }

    private fun unescape(nal: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        var i = from
        while (i < nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 0x03 && i + 1 < nal.size && (nal[i + 1].toInt() and 0xFF) <= 0x03) {
                zeros = 0
            } else {
                out.write(b)
                zeros = if (b == 0) zeros + 1 else 0
            }
            i++
        }
        return out.toByteArray()
    }

    private class BitReader(val data: ByteArray) {
        private var bit = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val byteIdx = bit ushr 3
                val b = if (byteIdx < data.size) data[byteIdx].toInt() and 0xFF else 0
                val bitVal = (b ushr (7 - (bit and 7))) and 1
                v = (v shl 1) or bitVal
                bit++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0 && zeros < 32) zeros++
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + u(zeros)
        }
        fun se(): Int {
            val v = ue()
            return if (v and 1 == 0) -(v ushr 1) else (v + 1) ushr 1
        }
    }
}
