package com.wiredtooth.receiver.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compare mode: the loudness match and the crossfade.
 *
 * The thing being defended here is subtle. An A/B button that changes the
 * level as well as the tone does not compare two tones, it compares two
 * volumes, and listeners reliably call the louder one better. Every test in
 * this file exists to keep the level out of the comparison.
 */
class CompareTest {

    private val rate = 48000

    private fun bands(vararg gains: Pair<Int, Float>): List<EqBand> =
        EqSettings.defaultBands().mapIndexed { i, b ->
            gains.firstOrNull { it.first == i }?.let { b.copy(gainDb = it.second) } ?: b
        }

    private fun rms(x: FloatArray): Double {
        var acc = 0.0
        for (v in x) acc += v.toDouble() * v
        return sqrt(acc / x.size)
    }

    private fun db(ratio: Double) = 20.0 * log10(ratio)

    /**
     * A signal with equal power per octave, built from log-spaced sinusoids.
     *
     * Written here rather than borrowed: components spaced evenly in log
     * frequency, all at the same amplitude, is equal power per octave by
     * construction, which is what pink means. Fixed irrational phase steps
     * keep the crest factor sane without needing a random number generator,
     * and keep the test deterministic.
     */
    private fun pinkLike(
        frames: Int,
        components: Int = 220,
        lowHz: Double = 25.0,
        highHz: Double = 16000.0,
        tiltDbPerOct: Double = 0.0,
        tiltFromHz: Double = 1000.0,
        lowRolloffDbPerOct: Double = 0.0,
        lowFromHz: Double = 60.0,
    ): FloatArray {
        val out = FloatArray(frames)
        val ratio = (highHz / lowHz).pow(1.0 / (components - 1))
        var f = lowHz
        for (k in 0 until components) {
            val octavesAbove = if (f > tiltFromHz) log10(f / tiltFromHz) / log10(2.0) else 0.0
            val octavesBelow = if (f < lowFromHz) log10(lowFromHz / f) / log10(2.0) else 0.0
            val shapeDb = tiltDbPerOct * octavesAbove - lowRolloffDbPerOct * octavesBelow
            val amp = 10.0.pow(shapeDb / 20.0)
            val w = 2.0 * PI * f / rate
            val phase = (k * 2.399963) % (2.0 * PI)       // golden angle, decorrelated
            for (n in 0 until frames) out[n] += (amp * sin(w * n + phase)).toFloat()
            f *= ratio
        }
        // Normalise well below full scale so nothing here is testing the limiter.
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        val norm = (0.25f / peak)
        for (n in 0 until frames) out[n] = out[n] * norm
        return out
    }

    private fun throughEq(settings: EqSettings, input: FloatArray): FloatArray {
        val eq = EqEngine().apply { configure(rate, 1) }
        val buf = input.copyOf()
        eq.process(settings, buf, buf.size)
        return buf
    }

    // ---- the crossfade law ---------------------------------------------

