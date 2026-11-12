package com.wiredtooth.receiver.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order EQ sections -- peaking, low shelf and high shelf --
 * coefficient design and the difference equation that runs them.
 *
 * SOURCE OF THE MATH. The coefficients are the peaking-EQ, lowShelf and
 * highShelf cases of the "Cookbook formulae for audio equalizer biquad
 * filter coefficients" by Robert Bristow-Johnson, the standard public
 * reference for this filter family. The formulas are written out below and
 * implemented directly; no code was taken from any implementation of them.
 *
 * WHICH BANDWIDTH PARAMETER. The cookbook offers three interchangeable ways
 * to set a section's width -- Q, bandwidth in octaves, and for shelves a
 * slope S -- and says only that S is the one usually wanted for shelving
 * filters. We use the Q case for all three shapes, which keeps one meaning
 * of [q] across the whole engine, and it is also the case that parametric
 * files in the wild are written against: the files carry a "Q" column for
 * shelf rows too. The two are the same filter at
 *
 *     1 / Q^2 = (A + 1/A) * (1/S - 1) + 2
 *
 * so a shelf written with Q 0.7 at a few dB of gain is a slope of about
 * 0.98, just inside the S <= 1 region where a shelf does not ripple.
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

        normalise(coef, offset, b0, b1, b2, a0, a1, a2)
    }

    /**
     * The shelf cases, which share everything except three signs.
     *
     *     A     = 10^(dBgain / 40)
     *     w0    = 2 * pi * f0 / Fs
     *     alpha = sin(w0) / (2 * Q)
     *     beta  = 2 * sqrt(A) * alpha
     *
     *     lowShelf                            highShelf
     *     b0 =    A*((A+1) - (A-1)*cos + beta)   A*((A+1) + (A-1)*cos + beta)
     *     b1 =  2*A*((A-1) - (A+1)*cos)         -2*A*((A-1) + (A+1)*cos)
     *     b2 =    A*((A+1) - (A-1)*cos - beta)   A*((A+1) + (A-1)*cos - beta)
     *     a0 =       (A+1) + (A-1)*cos + beta       (A+1) - (A-1)*cos + beta
     *     a1 =    -2*((A-1) + (A+1)*cos)          2*((A-1) - (A+1)*cos)
     *     a2 =       (A+1) + (A-1)*cos - beta       (A+1) - (A-1)*cos - beta
     *
     * WHY THE CORNER IS AT HALF GAIN, NOT FULL. A shelf's Fc is the midpoint
     * of the transition, so a -3.2 dB shelf reads -1.6 dB at its own Fc and
     * only reaches -3.2 dB well into the plateau. That is the cookbook's
     * definition and the one parametric files are written against, so it is
     * also what the tests assert. Reading Fc as the point where the full gain
     * is reached is the classic way to implement a shelf that is half a
     * transition out of position.
     */
    fun designLowShelf(
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
        val beta = 2.0 * sqrt(a) * (sin(w0) / (2.0 * qq))

        val ap1 = a + 1.0
        val am1 = a - 1.0

        val b0 = a * (ap1 - am1 * cosW0 + beta)
        val b1 = 2.0 * a * (am1 - ap1 * cosW0)
        val b2 = a * (ap1 - am1 * cosW0 - beta)
        val a0 = ap1 + am1 * cosW0 + beta
        val a1 = -2.0 * (am1 + ap1 * cosW0)
        val a2 = ap1 + am1 * cosW0 - beta

        normalise(coef, offset, b0, b1, b2, a0, a1, a2)
    }

    /** The high-shelf case. See [designLowShelf] for the formulas. */
    fun designHighShelf(
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
        val beta = 2.0 * sqrt(a) * (sin(w0) / (2.0 * qq))

        val ap1 = a + 1.0
        val am1 = a - 1.0

        val b0 = a * (ap1 + am1 * cosW0 + beta)
        val b1 = -2.0 * a * (am1 + ap1 * cosW0)
        val b2 = a * (ap1 + am1 * cosW0 - beta)
        val a0 = ap1 - am1 * cosW0 + beta
        val a1 = 2.0 * (am1 - ap1 * cosW0)
        val a2 = ap1 - am1 * cosW0 - beta

        normalise(coef, offset, b0, b1, b2, a0, a1, a2)
    }

    /**
     * Designs whichever shape [type] names.
     *
     * The one place the three cases are chosen between, so the preamp scan,
     * the on-screen curve and the render path cannot end up disagreeing
     * about what a band is.
     */
    fun design(
        coef: DoubleArray,
        offset: Int,
        type: BandType,
        frequencyHz: Double,
        q: Double,
        gainDb: Double,
        sampleRate: Double,
    ) = when (type) {
        BandType.Peaking -> designPeaking(coef, offset, frequencyHz, q, gainDb, sampleRate)
        BandType.LowShelf -> designLowShelf(coef, offset, frequencyHz, q, gainDb, sampleRate)
        BandType.HighShelf -> designHighShelf(coef, offset, frequencyHz, q, gainDb, sampleRate)
    }

    /**
     * Divides through by a0 so the difference equation never has to.
     *
     * a0 is a sum of positive terms for every shape here, so it cannot be
     * zero for any finite input: A > 0, and beta > 0 once the frequency and Q
     * clamps above have been applied.
     */
    private fun normalise(
        coef: DoubleArray,
        offset: Int,
        b0: Double,
        b1: Double,
        b2: Double,
        a0: Double,
        a1: Double,
        a2: Double,
    ) {
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
