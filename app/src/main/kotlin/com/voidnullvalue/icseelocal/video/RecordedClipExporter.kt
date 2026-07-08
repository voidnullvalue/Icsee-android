package com.voidnullvalue.icseelocal.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Downloads a recorded clip off the camera's SD card and remuxes it to a
 * standard MP4 that ExoPlayer (or any player) can open.
 *
 * Recorded clips are stored as Xiongmai's private-framed HEVC: a stream of
 * `00 00 01 <marker>` units where markers 0xF9/0xFA/0xFC/0xFD are XM wrappers
 * (reserved HEVC NAL types 124-126, which decoders ignore) and the *real*
 * video is ordinary HEVC NALs interleaved between them -- VPS(32)/SPS(33)/
 * PPS(34)/IDR(19)/TRAIL(0,1). This was live-confirmed on 2026-07-09 by pulling
 * a clip and decoding it (see tools/live/ + PROTOCOL_STATUS.md); the earlier
 * "codec unknown / playback blocked" note was wrong -- it was HEVC all along,
 * missed because the code looked for H.264 start codes.
 *
 * Wire sequence (per OpenIPC/python-dvr, live-confirmed): Claim on msg 1424,
 * then DownloadStart on msg 1420; file bytes arrive on msg 1426 until a frame
 * with a zero-length payload terminates it; DownloadStop on 1420 to release.
 */
class RecordedClipExporter(
    private val transport: DvripTransport,
    private val commandChannel: DvripCommandChannel,
    private val sessionId: UInt,
) {
    private fun sidHex() = "0x%08x".format(sessionId.toLong())

    private fun body(action: String, fileName: String, begin: String, end: String): String =
        Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("Name", "OPPlayBack")
                put("OPPlayBack", buildJsonObject {
                    put("Action", action)
                    put("StartTime", begin)
                    put("EndTime", end)
                    put("Parameter", buildJsonObject {
                        put("PlayMode", "ByName")
                        put("FileName", fileName)
                        put("StreamType", 0)
                        put("Value", 0)
                        put("TransMode", "TCP")
                    })
                })
                put("SessionID", sidHex())
            },
        )

    /** Downloads the raw XM-framed clip bytes into memory. */
    suspend fun download(fileName: String, begin: String, end: String, onProgress: (Long) -> Unit = {}): ByteArray =
        coroutineScope {
            val collected = ByteArrayOutputStream()
            // Subscribe BEFORE sending so no data frame is missed (same
            // race-avoidance pattern the rest of the app uses).
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                transport.incomingFrames
                    .filter { it.header.messageId == PB_DATA }
                    .takeWhile { it.header.payloadLength > 0 }
                    // Skip interleaved JSON status frames; keep binary media.
                    .filter { it.payload.isEmpty() || it.payload[0] != '{'.code.toByte() }
                    .collect {
                        collected.write(it.payload)
                        onProgress(collected.size().toLong())
                    }
                collected.toByteArray()
            }
            commandChannel.sendJson(PB_CLAIM, body("Claim", fileName, begin, end))
            commandChannel.sendJson(PB_CTRL, body("DownloadStart", fileName, begin, end))
            // Generous cap so a missing terminator can't hang forever.
            val raw = withTimeout(DOWNLOAD_TIMEOUT_MS) { job.await() }
            runCatching { commandChannel.sendJson(PB_CTRL, body("DownloadStop", fileName, begin, end)) }
            raw
        }

    /** Downloads [fileName] and writes a playable MP4 to [outFile]; returns it. */
    suspend fun exportToMp4(fileName: String, begin: String, end: String, outFile: File, onProgress: (Long) -> Unit = {}): File {
        val raw = download(fileName, begin, end, onProgress)
        XmHevcMuxer.writeMp4(raw, outFile)
        return outFile
    }

    companion object {
        const val PB_CLAIM = 1424
        const val PB_CTRL = 1420
        const val PB_DATA = 1426
        private const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}