    /**
     * A steady sine through a FLAT curve must survive the crossfade untouched.
     *
     * With a flat curve the filtered signal is the original, so a correct
     * crossfade is mathematically a no-op: a*x + (1-a)*x = x for every a. Any
     * deviation is the fade law leaking into the signal, and 0.1 dB is far
     * below anything audible while still being tight enough to catch the
     * 3 dB error that the wrong law produces.
     */
    @Test
    fun linearCrossfadeOfAFlatCurveIsSilentOnStreamingSine() {
        val frames = rate / 2                        // 500ms, ten times the fade
        val channels = 1
        val settings = EqSettings(enabled = true, sampleRateForPreamp = rate)
        assertEquals("the flat curve must need no trim", 0f, settings.compareDryTrimDb, 1e-6f)

        val fader = CompareFader().apply { configure(rate); snapToEqualized() }
        val eq = EqEngine().apply { configure(rate, channels) }

        val w = 2.0 * PI * 1000.0 / rate
        val amp = 0.5
        val quantum = rate / 100
        val dry = FloatArray(quantum)
        val wet = FloatArray(quantum)

        var worstDb = 0.0
        var n = 0
        var block = 0
        while (n < frames) {
            // Toggle to the original side a tenth of the way in, so the fade
            // runs in the middle of the measurement rather than at its edge.
            if (block == 10) fader.request(showEqualized = false, dryTrimDb = 0f)
            if (block == 30) fader.request(showEqualized = true, dryTrimDb = 0f)

            for (i in 0 until quantum) dry[i] = (amp * sin(w * (n + i))).toFloat()
            System.arraycopy(dry, 0, wet, 0, quantum)
            eq.process(settings, wet, quantum)
            fader.blend(dry, wet, quantum, channels)

            // Compare the envelope sample by sample against the pure sine.
            for (i in 0 until quantum) {
                val want = abs(dry[i].toDouble())
                val got = abs(wet[i].toDouble())
                if (want > amp * 0.5) {               // ignore zero crossings
                    val d = abs(db(got / want))
                    if (d > worstDb) worstDb = d
                }
            }
            n += quantum
            block++
        }

        assertTrue(
            "crossfade moved the level by ${"%.3f".format(worstDb)} dB",
            worstDb < 0.1,
        )
    }

    /**
     * Proof that the test above has teeth.
     *
     * An equal-power law on these two correlated signals would put a +3 dB
     * bump in the middle of every toggle. If this assertion ever fails it
     * means the test above would pass under either law and is checking
     * nothing.
     */
    @Test
    fun equalPowerWouldHaveFailedThatTest() {
        var worstDb = 0.0
        var a = 0.0
        while (a <= 1.0) {
            // Same source on both sides, so the amplitudes add.
            val linear = a + (1 - a)
            val equalPower = sin(a * PI / 2) + cos(a * PI / 2)
            worstDb = maxOf(worstDb, abs(db(equalPower / linear)))
            a += 0.01
        }
        assertTrue(
            "equal-power peaked at only ${"%.2f".format(worstDb)} dB, so the " +
                "flatness test does not discriminate between the two laws",
            worstDb > 2.9,
        )
    }

    /** The fade must take the full 50 ms, not jump. */
    @Test
    fun theFadeTakesFiftyMilliseconds() {
        val fader = CompareFader().apply { configure(rate); snapToEqualized() }
        val channels = 1
        val frames = 16
        val dry = FloatArray(frames) { 1f }

        fader.request(showEqualized = false, dryTrimDb = 0f)

        var elapsed = 0
        var arrived = -1
        while (elapsed < rate) {                      // up to a second
            val wet = FloatArray(frames) { 1f }
            fader.blend(dry, wet, frames, channels)
            elapsed += frames
            // dry and wet are both 1.0 here, so the output only reaches a
            // steady 1.0 again once the ramp has finished travelling.
            if (arrived < 0 && fader.atRest()) arrived = elapsed
            if (elapsed > rate / 4) break
        }

        val expected = (CompareFader.FADE_MS / 1000.0 * rate).toInt()
        // Travelling the other way, which is the one atRest() reports on.
        fader.request(showEqualized = true, dryTrimDb = 0f)
        var back = 0
        while (!fader.atRest() && back < rate) {
            val wet = FloatArray(frames) { 1f }
            fader.blend(dry, wet, frames, channels)
            back += frames
        }
        assertTrue(
            "fade home took $back frames, expected about $expected",
            back in (expected - frames * 2)..(expected + frames * 2),
        )
    }

