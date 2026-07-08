package com.voidnullvalue.icseelocal.ptz

/**
 * Easter egg unlock: the classic Konami code (Up Up Down Down Left Right Left
 * Right), entered on the PTZ directional pad itself instead of a hidden menu or
 * a tap-the-logo-7-times gimmick -- the input mechanism IS the joystick.
 *
 * Feed every [PtzCommand] the pad's `onPtzDown` sees to [onInput]; a wrong step
 * resets progress back to zero rather than requiring the whole sequence to be
 * retried from a clean slate perfectly, and (via [timeoutMillis]) an overly slow
 * entry (more than [timeoutMillis] between two consecutive correct steps) also
 * resets, so idle panning around while filming doesn't eventually trip it by
 * accident over a long session.
 *
 * [SEQUENCE] is in wire-command terms, NOT visual button-press terms: this
 * camera's Left/Right pan is mirrored relative to the `DirectionLeft`/
 * `DirectionRight` wire names (see `PtzPad`/`dragToPtz` in LiveControlScreen.kt,
 * same swap), so pressing the buttons visually labeled "Left, Right, Left,
 * Right" actually emits `DIRECTION_RIGHT, DIRECTION_LEFT, DIRECTION_RIGHT,
 * DIRECTION_LEFT` -- [SEQUENCE] matches that, or the code wouldn't recognize
 * itself being entered as displayed on screen. Up/Down are not swapped.
 */
class KonamiCodeDetector(
    private val timeoutMillis: Long = 2000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var progress = 0
    private var lastStepAt: Long = 0

    /** Returns true exactly once, on the input that completes the sequence. */
    fun onInput(command: PtzCommand): Boolean {
        val now = clock()
        if (progress > 0 && now - lastStepAt > timeoutMillis) progress = 0

        val expected = SEQUENCE[progress]
        progress = if (command == expected) {
            progress + 1
        } else if (command == SEQUENCE[0]) {
            1 // wrong step, but it happens to be a valid restart (e.g. "up" again)
        } else {
            0
        }
        lastStepAt = now

        if (progress == SEQUENCE.size) {
            progress = 0
            return true
        }
        return false
    }

    fun reset() {
        progress = 0
    }

    companion object {
        val SEQUENCE = listOf(
            PtzCommand.DIRECTION_UP, PtzCommand.DIRECTION_UP,
            PtzCommand.DIRECTION_DOWN, PtzCommand.DIRECTION_DOWN,
            // Swapped relative to the visual Left/Right labels -- see class doc.
            PtzCommand.DIRECTION_RIGHT, PtzCommand.DIRECTION_LEFT,
            PtzCommand.DIRECTION_RIGHT, PtzCommand.DIRECTION_LEFT,
        )
    }
}
