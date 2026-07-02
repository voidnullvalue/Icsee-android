package com.voidnullvalue.icseelocal.video

import com.voidnullvalue.icseelocal.crypto.NullSessionCrypto
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Owns the **independent** video/audio media TCP connection -- confirmed
 * separate from the control connection (see PROTOCOL_NOTES.md "Video/audio
 * media channel": the connection that actually streamed video never sent a
 * login, it reused the session id established on the control connection).
 * Does not perform its own authentication.
 *
 * Video decode is only attempted once [CodecProbe] actually identifies a
 * codec from real bytes -- see PROTOCOL_STATUS.md: this has not happened
 * against the target camera yet, so this controller honestly reports
 * "unknown codec, 0 frames decoded" rather than claim success once
 * `OPMonitor`'s `Ret: 100` comes back.
 */
class VideoStreamController(
    private val host: String,
    private val port: Int,
    private val sessionId: UInt,
    private val channelIndex: Int = 0,
    private val sampleExport: BoundedSampleExporter? = null,
    /** Session-shared sequence counter; the OPMonitor claim must continue the
     *  session's sequence rather than restart at 0. See [DvripTransport]. */
    private val sessionSequence: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0),
) {
    private var transport: DvripTransport? = null
    private var collectJob: Job? = null
    private val reassembler = MediaStreamReassembler()

    private val _stats = MutableStateFlow(VideoStats())
    val stats: StateFlow<VideoStats> = _stats.asStateFlow()

    suspend fun start(scope: CoroutineScope) {
        val t = DvripTransport(host, port, sequence = sessionSequence)
        t.connect()
        transport = t
        val channel = DvripCommandChannel(t, sessionId, NullSessionCrypto)

        collectJob = t.incomingFrames
            .onEach { frame -> handleFrame(frame.header.messageId, frame.payload) }
            .launchIn(scope)

        // Confirmed real request shape: single combined claim+start, CombinMode NONE.
        // See PROTOCOL_NOTES.md -- this is the shape actually used on the connection
        // that received video, not the task brief's two-step Claim/Start example.
        val claimJson = """{"Name":"OPMonitor","OPMonitor":{"Action":"Claim","Action1":"Start",""" +
            """"Parameter":{"Channel":$channelIndex,"CombinMode":"NONE","StreamType":"Main","TransMode":"TCP"}},""" +
            """"SessionID":"${sessionIdHex()}"}"""
        channel.sendJson(DvripMessageIds.MONITOR_REQUEST, claimJson)
    }

    // 10 lowercase hex digits, matching the reference app's OPMonitor claim in
    // the capture ("0x000000001b").
    private fun sessionIdHex(): String = "0x%010x".format(sessionId.toLong())

    private fun handleFrame(messageId: Int, payload: ByteArray) {
        when (messageId) {
            DvripMessageIds.MONITOR_RESPONSE -> {
                val json = DvripPayloads.decodeJsonOrNull(payload)
                if (json == null || !json.contains("\"Ret\":100") && !json.contains("\"Ret\" : 100")) {
                    _stats.update { it.copy(lastError = "OPMonitor claim did not return Ret:100: $json") }
                }
                // Ret:100 alone does not mean video is working -- see class doc. Stats below
                // only reflect what's actually decoded, not the claim acknowledgement.
            }
            DvripMessageIds.MEDIA_STREAM -> {
                _stats.update {
                    it.copy(
                        videoBytesReceived = it.videoBytesReceived + payload.size,
                        dvripMediaFrames = it.dvripMediaFrames + 1,
                    )
                }
                val unit = try {
                    reassembler.offer(payload)
                } catch (e: IllegalArgumentException) {
                    _stats.update { it.copy(lastError = "media reassembly desync: ${e.message}") }
                    reassembler.reset()
                    null
                }
                if (unit != null) handleCompletedUnit(unit)
            }
        }
    }

    private fun handleCompletedUnit(unit: MediaUnit) {
        sampleExport?.offer(unit.bytes)
        if (unit.type == MediaUnitType.VIDEO_FIRST || unit.type == MediaUnitType.VIDEO_SUBSEQUENT) {
            if (_stats.value.detectedCodec == DetectedCodec.UNKNOWN) {
                val detected = CodecProbe.detect(unit)
                if (detected != DetectedCodec.UNKNOWN) {
                    _stats.update { it.copy(detectedCodec = detected) }
                }
            }
            // No confirmed codec yet on real evidence -- see class doc. codecFramesSubmitted/
            // framesDecoded stay at 0 until CodecProbe (or corrected evidence) identifies one.
        }
    }

    fun stop() {
        collectJob?.cancel()
        transport?.close()
        transport = null
        reassembler.reset()
    }
}