    /**
     * No click on a toggle.
     *
     * A click is a discontinuity, so the measurement is the largest jump
     * between consecutive output samples. For a sine the natural maximum jump
     * is amp * w per sample; anything meaningfully above that is the fade
     * stepping rather than sliding.
     */
    @Test
    fun togglingDoesNotClick() {
        val channels = 1
        val quantum = rate / 100
        val hz = 440.0
        val amp = 0.5
        val w = 2.0 * PI * hz / rate
        val naturalJump = amp * w

        val settings = EqSettings(
            enabled = true,
            bands = bands(0 to 8f, 1 to 6f),
            sampleRateForPreamp = rate,
        )
        val eq = EqEngine().apply { configure(rate, channels) }
        val fader = CompareFader().apply { configure(rate); snapToEqualized() }

        val dry = FloatArray(quantum)
        val wet = FloatArray(quantum)
        var previous = 0.0
        var worstJump = 0.0
        var n = 0

        for (block in 0 until 100) {
            if (block == 20) fader.request(false, settings.compareDryTrimDb)
            if (block == 50) fader.request(true, settings.compareDryTrimDb)
            if (block == 80) fader.release()

            for (i in 0 until quantum) dry[i] = (amp * sin(w * (n + i))).toFloat()
            System.arraycopy(dry, 0, wet, 0, quantum)
            eq.process(settings, wet, quantum)
            fader.blend(dry, wet, quantum, channels)

            for (i in 0 until quantum) {
                val v = wet[i].toDouble()
                if (n + i > 0) {
                    val jump = abs(v - previous)
                    if (jump > worstJump) worstJump = jump
                }
                previous = v
            }
            n += quantum
        }

        // The filtered signal has its own slope, which at these gains is
        // larger than the raw sine's, so the bound allows for that and still
        // catches a step change, which would be orders of magnitude bigger.
        assertTrue(
            "largest sample-to-sample jump was ${"%.6f".format(worstJump)}, " +
                "against a natural sine slope of ${"%.6f".format(naturalJump)}",
            worstJump < naturalJump * 4,
        )
    }

    // ---- the loudness match --------------------------------------------

    /**
     * On material whose spectrum matches the weighting model, the match
     * should be near exact. This validates the arithmetic itself.
     */
    @Test
    fun matchIsAccurateOnMaterialThatMatchesTheModel() {
        val frames = rate
        // The exact shape FrequencyResponse assumes: pink between 60 Hz and
        // 1 kHz, -4 dB/octave above, -9 dB/octave below. Getting this wrong
        // is how the first version of this test failed -- it applied only the
        // treble tilt, so a bass-boost curve was bound to disagree with a
        // model that also rolls off the bottom.
        val signal = pinkLike(
            frames,
            tiltDbPerOct = -4.0, tiltFromHz = 1000.0,
            lowRolloffDbPerOct = 9.0, lowFromHz = 60.0,
        )

        for (curve in listOf(
            bands(0 to 8f, 1 to 6f),
            bands(8 to 8f, 9 to 6f),
            bands(4 to 6f, 5 to 6f),
            bands(2 to -8f, 6 to 5f),
        )) {
            val settings = EqSettings(enabled = true, bands = curve, sampleRateForPreamp = rate)
            val wet = throughEq(settings, signal)
            val dryTrimmed = 10.0.pow(settings.compareDryTrimDb / 20.0)

            val measured = db(rms(wet) / (rms(signal) * dryTrimmed))
            println("MODEL trim=${"%.2f".format(settings.compareDryTrimDb)}dB " +
                "residual=${"%.2f".format(measured)}dB")
            assertTrue(
                "curve $curve left a ${"%.2f".format(measured)} dB mismatch",
                abs(measured) < 1.0,
            )
        }
    }

