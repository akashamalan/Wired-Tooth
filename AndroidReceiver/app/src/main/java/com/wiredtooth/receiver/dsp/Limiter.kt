package com.wiredtooth.receiver.dsp

import kotlin.math.abs
import kotlin.math.exp

/**
 * Smooth peak limiter: the last thing between a boosted curve and the output.
 *
 * It computes the gain that would bring the current peak back to the
 * threshold, then moves towards that gain through a one-pole smoother rather
 * than applying it at once. Attack is fast so a loud passage is caught
 * quickly; release is slow so the level does not pump audibly between
 * syllables.
 *
 * WHAT THIS CANNOT DO, AND WHY. A limiter that never overshoots has to see
 * the transient before it arrives, which means a look-ahead delay. Look-ahead
 * is latency, and latency is the one thing this project will not spend, so
 * there is none here. The consequence is honest and bounded: a sharp attack
 * can exceed the threshold for roughly the attack time, about a millisecond,
 * before the gain catches up. The hard clamp downstream is what guarantees
 * the sample never leaves [-1, 1]; this stage is what stops that clamp from
 * being reached often enough to hear.
 *
 * Both channels share one gain. Limiting them separately would pull the
 * stereo image towards whichever side happens to be louder.
 */
internal class Limiter {

    /**
     * Just under full scale. The gap leaves the clamp something to do only in
     * the overshoot window rather than on every loud sample.
     */
    private val threshold = 0.995

    private var attackCoef = 0.0
    private var releaseCoef = 0.0
    private var gain = 1.0

    /** Highest gain reduction applied since the last [resetMetering], 1.0 = none. */
    var minGainSeen = 1.0
        private set

    fun configure(sampleRate: Int) {
        // One-pole coefficients: a step reaches 63% of its target in tau.
        attackCoef = coefFor(0.001, sampleRate)     // 1 ms
        releaseCoef = coefFor(0.120, sampleRate)    // 120 ms
        reset()
    }

    private fun coefFor(tauSeconds: Double, sampleRate: Int): Double =
        1.0 - exp(-1.0 / (tauSeconds * sampleRate))

    fun reset() {
        gain = 1.0
        minGainSeen = 1.0
    }

    fun resetMetering() {
        minGainSeen = 1.0
    }

    /**
     * Applies limiting to one frame, in place.
     *
     * [samples] holds interleaved frames; this call handles the [channels]
     * samples starting at [base].
     */
    fun processFrame(samples: FloatArray, base: Int, channels: Int) {
        var peak = 0.0
        for (c in 0 until channels) {
            val v = abs(samples[base + c].toDouble())
            if (v > peak) peak = v
        }

        val desired = if (peak > threshold) threshold / peak else 1.0
        val coef = if (desired < gain) attackCoef else releaseCoef
        gain += (desired - gain) * coef

        if (gain < minGainSeen) minGainSeen = gain

        if (gain < 1.0) {
            for (c in 0 until channels) {
                samples[base + c] = (samples[base + c] * gain).toFloat()
            }
        }
    }
}
