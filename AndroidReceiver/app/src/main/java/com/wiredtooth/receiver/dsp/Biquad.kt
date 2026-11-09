package com.wiredtooth.receiver.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Second-order peaking-EQ section: coefficient design and the difference
 * equation that runs it.
 *
 * SOURCE OF THE MATH. The coefficients are the peaking-EQ case of the
 * "Cookbook formulae for audio equalizer biquad filter coefficients" by
 * Robert Bristow-Johnson, the standard public reference for this filter
 * family. The formulas are written out below and implemented directly; no
 * code was taken from any implementation of them.
 *
 *     A     = 10^(dBgain / 40)
 *     w0    = 2 * pi * f0 / Fs
 *     alpha = sin(w0) / (2 * Q)
 *
 *     b0 =  1 + alpha * A
 *     b1 = -2 * cos(w0)
 *     b2 =  1 - alpha * A
 *     a0 =  1 + alpha / A
 *     a1 = -2 * cos(w0)
 *     a2 =  1 - alpha / A
 *
 * Every coefficient is then divided by a0, so the difference equation does not
 * have to divide per sample.
 *
 * WHY THIS FILTER IS ZERO LATENCY. The output depends on the current input
 * through b0, with no delay line in front of it. y[n] is available the moment
 * x[n] arrives, so the section adds exactly zero samples. That is the whole
 * reason this is an IIR cookbook filter and not a linear-phase FIR: an FIR
 * with a usable low-frequency response would cost tens of milliseconds, which
 * is most of the latency budget this project exists to protect.
 *
 * WHY A 0 dB BAND IS EXACTLY TRANSPARENT. At gain 0, A = 1, so b0 = a0,
 * b1 = a1 and b2 = a2. After normalisation the transfer function is 1. A flat
 * curve therefore passes the signal through unchanged by construction rather
 * than by approximation, and an untouched band costs nothing in accuracy.
 */
internal object Biquad {

    /** b0, b1, b2, a1, a2 after normalising by a0. */
    const val COEFFS = 5

    /** The identity section: output equals input. */
    fun setIdentity(coef: DoubleArray, offset: Int) {
        coef[offset] = 1.0
        coef[offset + 1] = 0.0
        coef[offset + 2] = 0.0
        coef[offset + 3] = 0.0
        coef[offset + 4] = 0.0
    }

    /**
     * Designs one peaking section into [coef] at [offset].
     *
     * The frequency is clamped below Nyquist because the cookbook formulas
     * assume w0 < pi; past that, sin(w0) turns over and the filter stops being
     * a peak at all. Q is clamped away from zero because alpha divides by it.
     */
    fun designPeaking(
        coef: DoubleArray,
        offset: Int,
        frequencyHz: Double,
        q: Double,
        gainDb: Double,
        sampleRate: Double,
    ) {
        if (gainDb == 0.0) {
            setIdentity(coef, offset)
            return
        }

        val nyquist = sampleRate * 0.5
        val f0 = frequencyHz.coerceIn(10.0, nyquist * 0.95)
        val qq = q.coerceIn(0.1, 18.0)

        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * qq)

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW0
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha / a

        val inv = 1.0 / a0
        coef[offset] = b0 * inv
        coef[offset + 1] = b1 * inv
        coef[offset + 2] = b2 * inv
        coef[offset + 3] = a1 * inv
        coef[offset + 4] = a2 * inv
    }

    /**
     * One sample through one section, transposed direct form II.
     *
     *     y  = b0*x + s1
     *     s1 = b1*x - a1*y + s2
     *     s2 = b2*x - a2*y
     *
     * Transposed direct form II rather than direct form I because it needs two
     * state words instead of four and, more importantly, because its state
     * holds filtered quantities rather than raw input history, which keeps the
     * numbers well scaled at the low end. The 31 Hz band at 48 kHz sits at a
     * normalised frequency near 0.004, where a direct-form-I float biquad
     * loses meaningful precision. State is held in Double for the same reason:
     * doubling the state memory costs nothing here and removes the question
     * entirely.
     */
    fun processSample(
        x: Double,
        coef: DoubleArray,
        c: Int,
        state: DoubleArray,
        s: Int,
    ): Double {
        val y = coef[c] * x + state[s]
        state[s] = coef[c + 1] * x - coef[c + 3] * y + state[s + 1]
        state[s + 1] = coef[c + 2] * x - coef[c + 4] * y
        return y
    }
}