    /**
     * On pure pink, which is NOT what the model assumes, the match is worse.
     *
     * This is the number that says how approximate "approximately" is, so it
     * is asserted loosely and printed rather than hidden. The point is that
     * the residual is small compared with the thing it replaces: without any
     * match at all these curves are 5 to 8 dB down, which is the difference
     * between "sounds different" and "sounds broken".
     */
    @Test
    fun matchIsApproximateOnPurePinkAndStillFarBetterThanNothing() {
        val frames = rate
        val signal = pinkLike(frames, tiltDbPerOct = 0.0)

        for (curve in listOf(
            bands(0 to 8f, 1 to 6f),
            bands(8 to 8f, 9 to 6f),
            bands(4 to 6f, 5 to 6f),
        )) {
            val settings = EqSettings(enabled = true, bands = curve, sampleRateForPreamp = rate)
            val wet = throughEq(settings, signal)

            val unmatched = db(rms(wet) / rms(signal))
            val dryTrimmed = 10.0.pow(settings.compareDryTrimDb / 20.0)
            val matched = db(rms(wet) / (rms(signal) * dryTrimmed))

            println(
                "PINK curve=$curve unmatched=${"%.2f".format(unmatched)}dB " +
                    "matched=${"%.2f".format(matched)}dB " +
                    "trim=${"%.2f".format(settings.compareDryTrimDb)}dB",
            )
            assertTrue(
                "match left ${"%.2f".format(matched)} dB on pure pink",
                abs(matched) < 2.5,
            )
            assertTrue(
                "matching made it worse: unmatched ${"%.2f".format(unmatched)} " +
                    "vs matched ${"%.2f".format(matched)}",
                abs(matched) < abs(unmatched),
            )
        }
    }

    /** The trim is an attenuation, always, for every curve the UI can make. */
    @Test
    fun theTrimIsNeverABoost() {
        val steps = listOf(-6f, -3f, 0f, 3f, 6f)
        for (p in Preset.entries) {
            for (b in steps) {
                for (wv in steps) {
                    val st = SoundState(enabled = true, preset = p).withBass(b).withWarmth(wv)
                    val settings = st.toEqSettings(rate)
                    assertTrue(
                        "${p.name} bass=$b warmth=$wv gave a trim of " +
                            "${settings.compareDryTrimDb}",
                        settings.compareDryTrimDb <= 0f,
                    )
                }
            }
        }
    }

    /** A flat curve needs no match at all, so Compare is transparent on it. */
    @Test
    fun flatNeedsNoTrim() {
        assertEquals(0f, EqSettings(enabled = true).compareDryTrimDb, 1e-6f)
    }

    /**
     * Compare must not touch the preamp.
     *
     * The level match is a property of the comparison, not of the sound, so
     * leaving Compare mode has to leave the listener exactly where they were.
     * The trim lives on the fader, the preamp lives in the settings, and
     * nothing here may move the latter.
     */
    @Test
    fun headroomIsUnchangedByCompare() {
        val st = SoundState(enabled = true, preset = Preset.Bass).withBass(6f)
        val before = st.toEqSettings(rate).preampDb

        val fader = CompareFader().apply { configure(rate); snapToEqualized() }
        fader.request(showEqualized = false, dryTrimDb = st.toEqSettings(rate).compareDryTrimDb)
        val dry = FloatArray(480) { 0.3f }
        repeat(50) {
            val wet = FloatArray(480) { 0.3f }
            fader.blend(dry, wet, 480, 1)
        }
        fader.release()
        repeat(50) {
            val wet = FloatArray(480) { 0.3f }
            fader.blend(dry, wet, 480, 1)
        }

        assertEquals(before, st.toEqSettings(rate).preampDb, 0f)
        assertTrue("the fader must come home", fader.atRest())
    }

    /**
     * Once home, the fader is a pass-through for the filtered signal.
     *
     * This is what makes handing the stream back to the ordinary playback
     * path silent: at rest the two produce identical samples.
     */
    @Test
    fun atRestTheFaderIsBitIdentical() {
        val fader = CompareFader().apply { configure(rate); snapToEqualized() }
        val dry = FloatArray(256) { 0.4f }
        val wet = FloatArray(256) { i -> 0.1f * (i % 7) }
        val expected = wet.copyOf()
        fader.blend(dry, wet, 256, 1)
        for (i in wet.indices) assertEquals(expected[i], wet[i], 0f)
    }
}
