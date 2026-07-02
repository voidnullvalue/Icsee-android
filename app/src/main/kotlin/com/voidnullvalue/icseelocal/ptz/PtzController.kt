package com.voidnullvalue.icseelocal.ptz

import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed class PtzIntent {
    data class Move(val command: PtzCommand, val step: Int, val preset: Int = 0) : PtzIntent()
    object Stop : PtzIntent()
}

/**
 * Press-and-hold PTZ driver: one FIFO queue, one consumer coroutine, so
 * movement and stop commands are always serialized and sent in the order
 * they were requested. A queued-but-not-yet-sent movement command is
 * dropped whenever a newer one supersedes it, but a queued Stop is never
 * dropped this way -- Stop always wins and is never silently coalesced
 * away, satisfying "stop before changing direction" even when that means
 * enqueueing Stop immediately followed by a new Move.
 */
class PtzController(
    private val channel: DvripCommandChannel,
    private val sessionId: UInt,
    private val scope: CoroutineScope,
    private val ptzChannelIndex: Int = 0,
) {
    private val mutex = Mutex()
    private val queue = ArrayDeque<PtzIntent>()
    private var consumerRunning = false

    private fun enqueue(vararg newIntents: PtzIntent) {
        scope.launch {
            mutex.withLock {
                for (intent in newIntents) {
                    if (intent is PtzIntent.Move) {
                        queue.removeAll { it is PtzIntent.Move }
                    }
                    queue.addLast(intent)
                }
                if (!consumerRunning) {
                    consumerRunning = true
                    scope.launch { runConsumer() }
                }
            }
        }
    }

    private suspend fun runConsumer() {
        while (true) {
            val next = mutex.withLock {
                if (queue.isEmpty()) {
                    consumerRunning = false
                    null
                } else {
                    queue.removeFirst()
                }
            } ?: break
            sendIntent(next)
        }
    }

    private suspend fun sendIntent(intent: PtzIntent) {
        val json = when (intent) {
            is PtzIntent.Move -> PtzRequestBuilder.build(intent.command, sessionId, channel = ptzChannelIndex, step = intent.step, preset = intent.preset)
            is PtzIntent.Stop -> PtzRequestBuilder.buildCompatibilityStop(sessionId, channel = ptzChannelIndex)
        }
        runCatching { channel.sendJson(DvripMessageIds.PTZ_CONTROL_REQUEST, json) }
    }

    // --- UI-facing intent entry points ---
    fun onPointerDown(command: PtzCommand, step: Int) = enqueue(PtzIntent.Move(command, step))
    fun onPointerUp() = enqueue(PtzIntent.Stop)
    fun onPointerCancel() = enqueue(PtzIntent.Stop)
    fun onDirectionChange(command: PtzCommand, step: Int) = enqueue(PtzIntent.Stop, PtzIntent.Move(command, step))
    fun onForegroundLost() = enqueue(PtzIntent.Stop)
    fun onScreenDisposed() = enqueue(PtzIntent.Stop)

    // --- One-shot commands (no press-and-hold semantics) ---
    fun setPreset(preset: Int) = enqueue(PtzIntent.Move(PtzCommand.SET_PRESET, step = 0, preset = preset))
    fun gotoPreset(preset: Int) = enqueue(PtzIntent.Move(PtzCommand.GOTO_PRESET, step = 0, preset = preset))
    fun clearPreset(preset: Int) = enqueue(PtzIntent.Move(PtzCommand.CLEAR_PRESET, step = 0, preset = preset))
    fun startTour() = enqueue(PtzIntent.Move(PtzCommand.START_TOUR, step = 0))
    fun stopTour() = enqueue(PtzIntent.Move(PtzCommand.STOP_TOUR, step = 0))
}
