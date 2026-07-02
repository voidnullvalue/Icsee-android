package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.dvrip.DvripHeader
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class KeepaliveTaskTest {

    @Test
    fun `sends a keepalive before the interval expires and stop prevents further sends`() = runBlocking {
        val server = ServerSocket(0)
        val receivedCount = AtomicInteger(0)
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 8000
            val client = server.accept()
            val input = client.getInputStream()
            repeat(2) {
                val header = ByteArray(DvripHeader.HEADER_LEN)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) return@async
                    read += n
                }
                val h = DvripHeader.decode(header)
                var payloadRead = 0
                val payload = ByteArray(h.payloadLength)
                while (payloadRead < payload.size) {
                    val n = input.read(payload, payloadRead, payload.size - payloadRead)
                    if (n < 0) break
                    payloadRead += n
                }
                if (h.messageId == DvripMessageIds.KEEPALIVE_REQUEST) receivedCount.incrementAndGet()
            }
        }

        val transport = DvripTransport("127.0.0.1", server.localPort)
        transport.connect()
        val channel = DvripCommandChannel(transport, sessionId = 0x1Bu, crypto = KeepaliveTestPassthroughCrypto)
        // aliveIntervalSeconds=1, margin=0 -> fires after ~1s (floor enforced by KeepaliveTask).
        val task = KeepaliveTask(channel, aliveIntervalSeconds = 1, sessionIdHex = "0x0000001B", marginMillis = 0)
        task.start(scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO))

        withTimeout(5000) {
            while (receivedCount.get() < 1) delay(50)
        }
        assertEquals(1, receivedCount.get())
        assertTrue(task.isRunning)

        task.stop()
        assertTrue(!task.isRunning)
        delay(1500) // long enough that a second keepalive would have fired if stop() didn't work
        assertEquals(1, receivedCount.get())

        transport.close()
        server.close()
        serverJob.cancel()
    }
}

private object KeepaliveTestPassthroughCrypto : com.voidnullvalue.icseelocal.crypto.SessionCrypto {
    override fun shouldEncrypt(messageId: Int) = messageId !in DvripMessageIds.TRANSPORT_UNENCRYPTED_IDS
    override fun encrypt(messageId: Int, plaintext: ByteArray) = plaintext
    override fun decrypt(messageId: Int, ciphertext: ByteArray) = ciphertext
}
