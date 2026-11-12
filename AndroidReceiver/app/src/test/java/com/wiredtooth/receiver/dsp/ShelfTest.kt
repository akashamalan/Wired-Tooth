package com.wiredtooth.receiver.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf sections, measured rather than inspected.
 *
 * HOW THE RESPONSE IS MEASURED HERE. Not by re-deriving |H(e^jw)| from the
 * coefficients -- that would only check the coefficients against the same
 * algebra that produced them. Instead a real sine goes through
 * [Biquad.processSample], the filter is allowed to settle, and the amplitude
 * is recovered with a single-bin DFT over a whole number of cycles. That
 * measures the design and the difference equation together, which is what
 * actually runs on the audio thread.
 *
 * The analysis length is chosen as fs / gcd(f, fs), the shortest window
 * holding an exact integer number of cycles, so the DFT bin is exact and
 * there is no spectral leakage to allow for. Every fixture below is
 * therefore asserted to 0.3 dB against a figure that leaves no room for
 * windowing error.
 *
 * WHERE THE FIXTURE VALUES CAME FROM. Our own implementation of the RBJ
 * cookbook's shelving cases, and then cross-checked once against AutoEq's own
 * coefficient expressions (autoeq/peq.py), transcribed into a throwaway
 * script outside this repository and run for these six frequencies only. The
 * two agreed to within 0.0005 dB everywhere -- the residual being the four
 * decimal places the fixtures are rounded to -- which confirms that their Q
 * is the cookbook's Q and that our reading of their sign convention was
 * right. Only the numbers were kept; no code was copied, and no measurement
 * data was downloaded.
 */
class ShelfTest {

    private val fs = 48000

    // ---- measurement -----------------------------------------------------

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Steady-state gain of one section at [freqHz], in dB. */
    private fun measureDb(coef: DoubleArray, freqHz: Int): Double {
        val period = fs / gcd(freqHz, fs)
        var window = period
        while (window < 4096) window += period        // enough cycles to average well
        val settle = 96000                            // 2 s: slow enough for a 20 Hz shelf

        val state = DoubleArray(2)
        val w = 2.0 * PI * freqHz / fs

        var n = 0
        while (n < settle) {
            Biquad.processSample(sin(w * n), coef, 0, state, 0)
            n++
        }

        var inRe = 0.0
        var inIm = 0.0
        var outRe = 0.0
        var outIm = 0.0
        var k = 0
        while (k < window) {
            val x = sin(w * (settle + k))
            val y = Biquad.processSample(x, coef, 0, state, 0)
            val c = cos(w * k)
            val s = sin(w * k)
            inRe += x * c; inIm += x * s
            outRe += y * c; outIm += y * s
            k++
        }
        return 20.0 * kotlin.math.log10(hypot(outRe, outIm) / hypot(inRe, inIm))
    }

    private fun lowShelf(fc: Double, q: Double, gainDb: Double): DoubleArray {
        val c = DoubleArray(Biquad.COEFFS)
        Biquad.designLowShelf(c, 0, fc, q, gainDb, fs.toDouble())
        return c
    }

    private fun highShelf(fc: Double, q: Double, gainDb: Double): DoubleArray {
        val c = DoubleArray(Biquad.COEFFS)
        Biquad.designHighShelf(c, 0, fc, q, gainDb, fs.toDouble())
        return c
    }

    private fun peaking(fc: Double, q: Double, gainDb: Double): DoubleArray {
        val c = DoubleArray(Biquad.COEFFS)
        Biquad.designPeaking(c, 0, fc, q, gainDb, fs.toDouble())
        return c
    }

    private val tol = 0.3

    // ---- fixtures --------------------------------------------------------

    /**
     * A low shelf at 105 Hz, Q 0.70, -3.2 dB -- the shape and the numbers a
     * real parametric file carries for a bass correction.
     */
    @Test
    fun lowShelfMatchesItsFixtures() {
        val c = lowShelf(105.0, 0.70, -3.2)
        val expected = listOf(
            20 to -3.1933,
            60 to -2.8713,
            105 to -1.6000,
            250 to -0.1090,
            1000 to -0.0011,
            10000 to -0.0000,
        )
        for ((f, want) in expected) {
            assertEquals("low shelf at $f Hz", want, measureDb(c, f), tol)
        }
    }