/** Converts an XM private-framed HEVC clip to a standard MP4 via [MediaMuxer]. */
object XmHevcMuxer {
    private const val MIME = "video/hevc"
    private const val DEFAULT_FPS = 15

    fun writeMp4(raw: ByteArray, outFile: File, fps: Int = DEFAULT_FPS) {
        val nals = splitNals(raw)
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val frames = ArrayList<ByteArray>() // each entry is one VCL NAL (Annex-B, no start code)
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (val type = (nal[0].toInt() shr 1) and 0x3F) {
                32 -> vps = nal
                33 -> sps = nal
                34 -> pps = nal
                in 0..31 -> frames.add(nal) // VCL slice
                else -> { /* XM wrapper NAL (124-126) or non-VCL we don't need */ }
            }
        }
        val spsNal = requireNotNull(sps) { "no HEVC SPS found in clip" }
        val (w, h) = HevcSps.dimensions(spsNal)

        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            // csd-0 carries VPS+SPS+PPS (Annex-B) for the MP4 hvcC box.
            val csd = ByteArrayOutputStream()
            listOfNotNull(vps, sps, pps).forEach { csd.write(START_CODE); csd.write(it) }
            setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()))
        }

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = muxer.addTrack(format)
        muxer.start()
        val info = MediaCodec.BufferInfo()
        val frameDurUs = 1_000_000L / fps
        frames.forEachIndexed { i, nal ->
            val sample = ByteArrayOutputStream().apply { write(START_CODE); write(nal) }.toByteArray()
            val buf = ByteBuffer.wrap(sample)
            val isKey = ((nal[0].toInt() shr 1) and 0x3F) in 16..21 // IDR/CRA/BLA keyframe slice types
            info.set(0, sample.size, i * frameDurUs, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, buf, info)
        }
        muxer.stop()
        muxer.release()
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Splits an Annex-B (or XM-framed) byte stream into NAL payloads (start codes removed). */
    private fun splitNals(data: ByteArray): List<ByteArray> {
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
            // trim a trailing 0x00 that belonged to the next start code prefix
            if (e > s && e < data.size && data[e - 1].toInt() == 0) e--
            if (e > s) out.add(data.copyOfRange(s, e))
        }
        return out
    }
}

/** Minimal HEVC SPS parser: recovers coded width/height for the muxer. */
internal object HevcSps {
    fun dimensions(spsNal: ByteArray): Pair<Int, Int> {
        val rbsp = unescape(spsNal, from = 2) // skip 2-byte NAL header, strip emulation bytes
        val br = BitReader(rbsp)
        br.u(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = br.u(3)
        br.u(1) // temporal_id_nesting_flag
        skipProfileTierLevel(br, maxSubLayersMinus1)
        br.ue() // sps_seq_parameter_set_id
        val chroma = br.ue()
        if (chroma == 3) br.u(1) // separate_colour_plane_flag
        val width = br.ue()
        val height = br.ue()
        return width to height
    }

    private fun skipProfileTierLevel(br: BitReader, maxSubLayersMinus1: Int) {
        // general_profile_tier_level: 88 bits + general_level_idc(8) = 96 bits.
        br.skip(96)
        if (maxSubLayersMinus1 > 0) {
            val present = ArrayList<Pair<Boolean, Boolean>>()
            for (i in 0 until maxSubLayersMinus1) {
                val profilePresent = br.u(1) == 1
                val levelPresent = br.u(1) == 1
                present.add(profilePresent to levelPresent)
            }
            for (i in maxSubLayersMinus1 until 8) br.u(2) // reserved_zero_2bits
            for ((profile, level) in present) {
                if (profile) br.skip(88)
                if (level) br.skip(8)
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
                zeros = 0 // skip emulation-prevention byte
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
        fun skip(n: Int) { bit += n }
        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0 && zeros < 32) zeros++
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + u(zeros)
        }
    }
}
