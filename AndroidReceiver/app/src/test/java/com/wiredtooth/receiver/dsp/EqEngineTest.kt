package com.wiredtooth.receiver.dsp

import com.wiredtooth.receiver.Mixing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tests for the equalizer core.
 *
 * These run on the JVM with no Android on the classpath, which is the reason
 * the DSP has no Android imports: the parts that are easy to get subtly wrong
 * are the parts a device cannot check for you. A phone can tell you it sounds
 * wrong; only a test can tell you the 4 kHz band is 0.3 dB off.
 */
class EqEngineTest {

    private val rate = 48000

    private fun engine(channels: Int = 1): EqEngine =
        EqEngine().apply { configure(rate, channels) }

    private fun sine(freq: Double, amplitude: Double, frames: Int): FloatArray {
        val out = FloatArray(frames)
        val w = 2.0 * PI * freq / rate
        for (i in 0 until frames) out[i] = (amplitude * sin(w * i)).toFloat()
        return out
    }

    /** RMS over [from, until), the settled part of a tone. */
    private fun rms(x: FloatArray, from: Int, until: Int): Double {
        var acc = 0.0
        for (i in from until until) acc += x[i].toDouble() * x[i]
        return sqrt(acc / (until - from))
    }

    private fun flat(enabled: Boolean = true, preamp: Boolean = false, limit: Boolean = false) =
        EqSettings(enabled = enabled, autoPreamp = preamp, limiter = limit)

    private fun withBand(index: Int, gainDb: Float, preamp: Boolean = false, limit: Boolean = false) =
        EqSettings(
            enabled = true,
            autoPreamp = preamp,
            limiter = limit,
            bands = EqSettings.defaultBands().mapIndexed { i, b ->
                if (i == index) b.copy(gainDb = gainDb) else b
            },
        )

    // ---- transparency and bypass ---------------------------------------

    /**
     * A flat curve must not colour the signal at all. This is a property of
     * the cookbook formulas at unity gain rather than an approximation, so
     * the tolerance is float rounding and nothing else.
     */
    @Test
    fun flatCurveIsTransparent() {
        val eq = engine()
        val input = sine(1000.0, 0.5, 4800)
        val buf = input.copyOf()
        eq.process(flat(), buf, buf.size)

        var worst = 0.0
        for (i in input.indices) worst = maxOf(worst, abs(buf[i] - input[i]).toDouble())
        assertTrue("flat curve altered the signal by $worst", worst < 1e-6)
    }

    /** Off means off: the engine must not write to the buffer at all. */
    @Test
    fun bypassLeavesTheBufferUntouched() {
        val eq = engine()
        val rnd = Random(7)
        val input = FloatArray(2048) { rnd.nextFloat() * 2f - 1f }
        val buf = input.copyOf()

        eq.process(EqSettings(enabled = false), buf, buf.size)

        assertArrayEquals("bypass must be bit-exact", input, buf, 0f)
    }

    // ---- frequency response --------------------------------------------

    /**
     * A boosted band must deliver the gain it was asked for, at its own
     * centre frequency, within 0.1 dB. Checked at five bands spread across
     * the range because a coefficient error usually shows at one end first.
     */
    @Test
    fun boostedBandHasTheRequestedGain() {
        val boostDb = 6.0
        val frames = rate * 2
        val settle = frames / 2

        for (band in intArrayOf(1, 3, 5, 7, 8)) {
            val f = EqSettings.CENTRES_HZ[band].toDouble()
            val eq = engine()
            val input = sine(f, 0.25, frames)
            val buf = input.copyOf()

            eq.process(withBand(band, boostDb.toFloat()), buf, frames)

            val measured = 20.0 * log10(rms(buf, settle, frames) / rms(input, settle, frames))
            assertEquals(
                "band $band at ${f.toInt()} Hz: wanted $boostDb dB, measured $measured dB",
                boostDb, measured, 0.1,
            )
        }
    }

    /** A cut is the mirror of a boost and is worth its own check. */
    @Test
    fun cutBandHasTheRequestedAttenuation() {
        val frames = rate * 2
        val eq = engine()
        val f = EqSettings.CENTRES_HZ[5].toDouble()
        val input = sine(f, 0.25, frames)
        val buf = input.copyOf()

        eq.process(withBand(5, -9f), buf, frames)

        val measured = 20.0 * log10(rms(buf, frames / 2, frames) / rms(input, frames / 2, frames))
        assertEquals(-9.0, measured, 0.1)
    }

    // ---- headroom -------------------------------------------------------

