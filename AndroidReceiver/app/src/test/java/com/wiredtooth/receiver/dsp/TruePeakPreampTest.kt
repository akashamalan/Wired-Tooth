package com.wiredtooth.receiver.dsp

import com.wiredtooth.receiver.Mixing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

/**
 * The preamp has to know the real peak of the curve, not the largest band.
 *
 * At the default Q the bands overlap, so two neighbours at +12 dB make a
 * peak between them that is higher than either. The old preamp subtracted
 * 12 dB and left the rest for the limiter, which is the one stage that
 * cannot deal with it cleanly, because without look-ahead it only reacts
 * after the peak has already passed.
 */
class TruePeakPreampTest {

    private val rate = 48000

    private fun bands(vararg gains: Pair<Int, Float>): List<EqBand> =
        EqSettings.defaultBands().mapIndexed { i, b ->
            gains.firstOrNull { it.first == i }?.let { b.copy(gainDb = it.second) } ?: b
        }

    /** A brute-force scan, far denser than the 256-point one, as the reference. */
    private fun densePeakDb(bands: List<EqBand>, points: Int = 20000): Double {
        val coef = DoubleArray(bands.size * Biquad.COEFFS)
        for (i in bands.indices) {
            Biquad.designPeaking(
                coef, i * Biquad.COEFFS,
                bands[i].frequencyHz.toDouble(), bands[i].q.toDouble(),
                bands[i].gainDb.toDouble(), rate.toDouble(),
            )
        }
        val lo = 20.0
        val hi = minOf(20000.0, rate * 0.5 * 0.95)
        val span = ln(hi / lo)
        var peak = 0.0
        for (k in 0 until points) {
            val f = lo * exp(span * k / (points - 1).toDouble())
            val w = 2.0 * PI * f / rate
            var mag = 1.0
            for (i in bands.indices) {
                val o = i * Biquad.COEFFS
                val c1 = kotlin.math.cos(w); val s1 = sin(w)
                val c2 = kotlin.math.cos(2 * w); val s2 = sin(2 * w)
                val nr = coef[o] + coef[o + 1] * c1 + coef[o + 2] * c2
                val ni = -(coef[o + 1] * s1 + coef[o + 2] * s2)
                val dr = 1.0 + coef[o + 3] * c1 + coef[o + 4] * c2
                val di = -(coef[o + 3] * s1 + coef[o + 4] * s2)
                mag *= kotlin.math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
            }
            if (mag > peak) peak = mag
        }
        return 20.0 * log10(peak)
    }

    /** Where the combined curve is loudest, found by the same dense scan. */
    private fun peakFrequency(bands: List<EqBand>, points: Int = 20000): Double {
        val coef = DoubleArray(bands.size * Biquad.COEFFS)
        for (i in bands.indices) {
            Biquad.designPeaking(
                coef, i * Biquad.COEFFS,
                bands[i].frequencyHz.toDouble(), bands[i].q.toDouble(),
                bands[i].gainDb.toDouble(), rate.toDouble(),
            )
        }
        val lo = 20.0
        val hi = minOf(20000.0, rate * 0.5 * 0.95)
        val span = ln(hi / lo)
        var peak = 0.0
        var at = lo
        for (k in 0 until points) {
            val f = lo * exp(span * k / (points - 1).toDouble())
            val w = 2.0 * PI * f / rate
            var mag = 1.0
            for (i in bands.indices) {
                val o = i * Biquad.COEFFS
                val c1 = kotlin.math.cos(w); val s1 = sin(w)
                val c2 = kotlin.math.cos(2 * w); val s2 = sin(2 * w)
                val nr = coef[o] + coef[o + 1] * c1 + coef[o + 2] * c2
                val ni = -(coef[o + 1] * s1 + coef[o + 2] * s2)
                val dr = 1.0 + coef[o + 3] * c1 + coef[o + 4] * c2
                val di = -(coef[o + 3] * s1 + coef[o + 4] * s2)
                mag *= kotlin.math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
            }
            if (mag > peak) { peak = mag; at = f }
        }
        return at
    }

    // ---- the headline case ---------------------------------------------

    /**
     * Two adjacent +12 dB bands. The preamp must match the true summed peak
     * to within 0.2 dB, and it must be measurably more than 12 dB, which is
     * the whole reason this replaced the old rule.
     */
    @Test
    fun adjacentBoostsGiveTheTrueSummedPeak() {
        val curve = bands(4 to 12f, 5 to 12f)
        val settings = EqSettings(enabled = true, bands = curve, sampleRateForPreamp = rate)

        val reference = densePeakDb(curve)

        // The measurement itself must match a far denser scan.
        assertEquals(
            "the scan must find the real peak",
            reference, FrequencyResponse.peakGainDb(curve, rate), 0.2,
        )
        // The preamp cancels that peak, plus a deliberate margin so the
        // steady state does not sit exactly on full scale.
        assertEquals(
            "preamp should cancel the real peak plus the headroom margin",
            -(reference + FrequencyResponse.HEADROOM_MARGIN_DB),
            settings.preampDb.toDouble(), 0.2,
        )
        assertTrue(
            "the true peak ${"%.2f".format(reference)} dB must exceed the largest single " +
                "band (12 dB), or this test is not exercising the overlap",
            reference > 12.3,
        )
    }

