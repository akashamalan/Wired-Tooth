package com.wiredtooth.receiver.dsp

import kotlin.math.abs
import kotlin.math.pow

/**
 * The 50 ms crossfade between the original and the equalized signal.
 *
 * WHY LINEAR AND NOT EQUAL-POWER. Equal-power (cos/sin) is the right law for
 * crossfading two UNRELATED signals, where the two are uncorrelated and their
 * POWERS add: at the midpoint each is down 3 dB, the powers sum back to one,
 * and the level holds. These two signals are not unrelated. They are the same
 * source, one of them filtered, so they are strongly correlated and their
 * AMPLITUDES add. Under an equal-power law the midpoint would be
 * 0.707 + 0.707 = 1.414 of the original amplitude -- a +3 dB bump in the
 * middle of every single toggle, which is exactly the loudness artefact the
 * whole feature exists to eliminate.
 *
 * Linear (equal-gain) is the correct law here: a + (1 - a) = 1 for every a,
 * so with a flat curve, where the filtered signal is identical to the
 * original, the crossfade is mathematically a no-op. That is the property
 * [CompareFaderTest] pins down.
 *
 * WHY THE ENGINE KEEPS RUNNING. This class only ever moves a blend
 * coefficient. It never starts or stops the equalizer, because an IIR filter
 * that is switched off and on again has to refill its own state, and that
 * refill is an audible swell on every toggle -- a filter settling, not a
 * level change. Leaving the engine running means the only thing a toggle
 * changes is one multiply per sample.
 */
internal class CompareFader {

    /** Milliseconds for a full travel between the two sides. */
    companion object {
        const val FADE_MS = 50.0
    }

    private var stepPerFrame = 1.0f

    /** 0 = the original signal, 1 = the equalized signal. */
    private var current = 1.0f

    /** Where the fade is heading. Written by the UI thread. */
    @Volatile
    var target = 1.0f
        private set

    /** The dry-side attenuation, as a linear gain, smoothed on the same ramp. */
    private var dryGainCurrent = 1.0f

    @Volatile
    private var dryGainTarget = 1.0f

    fun configure(sampleRate: Int) {
        val frames = (FADE_MS / 1000.0) * sampleRate
        stepPerFrame = if (frames <= 1.0) 1.0f else (1.0 / frames).toFloat()
    }

    /**
     * Chooses a side and the dry trim that goes with it.
     *
     * [dryTrimDb] is applied to the original side only, and is expected to be
     * zero or negative; a positive value would be adding gain to make a
     * comparison come out a particular way, which is never done here.
     */
    fun request(showEqualized: Boolean, dryTrimDb: Float) {
        target = if (showEqualized) 1.0f else 0.0f
        dryGainTarget = 10.0.pow(dryTrimDb.coerceAtMost(0f).toDouble() / 20.0).toFloat()
    }

    /** Back to the equalized side at full dry level, for leaving Compare mode. */
    fun release() {
        target = 1.0f
        dryGainTarget = 1.0f
    }

    /**
     * True once the fade has fully arrived at the equalized side with no dry
     * trim left, which is the only moment the compare path can be handed back
     * to the ordinary one without a discontinuity. At that point this class
     * outputs the filtered signal unaltered, so the handover is bit-identical.
     */
    fun atRest(): Boolean =
        current >= 1.0f && abs(dryGainCurrent - 1.0f) < 1e-6f

    fun snapToEqualized() {
        current = 1.0f
        dryGainCurrent = 1.0f
    }

    /**
     * Writes `fade * wet + (1 - fade) * dry * dryGain` into [wet], advancing
     * the ramp once per FRAME so that every channel of a frame shares one
     * coefficient. Fading the channels of a stereo frame by different amounts
     * would move the stereo image during the fade.
     */
    fun blend(dry: FloatArray, wet: FloatArray, frames: Int, channels: Int) {
        var f = 0
        while (f < frames) {
            current = approach(current, target, stepPerFrame)
            dryGainCurrent = approach(dryGainCurrent, dryGainTarget, stepPerFrame)

            val a = current
            val b = (1.0f - a) * dryGainCurrent
            val base = f * channels
            var c = 0
            while (c < channels) {
                val i = base + c
                wet[i] = a * wet[i] + b * dry[i]
                c++
            }
            f++
        }
    }

    private fun approach(value: Float, goal: Float, step: Float): Float {
        val delta = goal - value
        if (abs(delta) <= step) return goal
        return value + if (delta > 0f) step else -step
    }
}