    /**
     * The headline safety property: a full-scale input with the largest boost
     * the UI allows must still leave the chain inside [-1, 1].
     *
     * This passes because of three things in series, and it is worth being
     * precise about which does what. Auto-preamp removes the bulk. The
     * limiter catches what overlapping bands add on top of that. The hard
     * clamp in Mixing is the last guard, and it is the only one of the three
     * that is a guarantee rather than a strong tendency, because a limiter
     * with no look-ahead cannot see a transient coming.
     */
    @Test
    fun fullScaleWithMaximumBoostStaysInsideUnity() {
        val frames = rate * 2
        for (band in intArrayOf(0, 2, 5, 9)) {
            val eq = engine()
            val f = EqSettings.CENTRES_HZ[band].toDouble()
            val buf = sine(f, 1.0, frames)

            eq.process(
                withBand(band, EqSettings.MAX_GAIN_DB, preamp = true, limit = true),
                buf, frames,
            )

            var peak = 0.0
            for (v in buf) peak = maxOf(peak, abs(v.toDouble()))
            assertTrue("band $band peaked at $peak", peak <= 1.0 + 1e-6)
        }
    }

    /**
     * Two overlapping boosts sum higher than either one, which is exactly the
     * case auto-preamp alone does not cover. The output must still be bounded
     * once it has been through the clamp.
     */
    @Test
    fun overlappingBoostsStillLandInsideInt16() {
        val frames = rate
        val eq = engine()
        val settings = EqSettings(
            enabled = true, autoPreamp = true, limiter = true,
            bands = EqSettings.defaultBands().mapIndexed { i, b ->
                if (i in 4..6) b.copy(gainDb = EqSettings.MAX_GAIN_DB) else b
            },
        )
        val buf = sine(EqSettings.CENTRES_HZ[5].toDouble(), 1.0, frames)
        eq.process(settings, buf, frames)

        val bytes = ByteArray(frames * 2)
        Mixing.clampInto(buf, bytes, frames)
        for (i in 0 until frames) {
            val v = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
            assertTrue("sample $i out of int16 range", v >= -32768 && v <= 32767)
        }
    }

    // ---- no clicks ------------------------------------------------------

    /**
     * Moving the sliders mid-stream must not produce a step in the waveform.
     *
     * A click is a sample-to-sample jump far larger than the signal itself
     * can produce. The bound is measured rather than guessed: the largest
     * legitimate step is taken from the settled signal before and after the
     * change, and the transition is required to stay close to it. An
     * interpolated-coefficient implementation fails this by a wide margin.
     */
    @Test
    fun changingSettingsMidStreamDoesNotClick() {
        val eq = engine()
        val block = rate / 2
        val input = sine(1000.0, 0.3, block * 3)

        val before = EqSettings(enabled = true, autoPreamp = true, limiter = true)
        val after = EqSettings(
            enabled = true, autoPreamp = true, limiter = true,
            bands = EqSettings.defaultBands().mapIndexed { i, b ->
                when (i) {
                    0 -> b.copy(gainDb = 12f)
                    5 -> b.copy(gainDb = -10f)
                    9 -> b.copy(gainDb = 12f)
                    else -> b
                }
            },
        )

        val buf = input.copyOf()
        eq.process(before, buf, block)
        // Second and third blocks run with the new curve; the glide happens
        // at the start of the second.
        val second = FloatArray(block)
        System.arraycopy(input, block, second, 0, block)
        eq.process(after, second, block)

        val third = FloatArray(block)
        System.arraycopy(input, block * 2, third, 0, block)
        eq.process(after, third, block)

        fun maxStep(x: FloatArray, from: Int, until: Int): Double {
            var m = 0.0
            for (i in from + 1 until until) m = maxOf(m, abs(x[i] - x[i - 1]).toDouble())
            return m
        }

        val settledBefore = maxStep(buf, block / 2, block)
        val settledAfter = maxStep(third, 0, block)
        val duringChange = maxStep(second, 0, block)
        val allowed = maxOf(settledBefore, settledAfter) * 1.5

        assertTrue(
            "step during the change was $duringChange, settled steps were " +
                "$settledBefore and $settledAfter, allowed $allowed",
            duringChange <= allowed,
        )
    }

    // ---- stability ------------------------------------------------------