    @Test
    fun positiveLowShelfMatchesItsFixtures() {
        val c = lowShelf(105.0, 0.70, 6.0)
        val expected = listOf(
            20 to 5.9870,
            60 to 5.3603,
            105 to 3.0000,
            250 to 0.2140,
            1000 to 0.0022,
            10000 to 0.0000,
        )
        for ((f, want) in expected) {
            assertEquals("low shelf +6 at $f Hz", want, measureDb(c, f), tol)
        }
    }

    @Test
    fun highShelfMatchesItsFixtures() {
        val c = highShelf(10000.0, 0.70, 4.0)
        val expected = listOf(
            20 to 0.0000,
            60 to 0.0000,
            105 to 0.0000,
            250 to 0.0000,
            1000 to 0.0008,
            10000 to 2.0000,
        )
        for ((f, want) in expected) {
            assertEquals("high shelf at $f Hz", want, measureDb(c, f), tol)
        }
    }

    // ---- the defining properties ----------------------------------------

    /**
     * Fc sits at HALF the shelf's gain, not at the full gain.
     *
     * This is the cookbook's definition and the one parametric files are
     * written against. Getting it wrong puts the whole transition half an
     * octave out of place while still looking plausible on a graph, so it is
     * worth a test of its own at three different gains.
     */
    @Test
    fun theCornerIsAtHalfGain() {
        assertEquals(-1.60, measureDb(lowShelf(105.0, 0.70, -3.2), 105), tol)
        assertEquals(3.00, measureDb(lowShelf(105.0, 0.70, 6.0), 105), tol)
        assertEquals(2.00, measureDb(highShelf(10000.0, 0.70, 4.0), 10000), tol)
    }

    /** Well into the plateau, a shelf reaches its nominal gain. */
    @Test
    fun thePlateauReachesTheNominalGain() {
        assertEquals(-3.2, measureDb(lowShelf(105.0, 0.70, -3.2), 20), tol)
        assertEquals(6.0, measureDb(lowShelf(105.0, 0.70, 6.0), 20), tol)
    }

    /** Far past the corner, a shelf is out of the way. */
    @Test
    fun theFarSideIsUnity() {
        assertEquals(0.0, measureDb(lowShelf(105.0, 0.70, -3.2), 10000), tol)
        assertEquals(0.0, measureDb(highShelf(10000.0, 0.70, 4.0), 60), tol)
    }

    // ---- sign errors, the classic bug ------------------------------------

    /**
     * A negative low shelf cuts the bass and boosts nothing, anywhere.
     *
     * A sign slip in the shelf algebra produces a filter that is the right
     * shape and the wrong direction, which on music reads as "the correction
     * made it worse" rather than as an obvious fault. Both halves are
     * asserted: that the bass really is down, and that no frequency in the
     * audible range came up even slightly.
     */
    @Test
    fun aNegativeLowShelfCutsTheBassAndNeverBoosts() {
        val c = lowShelf(105.0, 0.70, -3.2)
        assertTrue(
            "20 Hz should be cut by about 3.2 dB",
            measureDb(c, 20) < -3.0,
        )
        val sweep = FrequencyResponse.curveDb(
            listOf(EqBand(105f, -3.2f, 0.70f, BandType.LowShelf)), fs, 512,
        )
        val highest = sweep.max()
        assertTrue("no frequency may be boosted, saw ${highest} dB", highest <= 0.01f)
    }

    /** And the mirror case: a positive low shelf cuts nothing. */
    @Test
    fun aPositiveLowShelfBoostsTheBassAndNeverCuts() {
        val c = lowShelf(105.0, 0.70, 6.0)
        assertTrue("20 Hz should be boosted", measureDb(c, 20) > 5.7)
        val sweep = FrequencyResponse.curveDb(
            listOf(EqBand(105f, 6f, 0.70f, BandType.LowShelf)), fs, 512,
        )
        val lowest = sweep.min()
        assertTrue("no frequency may be cut, saw ${lowest} dB", lowest >= -0.01f)
    }

