package com.wiredtooth.receiver.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
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

    const val MIN_HZ = 20.0
    const val MAX_HZ = 20000.0

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

    /** The highest frequency worth scanning at this rate. */
    fun topHz(sampleRate: Int): Double = minOf(MAX_HZ, sampleRate * 0.5 * 0.95)

    /**
     * Walks the log-spaced grid, handing each point's frequency and combined
     * magnitude to [body].
     *
     * THE ONLY PLACE THE CASCADE'S MAGNITUDE IS COMPUTED. The preamp, the
     * loudness match and the on-screen curve all come through here, which is
     * what guarantees the graph cannot draw one thing while the preamp
     * assumes another. Three copies of this loop would eventually disagree,
     * and the disagreement would be invisible until somebody's ears found it.
     *
     * Returns false if there is nothing to scan.
     */
    private inline fun scan(
        bands: List<EqBand>,
        sampleRate: Int,
        points: Int,
        body: (index: Int, freqHz: Double, magnitude: Double) -> Unit,
    ): Boolean {
        if (bands.isEmpty() || sampleRate <= 0 || points < 2) return false
        val top = topHz(sampleRate)
        if (top <= MIN_HZ) return false

        val coef = DoubleArray(bands.size * Biquad.COEFFS)
        for (i in bands.indices) {
            Biquad.design(
                coef, i * Biquad.COEFFS,
                bands[i].type,
                bands[i].frequencyHz.toDouble(),
                bands[i].q.toDouble(),
                bands[i].gainDb.toDouble(),
                sampleRate.toDouble(),
            )
        }

        val span = ln(top / MIN_HZ)
        for (k in 0 until points) {
            val f = MIN_HZ * exp(span * k / (points - 1).toDouble())
            val w = 2.0 * PI * f / sampleRate
            var mag = 1.0
            for (i in bands.indices) mag *= sectionMagnitude(coef, i * Biquad.COEFFS, w)
            body(k, f, mag)
        }
        return true
    }

    /** Highest point of the combined response, in dB. Negative if the curve only cuts. */
    fun peakGainDb(bands: List<EqBand>, sampleRate: Int): Double {
        var peak = 0.0
        val ran = scan(bands, sampleRate, POINTS) { _, _, mag -> if (mag > peak) peak = mag }
        if (!ran || peak <= 0.0) return 0.0
        return 20.0 * log10(peak)
    }

    /**
     * The combined response in dB at [points] log-spaced frequencies from
     * [MIN_HZ] to [topHz], for drawing.
     *
     * The same scan the preamp uses, so the line on screen is the curve the
     * audio thread is actually running and not a second opinion about it.
     */
    fun curveDb(bands: List<EqBand>, sampleRate: Int, points: Int = POINTS): FloatArray {
        val out = FloatArray(points)
        scan(bands, sampleRate, points) { k, _, mag ->
            out[k] = if (mag <= 0.0) -120f else (20.0 * log10(mag)).toFloat()
        }
        return out
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

    // ---- loudness matching for Compare mode ----------------------------

    /**
     * Where the assumed music spectrum stops being flat per octave.
     *
     * Below [LOW_CORNER] the model rolls off, because very little music has
     * much energy below about 60 Hz and weighting it as though it did would
     * let the lowest band dominate the match. Above [HIGH_CORNER] it tilts
     * down, because average music spectra fall off through the treble rather
     * than staying pink all the way to 20 kHz.
     */
    private const val LOW_CORNER = 60.0
    private const val HIGH_CORNER = 1000.0
    private const val LOW_SLOPE_DB_PER_OCT = 9.0
    private const val HIGH_SLOPE_DB_PER_OCT = 4.0

    /**
     * Relative power of the assumed spectrum at [f], in dB, per log-spaced
     * scan point.
     *
     * WHY FLAT IS PINK HERE. The scan grid is logarithmic, so every point
     * covers an equal fraction of an octave. Pink noise has equal power per
     * octave by definition, so a pink spectrum on this grid is a CONSTANT
     * weight, not a 1/f curve -- the 1/f is already absorbed by the spacing.
     * The corners and slopes above are therefore the departure from pink,
     * not the pinkness itself.
     */
    private fun weightDb(f: Double): Double = when {
        f < LOW_CORNER -> -LOW_SLOPE_DB_PER_OCT * log2(LOW_CORNER / f)
        f <= HIGH_CORNER -> 0.0
        else -> -HIGH_SLOPE_DB_PER_OCT * log2(f / HIGH_CORNER)
    }

    /**
     * The curve's gain averaged over the assumed spectrum, in dB.
     *
     * AVERAGED IN THE POWER DOMAIN, NOT IN dB. The formula is
     *
     *     10 * log10( sum(w * |H|^2) / sum(w) )
     *
     * and the distinction matters. Averaging decibel values would be taking
     * the mean of a logarithm, which is a geometric mean of power -- and a
     * geometric mean is dominated by the small terms, not the large ones.
     * Loudness works the other way round: it follows total energy, so the
     * loud parts of the curve are precisely the parts that should count most.
     * A narrow +8 dB peak contributes 6.3 times unity power to the sum, while
     * its contribution to a dB average would be a mere +8 spread thin across
     * every other point. The first number is the one you can hear.
     *
     * Not a loudness measurement. There is no K-weighting, no gating and no
     * knowledge of the actual material -- see [EqSettings.compareDryTrimDb]
     * for what that costs.
     */
    fun weightedGainDb(bands: List<EqBand>, sampleRate: Int): Double {
        var weightedPower = 0.0
        var weightSum = 0.0
        val ran = scan(bands, sampleRate, POINTS) { _, f, mag ->
            val w = 10.0.pow(weightDb(f) / 10.0)
            weightedPower += w * mag * mag          // power, so magnitude squared
            weightSum += w
        }
        if (!ran || weightSum <= 0.0 || weightedPower <= 0.0) return 0.0
        return 10.0 * log10(weightedPower / weightSum)
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