    /** The 256-point scan must agree with a 20000-point scan across many curves. */
    @Test
    fun theScanIsDenseEnough() {
        val cases = listOf(
            bands(0 to 12f),
            bands(9 to 12f),
            bands(3 to 12f, 4 to 12f, 5 to 12f),
            bands(0 to 9f, 2 to 6f, 4 to 12f, 7 to 3f),
            bands(1 to 12f, 2 to -12f, 3 to 12f),
        )
        for ((n, curve) in cases.withIndex()) {
            val coarse = FrequencyResponse.peakGainDb(curve, rate)
            val dense = densePeakDb(curve)
            assertEquals("case $n", dense, coarse, 0.2)
        }
    }

    /** A curve that only cuts cannot exceed unity, so it needs no preamp. */
    @Test
    fun cutOnlyCurvesNeedNoPreamp() {
        val settings = EqSettings(
            enabled = true,
            bands = bands(2 to -12f, 5 to -6f, 8 to -9f),
            sampleRateForPreamp = rate,
        )
        assertEquals(0f, settings.preampDb, 0f)
    }

    @Test
    fun flatCurveNeedsNoPreamp() {
        assertEquals(0f, EqSettings(enabled = true).preampDb, 0f)
    }

    @Test
    fun preampIsZeroWhenAutoPreampIsOff() {
        val s = EqSettings(enabled = true, autoPreamp = false, bands = bands(4 to 12f, 5 to 12f))
        assertEquals(0f, s.preampDb, 0f)
    }

    // ---- what it buys at the output ------------------------------------

    /**
     * A full-scale sine at the peak frequency of two overlapping boosts must
     * not reach the hard clamp.
     *
     * This is the case the old preamp failed. It subtracted 12 dB against a
     * peak of more than 13, so the excess arrived at the limiter, and
     * whatever the limiter missed in its attack window hit the clamp.
     */
    @Test
    fun fullScaleAtThePeakDoesNotReachTheClamp() {
        val curve = bands(4 to 12f, 5 to 12f)
        val settings = EqSettings(
            enabled = true, autoPreamp = true, limiter = true,
            bands = curve, sampleRateForPreamp = rate,
        )

        val peakHz = peakFrequency(curve)

        val frames = rate * 2
        val eq = EqEngine().apply { configure(rate, 1) }
        val buf = FloatArray(frames)
        val w = 2.0 * PI * peakHz / rate
        for (i in 0 until frames) buf[i] = sin(w * i).toFloat()

        eq.process(settings, buf, frames)

        val out = ByteArray(frames * 2)
        val clamped = Mixing.clampInto(buf, out, frames)
        assertEquals("a full-scale sine at the peak should never reach the clamp", 0, clamped)

        var peak = 0.0
        for (v in buf) peak = maxOf(peak, abs(v.toDouble()))
        assertTrue("peak was $peak", peak <= 1.0 + 1e-6)
    }

    // ---- the clamp counter ---------------------------------------------

    /**
     * Music-like material, not a sine.
     *
     * A sustained full-scale sine is the single worst case for a limiter and
     * tells you little about ordinary listening. This is a sum of partials
     * with a realistic crest factor and an envelope, which is closer to what
     * the clamp will actually see, and on it the clamp should essentially
     * never fire.
     */
    @Test
    fun clampRarelyEngagesOnMusicLikeMaterial() {
        val frames = rate * 5
        val buf = FloatArray(frames)
        val partials = doubleArrayOf(110.0, 220.0, 330.0, 554.0, 880.0, 1760.0, 3520.0)
        val amps = doubleArrayOf(1.0, 0.7, 0.5, 0.35, 0.25, 0.15, 0.08)
        val phases = doubleArrayOf(0.0, 1.1, 2.3, 0.7, 1.9, 2.8, 0.4)

        var raw = 0.0
        for (i in 0 until frames) {
            var v = 0.0
            for (k in partials.indices) {
                v += amps[k] * sin(2.0 * PI * partials[k] * i / rate + phases[k])
            }
            // A slow envelope, so it is not a steady tone.
            v *= 0.6 + 0.4 * sin(2.0 * PI * 0.7 * i / rate)
            buf[i] = v.toFloat()
            raw = maxOf(raw, abs(v))
        }
        // Normalise to just under full scale, the way a mastered track sits.
        val norm = (0.89 / raw).toFloat()
        for (i in 0 until frames) buf[i] = buf[i] * norm

        val settings = EqSettings(
            enabled = true, autoPreamp = true, limiter = true,
            bands = bands(0 to 8f, 1 to 6f, 8 to 5f),
            sampleRateForPreamp = rate,
        )
        val eq = EqEngine().apply { configure(rate, 1) }
        eq.process(settings, buf, frames)

        val out = ByteArray(frames * 2)
        val clamped = Mixing.clampInto(buf, out, frames)
        val rate1 = clamped.toDouble() / frames
        assertTrue(
            "clamp engaged on $clamped of $frames samples (${"%.4f".format(rate1 * 100)}%)",
            rate1 < 0.0001,
        )
    }

    /** The counter has to actually count, or the test above proves nothing. */
    @Test
    fun clampCounterCountsWhenItShould() {
        val f = floatArrayOf(2f, -2f, 0.5f, 1.5f, 0f)
        val out = ByteArray(f.size * 2)
        assertEquals(3, Mixing.clampInto(f, out, f.size))

        val ints = intArrayOf(40000, -40000, 100, 0)
        val out2 = ByteArray(ints.size * 2)
        assertEquals(2, Mixing.clampInto(ints, out2))
    }
}
