package com.voidnullvalue.icseelocal.video

import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import java.io.ByteArrayOutputStream
import java.io.File
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
 * Recorded clips are stored as Xiongmai's private-framed H.265 (sometimes H.264):
 * a stream of `00 00 01 <marker>` units where markers 0xF9/0xFA/0xFC/0xFD are XM
 * wrappers (reserved HEVC NAL types 124-126) and the real video is ordinary
 * VPS/SPS/PPS/IDR/TRAIL NALs — either interleaved or nested inside the wrappers.
 * See [XmClipParser] / [XmHevcMuxer].
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
        downloadInternal(fileName, begin, end, maxBytes = Long.MAX_VALUE, onProgress)

    /**
     * Pulls only the start of a clip (enough for VPS/SPS/PPS + first IDR) so we
     * can build a thumbnail without waiting for a full multi-MB download.
     */
    suspend fun downloadPrefix(
        fileName: String,
        begin: String,
        end: String,
        maxBytes: Long = THUMB_PREFIX_BYTES,
    ): ByteArray = downloadInternal(fileName, begin, end, maxBytes = maxBytes)

    private suspend fun downloadInternal(
        fileName: String,
        begin: String,
        end: String,
        maxBytes: Long,
        onProgress: (Long) -> Unit = {},
    ): ByteArray =
        coroutineScope {
            val collected = ByteArrayOutputStream()
            // Subscribe BEFORE sending so no data frame is missed (same
            // race-avoidance pattern the rest of the app uses).
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                var lastReport = 0L
                // Match tools/live/sdcard_download.py: after DownloadStart, keep every
                // non-JSON body until a zero-length frame. Filtering only mid=1426
                // misses firmwares that deliver the file on a neighbouring mid.
                transport.incomingFrames
                    .takeWhile { frame ->
                        frame.header.payloadLength > 0 && collected.size() < maxBytes
                    }
                    .filter { frame ->
                        val p = frame.payload
                        if (p.isEmpty()) return@filter false
                        if (p[0] == '{'.code.toByte()) return@filter false
                        frame.header.messageId == PB_DATA ||
                            (p.size >= 4 && p[0] == 0.toByte() && p[1] == 0.toByte() && p[2] == 1.toByte())
                    }
                    .collect {
                        collected.write(it.payload)
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250) {
                            onProgress(collected.size().toLong())
                            lastReport = now
                        }
                    }
                onProgress(collected.size().toLong())
                collected.toByteArray()
            }
            commandChannel.sendJson(PB_CLAIM, body("Claim", fileName, begin, end))
            commandChannel.sendJson(PB_CTRL, body("DownloadStart", fileName, begin, end))
            val timeout = if (maxBytes < Long.MAX_VALUE) THUMB_TIMEOUT_MS else DOWNLOAD_TIMEOUT_MS
            val raw = try {
                withTimeout(timeout) { job.await() }
            } finally {
                runCatching { commandChannel.sendJson(PB_CTRL, body("DownloadStop", fileName, begin, end)) }
                // Prefix mode cancels the collector mid-stream; let the camera settle.
                if (maxBytes < Long.MAX_VALUE) {
                    job.cancel()
                }
            }
            raw
        }

    /** Downloads [fileName] and writes a playable MP4 to [outFile]; returns it. */
    suspend fun exportToMp4(fileName: String, begin: String, end: String, outFile: File, onProgress: (Long) -> Unit = {}): File {
        val raw = download(fileName, begin, end, onProgress)
        XmHevcMuxer.writeMp4(raw, outFile)
        return outFile
    }

    /** Downloads a short prefix and remuxes a tiny MP4 suitable for a first-frame thumb. */
    suspend fun exportThumbMp4(fileName: String, begin: String, end: String, outFile: File): File {
        val raw = downloadPrefix(fileName, begin, end)
        require(raw.isNotEmpty()) { "empty prefix for thumbnail" }
        XmHevcMuxer.writeMp4(raw, outFile)
        return outFile
    }

    /**
     * Streams a clip for in-app playback (cache only — not gallery).
     * Remuxes as soon as enough of the HEVC stream is parseable and invokes
     * [onEarlyMp4] so the player can start while the rest is still arriving.
     * Returns the final full remux when the camera finishes sending.
     */
    suspend fun streamForPlayback(
        fileName: String,
        begin: String,
        end: String,
        cacheDir: File,
        onProgress: (Long) -> Unit = {},
        onEarlyMp4: (File) -> Unit = {},
    ): File = coroutineScope {
        cacheDir.mkdirs()
        val collected = ByteArrayOutputStream()
        var earlySent = false
        var nextTryAt = EARLY_PLAY_MIN_BYTES
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            var lastReport = 0L
            transport.incomingFrames
                .takeWhile { it.header.payloadLength > 0 }
                .filter { frame ->
                    val p = frame.payload
                    if (p.isEmpty()) return@filter false
                    if (p[0] == '{'.code.toByte()) return@filter false
                    frame.header.messageId == PB_DATA ||
                        (p.size >= 4 && p[0] == 0.toByte() && p[1] == 0.toByte() && p[2] == 1.toByte())
                }
                .collect {
                    collected.write(it.payload)
                    val size = collected.size().toLong()
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 200) {
                        onProgress(size)
                        lastReport = now
                    }
                    if (!earlySent && size >= nextTryAt) {
                        val snap = collected.toByteArray()
                        val early = File(cacheDir, "play_early_${System.currentTimeMillis()}.mp4")
                        val ok = runCatching {
                            XmHevcMuxer.writeMp4(snap, early)
                            early.length() > 0
                        }.getOrDefault(false)
                        if (ok) {
                            earlySent = true
                            onEarlyMp4(early)
                        } else {
                            runCatching { early.delete() }
                            nextTryAt = size + EARLY_PLAY_RETRY_BYTES
                        }
                    }
                }
            onProgress(collected.size().toLong())
            collected.toByteArray()
        }
        commandChannel.sendJson(PB_CLAIM, body("Claim", fileName, begin, end))
        commandChannel.sendJson(PB_CTRL, body("DownloadStart", fileName, begin, end))
        val raw = try {
            withTimeout(DOWNLOAD_TIMEOUT_MS) { job.await() }
        } finally {
            runCatching { commandChannel.sendJson(PB_CTRL, body("DownloadStop", fileName, begin, end)) }
        }
        require(raw.isNotEmpty()) { "empty stream for playback" }
        val finalFile = File(cacheDir, "play_full_${System.currentTimeMillis()}.mp4")
        XmHevcMuxer.writeMp4(raw, finalFile)
        require(finalFile.exists() && finalFile.length() > 0) { "remux produced empty file" }
        if (!earlySent) onEarlyMp4(finalFile)
        finalFile
    }

    companion object {
        const val PB_CLAIM = 1424
        const val PB_CTRL = 1420
        const val PB_DATA = 1426
        private const val DOWNLOAD_TIMEOUT_MS = 180_000L
        private const val THUMB_TIMEOUT_MS = 25_000L
        /** ~first GOP; enough for SPS + IDR on typical XM clips. */
        const val THUMB_PREFIX_BYTES = 768_000L
        /** First attempt at an early playable remux (needs SPS + IDR). */
        const val EARLY_PLAY_MIN_BYTES = 600_000L
        /** Retry interval when early remux fails (SPS/IDR not yet present). */
        const val EARLY_PLAY_RETRY_BYTES = 350_000L
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
