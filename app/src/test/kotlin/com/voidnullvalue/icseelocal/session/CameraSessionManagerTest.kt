package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripHeader
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.model.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class CameraSessionManagerTest {

    // Real message 1001 response from a live successful login on 2026-07-01 (see PROTOCOL_NOTES.md).
    private val realLoginResponseJson = """{ "AdminToken" : "7H3fdUf0w+I+GiJs8U1/m8neYIGD4XRktHu4j0Leqcg=", """ +
        """"AliveInterval" : 30, "ChannelNum" : 1, "DeviceType " : "IPC", "ExtraChannel" : 0, "Ret" : 100, "SessionID" : "0x0000001d" }"""

    @Test
    fun `manual connect walks the expected states through to a real successful login`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 5000
            val client = server.accept()
            val input = client.getInputStream()
            val header = ByteArray(DvripHeader.HEADER_LEN)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) return@async
                read += n
            }
            val responseFrame = DvripFrame.of(
                0u, 0u, DvripMessageIds.LOGIN_RESPONSE,
                DvripPayloads.encodeJson(realLoginResponseJson),
            )
            client.getOutputStream().write(responseFrame.encode())
            client.getOutputStream().flush()
            // Keep the connection open past login so the keepalive task doesn't
            // immediately tear the session back down mid-assertion.
            Thread.sleep(2000)
            client.close()
        }

        val manager = CameraSessionManager("127.0.0.1", server.localPort)

        manager.connect(CameraCredentials("testuser", "hunter2"))

        withTimeout(5000) {
            while (manager.state.value !is ConnectionState.Authenticated) delay(20)
        }

        val authenticated = manager.state.value as ConnectionState.Authenticated
        assertTrue(authenticated.sessionId == 0x1du)
        assertTrue(authenticated.aliveIntervalSeconds == 30)

        serverJob.cancel()
        manager.shutdown()
        server.close()
    }

    @Test
    fun `manual connect against an unreachable port fails cleanly without crashing`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()

        val manager = CameraSessionManager("127.0.0.1", port)
        manager.connect(CameraCredentials("user", "pass"))

        withTimeout(5000) {
            while (manager.state.value !is ConnectionState.Failed) delay(20)
        }
        assertTrue(manager.state.value is ConnectionState.Failed)
        manager.shutdown()
    }

    @Test
    fun `disconnect from a failed state returns to disconnected`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()

        val manager = CameraSessionManager("127.0.0.1", port)
        manager.connect(CameraCredentials("user", "pass"))
        withTimeout(5000) {
            while (manager.state.value !is ConnectionState.Failed) delay(20)
        }
        manager.disconnect()
        assertTrue(manager.state.value is ConnectionState.Disconnected)
        manager.shutdown()
    }
}
