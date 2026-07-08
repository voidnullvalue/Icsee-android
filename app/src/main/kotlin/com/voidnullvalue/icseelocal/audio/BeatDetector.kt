package com.voidnullvalue.icseelocal.audio

/**
 * Simple energy-based beat detector: flags a chunk as a beat onset when its
 * instantaneous energy spikes well above the local rolling average energy --
 * the classic "sound energy" approach (compare current short-term energy to
 * recent history), not anything novel or proprietary. Pure numeric signal
 * analysis on raw PCM sample values; no audio content is inspected, stored,
 * or reproduced, just sums of squares.
 */
class BeatDetector(
    private val historySize: Int = 32,
    private val energyThresholdMultiplier: Double = 1.4,
    private val minIntervalMillis: Long = 220,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val historyEnergies = ArrayDeque<Double>()
    private var lastBeatAt = 0L

    /** Feed one PCM chunk; returns true if this chunk should be treated as a beat onset. */
    fun onChunk(pcm: ShortArray): Boolean {
        val instantEnergy = pcm.sumOf { it.toDouble() * it.toDouble() }
        val now = clock()

        // Require some history before ever firing, so the first couple of chunks
        // (near-silent local average) can't trivially look like a huge spike.
        val isBeat = historyEnergies.size >= historySize / 2 &&
            instantEnergy > historyEnergies.average() * energyThresholdMultiplier &&
            (now - lastBeatAt) >= minIntervalMillis

        historyEnergies.addLast(instantEnergy)
        if (historyEnergies.size > historySize) historyEnergies.removeFirst()

        if (isBeat) lastBeatAt = now
        return isBeat
    }
}
