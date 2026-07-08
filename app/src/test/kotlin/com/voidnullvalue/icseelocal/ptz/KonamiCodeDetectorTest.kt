package com.voidnullvalue.icseelocal.ptz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KonamiCodeDetectorTest {

    private val sequence = listOf(
        PtzCommand.DIRECTION_UP, PtzCommand.DIRECTION_UP,
        PtzCommand.DIRECTION_DOWN, PtzCommand.DIRECTION_DOWN,
        PtzCommand.DIRECTION_LEFT, PtzCommand.DIRECTION_RIGHT,
        PtzCommand.DIRECTION_LEFT, PtzCommand.DIRECTION_RIGHT,
    )

    @Test
    fun `fires exactly on the last correct input of the sequence, not before`() {
        var now = 0L
        val detector = KonamiCodeDetector(clock = { now })
        for (cmd in sequence.dropLast(1)) {
            assertFalse("should not fire before the full sequence is entered", detector.onInput(cmd))
            now += 100
        }
        assertTrue("should fire on the final input", detector.onInput(sequence.last()))
    }

    @Test
    fun `does not fire on a partial or wrong sequence`() {
        val detector = KonamiCodeDetector()
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP))
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP))
        assertFalse(detector.onInput(PtzCommand.DIRECTION_LEFT)) // wrong -- should be DOWN
    }

    @Test
    fun `resets after a wrong step and can be retried`() {
        val detector = KonamiCodeDetector()
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP))
        assertFalse(detector.onInput(PtzCommand.DIRECTION_RIGHT)) // wrong, resets to 0
        for (cmd in sequence.dropLast(1)) assertFalse(detector.onInput(cmd))
        assertTrue(detector.onInput(sequence.last()))
    }

    @Test
    fun `a wrong step that happens to equal the first step restarts progress at 1`() {
        val detector = KonamiCodeDetector()
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP))
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP))
        // Wrong (should be DOWN), but it's another UP, so this should count as a fresh restart (progress=1), not 0.
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP))
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP))
        for (cmd in sequence.drop(2).dropLast(1)) assertFalse(detector.onInput(cmd))
        assertTrue(detector.onInput(sequence.last()))
    }

    @Test
    fun `resets after a timeout between steps`() {
        var now = 0L
        val detector = KonamiCodeDetector(timeoutMillis = 1000, clock = { now })
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP))
        now = 2000 // too slow
        assertFalse(detector.onInput(PtzCommand.DIRECTION_UP)) // treated as a fresh restart (still step 0 == UP)
        for (cmd in sequence.drop(1).dropLast(1)) { now += 10; assertFalse(detector.onInput(cmd)) }
        now += 10
        assertTrue(detector.onInput(sequence.last()))
    }

    @Test
    fun `fires again on a second full entry after firing once`() {
        val detector = KonamiCodeDetector()
        for (cmd in sequence.dropLast(1)) assertFalse(detector.onInput(cmd))
        assertTrue(detector.onInput(sequence.last()))
        for (cmd in sequence.dropLast(1)) assertFalse(detector.onInput(cmd))
        assertTrue(detector.onInput(sequence.last()))
    }
}