    /** A negative high shelf cuts the treble, not the bass. */
    @Test
    fun aNegativeHighShelfCutsTheTreble() {
        val c = highShelf(10000.0, 0.70, -4.0)
        assertTrue("10 kHz should be cut", measureDb(c, 10000) < -1.5)
        assertEquals("60 Hz must be untouched", 0.0, measureDb(c, 60), tol)
    }

    // ---- shelves are not peaks -------------------------------------------

    /**
     * A shelf and a peak at the same Fc, Q and gain are different filters.
     *
     * The point of the three-way type field is that an imported LSC must not
     * quietly become a PK. At 20 Hz the two differ by about 3 dB -- the shelf
     * is still at full depth where the peak has almost returned to flat --
     * which is the most audible part of a headphone correction. If a
     * refactor ever routes shelves back through the peaking design, this is
     * the test that notices.
     */
    @Test
    fun aShelfIsNotAPeakAtTheSameSettings() {
        val shelf = measureDb(lowShelf(105.0, 0.70, -3.2), 20)
        val peak = measureDb(peaking(105.0, 0.70, -3.2), 20)
        assertTrue(
            "shelf $shelf and peak $peak should differ by more than 2 dB at 20 Hz",
            abs(shelf - peak) > 2.0,
        )
    }

    /** Zero gain is exactly transparent for every shape, by construction. */
    @Test
    fun zeroGainIsTheIdentityForEveryShape() {
        for (type in BandType.entries) {
            val c = DoubleArray(Biquad.COEFFS)
            Biquad.design(c, 0, type, 1000.0, 0.70, 0.0, fs.toDouble())
            assertEquals("$type b0", 1.0, c[0], 0.0)
            assertEquals("$type b1", 0.0, c[1], 0.0)
            assertEquals("$type b2", 0.0, c[2], 0.0)
            assertEquals("$type a1", 0.0, c[3], 0.0)
            assertEquals("$type a2", 0.0, c[4], 0.0)
        }
    }

    /** The dispatcher routes each type to the design that type names. */
    @Test
    fun theDispatcherRoutesEachTypeCorrectly() {
        for (type in BandType.entries) {
            val viaDispatch = DoubleArray(Biquad.COEFFS)
            Biquad.design(viaDispatch, 0, type, 105.0, 0.70, -3.2, fs.toDouble())

            val direct = DoubleArray(Biquad.COEFFS)
            when (type) {
                BandType.Peaking -> Biquad.designPeaking(direct, 0, 105.0, 0.70, -3.2, fs.toDouble())
                BandType.LowShelf -> Biquad.designLowShelf(direct, 0, 105.0, 0.70, -3.2, fs.toDouble())
                BandType.HighShelf -> Biquad.designHighShelf(direct, 0, 105.0, 0.70, -3.2, fs.toDouble())
            }
            for (i in 0 until Biquad.COEFFS) {
                assertEquals("$type coefficient $i", direct[i], viaDispatch[i], 0.0)
            }
        }
    }

    /**
     * The preamp scan sees shelves too.
     *
     * [FrequencyResponse] works off normalised coefficients, so it needed no
     * change for shelves -- but "needed no change" is a claim worth holding
     * down with a test, because the alternative is a curve whose boost the
     * preamp never knew about.
     */
    @Test
    fun theTruePeakScanAccountsForAShelfBoost() {
        val bands = listOf(EqBand(105f, 8f, 0.70f, BandType.LowShelf))
        val peak = FrequencyResponse.peakGainDb(bands, fs)
        assertEquals("a +8 dB shelf peaks at about +8 dB", 8.0, peak, 0.3)
        val preamp = FrequencyResponse.preampDb(bands, fs)
        assertTrue("the preamp must pay for it, saw $preamp", preamp < -8.0)
    }
}
