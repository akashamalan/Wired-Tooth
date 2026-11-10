package com.wiredtooth.receiver.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from what the user chose to what the filters do.
 *
 * Worth testing on its own because it is the layer where a mistake is silent:
 * a wrong weight does not crash, it just makes the Bass slider adjust the
 * wrong thing, and nobody notices until they are listening on hardware.
 */
class SoundStateTest {

    private val rate = 48000

    // ---- defaults and bypass -------------------------------------------

    @Test
    fun defaultIsOffAndFlat() {
        val s = SoundState()
        assertFalse(s.enabled)
        assertEquals(Preset.Flat, s.preset)
        assertTrue(s.gains().all { it == 0f })
        assertEquals(0f, s.toEqSettings(rate).preampDb, 0f)
    }

    @Test
    fun flatWithNoMacrosCostsNoHeadroom() {
        val s = SoundState(enabled = true)
        assertEquals(0f, s.toEqSettings(rate).preampDb, 0f)
    }

    /** A zero rate must not reach the filter designer. */
    @Test
    fun aMissingSampleRateFallsBackRatherThanDividingByZero() {
        val s = SoundState(enabled = true, preset = Preset.Bass)
        assertEquals(48000, s.toEqSettings(0).sampleRateForPreamp)
        assertEquals(48000, s.toEqSettings(-1).sampleRateForPreamp)
        assertTrue(s.toEqSettings(0).preampDb < 0f)
    }

    // ---- the macros ----------------------------------------------------

    /** Bass moves 31, 62 and 125 Hz, and nothing at or above 250. */
    @Test
    fun bassMacroTouchesOnlyTheLowestThreeBands() {
        val g = SoundState(enabled = true).withBass(6f).gains()
        assertEquals(6f, g[0], 1e-4f)
        assertEquals(6f, g[1], 1e-4f)
        assertEquals(3.6f, g[2], 1e-4f)         // tapered on purpose
        for (i in 3 until EqSettings.BAND_COUNT) {
            assertEquals("band $i must not move", 0f, g[i], 1e-4f)
        }
    }

    /** Warmth lifts 250 and 500, and takes a little off from 4 kHz up. */
    @Test
    fun warmthMacroLiftsLowerMidsAndTrimsTreble() {
        val g = SoundState(enabled = true).withWarmth(6f).gains()
        assertEquals(0f, g[0], 1e-4f)
        assertEquals(6f, g[3], 1e-4f)
        assertEquals(4.2f, g[4], 1e-4f)
        assertEquals(0f, g[5], 1e-4f)
        assertTrue("4 kHz should be cut, was ${g[7]}", g[7] < 0f)
        assertTrue("8 kHz should be cut, was ${g[8]}", g[8] < 0f)
        assertTrue("16 kHz should be cut, was ${g[9]}", g[9] < 0f)
    }

    /** Negative warmth is the honest mirror: mids down, treble slightly up. */
    @Test
    fun negativeWarmthIsTheMirrorImage() {
        val g = SoundState(enabled = true).withWarmth(-6f).gains()
        assertEquals(-6f, g[3], 1e-4f)
        assertTrue(g[7] > 0f)
    }

    @Test
    fun macrosAreCappedAtSixDb() {
        assertEquals(6f, SoundState().withBass(99f).bassDb, 0f)
        assertEquals(-6f, SoundState().withBass(-99f).bassDb, 0f)
        assertEquals(6f, SoundState().withWarmth(99f).warmthDb, 0f)
        assertEquals(-6f, SoundState().withWarmth(-99f).warmthDb, 0f)
    }

    /** A preset plus a macro cannot push a band past the boost cap. */
    @Test
    fun presetPlusMacroStaysInsideTheBandRange() {
        val g = SoundState(enabled = true, preset = Preset.Bass).withBass(6f).gains()
        for ((i, v) in g.withIndex()) {
            assertTrue(
                "band $i was $v",
                v >= EqSettings.MIN_GAIN_DB && v <= SoundState.MAX_BOOST_DB,
            )
        }
        // 31 Hz: preset +6, macro +6, so it must be capped rather than +12.
        assertEquals(SoundState.MAX_BOOST_DB, g[0], 1e-4f)
    }

