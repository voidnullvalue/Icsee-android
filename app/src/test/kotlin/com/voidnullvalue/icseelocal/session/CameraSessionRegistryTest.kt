package com.voidnullvalue.icseelocal.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.ServerSocket

class CameraSessionRegistryTest {

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    // Managers point at a closed port, so any connect fails fast to Failed (there is
    // no auto-reconnect) -- these tests exercise the registry's ref-counting/linger,
    // not the network.
    private fun trackingFactory(
        createdManagers: MutableList<CameraSessionManager>,
        limiters: MutableList<LoginRateLimiter>,
    ): (String, Int, LoginRateLimiter) -> CameraSessionManager = { host, port, limiter ->
        limiters += limiter
        CameraSessionManager(host, port, loginRateLimiter = limiter)
            .also { createdManagers += it }
    }

    private val creds = CameraCredentials("admin", "")

    @Test
    fun `two acquires of the same camera share one manager`() {
        val created = mutableListOf<CameraSessionManager>()
        val registry = CameraSessionRegistry(
            lingerMillis = 100,
            managerFactory = trackingFactory(created, mutableListOf()),
        )
        val port = closedPort()

        val a = registry.acquire("127.0.0.1", port, creds)
        val b = registry.acquire("127.0.0.1", port, creds)

        assertSame("both consumers get the same shared manager", a, b)
        assertEquals("only one manager built for the camera", 1, created.size)
    }

    @Test
    fun `releasing within the linger window keeps the same manager`() = runBlocking {
        val created = mutableListOf<CameraSessionManager>()
        val registry = CameraSessionRegistry(
            lingerMillis = 300,
            managerFactory = trackingFactory(created, mutableListOf()),
        )
        val port = closedPort()

        val first = registry.acquire("127.0.0.1", port, creds)
        registry.release("127.0.0.1", port) // refcount -> 0, teardown scheduled

        // Come back before the linger elapses -- the scheduled teardown must be cancelled.
        delay(100)
        val second = registry.acquire("127.0.0.1", port, creds)

        // And confirm it is NOT torn down after the original window would have passed.
        delay(400)
        assertSame("re-acquire within linger reuses the live manager", first, second)
        assertEquals("no second manager was built", 1, created.size)
    }

    @Test
    fun `after the linger window the manager is torn down and the next acquire rebuilds`() = runBlocking {
        val created = mutableListOf<CameraSessionManager>()
        val registry = CameraSessionRegistry(
            lingerMillis = 150,
            managerFactory = trackingFactory(created, mutableListOf()),
        )
        val port = closedPort()

        val first = registry.acquire("127.0.0.1", port, creds)
        registry.release("127.0.0.1", port)
        delay(400) // let the linger elapse and teardown run

        val second = registry.acquire("127.0.0.1", port, creds)
        assertNotSame("a fully torn-down camera is rebuilt fresh", first, second)
        assertEquals(2, created.size)
    }

    @Test
    fun `the rate limiter is shared across manager rebuilds for a camera`() = runBlocking {
        val limiters = mutableListOf<LoginRateLimiter>()
        val registry = CameraSessionRegistry(
            lingerMillis = 100,
            managerFactory = trackingFactory(mutableListOf(), limiters),
        )
        val port = closedPort()

        registry.acquire("127.0.0.1", port, creds)
        registry.release("127.0.0.1", port)
        delay(300) // tear down
        registry.acquire("127.0.0.1", port, creds) // rebuild

        assertEquals(2, limiters.size)
        assertSame("the Ret:205 budget must survive the manager being rebuilt", limiters[0], limiters[1])
    }
}
