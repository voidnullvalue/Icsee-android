package com.voidnullvalue.icseelocal.ptz

import org.junit.Assert.assertEquals
import org.junit.Test

class PtzCommandInversionTest {
    @Test
    fun `all movement directions invert across both axes`() {
        assertEquals(PtzCommand.DIRECTION_DOWN, PtzCommand.DIRECTION_UP.invertedDirection())
        assertEquals(PtzCommand.DIRECTION_UP, PtzCommand.DIRECTION_DOWN.invertedDirection())
        assertEquals(PtzCommand.DIRECTION_RIGHT, PtzCommand.DIRECTION_LEFT.invertedDirection())
        assertEquals(PtzCommand.DIRECTION_LEFT, PtzCommand.DIRECTION_RIGHT.invertedDirection())
        assertEquals(PtzCommand.DIRECTION_RIGHT_DOWN, PtzCommand.DIRECTION_LEFT_UP.invertedDirection())
        assertEquals(PtzCommand.DIRECTION_RIGHT_UP, PtzCommand.DIRECTION_LEFT_DOWN.invertedDirection())
        assertEquals(PtzCommand.DIRECTION_LEFT_DOWN, PtzCommand.DIRECTION_RIGHT_UP.invertedDirection())
        assertEquals(PtzCommand.DIRECTION_LEFT_UP, PtzCommand.DIRECTION_RIGHT_DOWN.invertedDirection())
    }

    @Test
    fun `non-direction PTZ commands are unchanged`() {
        val unchanged = listOf(
            PtzCommand.ZOOM_TILE,
            PtzCommand.ZOOM_WIDE,
            PtzCommand.FOCUS_NEAR,
            PtzCommand.FOCUS_FAR,
            PtzCommand.IRIS_SMALL,
            PtzCommand.IRIS_LARGE,
            PtzCommand.SET_PRESET,
            PtzCommand.GOTO_PRESET,
            PtzCommand.CLEAR_PRESET,
            PtzCommand.START_TOUR,
            PtzCommand.STOP_TOUR,
        )

        unchanged.forEach { command ->
            assertEquals(command, command.invertedDirection())
        }
    }
}
