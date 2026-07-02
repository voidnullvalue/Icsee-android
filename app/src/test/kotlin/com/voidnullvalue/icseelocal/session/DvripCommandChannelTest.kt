package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.crypto.AesSessionCrypto
import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripHeader
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.crypto.SessionCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Base64

private object PassthroughCrypto : SessionCrypto {
    override fun shouldEncrypt(messageId: Int) = messageId !in DvripMessageIds.TRANSPORT_UNENCRYPTED_IDS
    override fun encrypt(messageId: Int, plaintext: ByteArray) = plaintext
    override fun decrypt(messageId: Int, ciphertext: ByteArray) = ciphertext
}

class DvripCommandChannelTest {

    @Test
    fun `unencrypted message ids are sent as plaintext JSON with 0x0A 0x00 terminator`() = runTest {
        val server = ServerSocket(0)
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 5000
            val client = server.accept()
            val header = ByteArray(DvripHeader.HEADER_LEN)
            var read = 0
            while (read < header.size) {
                val n = client.getInputStream().read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            val h = DvripHeader.decode(header)
            val payload = ByteArray(h.payloadLength)
            read = 0
            while (read < payload.size) {
                val n = client.getInputStream().read(payload, read, payload.size - read)
                if (n < 0) break
                read += n
            }
            client.close()
            payload
        }

        val transport = DvripTransport("127.0.0.1", server.localPort)
        transport.connect()
        val channel = DvripCommandChannel(transport, sessionId = 0x1Bu, crypto = PassthroughCrypto)
        channel.sendJson(DvripMessageIds.MONITOR_REQUEST, """{"Name":"OPMonitor"}""") // 1413, unencrypted per evidence

        val payload = serverJob.await()
        assertEquals("""{"Name":"OPMonitor"}""", DvripPayloads.decodeJsonOrNull(payload))

        transport.close()
        server.close()
    }

    @Test
    fun `encrypted message ids are sent as base64 text with single 0x00 terminator`() = runTest {
        val server = ServerSocket(0)
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 5000
            val client = server.accept()
            val header = ByteArray(DvripHeader.HEADER_LEN)
            var read = 0
            while (read < header.size) {
                val n = client.getInputStream().read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            val h = DvripHeader.decode(header)
            val payload = ByteArray(h.payloadLength)
            read = 0
            while (read < payload.size) {
                val n = client.getInputStream().read(payload, read, payload.size - read)
                if (n < 0) break
                read += n
            }
            client.close()
            payload
        }

        val key = ByteArray(16) { it.toByte() }
        val crypto = AesSessionCrypto(key, AesSessionCrypto.CANDIDATE_ECB_NO_PADDING)
        val transport = DvripTransport("127.0.0.1", server.localPort)
        transport.connect()
        val channel = DvripCommandChannel(transport, sessionId = 0x1Bu, crypto = crypto)
        // PTZ payload padded to a block boundary for AES/ECB/NoPadding.
        channel.sendJson(DvripMessageIds.PTZ_CONTROL_REQUEST, """{"Name":"OPPTZControl"}""".padEnd(32))

        val payload = serverJob.await()
        assertEquals(0, payload.last().toInt()) // single 0x00 terminator, not 0x0A 0x00
        val base64Text = DvripPayloads.decodeBase64TextOrNull(payload)
        assertTrue(base64Text != null && base64Text.isNotEmpty())
        val decrypted = crypto.decrypt(DvripMessageIds.PTZ_CONTROL_REQUEST, Base64.getDecoder().decode(base64Text))
        assertEquals("""{"Name":"OPPTZControl"}""".padEnd(32), decrypted.toString(Charsets.UTF_8))

        transport.close()
        server.close()
    }

    @Test
    fun `decodeResponse round trips an encrypted frame`() {
        val key = ByteArray(16) { (it * 2).toByte() }
        val crypto = AesSessionCrypto(key, AesSessionCrypto.CANDIDATE_ECB_NO_PADDING)
        val channel = DvripCommandChannel(DvripTransport("unused", 0), sessionId = 0x1Bu, crypto = crypto)
        val plaintext = """{"Ret":100}""".padEnd(16)
        val ciphertext = crypto.encrypt(DvripMessageIds.PTZ_CONTROL_RESPONSE, plaintext.toByteArray())
        val wirePayload = DvripPayloads.encodeBase64Text(Base64.getEncoder().encodeToString(ciphertext))
        val frame = DvripFrame.of(0x1Bu, 1u, DvripMessageIds.PTZ_CONTROL_RESPONSE, wirePayload)
        assertEquals(plaintext, channel.decodeResponse(frame))
    }

    @Test
    fun `decodeResponse returns null for malformed encrypted payload`() {
        val crypto = AesSessionCrypto(ByteArray(16), AesSessionCrypto.CANDIDATE_ECB_NO_PADDING)
        val channel = DvripCommandChannel(DvripTransport("unused", 0), sessionId = 0x1Bu, crypto = crypto)
        val frame = DvripFrame.of(0x1Bu, 1u, DvripMessageIds.PTZ_CONTROL_RESPONSE, byteArrayOf(1, 2, 3))
        assertNull(channel.decodeResponse(frame))
    }
}
