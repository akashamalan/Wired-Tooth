package com.wiredtooth.receiver.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared gain budget: the profile keeps its depth, the macros take the
 * squeeze, and the headroom line can never read worse than -14.0 dB.
 *
 * The four profiles below are the same four used to find the problem in the
 * first place. Three of them are adversarial by construction -- a profile
 * arrives as a text file, so "ten filters at the ceiling on one frequency"
 * is a thing a file can say -- and the fourth is a mild, plausible
 * correction that must come through untouched.
 *
 * NONE OF THESE IS MEASURED DATA. They are shapes written here to exercise
 * the arithmetic. No third-party profile data is bundled anywhere in this
 * project.
 */
class GainBudgetTest {

    private val fs = 48000

    /** The worst curve the sliders can reach: Bass preset, both macros at max. */
    private val macrosAtMax: List<Float> =
        SoundState(enabled = true, preset = Preset.Bass, bassDb = 6f, warmthDb = 6f).gains()

    private val macrosFlat: List<Float> = List(EqSettings.BAND_COUNT) { 0f }

    // ---- the four profiles ----------------------------------------------

    /** A plausible six-filter correction. Should sail through untouched. */
    private val mild = listOf(
        EqBand(105f, -3.2f, 0.70f, BandType.LowShelf),
        EqBand(2800f, 3.5f, 1.20f),
        EqBand(5500f, -4.0f, 2.00f),
        EqBand(180f, -1.5f, 0.90f),
        EqBand(900f, 1.0f, 1.00f),
        EqBand(10000f, 2.0f, 0.70f, BandType.HighShelf),
    )

    /** Ten filters at the per-filter ceiling, spread across the spectrum. */
    private val spread = (0 until 10).map {
        EqBand((20.0 * Math.pow(500.0, it / 9.0)).toFloat(), 8f, 0.70f)
    }

    /** Both shelves and eight peaks, all at the ceiling. */
    private val shelvesAndPeaks = listOf(
        EqBand(105f, 8f, 0.70f, BandType.LowShelf),
        EqBand(10000f, 8f, 0.70f, BandType.HighShelf),
    ) + (0 until 8).map { EqBand((125.0 * Math.pow(2.0, it * 0.5)).toFloat(), 8f, 0.70f) }

    /** The pathological case: every filter stacked on one frequency. */
    private val stacked = (0 until 10).map { EqBand(1000f, 8f, 0.70f) }

    private fun allFour() = listOf(
        "mild" to mild,
        "spread" to spread,
        "shelvesAndPeaks" to shelvesAndPeaks,
        "stacked" to stacked,
    )

    private fun headroomOf(l: GainBudget.Layered): Float =
        FrequencyResponse.preampDb(l.bands, fs)

    // ---- the guarantee ---------------------------------------------------

    /**
     * The headroom floor, which is the whole reason this class exists.
     *
     * -14.0 dB is [GainBudget.TOTAL_PEAK_CEILING_DB] plus the preamp's half
     * decibel of margin. A hundredth of a decibel of slack is allowed for the
     * bisection stopping just short of the boundary.
     */
    @Test
    fun everyProfileLandsAtMinusFourteenDbOrBetter() {
        for ((name, profile) in allFour()) {
            val l = GainBudget.layer(profile, macrosAtMax, fs)
            val hr = headroomOf(l)
            assertTrue(
                "$name gave headroom $hr dB, worse than -14.0",
                hr >= -14.01f,
            )
        }
    }

    /** The same floor holds with the macros flat, and with no profile at all. */
    @Test
    fun theFloorHoldsForEveryMacroSetting() {
        for ((name, profile) in allFour()) {
            for (macros in listOf(macrosFlat, macrosAtMax)) {
                val hr = headroomOf(GainBudget.layer(profile, macros, fs))
                assertTrue("$name gave $hr dB", hr >= -14.01f)
            }
        }
        val none = GainBudget.layer(emptyList(), macrosAtMax, fs)
        assertTrue(headroomOf(none) >= -14.01f)
    }

    /**
     * The bisection never returns a scale it has not measured as fitting.
     *
     * This is the property that makes the near-convexity argument safe
     * rather than load-bearing: even if the peak were not convex in the
     * scale, a tested lower bound cannot breach the ceiling.
     */
    @Test
    fun theResultAlwaysFitsTheCeilingItWasGiven() {
        for ((name, profile) in allFour()) {
            val l = GainBudget.layer(profile, macrosAtMax, fs)
            val peak = FrequencyResponse.peakGainDb(l.bands, fs)
            assertTrue(
                "$name combined peak $peak exceeds the ceiling",
                peak <= GainBudget.TOTAL_PEAK_CEILING_DB + 0.01,
            )
            val profileOnly = l.bands.drop(EqSettings.BAND_COUNT)
            if (profileOnly.isNotEmpty()) {
                val pp = FrequencyResponse.peakGainDb(profileOnly, fs)
                assertTrue(
                    "$name profile peak $pp exceeds its own ceiling",
                    pp <= GainBudget.PROFILE_PEAK_CEILING_DB + 0.01,
                )
            }
        }
    }

    // ---- the priority flip ----------------------------------------------

    /**
     * Moving Bass or Warmth must never change the profile.
     *
     * The earbud correction is a statement about the hardware; the macros are
     * taste. If a slider could scale the profile, the correction would get
     * quietly weaker as somebody reached for more bass.
     */
    @Test
    fun theMacrosNeverChangeTheProfile() {
        for ((name, profile) in allFour()) {
            val flat = GainBudget.layer(profile, macrosFlat, fs)
            val max = GainBudget.layer(profile, macrosAtMax, fs)
            assertEquals(
                "$name profile scale moved with the macros",
                flat.profileScale, max.profileScale, 0.0,
            )
            assertEquals(
                "$name profile bands moved with the macros",
                flat.bands.drop(EqSettings.BAND_COUNT),
                max.bands.drop(EqSettings.BAND_COUNT),
            )
        }
    }

