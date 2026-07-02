package com.voidnullvalue.icseelocal.audio

import android.util.Log
import com.voidnullvalue.icseelocal.crypto.NullSessionCrypto
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Independent talk connection -- confirmed separate TCP connection from
 * control and video (see PROTOCOL_NOTES.md "Talk channel"), reusing the
 * session id established on the control connection, no re-authentication.
 *
 * The full working handshake (LIVE-CONFIRMED 2026-07-02) is: send the OPTalk
 * *Claim* (1434) on this talk connection, then an OPTalk *Start* (1430) on the
 * *control* connection -- the Start is what actually opens the camera speaker;
 * the claim alone returns Ret:100 but plays nothing -- then stream 1432 audio
 * frames here. Release sends OPTalk *Stop* (1430) on the control connection.
 */
class TalkController(
    private val host: String,
    private val port: Int,
    private val sessionId: UInt,
    private val microphone: MicrophoneSource,
    /**
     * The session's shared sequence counter (see [CameraSessionManager.sessionSequence]).
     * The OPTalk claim must continue the session's sequence, not restart at 0,
     * or the camera acks it but never routes audio.
     */
    private val sessionSequence: java.util.concurrent.atomic.AtomicLong,
    /**
     * The already-authenticated control connection's command channel. The OPTalk
     * Start/Stop lifecycle commands (1430) that open/close the speaker go here,
     * NOT on the talk connection (matches the reference capture).
     */
    private val controlChannel: DvripCommandChannel,
) {
    private var transport: DvripTransport? = null
    private var captureJob: Job? = null
    private var claimResponseJob: Job? = null
    private var controlScope: CoroutineScope? = null

    // Reference app formats the claim's JSON SessionID as "0x" + 10 lowercase
    // hex digits (confirmed in the capture: "0x000000001b"), unlike the 8-digit
    // form used elsewhere. Matched here for fidelity on the claim.
    private fun sessionIdHex(): String = "0x%010x".format(sessionId.toLong())

    /**
     * Press-and-hold entry point: claims the talk channel and starts streaming mic audio.
     * Throws (after cleaning up any partial state via [stop]) if the connection or the
     * claim send itself fails -- callers must catch this, since without it a failure here
     * would otherwise look identical to a silently-successful claim.
     */
    suspend fun start(scope: CoroutineScope, onError: (Throwable) -> Unit = {}) {
        try {
            controlScope = scope
            val t = DvripTransport(host, port, sequence = sessionSequence)
            t.connect()
            transport = t
            val channel = DvripCommandChannel(t, sessionId, NullSessionCrypto)

            // The claim ack (Ret:100/not) only arrives asynchronously after start()
            // has already returned, same as capture-time errors below -- so a rejected
            // claim reports through the same onError callback the caller already wires
            // up to reset its "talking" UI state.
            claimResponseJob = t.incomingFrames
                .filter { it.header.messageId == DvripMessageIds.TALK_CLAIM_RESPONSE }
                .onEach { frame ->
                    val json = DvripPayloads.decodeJsonOrNull(frame.payload)
                    if (json == null || (!json.contains("\"Ret\":100") && !json.contains("\"Ret\" : 100"))) {
                        Log.e(TAG, "OPTalk claim rejected: $json")
                        onError(IllegalStateException("OPTalk claim rejected: $json"))
                        stop()
                    } else {
                        Log.d(TAG, "OPTalk claim accepted")
                    }
                }
                .launchIn(scope)

            val claimJson = """{"Name":"OPTalk","OPTalk":{"Action":"Claim","AudioFormat":{"BitRate":0,""" +
                """"EncodeType":"G711_ALAW","SampleBit":8,"SampleRate":8}},"SessionID":"${sessionIdHex()}"}"""
            channel.sendJson(DvripMessageIds.TALK_CLAIM_REQUEST, claimJson)

            // The step that actually opens the camera speaker: OPTalk Start on the
            // *control* connection. Without this the claim above returns Ret:100 but
            // no audio ever plays (live-confirmed 2026-07-02).
            val startJson = """{"Name":"OPTalk","OPTalk":{"Action":"Start"},"SessionID":"${sessionIdHex()}"}"""
            controlChannel.sendJson(DvripMessageIds.OPTALK_CONTROL_REQUEST, startJson)
            Log.d(TAG, "sent OPTalk Start on control connection")

            captureJob = microphone.captureAlawChunks()
                // Audio data frames carry a constant sequence of 0 in the capture,
                // not the monotonic counter the claim uses.
                .onEach { alaw -> t.send(sessionId, DvripMessageIds.TALK_AUDIO_UPSTREAM, TalkAudioFrame.wrap(alaw), sequenceOverride = 0u) }
                .catch {
                    Log.e(TAG, "talk audio capture/send failed", it)
                    onError(it)
                    stop()
                }
                .launchIn(scope)
        } catch (e: Exception) {
            Log.e(TAG, "failed to start talk connection", e)
            stop()
            throw e
        }
    }

    /** Immediate release -- button-up, cancellation, app backgrounded, or screen disposed all call this. */
    fun stop() {
        captureJob?.cancel()
        captureJob = null
        claimResponseJob?.cancel()
        claimResponseJob = null
        transport?.close()
        transport = null
        // Best-effort OPTalk Stop on the control connection so the camera closes the
        // speaker channel; fire-and-forget since release must not block on the network.
        val scope = controlScope
        controlScope = null
        if (scope != null) {
            val stopJson = """{"Name":"OPTalk","OPTalk":{"Action":"Stop"},"SessionID":"${sessionIdHex()}"}"""
            scope.launch {
                runCatching { controlChannel.sendJson(DvripMessageIds.OPTALK_CONTROL_REQUEST, stopJson) }
                    .onFailure { Log.w(TAG, "OPTalk Stop send failed (ignored)", it) }
            }
        }
    }

    companion object {
        private const val TAG = "TalkController"
    }

    val isActive: Boolean get() = transport != null
}
