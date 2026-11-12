package com.wiredtooth.receiver.dsp

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dropping identity sections from the cascade changes nothing.
 *
 * The saving is only worth having if it is free, and "free" here means
 * bit-identical and not merely inaudible. These tests hold that down at both
 * levels: the two lines of algebra that make it true, and the engine's
 * output compared sample for sample.
 */
class BandSkippingTest {

    private val fs = 48000
    private val channels = 2

    private fun signal(frames: Int): FloatArray {
        val out = FloatArray(frames * channels)
        for (n in 0 until frames) {
            // Two tones, so a filter anywhere in the band has something to act
            // on, at an amplitude that leaves the limiter out of it.
            val a = 0.4 * sin(2.0 * PI * 220.0 * n / fs)
            val b = 0.3 * sin(2.0 * PI * 3300.0 * n / fs)
            out[n * channels] = (a + b).toFloat()
            out[n * channels + 1] = (a - b).toFloat()
        }
        return out
    }

    private fun run(settings: EqSettings, frames: Int): FloatArray {
        val eq = EqEngine()
        eq.configure(fs, channels)
        val buf = signal(frames)
        eq.process(settings, buf, frames)
        return buf
    }

    // ---- the premise -----------------------------------------------------

    /**
     * An identity section empties itself in exactly two samples.
     *
     * With b0 = 1 and everything else zero the difference equation becomes
     * y = x + s0, s0 = s1, s1 = 0. So one sample clears s1, the second
     * clears s0, and from the third onwards the output is the input with no
     * arithmetic left in the state. That is why [EqEngine.FLUSH_SAMPLES] is
     * 2 and why dropping the section afterwards is exact rather than close.
     */
    @Test
    fun anIdentitySectionEmptiesItselfInTwoSamples() {
        val coef = DoubleArray(Biquad.COEFFS)
        Biquad.setIdentity(coef, 0)
        val state = doubleArrayOf(0.7, -0.3)

        Biquad.processSample(0.5, coef, 0, state, 0)
        assertEquals("s1 clears first", 0.0, state[1], 0.0)

        Biquad.processSample(0.5, coef, 0, state, 0)
        assertEquals("s0 clears second", 0.0, state[0], 0.0)
        assertEquals(0.0, state[1], 0.0)

        // From here the section is a wire, exactly.
        for (x in listOf(0.123, -0.9, 1e-9, 0.0)) {
            assertEquals(x, Biquad.processSample(x, coef, 0, state, 0), 0.0)
            assertEquals(0.0, state[0], 0.0)
            assertEquals(0.0, state[1], 0.0)
        }
    }

    @Test
    fun theFlushConstantIsTwo() {
        assertEquals(2, EqEngine.FLUSH_SAMPLES)
    }

    // ---- the engine ------------------------------------------------------

    /**
     * Ten real bands followed by ten bands at 0 dB is bit-identical to the
     * ten real bands on their own.
     *
     * This is the case a loaded-then-cleared profile leaves behind, and the
     * case of any profile with fewer than ten filters.
     */
    @Test
    fun trailingZeroBandsDoNotChangeOneBit() {
        val real = EqSettings.fromGains(
            listOf(6f, 5f, 3f, 1f, 0f, -2f, 0f, 4f, 2f, -1f),
        )
        val padded = real + List(EqSettings.MAX_BANDS - real.size) {
            EqBand(1000f, 0f, EqSettings.DEFAULT_Q)
        }

        val frames = 4800
        val a = run(EqSettings(enabled = true, bands = real), frames)
        val b = run(EqSettings(enabled = true, bands = padded), frames)
        assertArrayEquals("padding with identity bands changed the output", a, b, 0f)
    }

    /** The same holds when the padding is identity SHELVES rather than peaks. */
    @Test
    fun trailingZeroShelvesDoNotChangeOneBit() {
        val real = EqSettings.fromGains(
            listOf(0f, 3f, 0f, 0f, -4f, 0f, 0f, 0f, 5f, 0f),
        )
        val padded = real + listOf(
            EqBand(105f, 0f, 0.7f, BandType.LowShelf),
            EqBand(10000f, 0f, 0.7f, BandType.HighShelf),
            EqBand(500f, 0f, 1.0f, BandType.Peaking),
        )

        val frames = 4800
        val a = run(EqSettings(enabled = true, bands = real), frames)
        val b = run(EqSettings(enabled = true, bands = padded), frames)
        assertArrayEquals(a, b, 0f)
    }

