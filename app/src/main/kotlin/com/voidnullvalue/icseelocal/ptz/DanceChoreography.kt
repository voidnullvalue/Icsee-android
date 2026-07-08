package com.voidnullvalue.icseelocal.ptz

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Drives a looping PTZ movement pattern via [PtzController], advancing one step
 * per detected beat (see [com.voidnullvalue.icseelocal.audio.BeatDetector]/
 * [com.voidnullvalue.icseelocal.audio.PlaybackCaptureAudioSource.beats]) rather
 * than a fixed timer -- so the camera's movement actually lines up with
 * whatever's playing instead of drifting against it. Intentionally reuses the
 * same press/hold direction commands the live joystick uses (via
 * [PtzController.onDirectionChange]/[PtzController.onPointerUp]), not a new
 * command type -- the "dance" is just the existing PTZ control driven by beats
 * instead of a finger.
 */
class DanceChoreography(private val ptz: PtzController) {
    private var job: Job? = null

    fun start(scope: CoroutineScope, beats: Flow<Unit>, moves: List<PtzCommand?> = DEFAULT_MOVES) {
        stop()
        var index = 0
        job = beats.onEach {
            val command = moves[index % moves.size]
            if (command != null) ptz.onDirectionChange(command, STEP) else ptz.onPointerUp()
            index++
        }.launchIn(scope)
    }

    fun stop() {
        job?.cancel()
        job = null
        ptz.onPointerUp()
    }

    val isActive: Boolean get() = job?.isActive == true

    companion object {
        private const val STEP = 6

        /** One move per beat: right, left, up, down, then a quick left-right shimmy, repeats. */
        val DEFAULT_MOVES: List<PtzCommand?> = listOf(
            PtzCommand.DIRECTION_RIGHT,
            PtzCommand.DIRECTION_LEFT,
            PtzCommand.DIRECTION_UP,
            PtzCommand.DIRECTION_DOWN,
            PtzCommand.DIRECTION_RIGHT,
            PtzCommand.DIRECTION_LEFT,
            null,
        )
    }
}