    // ---- presets -------------------------------------------------------

    @Test
    fun everyPresetHasOneGainPerBand() {
        for (p in Preset.entries) {
            assertEquals(p.name, EqSettings.BAND_COUNT, p.gains.size)
            for (v in p.gains) {
                assertTrue(
                    "${p.name} has an out-of-range gain $v",
                    v >= EqSettings.MIN_GAIN_DB && v <= EqSettings.MAX_GAIN_DB,
                )
            }
        }
    }

    /**
     * Every preset that boosts must cost headroom, and Flat must not.
     *
     * This is the contract the Sound sheet's explanation rests on. If a
     * boosting preset reported zero headroom, the sheet would be claiming the
     * level is unchanged while the limiter quietly worked.
     */
    @Test
    fun boostingPresetsCostHeadroomAndFlatDoesNot() {
        assertEquals(
            0f,
            SoundState(enabled = true, preset = Preset.Flat).toEqSettings(rate).preampDb,
            0f,
        )
        for (p in listOf(Preset.Bass, Preset.Vocal, Preset.Bright)) {
            val preamp = SoundState(enabled = true, preset = p).toEqSettings(rate).preampDb
            assertTrue("${p.name} reported $preamp", preamp < 0f)
            // And it must exceed the largest single band, because the bands
            // overlap -- that is the whole point of the true-peak scan.
            val largest = p.gains.max()
            assertTrue(
                "${p.name}: preamp $preamp should exceed largest band $largest",
                -preamp > largest,
            )
        }
    }

    @Test
    fun presetNameRoundTripsAndUnknownNamesFallBackToFlat() {
        for (p in Preset.entries) assertEquals(p, Preset.byName(p.name))
        assertEquals(Preset.Flat, Preset.byName(null))
        assertEquals(Preset.Flat, Preset.byName("Nonsense"))
        assertEquals(Preset.Flat, Preset.byName(""))
    }

    // ---- custom curves -------------------------------------------------

    /** Editing a band freezes the generated curve rather than discarding it. */
    @Test
    fun editingABandKeepsTheRestOfTheCurve() {
        val before = SoundState(enabled = true, preset = Preset.Vocal).withBass(3f)
        val generated = before.gains()
        val after = before.withBandGain(6, -5f)

        assertTrue(after.isCustom)
        assertEquals(-5f, after.gains()[6], 1e-4f)
        for (i in 0 until EqSettings.BAND_COUNT) {
            if (i == 6) continue
            assertEquals("band $i should be unchanged", generated[i], after.gains()[i], 1e-4f)
        }
    }

    @Test
    fun aBandEditIsClampedToTheBandRange() {
        val s = SoundState(enabled = true).withBandGain(0, 99f)
        assertEquals(SoundState.MAX_BOOST_DB, s.gains()[0], 0f)
        assertEquals(EqSettings.MIN_GAIN_DB, s.withBandGain(0, -99f).gains()[0], 0f)
    }

    @Test
    fun anOutOfRangeBandIndexIsIgnoredRatherThanThrowing() {
        val s = SoundState(enabled = true)
        assertEquals(s, s.withBandGain(-1, 5f))
        assertEquals(s, s.withBandGain(EqSettings.BAND_COUNT, 5f))
    }

    /** Choosing a preset or moving a macro is the documented way out of custom. */
    @Test
    fun presetsAndMacrosClearAHandEditedCurve() {
        val custom = SoundState(enabled = true).withBandGain(4, 7f)
        assertTrue(custom.isCustom)

        assertFalse(custom.withPreset(Preset.Bright).isCustom)
        assertFalse(custom.withBass(1f).isCustom)
        assertFalse(custom.withWarmth(1f).isCustom)
        assertFalse(custom.withoutCustom().isCustom)

        // And the edit really is gone, not merely unflagged.
        assertEquals(0f, custom.withoutCustom().gains()[4], 1e-4f)
    }

