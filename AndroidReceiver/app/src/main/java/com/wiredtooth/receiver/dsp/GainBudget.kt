package com.wiredtooth.receiver.dsp

/**
 * Shares one gain budget between an imported profile and the user's own
 * preset-and-macro curve.
 *
 * WHY A SHARED BUDGET IS NEEDED AT ALL. A ceiling on each filter does not
 * bound a cascade. Ten filters each held to +8 dB, all sitting on the same
 * frequency, is +80 dB; the true-peak preamp dutifully subtracts all 80, and
 * the result is silence rather than distortion. So the thing that has to be
 * bounded is the peak of the combined response, not the gain of any one
 * section. A per-filter ceiling is still applied first -- it stops one
 * absurd line in a file from dominating the shape -- but it is not what
 * keeps the output audible.
 *
 * WHY THE PROFILE WINS. A profile is a correction for a particular pair of
 * earbuds: it is the closest thing here to a statement of fact about the
 * hardware, and it is not something the listener is adjusting moment to
 * moment. Bass and Warmth are taste, and they are attached to sliders. So
 * the profile is scaled only against its own ceiling, never against the
 * macros, and moving a slider can never change it. Whatever budget is left
 * goes to the macro layer. The alternative -- letting the macros push the
 * profile around -- would mean the earbud correction quietly got weaker as
 * somebody reached for more bass, which is backwards.
 *
 * WHERE THIS RUNS. The UI thread, on a profile load or a slider move, never
 * the audio thread. Each bisection step is a 256-point magnitude scan over
 * up to twenty sections; that is nothing next to a frame of UI work and far
 * too much inside a 10 ms render quantum.
 */
internal object GainBudget {

    /**
     * The most a profile's own combined response may peak at, in dB.
     *
     * Ten leaves a real profile untouched -- a measured correction's own
     * peak is usually a few dB, and its own suggested preamp is typically
     * -5 to -8 dB -- while still cutting an adversarial file down to
     * something audible.
     */
    const val PROFILE_PEAK_CEILING_DB = 10.0

    /**
     * The most the whole cascade may peak at, in dB.
     *
     * The preamp subtracts the peak plus [FrequencyResponse.HEADROOM_MARGIN_DB],
     * so this ceiling is exactly the headroom floor: 13.5 dB here means the
     * Headroom line can never read worse than -14.0 dB. It stays the real
     * measured number; this bounds it rather than hiding it.
     */
    const val TOTAL_PEAK_CEILING_DB = 13.5

    /**
     * Bisection steps. 24 halvings resolve the scale to under 1e-7, which is
     * far finer than any audible difference and costs 24 scans.
     */
    private const val BISECT_STEPS = 24

    /** The most profile filters that fit alongside the ten-band macro layer. */
    const val MAX_PROFILE_FILTERS = EqSettings.MAX_BANDS - EqSettings.BAND_COUNT

    /**
     * The finished cascade plus everything the UI needs to explain it.
     *
     * The three counts and scales map one-to-one onto the three things the
     * sheet may have to say, so the UI never has to re-derive why a curve
     * came out the way it did.
     */
    data class Layered(
        /** Macro layer at indices 0..9, profile from 10. Ready for [EqSettings]. */
        val bands: List<EqBand>,
        /** 1.0 when the profile was left alone. */
        val profileScale: Double,
        /** 1.0 when the macro layer was left alone. */
        val macroScale: Double,
        /** Profile filters whose own gain was clipped to the per-filter ceiling. */
        val cappedFilterCount: Int,
    ) {
        val profileReduced: Boolean get() = profileScale < 0.999
        val macrosReduced: Boolean get() = macroScale < 0.999
    }

