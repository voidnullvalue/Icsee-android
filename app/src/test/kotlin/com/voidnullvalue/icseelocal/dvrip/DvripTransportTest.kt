package com.voidnullvalue.icseelocal.dvrip

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * Exercises DvripTransport against a real loopback TCP server standing in
 * for the camera -- no mocking of java.net.Socket, actual bytes over an
 * actual local socket.
 */
class DvripTransportTest {

    @Test
    fun `connects, sends a frame, and the server observes the exact bytes`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 5000
            val client = server.accept()
            val header = ByteArray(DvripHeader.HEADER_LEN)
            var read = 0
            val input = client.getInputStream()
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            client.close()
            header
        }

        val transport = DvripTransport("127.0.0.1", server.localPort)
        transport.connect()
        val sentFrame = transport.send(session = 0x1Bu, messageId = DvripMessageIds.KEEPALIVE_REQUEST, payload = ByteArray(0))

        val observedHeader = withTimeout(5000) { serverJob.await() }
        assertEquals(sentFrame.header.encode().toList(), observedHeader.toList())

        transport.close()
        server.close()
    }

    @Test
    fun `receives and reassembles a frame sent by the server`() = runBlocking {
        val server = ServerSocket(0)
        val responseFrame = DvripFrame.of(0x1Bu, 1u, DvripMessageIds.LOGIN_RESPONSE, DvripPayloads.encodeJson("""{"Ret":100}"""))
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 5000
            val client = server.accept()
            val bytes = responseFrame.encode()
            // Dribble it out in two writes to exercise partial-read reassembly end to end.
            client.getOutputStream().write(bytes, 0, 10)
            client.getOutputStream().flush()
            Thread.sleep(50)
            client.getOutputStream().write(bytes, 10, bytes.size - 10)
            client.getOutputStream().flush()
            Thread.sleep(200)
            client.close()
        }

        val transport = DvripTransport("127.0.0.1", server.localPort, readPollTimeoutMillis = 200)

        lateinit var received: DvripFrame
        // Real wall-clock timeout (runBlocking, not runTest) so it waits for the
        // real loopback socket rather than the virtual test clock jumping past it.
        // Subscribe BEFORE connecting: incomingFrames is a replay=0 SharedFlow, so a
        // frame emitted before the collector registers would be dropped. connect()
        // suspends on withContext(IO), which yields and lets Turbine subscribe first,
        // then the server accepts and sends while we're already collecting.
        transport.incomingFrames.test(timeout = 5.seconds) {
            transport.connect() // starts the transport's own receive loop internally
            received = awaitItem()
        }

        serverJob.await()
        transport.close()
        server.close()

        assertEquals(DvripMessageIds.LOGIN_RESPONSE, received.header.messageId)
        assertEquals("""{"Ret":100}""", DvripPayloads.decodeJsonOrNull(received.payload))
    }

    @Test
    fun `a shared sequence counter continues across transports and an override forces a literal sequence`() = runBlocking {
        // Simulates the real DVRIP session model: one monotonic sequence shared
        // by the control connection and any secondary (video/talk) connection
        // that reuses the session. A secondary connection created with the
        // already-advanced shared counter must NOT restart at 0.
        val shared = java.util.concurrent.atomic.AtomicLong(0)

        val server = ServerSocket(0)
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 5000
            val client = server.accept()
            val input = client.getInputStream()
            val headers = mutableListOf<DvripHeader>()
            repeat(3) {
                val header = ByteArray(DvripHeader.HEADER_LEN)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                val h = DvripHeader.decode(header)
                headers += h
                // consume payload
                val payload = ByteArray(h.payloadLength)
                read = 0
                while (read < payload.size) {
                    val n = input.read(payload, read, payload.size - read)
                    if (n < 0) break
                    read += n
                }
            }
            client.close()
            headers
        }

        // Pretend the control connection already burned sequences 0 and 1.
        shared.getAndIncrement()
        shared.getAndIncrement()

        val transport = DvripTransport("127.0.0.1", server.localPort, sequence = shared)
        transport.connect()
        val claim = transport.send(0x1Bu, DvripMessageIds.TALK_CLAIM_REQUEST, ByteArray(4))
        val audio1 = transport.send(0x1Bu, DvripMessageIds.TALK_AUDIO_UPSTREAM, ByteArray(8), sequenceOverride = 0u)
        val audio2 = transport.send(0x1Bu, DvripMessageIds.TALK_AUDIO_UPSTREAM, ByteArray(8), sequenceOverride = 0u)

        val observed = withTimeout(5000) { serverJob.await() }
        transport.close()
        server.close()

        // Claim continued the shared counter (value 2), not a fresh 0.
        assertEquals(2u, claim.header.sequence)
        assertEquals(2u, observed[0].sequence)
        // Audio data frames pin sequence to 0 regardless of the counter.
        assertEquals(0u, audio1.header.sequence)
        assertEquals(0u, audio2.header.sequence)
        assertEquals(0u, observed[1].sequence)
        assertEquals(0u, observed[2].sequence)
    }

    @Test
    fun `close is idempotent and safe to call multiple times`() = runBlocking {
        val server = ServerSocket(0)
        val acceptJob = async(Dispatchers.IO) { server.accept() }
        val transport = DvripTransport("127.0.0.1", server.localPort)
        transport.connect()
        acceptJob.await().close()

        transport.close()
        transport.close()
        transport.close()
        assertFalse(transport.isConnected)
        server.close()
    }

    @Test
    fun `connect throws on refused connection within the timeout`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close() // nothing listening now

        val transport = DvripTransport("127.0.0.1", port, connectTimeoutMillis = 1000)
        var threw = false
        try {
            withContext(Dispatchers.IO) { transport.connect() }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `second connect on an already-connected transport is rejected`() = runBlocking {
        val server = ServerSocket(0)
        val acceptJob = async(Dispatchers.IO) { server.accept() }
        val transport = DvripTransport("127.0.0.1", server.localPort)
        transport.connect()
        acceptJob.await()

        var threw = false
        try {
            transport.connect()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        transport.close()
        server.close()
    }
}
