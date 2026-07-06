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
import org.junit.Assert.assertEquals
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
    fun `calling connect while already authenticated reconnects instead of throwing an illegal state transition`() = runBlocking {
        // Regression: connect() opens with a transition to Connecting, which is
        // illegal directly from Authenticated. The device-management screen calls
        // connect() to reconnect right after an in-session password/username change
        // (state is Authenticated at that point), so this must reconnect cleanly
        // rather than throw.
        val server = ServerSocket(0)
        val loginCount = java.util.concurrent.atomic.AtomicInteger(0)
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 5000
            repeat(2) {
                val client = server.accept()
                val input = client.getInputStream()
                val header = ByteArray(DvripHeader.HEADER_LEN)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) return@async
                    read += n
                }
                loginCount.incrementAndGet()
                val responseFrame = DvripFrame.of(
                    0u, 0u, DvripMessageIds.LOGIN_RESPONSE,
                    DvripPayloads.encodeJson(realLoginResponseJson),
                )
                client.getOutputStream().write(responseFrame.encode())
                client.getOutputStream().flush()
                Thread.sleep(1500)
                client.close()
            }
        }

        val manager = CameraSessionManager("127.0.0.1", server.localPort)
        manager.connect(CameraCredentials("testuser", "hunter2"))
        withTimeout(5000) {
            while (manager.state.value !is ConnectionState.Authenticated) delay(20)
        }

        // Reconnect straight from Authenticated -- must not throw, and must actually
        // send a second login (the old bug threw before opening the socket, so no
        // reconnect happened while the state stayed stuck at the first Authenticated).
        manager.connect(CameraCredentials("testuser", "hunter2"))
        withTimeout(5000) {
            while (loginCount.get() < 2) delay(20)
        }
        withTimeout(5000) {
            while (manager.state.value !is ConnectionState.Authenticated) delay(20)
        }
        assertEquals("a manual connect from Authenticated must re-login", 2, loginCount.get())

        serverJob.cancel()
        manager.shutdown()
        server.close()
    }

    @Test
    fun `a reconnect's login frame restarts the session sequence at 0 even after the shared counter has advanced`() = runBlocking {
        // Regression: the control connection's login draws from the session-shared
        // sequence counter, which keeps climbing for the whole life of the manager
        // (every keepalive/command increments it). A fresh login establishes a NEW
        // session and MUST send seq 0 -- the value the camera accepts (Ret:100).
        // Before the fix, a reconnect after some uptime sent its login with the old
        // session's grown sequence, which the camera rejects with Ret:203.
        val server = ServerSocket(0)
        val loginSequences = mutableListOf<UInt>()
        val serverJob = async(Dispatchers.IO) {
            server.soTimeout = 5000
            repeat(2) {
                val client = server.accept()
                val input = client.getInputStream()
                val header = ByteArray(DvripHeader.HEADER_LEN)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) return@async
                    read += n
                }
                synchronized(loginSequences) { loginSequences.add(DvripHeader.decode(header).sequence) }
                val responseFrame = DvripFrame.of(
                    0u, 0u, DvripMessageIds.LOGIN_RESPONSE,
                    DvripPayloads.encodeJson(realLoginResponseJson),
                )
                client.getOutputStream().write(responseFrame.encode())
                client.getOutputStream().flush()
                Thread.sleep(1500)
                client.close()
            }
        }

        val manager = CameraSessionManager("127.0.0.1", server.localPort)

        manager.connect(CameraCredentials("testuser", "hunter2"))
        withTimeout(5000) {
            while (manager.state.value !is ConnectionState.Authenticated) delay(20)
        }

        // Simulate the counter climbing during the session (keepalives, commands,
        // video/talk claims all draw from this same shared counter over time).
        repeat(2000) { manager.sessionSequence.getAndIncrement() }

        // A fresh connect = a fresh session. Its login must still go out at seq 0.
        manager.disconnect()
        manager.connect(CameraCredentials("testuser", "hunter2"))
        withTimeout(5000) {
            while (loginSequences.size < 2) delay(20)
        }

        assertEquals("both logins must start a new session at sequence 0", listOf(0u, 0u), loginSequences.toList())

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
