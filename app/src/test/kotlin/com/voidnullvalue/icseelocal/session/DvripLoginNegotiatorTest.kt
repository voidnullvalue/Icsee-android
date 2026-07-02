package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.crypto.SofiaHash
import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripHeader
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * Simulates the camera's confirmed plaintext login behavior over a
 * loopback socket -- see PROTOCOL_NOTES.md "Login -- LIVE AUTHENTICATION
 * CONFIRMED" for the real end-to-end verification against actual hardware
 * this is modeled on.
 */
class DvripLoginNegotiatorTest {

    private fun CoroutineScope.fakeCameraServer(server: ServerSocket, response1001Json: String) =
        async(Dispatchers.IO) {
            server.soTimeout = 5000
            val client = server.accept()
            val input = client.getInputStream()
            val header = ByteArray(DvripHeader.HEADER_LEN)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            val requestHeader = DvripHeader.decode(header)
            check(requestHeader.messageId == DvripMessageIds.LOGIN_REQUEST)
            val payload = ByteArray(requestHeader.payloadLength)
            read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) break
                read += n
            }
            val requestJson = DvripPayloads.decodeJsonOrNull(payload)
            val responseFrame = DvripFrame.of(
                session = 0u,
                sequence = 0u,
                messageId = DvripMessageIds.LOGIN_RESPONSE,
                payload = DvripPayloads.encodeJson(response1001Json),
            )
            client.getOutputStream().write(responseFrame.encode())
            client.getOutputStream().flush()
            Thread.sleep(100)
            client.close()
            requestJson
        }

    @Test
    fun `negotiate sends the confirmed plaintext login JSON and parses a successful response`() = runTest {
        // Response shape (field names, AdminToken, the literal "DeviceType "
        // trailing space) is real, from a live successful login on
        // 2026-07-01. SessionID/AliveInterval below are the real captured
        // values; credentials used here are a synthetic placeholder, not the
        // real device credentials -- see PROTOCOL_NOTES.md "Login".
        val realLoginResponseJson = """{ "AdminToken" : "7H3fdUf0w+I+GiJs8U1/m8neYIGD4XRktHu4j0Leqcg=", """ +
            """"AliveInterval" : 30, "ChannelNum" : 1, "DeviceType " : "IPC", "ExtraChannel" : 0, "Ret" : 100, "SessionID" : "0x0000001d" }"""
        val server = ServerSocket(0)
        val serverJob = fakeCameraServer(server, realLoginResponseJson)
        val transport = DvripTransport("127.0.0.1", server.localPort, readPollTimeoutMillis = 100)
        transport.connect()

        val negotiator = DvripLoginNegotiator()
        val session = runBlocking { negotiator.negotiate(transport, CameraCredentials("testuser", "hunter2")) }

        assertEquals(0x1du, session.sessionId)
        assertEquals(30, session.aliveIntervalSeconds)
        assertTrue(session.crypto === com.voidnullvalue.icseelocal.crypto.NullSessionCrypto)

        val sentJson = serverJob.await()
        requireNotNull(sentJson)
        assertTrue(sentJson.contains("\"LoginType\":\"DVRIP-Web\""))
        assertTrue(sentJson.contains("\"PassWord\":\"${SofiaHash.hash("hunter2")}\""))
        assertTrue(sentJson.contains("\"UserName\":\"testuser\""))

        transport.close()
        server.close()
    }

    @Test
    fun `negotiate throws cleanly when the camera rejects the login`() = runTest {
        val rejectionJson = """{ "Name" : "", "Ret" : 203, "SessionID" : "0x00000000" }"""
        val server = ServerSocket(0)
        val serverJob = fakeCameraServer(server, rejectionJson)
        val transport = DvripTransport("127.0.0.1", server.localPort, readPollTimeoutMillis = 100)
        transport.connect()

        val negotiator = DvripLoginNegotiator()
        val ex = assertThrows(LoginNegotiationBlockedException::class.java) {
            runBlocking { negotiator.negotiate(transport, CameraCredentials("wrong", "wrong")) }
        }
        assertTrue(ex.message!!.contains("203"))

        serverJob.await()
        transport.close()
        server.close()
    }
}
