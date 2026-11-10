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

    /** A preset plus a macro cannot push a band past the band limit. */
    @Test
    fun presetPlusMacroStaysInsideTheBandRange() {
        val g = SoundState(enabled = true, preset = Preset.Bass).withBass(6f).gains()
        for ((i, v) in g.withIndex()) {
            assertTrue(
                "band $i was $v",
                v >= EqSettings.MIN_GAIN_DB && v <= EqSettings.MAX_GAIN_DB,
            )
        }
        // 31 Hz: preset +6, macro +6, so it must be clamped rather than +12.
        assertEquals(EqSettings.MAX_GAIN_DB, g[0], 1e-4f)
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
        assertEquals(EqSettings.MAX_GAIN_DB, s.gains()[0], 0f)
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
