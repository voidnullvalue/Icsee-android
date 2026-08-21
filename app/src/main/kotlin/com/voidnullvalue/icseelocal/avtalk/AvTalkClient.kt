package com.voidnullvalue.icseelocal.avtalk

import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import com.voidnullvalue.icseelocal.session.FunSdkEncryptedLoginNegotiator
import com.voidnullvalue.icseelocal.session.KeepaliveTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime

/**
 * Two-connection AVTalk session compatible with the recovered iCSee FunSDK flow.
 *
 * Control: encrypted login -> 1360 -> 1415 Start/Stop.
 * Media: same SessionID and shared command sequence -> plaintext 1417 Claim,
 * then raw 1419 CSTDStream frames. 1419 itself always uses DVRIP sequence 0,
 * exactly as CMediaChannel -> CProtocolNetIP::NewMediaDataPTL does natively.
 */
class AvTalkClient(
    private val host: String,
    private val port: Int = 34567,
    private val credentials: CameraCredentials,
    val profile: AvTalkProfile = AvTalkProfile(),
    private val responseTimeoutMillis: Long = 5000,
    private val mediaStartDelayMillis: Long = 1200,
    private val postStopCapabilityRefreshMillis: Long = 2000,
    private val loginNegotiator: FunSdkEncryptedLoginNegotiator = FunSdkEncryptedLoginNegotiator(responseTimeoutMillis),
) {
    private val funSdkSequence = FunSdkSequence()
    private var controlTransport: DvripTransport? = null
    private var mediaTransport: DvripTransport? = null
    private var controlChannel: DvripCommandChannel? = null
    private var sessionId: UInt? = null
    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + Dispatchers.IO)
    private var keepalive: KeepaliveTask? = null

    @Volatile
    var isActive: Boolean = false
        private set

    suspend fun start() {
        check(controlTransport == null && mediaTransport == null) { "AVTalk client is already started; call stop() before reusing it" }
        val control = DvripTransport(host, port)
        try {
            control.connect()
            controlTransport = control
            val session = loginNegotiator.negotiate(control, credentials, funSdkSequence)
            sessionId = session.sessionId
            val channel = DvripCommandChannel(
                control,
                session.sessionId,
                session.crypto,
                sequenceProvider = { funSdkSequence.nextUInt() },
            )
            controlChannel = channel

            requestEncrypted(
                control,
                channel,
                DvripMessageIds.ABILITY_GET,
                DvripMessageIds.ABILITY_GET_RESPONSE,
                AvTalkPayloads.encodeCapability(session.sessionId),
                "EncodeCapability",
            )

            val media = DvripTransport(host, port)
            media.connect()
            mediaTransport = media
            requestPlain(
                media,
                session.sessionId,
                AvTalkMessageIds.CLAIM_REQUEST,
                AvTalkMessageIds.CLAIM_RESPONSE,
                AvTalkPayloads.claimWirePayload(session.sessionId, profile),
                funSdkSequence.nextUInt(),
                "AVTalk Claim",
            )

            requestEncrypted(
                control,
                channel,
                AvTalkMessageIds.CONTROL_REQUEST,
                AvTalkMessageIds.CONTROL_RESPONSE,
                AvTalkPayloads.start(session.sessionId, profile),
                "AVTalk Start",
            )
            if (mediaStartDelayMillis > 0) delay(mediaStartDelayMillis)
            isActive = true
            keepalive = KeepaliveTask(
                channel = channel,
                aliveIntervalSeconds = session.aliveIntervalSeconds,
                sessionIdHex = "0x%08X".format(session.sessionId.toLong()),
                onFailure = { closeTransports() },
            ).also { it.start(ownerScope) }
        } catch (t: Throwable) {
            closeTransports()
            throw t
        }
    }

    suspend fun sendH265KeyFrame(annexB: ByteArray, timestamp: LocalDateTime = LocalDateTime.now()) {
        sendMediaFrame(AvTalkFrameEncoder.h265KeyFrame(annexB, profile, timestamp))
    }

    suspend fun sendH265InterFrame(annexB: ByteArray) {
        sendMediaFrame(AvTalkFrameEncoder.h265InterFrame(annexB))
    }

    /**
     * Sends one G.711 A-law media frame. For the stock 8 kHz profile, the
     * observed cadence is 320 bytes every 40 ms; the caller controls pacing.
     */
    suspend fun sendAlawAudio(samples: ByteArray) {
        sendMediaFrame(AvTalkFrameEncoder.alawAudio(samples, profile))
    }

    /**
     * Sends one complete native CSTDStream frame. FunSDK fragments these at
     * 64 KiB before NewMediaDataPTL; every resulting 1419 DVRIP packet has
     * sequence 0 and the camera reassembles from the frame's own length field.
     */
    private suspend fun sendMediaFrame(frame: ByteArray) {
        check(isActive) { "AVTalk has not completed Start" }
        val media = checkNotNull(mediaTransport) { "AVTalk media transport is not connected" }
        val sid = checkNotNull(sessionId)
        val mediaGroupTag = funSdkSequence.next() and 0xff
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + FUNSDK_MEDIA_CHUNK_SIZE, frame.size)
            media.send(
                session = sid,
                messageId = AvTalkMessageIds.MEDIA_UPSTREAM,
                payload = frame.copyOfRange(offset, end),
                sequenceOverride = 0u,
                headerByte12 = mediaGroupTag,
            )
            offset = end
        }
    }

    suspend fun stop() {
        val control = controlTransport
        val channel = controlChannel
        val sid = sessionId
        var stopFailure: Throwable? = null
        keepalive?.stop()
        keepalive = null
        isActive = false
        if (control != null && channel != null && sid != null) {
            try {
                requestEncrypted(
                    control,
                    channel,
                    AvTalkMessageIds.CONTROL_REQUEST,
                    AvTalkMessageIds.CONTROL_RESPONSE,
                    AvTalkPayloads.stop(sid, profile),
                    "AVTalk Stop",
                )
            } catch (t: Throwable) {
                stopFailure = t
            }
        }
        mediaTransport?.close()
        mediaTransport = null

        // Stock app re-queries EncodeCapability about two seconds after Stop.
        if (stopFailure == null && control != null && channel != null && sid != null && postStopCapabilityRefreshMillis >= 0) {
            if (postStopCapabilityRefreshMillis > 0) delay(postStopCapabilityRefreshMillis)
            runCatching {
                requestEncrypted(
                    control,
                    channel,
                    DvripMessageIds.ABILITY_GET,
                    DvripMessageIds.ABILITY_GET_RESPONSE,
                    AvTalkPayloads.encodeCapability(sid),
                    "post-stop EncodeCapability",
                )
            }
        }
        control?.close()
        controlTransport = null
        controlChannel = null
        sessionId = null
        stopFailure?.let { throw it }
    }

    private suspend fun requestEncrypted(
        transport: DvripTransport,
        channel: DvripCommandChannel,
        requestId: Int,
        responseId: Int,
        json: String,
        operation: String,
    ) = withTimeout(responseTimeoutMillis) {
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                transport.incomingFrames.filter { it.header.messageId == responseId }.first()
            }
            channel.sendJson(requestId, json)
            val responseJson = channel.decodeResponse(response.await())
                ?: error("$operation response $responseId could not be decrypted/decoded")
            requireRet100(responseJson, operation)
        }
    }

    private suspend fun requestPlain(
        transport: DvripTransport,
        sid: UInt,
        requestId: Int,
        responseId: Int,
        payload: ByteArray,
        sequence: UInt,
        operation: String,
    ) = withTimeout(responseTimeoutMillis) {
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                transport.incomingFrames.filter { it.header.messageId == responseId }.first()
            }
            transport.send(sid, requestId, payload, sequenceOverride = sequence)
            val responseJson = DvripPayloads.decodeJsonOrNull(response.await().payload)
                ?: error("$operation response $responseId was not plaintext JSON")
            requireRet100(responseJson, operation)
        }
    }

    private fun requireRet100(json: String, operation: String) {
        val ret = Regex("\\\"Ret\\\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        check(ret == 100) { "$operation rejected: Ret=${ret ?: "missing"}: $json" }
    }

    private fun closeTransports() {
        keepalive?.stop()
        keepalive = null
        isActive = false
        mediaTransport?.close()
        mediaTransport = null
        controlTransport?.close()
        controlTransport = null
        controlChannel = null
        sessionId = null
    }

    companion object {
        /** CMediaChannel constructor initializes its media split size to exactly 0x10000. */
        const val FUNSDK_MEDIA_CHUNK_SIZE = 65_536
    }
}
