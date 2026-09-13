package com.wiredtooth.receiver

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Holds the jitter buffer at a target depth by nudging playback speed.
 *
 * Direct port of NetworkTest/DriftController.cs. Same algorithm, same 60 ms
 * target, same +/-0.5% clamp, on purpose: two receivers that correct drift
 * differently would be two different products, and any measurement taken on
 * one would say nothing about the other.
 *
 * The sender's sound card and this phone's are never at exactly the same rate.
 * A few parts per million sounds like nothing, but at 48 kHz a 20 ppm
 * difference is about one sample per second -- roughly 3.6 seconds of
 * accumulated error per hour. The buffer either drains to zero and clicks, or
 * grows until latency is unusable. It looks perfect for two minutes and fails
 * at twenty.
 */
class DriftController(private val targetMs: Double) {

    companion object {
        /**
         * Maximum speed deviation, +/-0.5%. About 8.7 cents of pitch, below
         * what anyone notices on programme material and an order of magnitude
         * larger than any real clock difference, so the clamp should only
         * engage while recovering from a startup transient.
         */
        const val MAX_DEVIATION = 0.005

        // An error of 100 ms commands the full +/-0.5%, so normal drift of a
        // few ms commands a correspondingly tiny fraction and the loop stays
        // firmly in its linear region.
        private const val KP = MAX_DEVIATION / 100.0

        // Applied once per 100 ms tick, so about a second to move most of the
        // way to a new commanded ratio. Slow enough that a burst of packets
        // does not produce a step change in pitch.
        private const val SMOOTHING = 0.1
    }

    /**
     * Current speed multiplier. 1.0 is unmodified; above 1.0 the receiver
     * consumes its input faster than real time, which drains the buffer.
     */
    @Volatile
    var ratio: Double = 1.0
        private set

    /** Call every 100 ms with the measured buffer depth. */
    fun update(bufferMs: Double): Double {
        val error = bufferMs - targetMs
        val commanded = (1.0 + KP * error).coerceIn(
            1.0 - MAX_DEVIATION,
            1.0 + MAX_DEVIATION,
        )
        // No integral term, deliberately. The steady-state offset a
        // proportional controller settles at IS the clock difference -- it is
        // the loop saying "run 12 ppm fast, forever". An integrator would
        // chase that to zero, wind up, and oscillate, which is audible as a
        // slow pitch waver.
        ratio += (commanded - ratio) * SMOOTHING
        return ratio
    }
}

/**
 * Interleaved PCM16 resampler for a continuously varying, near-unity ratio.
 *
 * Port of NetworkTest/LinearResampler.cs. Linear interpolation is chosen for
 * the same reason as on Windows: the ratio never leaves +/-0.5% of 1.0, so
 * every output sample falls between two input samples that are essentially
 * the same point in the waveform, and the interpolation error sits far below
 * the 16-bit noise floor. A windowed-sinc resampler would be better for real
 * rate conversion and pointless here.
 *
 * The state that matters is phase continuity. Fractional position and the
 * final frame of each block carry across calls, so a packet boundary is not a
 * discontinuity. Resetting per packet would click 140 times a second.
 */
class LinearResampler(private val channels: Int) {

    private val prev = ShortArray(channels)
    private var pos = 0.0

    companion object {
        /** Worst-case output frames, so callers size a buffer once. */
        fun maxOutputFrames(inputFrames: Int): Int =
            (inputFrames / (1.0 - DriftController.MAX_DEVIATION)).toInt() + 4
    }

    /**
     * Consumes one whole block and returns the number of frames written.
     * [speed] above 1.0 produces FEWER output frames than input frames, which
     * is what drains a too-full buffer.
     */
    fun resample(input: ShortArray, inputFrames: Int, speed: Double, output: ShortArray): Int {
        if (inputFrames == 0) return 0

        var produced = 0
        val maxOut = output.size / channels

        while (produced < maxOut) {
            val p = pos + produced * speed
            val i = floor(p).toInt()

            // Need i and i+1 to both exist. i == -1 is served from the
            // previous block's final frame, which is why it is kept.
            if (i + 1 > inputFrames - 1) break
            if (i < -1) break

            val frac = (p - i).toFloat()
            for (c in 0 until channels) {
                val a = if (i < 0) prev[c].toFloat() else input[i * channels + c].toFloat()
                val b = input[(i + 1) * channels + c].toFloat()
                val v = a + (b - a) * frac
                output[produced * channels + c] =
                    v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
            }
            produced++
        }

        // Rebase the phase onto the next block; everything before frame
        // `inputFrames` has now been consumed.
        pos = pos + produced * speed - inputFrames
        if (pos < -1.0) pos = -1.0

        for (c in 0 until channels) prev[c] = input[(inputFrames - 1) * channels + c]

        return produced
    }

    /**
     * Drop carried state. Used after concealment or a silence gap, where the
     * previous frame is no longer adjacent in time to what follows and
     * interpolating across the join would smear unrelated audio together.
     */
    fun reset() {
        java.util.Arrays.fill(prev, 0)
        pos = 0.0
    }
}
