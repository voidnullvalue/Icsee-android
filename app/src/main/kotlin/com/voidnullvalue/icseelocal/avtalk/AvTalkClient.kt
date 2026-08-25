package com.voidnullvalue.icseelocal.avtalk

import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.session.DvripLoginNegotiator
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Android translation of the working issue #6 main.py AVTalk flow.
 *
 * This intentionally does NOT reuse the previous speculative encrypted/FunSDK
 * implementation. It opens three independent TCP sockets that share only the
 * login SessionID:
 *
 *  control: plaintext MD5 login, 1360 DecoderPram, 1415 Start/Stop, keepalive
 *  monitor: 1413 OPMonitor Claim+Start; receive loop continuously drains 1412
 *  media:   1417 AVTalk Claim, then 1419 video/audio payloads
 *
 * The working script uses literal sequence 24 for both secondary-connection
 * claims and literal sequence 0 for every 1419 media packet. There is no
 * 64-KiB media fragmentation and no encrypted 1360/1415 path here.
 */
class AvTalkClient(
    private val host: String,
    private val port: Int = 34567,
    private val credentials: CameraCredentials,
    val profile: AvTalkProfile = AvTalkProfile(),
    private val responseTimeoutMillis: Long = 3000,
) {
    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + Dispatchers.IO)
    private val mediaFrameMutex = Mutex()

    private var controlTransport: DvripTransport? = null
    private var monitorTransport: DvripTransport? = null
    private var mediaTransport: DvripTransport? = null
    private var keepaliveJob: Job? = null
    private var keepaliveEchoJob: Job? = null
    private var sessionId: UInt? = null

    @Volatile
    var isActive: Boolean = false
        private set

    suspend fun start() {
        check(controlTransport == null && monitorTransport == null && mediaTransport == null) {
            "AVTalk client already started"
        }

        // main.py starts its control sequence at 0 then calls next_seq() for the
        // login, so the login is sent as sequence 1. Keep that exact behavior here.
        val control = DvripTransport(host, port, sequence = AtomicLong(1))
        try {
            control.connect()
            controlTransport = control

            val session = DvripLoginNegotiator(responseTimeoutMillis).negotiate(control, credentials)
            val sid = session.sessionId
            sessionId = sid

            startKeepalive(control, sid)

            // The working camera accepts AVTalk only while an OPMonitor live view
            // exists. This is a dedicated connection and its 1413 uses seq 24.
            val monitor = DvripTransport(host, port)
            monitor.connect()
            monitorTransport = monitor
            requestPlain(
                transport = monitor,
                sid = sid,
                requestId = AvTalkMessageIds.OPMONITOR_REQUEST,
                responseId = AvTalkMessageIds.OPMONITOR_RESPONSE,
                payload = AvTalkPayloads.opMonitorWire(sid, profile),
                sequenceOverride = 24u,
                operation = "OPMonitor Claim+Start",
            )
            // DvripTransport owns a continuous receive loop, so 1412 downlink
            // frames on this socket are drained even though AVTalk doesn't use them.

            // main.py queries DecoderPram here. The issue author notes 1360 is not
            // strictly required and capability details can vary by camera, so keep
            // it best-effort rather than rejecting an otherwise usable AVTalk model.
            runCatching {
                requestPlain(
                    transport = control,
                    sid = sid,
                    requestId = AvTalkMessageIds.DECODER_QUERY,
                    responseId = AvTalkMessageIds.DECODER_RESPONSE,
                    payload = AvTalkPayloads.decoderPramWire(sid),
                    sequenceOverride = null,
                    operation = "DecoderPram",
                )
            }

            val media = DvripTransport(host, port)
            media.connect()
            mediaTransport = media
            requestPlain(
                transport = media,
                sid = sid,
                requestId = AvTalkMessageIds.CLAIM_REQUEST,
                responseId = AvTalkMessageIds.CLAIM_RESPONSE,
                payload = AvTalkPayloads.claimWire(sid, profile),
                sequenceOverride = 24u,
                operation = "AVTalk Claim",
            )

            requestPlain(
                transport = control,
                sid = sid,
                requestId = AvTalkMessageIds.CONTROL_REQUEST,
                responseId = AvTalkMessageIds.CONTROL_RESPONSE,
                payload = AvTalkPayloads.startWire(sid, profile),
                sequenceOverride = null,
                operation = "AVTalk Start",
            )

            isActive = true
        } catch (t: Throwable) {
            closeTransports()
            throw t
        }
    }

    suspend fun sendH265KeyFrame(
        annexB: ByteArray,
        timestamp: LocalDateTime = LocalDateTime.now(),
    ) {
        val epoch = timestamp.atZone(ZoneId.systemDefault()).toEpochSecond()
        sendMediaFrame(AvTalkFrameEncoder.h265KeyFrame(annexB, profile, epoch))
    }

    suspend fun sendH265InterFrame(annexB: ByteArray) {
        sendMediaFrame(AvTalkFrameEncoder.h265InterFrame(annexB))
    }

    suspend fun sendAlawAudio(samples: ByteArray) {
        sendMediaFrame(AvTalkFrameEncoder.alawAudio(samples, profile))
    }

    private suspend fun sendMediaFrame(payload: ByteArray) = mediaFrameMutex.withLock {
        check(isActive) { "AVTalk has not completed Start" }
        val media = checkNotNull(mediaTransport) { "AVTalk media socket is not connected" }
        val sid = checkNotNull(sessionId)
        // main.py sends each complete 0xFC/0xFD/0xFA sub-frame in one 1419
        // packet and always uses outer DVRIP sequence 0.
        media.send(
            session = sid,
            messageId = AvTalkMessageIds.MEDIA_UPSTREAM,
            payload = payload,
            sequenceOverride = 0u,
        )
    }

    suspend fun stop() {
        val control = controlTransport
        val sid = sessionId
        var stopFailure: Throwable? = null

        isActive = false
        // Let a source frame already being written finish before Stop.
        mediaFrameMutex.withLock { }

        if (control != null && sid != null) {
            stopFailure = runCatching {
                requestPlain(
                    transport = control,
                    sid = sid,
                    requestId = AvTalkMessageIds.CONTROL_REQUEST,
                    responseId = AvTalkMessageIds.CONTROL_RESPONSE,
                    payload = AvTalkPayloads.stopWire(sid, profile),
                    sequenceOverride = null,
                    operation = "AVTalk Stop",
                )
            }.exceptionOrNull()
        }

        // main.py stops heartbeats only after sending Stop, then leaves the
        // connections up for roughly one second before closing them.
        keepaliveJob?.cancel()
        keepaliveJob = null
        keepaliveEchoJob?.cancel()
        keepaliveEchoJob = null
        delay(1000)
        closeTransports()
        stopFailure?.let { throw it }
    }

    private fun startKeepalive(control: DvripTransport, sid: UInt) {
        keepaliveEchoJob = ownerScope.launch {
            control.incomingFrames
                .filter { it.header.messageId == AvTalkMessageIds.KEEPALIVE }
                .collect {
                    runCatching {
                        control.send(
                            session = sid,
                            messageId = AvTalkMessageIds.KEEPALIVE,
                            payload = AvTalkPayloads.keepAliveWire(sid),
                        )
                    }
                }
        }
        keepaliveJob = ownerScope.launch {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val ok = runCatching {
                    control.send(
                        session = sid,
                        messageId = AvTalkMessageIds.KEEPALIVE,
                        payload = AvTalkPayloads.keepAliveWire(sid),
                    )
                }.isSuccess
                if (!ok) break
                delay(2000)
            }
        }
    }

    private suspend fun requestPlain(
        transport: DvripTransport,
        sid: UInt,
        requestId: Int,
        responseId: Int,
        payload: ByteArray,
        sequenceOverride: UInt?,
        operation: String,
    ) = withTimeout(responseTimeoutMillis) {
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                transport.incomingFrames
                    .filter { it.header.messageId == responseId }
                    .first()
            }
            transport.send(
                session = sid,
                messageId = requestId,
                payload = payload,
                sequenceOverride = sequenceOverride,
            )
            val json = DvripPayloads.decodeJsonOrNull(response.await().payload)
                ?: error("$operation response $responseId was not plaintext JSON")
            requireRet100(json, operation)
            json
        }
    }

    private fun requireRet100(json: String, operation: String) {
        val ret = Regex("\\\"Ret\\\"\\s*:\\s*(-?\\d+)")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        check(ret == 100) { "$operation rejected: Ret=${ret ?: "missing"}: $json" }
    }

    private fun closeTransports() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        keepaliveEchoJob?.cancel()
        keepaliveEchoJob = null
        isActive = false
        mediaTransport?.close()
        mediaTransport = null
        monitorTransport?.close()
        monitorTransport = null
        controlTransport?.close()
        controlTransport = null
        sessionId = null
        ownerJob.cancel()
    }
}