    /** Turning the switch off must not throw away the curve underneath it. */
    @Test
    fun togglingEnabledKeepsEverythingElse() {
        val s = SoundState(enabled = true, preset = Preset.Bright).withBass(2.5f)
        val off = s.copy(enabled = false)
        assertEquals(s.gains(), off.gains())
        assertEquals(Preset.Bright, off.preset)
        assertEquals(2.5f, off.bassDb, 0f)
        // Bypassed, so the engine does nothing -- but the description is intact.
        assertFalse(off.toEqSettings(rate).enabled)
    }

    /** A short or malformed stored curve must not produce a short band list. */
    @Test
    fun aTruncatedCustomCurveIsPaddedRatherThanTrusted() {
        val s = SoundState(enabled = true, custom = listOf(3f, 3f))
        assertEquals(EqSettings.BAND_COUNT, s.gains().size)
        assertEquals(3f, s.gains()[0], 0f)
        assertEquals(0f, s.gains()[9], 0f)
        assertEquals(EqSettings.BAND_COUNT, s.toEqSettings(rate).bands.size)
    }

    // ---- the boost cap -------------------------------------------------

    /**
     * No route through the UI may put a band above the cap.
     *
     * Every preset crossed with every extreme of both macros, because the cap
     * exists for exactly the combination the user can reach by accident: a
     * preset that already boosts a band and a macro that boosts the same one.
     */
    @Test
    fun noPresetAndMacroCombinationExceedsTheBoostCap() {
        val macroSteps = listOf(-6f, -3f, 0f, 3f, 6f)
        var sawTheCapBite = false
        for (p in Preset.entries) {
            for (b in macroSteps) {
                for (w in macroSteps) {
                    val s = SoundState(enabled = true, preset = p).withBass(b).withWarmth(w)
                    for ((i, v) in s.gains().withIndex()) {
                        assertTrue(
                            "${p.name} bass=$b warmth=$w band $i was $v",
                            v <= SoundState.MAX_BOOST_DB + 1e-4f,
                        )
                        assertTrue(
                            "${p.name} bass=$b warmth=$w band $i was $v",
                            v >= EqSettings.MIN_GAIN_DB - 1e-4f,
                        )
                    }
                    if (s.cappedBandCount() > 0) sawTheCapBite = true
                }
            }
        }
        assertTrue("no combination reached the cap, so this proves nothing", sawTheCapBite)
    }

    /** A hand-edited band is held to the same ceiling. */
    @Test
    fun handEditedBandsObeyTheSameCap() {
        for (i in 0 until EqSettings.BAND_COUNT) {
            val s = SoundState(enabled = true).withBandGain(i, 12f)
            assertEquals(SoundState.MAX_BOOST_DB, s.gains()[i], 1e-4f)
        }
        // Including a curve that arrived from storage written by an older build.
        val stored = SoundState(enabled = true, custom = List(EqSettings.BAND_COUNT) { 12f })
        assertTrue(stored.gains().all { it <= SoundState.MAX_BOOST_DB + 1e-4f })
    }

    /** Cuts are deliberately NOT capped: they cost no headroom. */
    @Test
    fun cutsKeepTheFullRange() {
        val s = SoundState(enabled = true).withBandGain(0, -12f)
        assertEquals(EqSettings.MIN_GAIN_DB, s.gains()[0], 0f)
        assertEquals(0f, s.toEqSettings(rate).preampDb, 0f)
    }

