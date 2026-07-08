package com.voidnullvalue.icseelocal.audio

import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatDetectorTest {

    private fun quietChunk(size: Int = 320, amplitude: Int = 50, seed: Long = 1): ShortArray {
        val rnd = Random(seed)
        return ShortArray(size) { (rnd.nextInt(-amplitude, amplitude)).toShort() }
    }

    private fun loudChunk(size: Int = 320, amplitude: Int = 20000, seed: Long = 2): ShortArray {
        val rnd = Random(seed)
        return ShortArray(size) { (rnd.nextInt(-amplitude, amplitude)).toShort() }
    }

    @Test
    fun `does not fire on steady quiet audio`() {
        var now = 0L
        val detector = BeatDetector(clock = { now })
        var anyBeat = false
        repeat(40) {
            if (detector.onChunk(quietChunk(seed = it.toLong()))) anyBeat = true
            now += 40
        }
        assertFalse("steady-level audio shouldn't look like a beat", anyBeat)
    }

    @Test
    fun `fires on a sudden loud spike after quiet history`() {
        var now = 0L
        val detector = BeatDetector(clock = { now })
        // Build up quiet history first.
        repeat(20) {
            detector.onChunk(quietChunk(seed = it.toLong()))
            now += 40
        }
        val fired = detector.onChunk(loudChunk())
        assertTrue("a sudden loud chunk after quiet history should register as a beat", fired)
    }

    @Test
    fun `respects the minimum interval between beats`() {
        var now = 0L
        val detector = BeatDetector(minIntervalMillis = 500, clock = { now })
        repeat(20) {
            detector.onChunk(quietChunk(seed = it.toLong()))
            now += 40
        }
        assertTrue(detector.onChunk(loudChunk(seed = 100)))
        now += 100 // well under the 500ms minimum interval
        assertFalse("a second loud chunk too soon after the last beat shouldn't re-fire", detector.onChunk(loudChunk(seed = 101)))
    }
}
