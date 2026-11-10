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
) {
    /** The ten band gains this state asks for, in the order of [EqSettings.CENTRES_HZ]. */
    fun gains(): List<Float> {
        custom?.let { c ->
            return List(EqSettings.BAND_COUNT) {
                (c.getOrNull(it) ?: 0f).coerceIn(EqSettings.MIN_GAIN_DB, EqSettings.MAX_GAIN_DB)
            }
        }
        val base = preset.gains
        return List(EqSettings.BAND_COUNT) { i ->
            (base[i] + bassDb * BASS_WEIGHTS[i] + warmthDb * WARMTH_WEIGHTS[i])
                .coerceIn(EqSettings.MIN_GAIN_DB, EqSettings.MAX_GAIN_DB)
        }
    }

    /**
     * The filter description, measured against the rate that is actually open.
     *
     * The rate matters: the preamp is the negative of the true peak of the
     * combined response, and that response is a function of the sample rate.
     * Passing the real rate is what keeps the headroom figure shown to the
     * user equal to the gain actually applied.
     */
    fun toEqSettings(sampleRate: Int): EqSettings = EqSettings(
        enabled = enabled,
        autoPreamp = true,
        limiter = true,
        bands = EqSettings.fromGains(gains()),
        sampleRateForPreamp = if (sampleRate > 0) sampleRate else 48000,
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
        next[index] = db.coerceIn(EqSettings.MIN_GAIN_DB, EqSettings.MAX_GAIN_DB)
        return copy(custom = next)
    }

    /** Back to the preset and macros, discarding hand edits. */
    fun withoutCustom(): SoundState = copy(custom = null)

    companion object {
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