    /** A mild profile is left completely alone, and so are the macros. */
    @Test
    fun aMildProfileAndTheMacrosBothSurviveIntact() {
        val l = GainBudget.layer(mild, macrosAtMax, fs)
        assertEquals("profile should be untouched", 1.0, l.profileScale, 0.0)
        assertEquals("macros should be untouched", 1.0, l.macroScale, 0.0)
        assertTrue(!l.profileReduced && !l.macrosReduced)
        assertEquals(0, l.cappedFilterCount)
    }

    /** An over-eager profile is scaled; the macros then get what is left. */
    @Test
    fun anOverEagerProfileIsScaledAndTheMacrosAreSqueezed() {
        val l = GainBudget.layer(spread, macrosAtMax, fs)
        assertTrue("profile should have been reduced", l.profileReduced)
        assertTrue("macros should have been squeezed", l.macrosReduced)
        assertTrue("profile scale ${l.profileScale}", l.profileScale in 0.40..0.70)
        assertTrue("macro scale ${l.macroScale}", l.macroScale in 0.20..0.55)
    }

    /**
     * A narrow profile spike leaves the macros alone.
     *
     * The stacked profile's peak is a spike at 1 kHz, where the Bass-plus-
     * Warmth curve is flat, so the two peaks do not coincide and there is
     * nothing to take off the macros. Worth asserting because it shows the
     * budget is measuring the real combined response rather than adding two
     * worst cases together.
     */
    @Test
    fun aNarrowProfileSpikeDoesNotCostTheMacrosAnything() {
        val l = GainBudget.layer(stacked, macrosAtMax, fs)
        assertTrue("profile should have been reduced hard", l.profileScale < 0.2)
        assertEquals("macros should be untouched", 1.0, l.macroScale, 0.0)
    }

    // ---- the per-filter ceiling -----------------------------------------

    @Test
    fun filtersOverTheCeilingAreCappedAndCounted() {
        val profile = listOf(
            EqBand(100f, 12f, 1f),
            EqBand(1000f, 8f, 1f),
            EqBand(4000f, 9.5f, 1f),
            EqBand(8000f, -20f, 1f),
        )
        val l = GainBudget.layer(profile, macrosFlat, fs)
        assertEquals("two filters asked for more than +8", 2, l.cappedFilterCount)

        val got = l.bands.drop(EqSettings.BAND_COUNT)
        // Capped at +8 and -12 before any scaling, so the ratios survive it.
        val scale = l.profileScale.toFloat()
        assertEquals(8f * scale, got[0].gainDb, 1e-4f)
        assertEquals(8f * scale, got[1].gainDb, 1e-4f)
        assertEquals(8f * scale, got[2].gainDb, 1e-4f)
        assertEquals(-12f * scale, got[3].gainDb, 1e-4f)
    }

    @Test
    fun aProfileIsTruncatedToTheBandsThatFit() {
        val profile = (0 until 30).map { EqBand(100f + it * 100f, 2f, 1f) }
        val l = GainBudget.layer(profile, macrosFlat, fs)
        assertEquals(
            "profile must not exceed its half of the engine",
            GainBudget.MAX_PROFILE_FILTERS,
            l.bands.size - EqSettings.BAND_COUNT,
        )
        assertTrue(l.bands.size <= EqSettings.MAX_BANDS)
    }

    // ---- layout ----------------------------------------------------------

    /**
     * The macro layer stays at indices 0..9 whatever the profile does.
     *
     * The engine keeps one filter state per index, so a profile arriving must
     * not renumber the bands the listener is already hearing.
     */
    @Test
    fun theMacroLayerKeepsTheFirstTenIndices() {
        for ((_, profile) in allFour()) {
            val l = GainBudget.layer(profile, macrosAtMax, fs)
            for (i in 0 until EqSettings.BAND_COUNT) {
                assertEquals(
                    "band $i should be the macro centre",
                    EqSettings.CENTRES_HZ[i], l.bands[i].frequencyHz, 0f,
                )
                assertEquals(BandType.Peaking, l.bands[i].type)
            }
        }
    }

    /** Shelf types survive the trip through the budget. */
    @Test
    fun shelfTypesSurviveScaling() {
        val l = GainBudget.layer(shelvesAndPeaks, macrosAtMax, fs)
        val got = l.bands.drop(EqSettings.BAND_COUNT)
        assertEquals(BandType.LowShelf, got[0].type)
        assertEquals(BandType.HighShelf, got[1].type)
        assertEquals(105f, got[0].frequencyHz, 0f)
        assertEquals(0.70f, got[0].q, 0f)
    }

    /** No profile at all leaves the existing behaviour exactly as it was. */
    @Test
    fun noProfileMeansNoChangeToTheTenBandCurve() {
        val l = GainBudget.layer(emptyList(), macrosAtMax, fs)
        assertEquals(EqSettings.BAND_COUNT, l.bands.size)
        assertEquals(1.0, l.macroScale, 0.0)
        assertEquals(EqSettings.fromGains(macrosAtMax), l.bands)
        // The pre-profile measurement, unchanged.
        assertEquals(-11.60f, FrequencyResponse.preampDb(l.bands, fs), 0.05f)
    }
}