    /**
     * Lays the macro gains over [profile] and fits the pair into the budget.
     *
     * [macroGains] are the ten already-capped gains from the user's preset
     * and macros, in [EqSettings.CENTRES_HZ] order.
     *
     * ORDER IN THE LIST IS DELIBERATE. The macro layer occupies indices 0..9
     * and the profile follows from 10. Cascaded biquads commute, so this
     * makes no audible difference -- but the engine keeps one filter state
     * per index, so pinning the macro bands to fixed indices means loading or
     * clearing a profile cannot disturb the state of the bands the listener
     * was already hearing. Putting the profile first would renumber the macro
     * bands every time a profile arrived, and renumbering a running IIR
     * filter is a click.
     */
    fun layer(
        profile: List<EqBand>,
        macroGains: List<Float>,
        sampleRate: Int,
    ): Layered {
        // 1. Per-filter ceiling, and count what it caught. Done first so the
        //    scaling below works on a shape no single line can dominate.
        var capped = 0
        val clamped = ArrayList<EqBand>(MAX_PROFILE_FILTERS)
        for (band in profile) {
            if (clamped.size >= MAX_PROFILE_FILTERS) break
            if (band.gainDb > SoundState.MAX_BOOST_DB) capped++
            clamped.add(
                band.copy(
                    gainDb = band.gainDb
                        .coerceIn(EqSettings.MIN_GAIN_DB, SoundState.MAX_BOOST_DB),
                ),
            )
        }

        // 2. The profile against its own ceiling only. Nothing about the
        //    macros enters here, which is the whole point of the priority.
        val profileScale =
            if (clamped.isEmpty()) 1.0
            else largestScale(PROFILE_PEAK_CEILING_DB, sampleRate) { s ->
                scaled(clamped, s)
            }
        val fittedProfile = scaled(clamped, profileScale)

        // 3. The macro layer gets what is left of the total.
        val macroBands = EqSettings.fromGains(macroGains)
        val macroScale =
            if (macroBands.none { it.gainDb != 0f }) 1.0
            else largestScale(TOTAL_PEAK_CEILING_DB, sampleRate) { k ->
                scaled(macroBands, k) + fittedProfile
            }

        return Layered(
            bands = scaled(macroBands, macroScale) + fittedProfile,
            profileScale = profileScale,
            macroScale = macroScale,
            cappedFilterCount = capped,
        )
    }

    /** Every band's gain multiplied by [scale]. Shape preserved, depth reduced. */
    private fun scaled(bands: List<EqBand>, scale: Double): List<EqBand> =
        if (scale >= 1.0) bands
        else bands.map { it.copy(gainDb = (it.gainDb * scale).toFloat()) }

    /**
     * The largest scale in [0, 1] whose cascade peaks no higher than
     * [ceilingDb].
     *
     * WHY BISECTION IS SOUND HERE, GIVEN THAT THE PEAK IS NOT MONOTONE IN THE
     * SCALE. It is not: a macro layer that cuts where the profile peaks makes
     * the combined peak fall for a while as the macros come up. What saves
     * the method is convexity. The peak is the maximum, over frequency, of
     * the summed dB response, and each frequency's response is very nearly
     * proportional to the scale -- so the peak is close to a maximum of
     * straight lines in the scale, and a maximum of straight lines is convex.
     * A convex function's sublevel set is an interval, so the scales that fit
     * form one unbroken range rather than scattered islands, and that range
     * always contains 0 because at scale 0 the layer is the identity and the
     * ceiling is met by construction. One interval anchored at 0 means one
     * crossing, which is exactly what bisection needs.
     *
     * AND IF THAT CONVEXITY EVER FAILED. It is near-exact rather than proven
     * -- a biquad's dB response is not quite linear in its gain -- so the
     * loop is written so that being wrong is harmless rather than unsafe.
     * [lo] is only ever moved to a scale that has been measured and found to
     * fit, so the value returned has always been tested. A convexity failure
     * could therefore only make the answer conservative: slightly less macro
     * than was really available. It can never return a scale that breaches
     * the ceiling, which is the property the headroom guarantee rests on.
     */
    private inline fun largestScale(
        ceilingDb: Double,
        sampleRate: Int,
        bandsAt: (Double) -> List<EqBand>,
    ): Double {
        if (FrequencyResponse.peakGainDb(bandsAt(1.0), sampleRate) <= ceilingDb) return 1.0

        var lo = 0.0   // measured to fit, always
        var hi = 1.0   // measured not to fit, or untested at the start
        var step = 0
        while (step < BISECT_STEPS) {
            val mid = (lo + hi) * 0.5
            if (FrequencyResponse.peakGainDb(bandsAt(mid), sampleRate) <= ceilingDb) {
                lo = mid
            } else {
                hi = mid
            }
            step++
        }
        return lo
    }
}
