package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One keepalive task per session (message 1006, per the confirmed
 * `AliveInterval` from the login response), sent slightly before
 * expiration. Cancelled by [stop] when the session closes; never touches
 * the transport after that -- [stop] cancels the coroutine before any
 * further `send()` call can start.
 */
class KeepaliveTask(
    private val channel: DvripCommandChannel,
    private val aliveIntervalSeconds: Int,
    private val sessionIdHex: String,
    private val marginMillis: Long = 3000,
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            val intervalMillis = aliveIntervalSeconds * 1000L
            val delayMillis = (intervalMillis - marginMillis).coerceAtLeast(1000L)
            while (isActive) {
                delay(delayMillis)
                if (!isActive) break
                try {
                    channel.sendJson(
                        DvripMessageIds.KEEPALIVE_REQUEST,
                        """{"Name":"KeepAlive","SessionID":"$sessionIdHex"}""",
                    )
                } catch (e: Exception) {
                    onFailure(e)
                    break
                }
            }
        }
    }

    /** Idempotent. Safe to call even if never started. */
    fun stop() {
        job?.cancel()
        job = null
    }

    val isRunning: Boolean get() = job?.isActive == true
}
