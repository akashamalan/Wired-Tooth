package com.wiredtooth.receiver.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The combined magnitude response of the band cascade, and the preamp that
 * flattens its highest point back to unity.
 *
 * WHY THE LARGEST BAND IS NOT THE ANSWER. The first version of auto-preamp
 * subtracted the largest single boost. That is right only when the bands do
 * not interact. At the default Q of 1.41 a band is a little under an octave
 * wide, and the band centres are one octave apart, so neighbours overlap:
 * two adjacent bands at +12 dB produce a peak between them that is higher
 * than either. Subtracting 12 dB left the excess for the limiter to absorb,
 * which is the one job the limiter should not have to do, because a
 * no-look-ahead limiter can only react after the fact.
 *
 * So the response is measured instead of guessed. A biquad's magnitude at a
 * given frequency follows directly from its coefficients, the cascade's
 * magnitude is the product of the sections, and the preamp is the negative
 * of the highest point found.
 *
 * WHERE THIS RUNS. Never on the audio thread. It is a few thousand sines and
 * cosines, which is nothing on a UI thread reacting to a slider and far too
 * much inside a 10 ms render quantum. [EqSettings] computes it once at
 * construction and carries the answer, so the audio thread only ever reads a
 * number.
 */
internal object FrequencyResponse {

    /**
     * Points in the scan, spaced logarithmically.
     *
     * Log spacing because hearing is logarithmic and so are the band
     * centres; a linear sweep would spend most of its points above 10 kHz
     * where nothing interesting happens and almost none near 31 Hz where the
     * lowest band lives. 256 points over the audible decade is about 26 per
     * octave, which is dense enough that the error at a smooth peak is well
     * under the 0.2 dB this needs to be accurate to.
     */
    const val POINTS = 256

    private const val MIN_HZ = 20.0
    private const val MAX_HZ = 20000.0

    /**
     * Extra reduction below the measured peak, in dB.
     *
     * Cancelling the peak exactly leaves the steady state sitting on full
     * scale with nothing to spare, and measurement showed what that costs: a
     * full-scale sine at the peak frequency of two overlapping boosts
     * reached 1.0010 and clipped five samples, because the filter's opening
     * transient arrives before the limiter's attack can respond to it. The
     * limiter has no look-ahead by design, so it can only ever react late;
     * the only way to make it react in time is to give it somewhere to react
     * into.
     *
     * Half a decibel is about fifty times the overshoot that was measured
     * and is not a level change anyone will notice.
     */
    const val HEADROOM_MARGIN_DB = 0.5

    /** Highest point of the combined response, in dB. Negative if the curve only cuts. */
    fun peakGainDb(bands: List<EqBand>, sampleRate: Int): Double {
        if (bands.isEmpty() || sampleRate <= 0) return 0.0

        val top = minOf(MAX_HZ, sampleRate * 0.5 * 0.95)
        if (top <= MIN_HZ) return 0.0

        val coef = DoubleArray(bands.size * Biquad.COEFFS)
        for (i in bands.indices) {
            Biquad.designPeaking(
                coef, i * Biquad.COEFFS,
                bands[i].frequencyHz.toDouble(),
                bands[i].q.toDouble(),
                bands[i].gainDb.toDouble(),
                sampleRate.toDouble(),
            )
        }

        val span = ln(top / MIN_HZ)
        var peak = 0.0
        for (k in 0 until POINTS) {
            val f = MIN_HZ * exp(span * k / (POINTS - 1).toDouble())
            val w = 2.0 * PI * f / sampleRate
            var mag = 1.0
            for (i in bands.indices) mag *= sectionMagnitude(coef, i * Biquad.COEFFS, w)
            if (mag > peak) peak = mag
        }
        return if (peak <= 0.0) 0.0 else 20.0 * log10(peak)
    }

    /**
     * How much to pull the signal down so the loudest point of the curve
     * lands back at unity. Zero or negative, never a boost.
     *
     * A curve that only cuts needs no preamp, and detecting that case by
     * hand is also what keeps the common flat curve free: with no positive
     * gain anywhere, a cascade of peaking sections cannot exceed unity, so
     * there is nothing to scan for.
     */
    fun preampDb(bands: List<EqBand>, sampleRate: Int): Float {
        if (bands.none { it.gainDb > 0f }) return 0f
        val peak = peakGainDb(bands, sampleRate)
        return if (peak > 0.0) (-(peak + HEADROOM_MARGIN_DB)).toFloat() else 0f
    }

    /**
     * |H(e^jw)| for one section, from its normalised coefficients.
     *
     *     H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
     *
     * with z^-1 = e^-jw, so the real and imaginary parts fall out of cos and
     * sin at w and 2w.
     */
    private fun sectionMagnitude(coef: DoubleArray, o: Int, w: Double): Double {
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)

        val numRe = coef[o] + coef[o + 1] * c1 + coef[o + 2] * c2
        val numIm = -(coef[o + 1] * s1 + coef[o + 2] * s2)
        val denRe = 1.0 + coef[o + 3] * c1 + coef[o + 4] * c2
        val denIm = -(coef[o + 3] * s1 + coef[o + 4] * s2)

        val den = denRe * denRe + denIm * denIm
        if (den <= 0.0) return 1.0
        return sqrt((numRe * numRe + numIm * numIm) / den)
    }
}
