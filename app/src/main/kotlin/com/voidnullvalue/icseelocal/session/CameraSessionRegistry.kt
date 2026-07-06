package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-scoped owner of exactly one [CameraSessionManager] per camera (keyed by
 * `host:port`), reference-counted across every screen that needs it. This exists
 * because the two session-consuming ViewModels are Activity-scoped and each used
 * to build their *own* manager -- so opening live view and then device management
 * for the same camera meant two independent DVRIP logins/sessions to one device,
 * and navigating back and forth re-authenticated every time. This camera's
 * firmware punishes login *rate* (Ret:205), so the app must instead keep a single
 * shared session and log in as rarely as possible.
 *
 * Three levers, all here so no individual screen can defeat them:
 *  - **Sharing** ([acquire]/[release]): all consumers of one camera share one
 *    session; a second acquirer of an already-live camera costs zero logins.
 *  - **Linger** ([lingerMillis]): the last [release] does not disconnect
 *    immediately -- it schedules teardown after a grace window, cancelled if
 *    anyone re-acquires. So a quick lock/unlock, or bouncing between screens,
 *    reuses the live socket instead of re-logging-in.
 *  - **Rate ceiling** ([LoginRateLimiter]): one persistent limiter per camera,
 *    injected into every manager built for it, so the Ret:205 budget survives
 *    manager teardown/rebuild and spans all callers.
 *
 * Not tied to any Android lifecycle itself; the ViewModels drive acquire/release
 * from their focus/foreground transitions.
 */
class CameraSessionRegistry(
    private val lingerMillis: Long = 25_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val managerFactory: (host: String, port: Int, limiter: LoginRateLimiter) -> CameraSessionManager =
        { host, port, limiter -> CameraSessionManager(host, port, loginRateLimiter = limiter) },
) {
    private class Entry(
        val manager: CameraSessionManager,
        var refCount: Int = 0,
        var teardownJob: Job? = null,
        var credentials: CameraCredentials,
    )

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    // Persists for the life of the registry, independent of Entry lifecycle, so a
    // camera's login budget is not reset by its session being torn down and rebuilt.
    private val rateLimiters = HashMap<String, LoginRateLimiter>()

    private fun key(host: String, port: Int) = "$host:$port"

    /**
     * Returns the shared manager for this camera, connecting it if it isn't already
     * up, and registers one more consumer. Reusing an already-Connecting/Authenticated
     * session performs no new login. A session sitting in [ConnectionState.Failed]
     * (auth rejection or a tripped rate limit) is deliberately left alone -- it is not
     * silently re-hammered here; the user reconnects explicitly via [reconnect].
     */
    fun acquire(host: String, port: Int, credentials: CameraCredentials): CameraSessionManager = synchronized(lock) {
        val k = key(host, port)
        val entry = entries.getOrPut(k) {
            val limiter = rateLimiters.getOrPut(k) { LoginRateLimiter() }
            Entry(managerFactory(host, port, limiter), credentials = credentials)
        }
        entry.teardownJob?.cancel()
        entry.teardownJob = null
        entry.refCount++
        entry.credentials = credentials
        if (entry.manager.state.value is ConnectionState.Disconnected) {
            entry.manager.connect(credentials)
        }
        entry.manager
    }

    /**
     * Registers that one consumer no longer needs this camera. When the last one
     * releases, teardown is scheduled after [lingerMillis] rather than run
     * immediately, so a re-[acquire] within the grace window keeps the live session.
     */
    fun release(host: String, port: Int) = synchronized(lock) {
        val k = key(host, port)
        val entry = entries[k] ?: return
        if (entry.refCount > 0) entry.refCount--
        if (entry.refCount == 0 && entry.teardownJob == null) {
            entry.teardownJob = scope.launch {
                delay(lingerMillis)
                synchronized(lock) {
                    val current = entries[k] ?: return@synchronized
                    if (current === entry && current.refCount == 0) {
                        current.manager.shutdown()
                        entries.remove(k)
                    }
                }
            }
        }
    }

    /**
     * Explicit, user-driven (re)connect for a currently-held camera -- the Reconnect
     * button, or re-authenticating under changed credentials. Unlike [acquire] this
     * will connect even from a Failed state, because the user asked for it. No-op if
     * the camera isn't currently held by anyone.
     */
    fun reconnect(host: String, port: Int, credentials: CameraCredentials) = synchronized(lock) {
        val entry = entries[key(host, port)] ?: return
        entry.credentials = credentials
        entry.manager.connect(credentials)
    }
}