    /**
     * The cap hint fires when the cap is invisible, and not otherwise.
     *
     * Zero on a hand-edited curve on purpose: there the slider stopped at the
     * cap in front of the user, so there is nothing hidden to disclose.
     */
    @Test
    fun theCapHintFiresOnlyWhenTheCapIsHidden() {
        assertEquals(0, SoundState(enabled = true).cappedBandCount())
        assertEquals(0, SoundState(enabled = true, preset = Preset.Bass).cappedBandCount())

        val stacked = SoundState(enabled = true, preset = Preset.Bass).withBass(6f)
        assertTrue(
            "preset +6 and macro +6 on the same band must report the cap",
            stacked.cappedBandCount() > 0,
        )

        assertEquals(0, stacked.withBandGain(0, 8f).cappedBandCount())
    }

    /**
     * The point of the cap: the worst reachable headroom cost.
     *
     * Before the cap the worst combination cost 15.3 dB, which is a reduction
     * large enough to make the feature feel like a volume control that also
     * changes the tone. The cap has to bring that down substantially, and it
     * must not have cost Flat its freedom or the presets their effect.
     */
    @Test
    fun theWorstReachableHeadroomCostIsBoundedByTheCap() {
        val macroSteps = listOf(-6f, -3f, 0f, 3f, 6f)
        var worst = 0f
        var worstLabel = ""
        for (p in Preset.entries) {
            for (b in macroSteps) {
                for (w in macroSteps) {
                    val s = SoundState(enabled = true, preset = p).withBass(b).withWarmth(w)
                    val preamp = s.toEqSettings(rate).preampDb
                    if (preamp < worst) {
                        worst = preamp
                        worstLabel = "${p.name} bass=$b warmth=$w"
                    }
                }
            }
        }
        assertTrue(
            "worst reachable headroom was $worst dB at $worstLabel, " +
                "which is no better than the 15.3 dB the cap was added to fix",
            worst > -12f,
        )
        assertTrue("the presets must still do something", worst < -5f)
    }

    /** All ten bands at the cap is the true worst case of the whole feature. */
    @Test
    fun tenBandsAtTheCapIsTheAbsoluteWorstCase() {
        val all = SoundState(enabled = true, custom = List(EqSettings.BAND_COUNT) { 99f })
        assertTrue(all.gains().all { it == SoundState.MAX_BOOST_DB })
        val preamp = all.toEqSettings(rate).preampDb
        // Ten overlapping bands at +8 sum well past 8, which is exactly the
        // effect the true-peak scan exists to measure rather than assume.
        assertTrue("absolute worst case was $preamp dB", preamp < -8f)
        assertTrue("absolute worst case was $preamp dB", preamp > -16f)
    }

    // ---- the whole pipeline --------------------------------------------

    @Test
    fun toEqSettingsCarriesTenBandsAtTheRightCentres() {
        val settings = SoundState(enabled = true, preset = Preset.Bass).toEqSettings(44100)
        assertEquals(EqSettings.BAND_COUNT, settings.bands.size)
        assertEquals(44100, settings.sampleRateForPreamp)
        for (i in 0 until EqSettings.BAND_COUNT) {
            assertEquals(EqSettings.CENTRES_HZ[i], settings.bands[i].frequencyHz, 0f)
            assertEquals(EqSettings.DEFAULT_Q, settings.bands[i].q, 0f)
        }
        assertTrue(settings.enabled)
        assertTrue(settings.autoPreamp)
        assertTrue(settings.limiter)
    }

    /**
     * The preamp is rate-dependent, which is why the receiver recomputes it on
     * a format change instead of carrying the old figure forward.
     */
    @Test
    fun theSameCurveNeedsADifferentPreampAtADifferentRate() {
        val s = SoundState(enabled = true, preset = Preset.Bright)
        assertNotEquals(
            s.toEqSettings(48000).preampDb,
            s.toEqSettings(44100).preampDb,
        )
    }

    /** A cut-only curve is free, whatever route it was built by. */
    @Test
    fun cutOnlyCurvesCostNothing() {
        val s = SoundState(enabled = true).withBandGain(0, -8f).withBandGain(5, -4f)
        assertEquals(0f, s.toEqSettings(rate).preampDb, 0f)
    }
}