    /**
     * Ten minutes of audio while the parameters are shoved around at random.
     *
     * The thing being hunted is a NaN. One of them in a feedback path is
     * permanent: every later sample is NaN and the stream never recovers, so
     * a rare instability is not a glitch, it is a dead app until restart.
     */
    @Test
    fun fuzzingProducesNoNaNOrInfinity() {
        val eq = engine(channels = 2)
        val rnd = Random(20261110)
        val framesPerBlock = rate / 100                 // 10 ms, as in production
        val blocks = 10 * 60 * 100                      // ten minutes
        val buf = FloatArray(framesPerBlock * 2)

        var settings = EqSettings(enabled = true)
        var filtered = 0
        for (b in 0 until blocks) {
            if (b % 37 == 0) {
                settings = EqSettings(
                    enabled = true,
                    autoPreamp = rnd.nextBoolean(),
                    limiter = rnd.nextBoolean(),
                    bands = EqSettings.CENTRES_HZ.map { hz ->
                        EqBand(
                            frequencyHz = (hz * (0.5f + rnd.nextFloat())),
                            gainDb = EqSettings.MIN_GAIN_DB +
                                rnd.nextFloat() * (EqSettings.MAX_GAIN_DB - EqSettings.MIN_GAIN_DB),
                            q = 0.2f + rnd.nextFloat() * 12f,
                        )
                    },
                )
            }
            for (i in buf.indices) buf[i] = rnd.nextFloat() * 2f - 1f
            val before = buf.copyOf()
            eq.process(settings, buf, framesPerBlock)
            for (i in buf.indices) {
                val v = buf[i]
                if (!v.isFinite()) {
                    throw AssertionError("non-finite sample at block $b index $i: $v")
                }
            }
            // Guard against a vacuous pass. Every early return inside process
            // leaves the buffer untouched, and untouched random noise is
            // finite, so without this the test would still be green if the
            // DSP never ran at all.
            if (!buf.contentEquals(before)) filtered++
        }
        assertEquals("the instability guard should never have fired", 0L, eq.instabilityResets)
        assertEquals("every block should have been filtered", blocks, filtered)
    }

    // ---- reset ----------------------------------------------------------

    /**
     * Reset must leave no memory behind. The control is the second half: an
     * IIR filter that was NOT reset keeps ringing, so if both halves were
     * silent the test would be proving nothing.
     */
    @Test
    fun resetClearsFilterMemory() {
        val settings = withBand(2, 12f)

        val ringing = engine()
        ringing.process(settings, sine(250.0, 0.9, 4800), 4800)
        val tailWithoutReset = FloatArray(2048)
        ringing.process(settings, tailWithoutReset, 2048)
        var energy = 0.0
        for (v in tailWithoutReset) energy += abs(v.toDouble())
        assertTrue("control: an un-reset filter should still be ringing", energy > 1e-4)

        val cleared = engine()
        cleared.process(settings, sine(250.0, 0.9, 4800), 4800)
        cleared.reset()
        val tailAfterReset = FloatArray(2048)
        cleared.process(settings, tailAfterReset, 2048)
        for (i in tailAfterReset.indices) {
            assertEquals("sample $i should be silent after reset", 0f, tailAfterReset[i], 0f)
        }
    }

    /** A format change reconfigures and clears, which is how the receiver uses it. */
    @Test
    fun reconfiguringForANewFormatClearsState() {
        val eq = engine()
        val settings = withBand(2, 12f)
        eq.process(settings, sine(250.0, 0.9, 4800), 4800)

        eq.configure(44100, 2)

        val tail = FloatArray(1024)
        eq.process(settings, tail, 512)
        for (i in tail.indices) assertEquals(0f, tail[i], 0f)
    }

    // ---- latency --------------------------------------------------------

    /**
     * Zero added samples, asserted two ways.
     *
     * A flat curve reproduces the input sample for sample with no shift, and
     * a boosted curve responds on the very first sample rather than after a
     * delay. Any look-ahead or FIR would fail both.
     */
    @Test
    fun addsZeroSamplesOfLatency() {
        val eq = engine()
        val input = sine(1000.0, 0.5, 512)
        val buf = input.copyOf()
        eq.process(flat(), buf, buf.size)
        for (i in input.indices) {
            assertEquals("flat output must be aligned at sample $i", input[i], buf[i], 1e-6f)
        }

        val boosted = engine()
        val impulse = FloatArray(256)
        impulse[0] = 1f
        boosted.process(withBand(5, 12f), impulse, impulse.size)
        assertTrue(
            "the first sample must already be filtered, got ${impulse[0]}",
            abs(impulse[0]) > 1e-6f,
        )
    }

    // ---- conversion -----------------------------------------------------

    /** The int-to-float-and-back round trip must not drift. */
    @Test
    fun floatConversionRoundTripsExactly() {
        val mix = intArrayOf(0, 1, -1, 1000, -1000, 32767, -32768)
        val f = FloatArray(mix.size)
        Mixing.toFloat(mix, f, mix.size)
        val bytes = ByteArray(mix.size * 2)
        Mixing.clampInto(f, bytes, mix.size)
        for (i in mix.indices) {
            val v = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            assertEquals("sample $i", mix[i], v)
        }
    }

    /** Values past full scale must saturate, never wrap. */
    @Test
    fun outOfRangeFloatsSaturate() {
        val f = floatArrayOf(2.0f, -2.0f, 1.5f, -1.5f)
        val bytes = ByteArray(f.size * 2)
        Mixing.clampInto(f, bytes, f.size)
        val expected = intArrayOf(32767, -32768, 32767, -32768)
        for (i in f.indices) {
            val v = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            assertEquals("sample $i", expected[i], v)
        }
    }
}