    /**
     * Bands at 0 dB inside the curve are skipped too, not just trailing ones.
     *
     * A curve of ten bands with six at zero is the common case -- any preset
     * that touches only part of the spectrum -- so the skipping has to work
     * on an interior hole, which is what makes the compacted index list
     * necessary rather than just a band count.
     */
    @Test
    fun interiorZeroBandsAreSkippedWithoutChangingTheOutput() {
        val sparse = listOf(
            EqBand(31f, 6f, 1.41f),
            EqBand(62f, 0f, 1.41f),
            EqBand(125f, 0f, 1.41f),
            EqBand(250f, 0f, 1.41f),
            EqBand(500f, -3f, 1.41f),
            EqBand(1000f, 0f, 1.41f),
            EqBand(2000f, 0f, 1.41f),
            EqBand(4000f, 4f, 1.41f),
            EqBand(8000f, 0f, 1.41f),
            EqBand(16000f, 0f, 1.41f),
        )
        val onlyLive = listOf(sparse[0], sparse[4], sparse[7])

        val frames = 4800
        val full = run(EqSettings(enabled = true, bands = sparse), frames)

        // The three live bands alone, in the same order. Cascaded biquads
        // commute in exact arithmetic but not in floating point, so this is
        // asserted to a tolerance rather than bit-for-bit: the point is that
        // the seven dead bands contributed nothing, not that addition is
        // associative.
        val live = run(EqSettings(enabled = true, bands = onlyLive), frames)
        for (i in full.indices) {
            assertEquals("sample $i", full[i].toDouble(), live[i].toDouble(), 1e-6)
        }
    }

    /** A curve of all zeros is a wire, and the preamp does not move either. */
    @Test
    fun anAllZeroCurveIsTransparent() {
        val frames = 2400
        val flat = EqSettings(enabled = true, bands = EqSettings.defaultBands())
        assertEquals("a flat curve needs no preamp", 0f, flat.preampDb, 0f)

        val input = signal(frames)
        val out = run(flat, frames)
        for (i in input.indices) {
            assertEquals("sample $i", input[i], out[i], 1e-7f)
        }
    }

    // ---- twenty bands actually run ---------------------------------------

    /**
     * Twenty live sections run, stay finite, and stay inside full scale.
     *
     * The ceiling the budget enforces is what keeps this true, so the test
     * goes through [GainBudget] rather than hand-building a 20-band curve:
     * it is the pair of them that has to hold.
     */
    @Test
    fun twentyLiveBandsRunCleanly() {
        val profile = (0 until 10).map {
            EqBand((60.0 * Math.pow(2.0, it * 0.7)).toFloat(), 6f, 0.9f)
        }
        val macros = SoundState(enabled = true, preset = Preset.Bass, bassDb = 6f, warmthDb = 6f)
        val layered = GainBudget.layer(profile, macros.gains(), fs)
        assertEquals(EqSettings.MAX_BANDS, layered.bands.size)

        val settings = EqSettings(enabled = true, bands = layered.bands, sampleRateForPreamp = fs)
        val frames = 9600
        val eq = EqEngine()
        eq.configure(fs, channels)
        val buf = signal(frames)
        eq.process(settings, buf, frames)

        assertEquals("no instability", 0L, eq.instabilityResets)
        var worst = 0f
        for (v in buf) {
            assertTrue("non-finite sample", v.isFinite())
            if (kotlin.math.abs(v) > worst) worst = kotlin.math.abs(v)
        }
        assertTrue("peak $worst should stay inside full scale", worst <= 1.0f)
    }

    /**
     * A band that glides down to zero ends up skipped, and the result still
     * matches the curve that never had it.
     *
     * The gain smoothing means this takes a moment: the band is still live
     * while its gain is on the way down, which is exactly what stops the
     * removal from being a click. Once it has arrived at zero and flushed,
     * the two cascades agree.
     */
    @Test
    fun aBandRemovedMidStreamConvergesOnTheCurveWithoutIt() {
        val withBand = EqSettings.fromGains(
            listOf(0f, 0f, 0f, 0f, 0f, 8f, 0f, 0f, 0f, 0f),
        )
        val withoutBand = EqSettings.fromGains(List(10) { 0f })

        val eq = EqEngine()
        eq.configure(fs, channels)
        val frames = 4800

        // Settle with the band in.
        val warm = signal(frames)
        eq.process(EqSettings(enabled = true, bands = withBand), warm, frames)

        // Now take it out and let the glide finish.
        for (pass in 0 until 4) {
            val buf = signal(frames)
            eq.process(EqSettings(enabled = true, bands = withoutBand), buf, frames)
        }

        // By now the gain has reached zero and the section has flushed, so
        // the engine should be passing the signal straight through.
        val input = signal(frames)
        val buf = signal(frames)
        eq.process(EqSettings(enabled = true, bands = withoutBand), buf, frames)
        for (i in input.indices) {
            assertEquals("sample $i", input[i], buf[i], 1e-6f)
        }
    }
}
