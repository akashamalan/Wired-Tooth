package com.wiredtooth.receiver.dsp

/**
 * What the user chose, as distinct from what the filters do.
 *
 * WHY THIS IS NOT [EqSettings]. EqSettings describes ten biquads: a frequency,
 * a gain and a Q each. That is what the DSP needs and it is the wrong thing to
 * store, because it throws away the user's intent. "Bass +4" and "somebody
 * dragged the three lowest sliders to +4, +4 and +2.4" produce identical band
 * lists, so a UI that round-trips through EqSettings cannot redraw its own
 * macro slider after a restart. So the choice is stored, and [toEqSettings] is
 * a one-way function from choice to filters. Persistence saves this type.
 *
 * All of it is pure Kotlin with no Android imports, so the mapping is testable
 * on the desktop JVM like the rest of the DSP.
 */
data class SoundState(
    val enabled: Boolean = false,
    val preset: Preset = Preset.Flat,
    /** Bass macro, in dB, [-MACRO_LIMIT_DB] .. [MACRO_LIMIT_DB]. */
    val bassDb: Float = 0f,
    /** Warmth macro, same range. */
    val warmthDb: Float = 0f,
    /**
     * A hand-edited curve, or null while the preset and macros are in charge.
     *
     * Editing a single band in Advanced freezes the generated curve into this
     * list and edits it from then on, because once you have moved one band by
     * hand the macros no longer describe what you are hearing and pretending
     * otherwise would make the next macro drag discard your edit silently.
     * Choosing a preset or moving a macro clears it, which is the documented
     * way back out.
     */
    val custom: List<Float>? = null,
    /**
     * The loaded earbud profile, or null.
     *
     * The BASE of the curve, not part of it: the preset and the macros are
     * layered over the profile rather than mixed into it, because a profile
     * is a correction for a particular pair of earbuds and the other two are
     * taste. Keeping them separate is what lets [GainBudget] protect the
     * profile from the macros rather than averaging the two together.
     */
    val profile: ProfileParser.Profile? = null,
) {
    /** The ten band gains this state asks for, in the order of [EqSettings.CENTRES_HZ]. */
    fun gains(): List<Float> {
        custom?.let { c ->
            return List(EqSettings.BAND_COUNT) {
                (c.getOrNull(it) ?: 0f).coerceIn(EqSettings.MIN_GAIN_DB, MAX_BOOST_DB)
            }
        }
        return List(EqSettings.BAND_COUNT) { i ->
            rawGain(i).coerceIn(EqSettings.MIN_GAIN_DB, MAX_BOOST_DB)
        }
    }

    /** The preset and macros summed, before the boost cap. */
    private fun rawGain(i: Int): Float =
        preset.gains[i] + bassDb * BASS_WEIGHTS[i] + warmthDb * WARMTH_WEIGHTS[i]

    /**
     * How many bands the boost cap is currently holding back.
     *
     * Only meaningful on the generated path, and deliberately zero once the
     * curve has been hand-edited: there the user set the number themselves and
     * the slider already stopped at the cap, so there is nothing hidden to
     * disclose. The cap is worth mentioning precisely when it is invisible --
     * when a preset and a macro both pushed the same band and the sum was
     * quietly trimmed.
     */
    fun cappedBandCount(): Int {
        if (custom != null) return 0
        var n = 0
        for (i in 0 until EqSettings.BAND_COUNT) {
            if (rawGain(i) > MAX_BOOST_DB + 1e-4f) n++
        }
        return n
    }

    /**
     * The filter description, measured against the rate that is actually open.
     *
     * The rate matters: the preamp is the negative of the true peak of the
     * combined response, and that response is a function of the sample rate.
     * Passing the real rate is what keeps the headroom figure shown to the
     * user equal to the gain actually applied.
     */
    fun toEqSettings(sampleRate: Int): EqSettings {
        val rate = if (sampleRate > 0) sampleRate else 48000
        return EqSettings(
            enabled = enabled,
            autoPreamp = true,
            limiter = true,
            bands = layered(rate).bands,
            sampleRateForPreamp = rate,
        )
    }

    /**
     * The profile and the macro curve, fitted into the shared gain budget.
     *
     * Separate from [toEqSettings] because the sheet needs more than the
     * bands: it has to say whether the profile was reduced, whether the
     * macros were squeezed, and how many filters hit the per-filter ceiling.
     * Computing it twice would risk the explanation and the filters coming
     * from two different answers.
     */
    fun layered(sampleRate: Int): GainBudget.Layered = GainBudget.layer(
        profile?.filters ?: emptyList(),
        gains(),
        if (sampleRate > 0) sampleRate else 48000,
    )

    val isCustom: Boolean get() = custom != null

    fun withPreset(p: Preset): SoundState = copy(preset = p, custom = null)

    fun withBass(db: Float): SoundState =
        copy(bassDb = db.coerceIn(-MACRO_LIMIT_DB, MACRO_LIMIT_DB), custom = null)

    fun withWarmth(db: Float): SoundState =
        copy(warmthDb = db.coerceIn(-MACRO_LIMIT_DB, MACRO_LIMIT_DB), custom = null)

    /** Editing one band by hand. Freezes the current curve and edits that. */
    fun withBandGain(index: Int, db: Float): SoundState {
        if (index !in 0 until EqSettings.BAND_COUNT) return this
        val next = gains().toMutableList()
        next[index] = db.coerceIn(EqSettings.MIN_GAIN_DB, MAX_BOOST_DB)
        return copy(custom = next)
    }

    /** Back to the preset and macros, discarding hand edits. */
    fun withoutCustom(): SoundState = copy(custom = null)

    /**
     * Loads a profile, which resets the preset to Flat and keeps the macros.
     *
     * WHY THE PRESET GOES AND THE MACROS STAY. A profile is a complete
     * starting curve, which is exactly what a preset is, so keeping both
     * would stack two opinions about the same thing and the result would
     * sound like neither. The macros are different: they are a nudge the user
     * applies to whatever the base happens to be, so carrying them over means
     * "a bit more bass than standard" still means that after the profile
     * arrives. A hand-edited curve goes with the preset, because once a
     * profile is the base, a curve frozen from the old base describes nothing.
     */
    fun withProfile(p: ProfileParser.Profile?): SoundState =
        copy(profile = p?.takeIf { !it.isEmpty }, preset = Preset.Flat, custom = null)

    fun withoutProfile(): SoundState = copy(profile = null)

    val hasProfile: Boolean get() = profile != null

    companion object {
        /**
         * The most any one band may be boosted, after the preset and the
         * macros have been summed.
         *
         * WHY 8 AND NOT THE ENGINE'S 12. The cost of a boost is not the boost,
         * it is the preamp that pays for it, and that cost grows faster than
         * the number on the slider because neighbouring bands overlap and sum.
         * Measured on this curve set, a preset at +6 with the matching macro
         * at +6 reached the old +12 ceiling on three bands and cost 15.3 dB of
         * headroom -- a reduction large enough that the honest description of
         * the feature became "it makes everything quieter and slightly
         * different", which is not what anyone wants from a bass control.
         *
         * Eight decibels on a single band is still a large, clearly audible
         * move. What it is not is a move that spends the whole dynamic range
         * paying for itself.
         *
         * ASYMMETRIC ON PURPOSE. Cuts stay at [EqSettings.MIN_GAIN_DB]. A
         * cascade of peaking sections that only cuts cannot exceed unity, so a
         * cut costs no headroom at all and there is nothing here to protect
         * against. Capping both directions would remove useful range for no
         * benefit.
         *
         * The engine's own limit stays at 12: this is a limit on the curve the
         * user can ask for, not on what the filters can do, and keeping the
         * two separate means a stored curve from an older build is clamped
         * rather than rejected.
         */
        const val MAX_BOOST_DB = 8f

        /**
         * The macro sliders stop at 6 dB, half of what a single band allows.
         *
         * A macro moves three bands at once and overlapping bands sum, so a
         * macro at its limit already produces more than 6 dB of real peak.
         * Letting it reach 12 would stack a preset on top of that and spend
         * the whole range on preamp reduction -- the curve would get more
         * extreme while everything got quieter. Six is where it still sounds
         * like an adjustment rather than a different recording.
         */
        const val MACRO_LIMIT_DB = 6f

        /**
         * Which bands the Bass macro moves: 31, 62, and 125 Hz tapered.
         *
         * 125 Hz gets 0.6 rather than 1.0 because that is where bass stops
         * being bass and starts being the bottom of the mid-range, where a
         * full boost reads as mud rather than weight. Tapering also means the
         * macro's curve fades into the untouched bands instead of ending at a
         * cliff, which is audible as a resonance at the edge.
         */
        val BASS_WEIGHTS = floatArrayOf(1f, 1f, 0.6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        /**
         * Warmth: 250 and 500 Hz up, and a slight cut from 4 kHz.
         *
         * Warmth is a balance, not an addition. Adding lower-mids alone makes
         * a recording louder and boxy; taking a little off the top at the same
         * time is what actually reads as warm, and it costs less headroom
         * because the cut does not raise the peak.
         *
         * The weights are signed, so a negative warmth setting is the honest
         * mirror image: lower-mids down, treble slightly up, which is the
         * thinner, brighter direction people reach for when a voice sounds
         * muffled.
         */
        val WARMTH_WEIGHTS =
            floatArrayOf(0f, 0f, 0f, 1f, 0.7f, 0f, 0f, -0.3f, -0.3f, -0.2f)
    }
}

/**
 * The four starting points.
 *
 * Deliberately few, and deliberately named after what you want rather than
 * what the filter does: "Vocal", not "mid-forward with a low-shelf cut". Each
 * is a complete curve, so picking one is a single tap that fully describes the
 * sound, and the macros then nudge it.
 */
enum class Preset(val label: String, val gains: List<Float>) {
    /** No filtering at all, and therefore no preamp and nothing to explain. */
    Flat("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),

    /** Weight at the bottom, falling away by 250 Hz so it does not get boxy. */
    Bass("Bass", listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),

    /**
     * Speech. The lift sits at 500 Hz to 2 kHz where consonants live, and the
     * two lowest bands come down, because room rumble and desk noise are the
     * things that actually make a voice hard to follow.
     */
    Vocal("Vocal", listOf(-3f, -2f, 0f, 1f, 3f, 4f, 3f, 1f, 0f, 0f)),

    /** Air and detail. Nothing below 1 kHz is touched, so it stays balanced. */
    Bright("Bright", listOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 5f, 3f));

    companion object {
        fun byName(name: String?): Preset =
            entries.firstOrNull { it.name == name } ?: Flat
    }
}
