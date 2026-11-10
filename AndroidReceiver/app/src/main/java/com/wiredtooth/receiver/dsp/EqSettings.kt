package com.wiredtooth.receiver.dsp

/**
 * One parametric band: a centre frequency, a boost or cut, and a width.
 *
 * Q is the usual bandwidth control. 1.41 is a little under one octave, which
 * is wide enough that ten bands overlap into a smooth curve rather than ten
 * separate bumps.
 */
data class EqBand(
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float,
)

/**
 * A complete, immutable description of what the equalizer should be doing.
 *
 * Immutable on purpose. This is the only thing that crosses from the UI thread
 * to the audio thread, and it crosses as a single atomic reference swap, so the
 * audio thread either sees the whole old setting or the whole new one. It can
 * never see a half-applied curve, and it never takes a lock to find out.
 */
data class EqSettings(
    val enabled: Boolean = false,
    /**
     * Subtract the largest boost in the curve before filtering.
     *
     * This is what stops "turn the bass up" from turning into distortion.
     * The amount comes from [preampDb], which measures the real peak of the
     * combined curve rather than assuming the largest single band is the
     * highest point. Overlapping bands sum, so that assumption was wrong by
     * however much the neighbours contributed.
     *
     * Still not a guarantee on its own. It flattens the steady-state peak;
     * a transient can briefly exceed it, which is what the limiter and then
     * the hard clamp are for.
     */
    val autoPreamp: Boolean = true,
    val limiter: Boolean = true,
    val bands: List<EqBand> = defaultBands(),
    /**
     * The rate the preamp is computed against.
     *
     * The response of a digital filter depends on the sample rate, so the
     * true peak does too. In practice the sender is always 48 kHz, but the
     * receiver takes the rate from the stream, so the caller passes whatever
     * is actually open and this stops being an assumption.
     */
    val sampleRateForPreamp: Int = 48000,
) {
    /**
     * How far to pull the signal down before filtering, in dB. Never a boost.
     *
     * Computed here, at construction, which is the whole point: constructing
     * settings happens on the UI thread when somebody moves a slider, and
     * the audio thread then reads one number out of an immutable object. A
     * few thousand sines and cosines are free in the first place and
     * impossible in the second.
     */
    val preampDb: Float =
        if (autoPreamp) FrequencyResponse.preampDb(bands, sampleRateForPreamp) else 0f

    /**
     * The largest single boost in the curve.
     *
     * No longer what drives the preamp -- it under-corrects whenever bands
     * overlap, which at one-octave spacing is most of the time. Kept because
     * the difference between this and [preampDb] is exactly the error the
     * true-peak scan exists to remove, and the tests assert on it.
     */
    fun maxBoostDb(): Float {
        var m = 0f
        for (b in bands) if (b.gainDb > m) m = b.gainDb
        return m
    }

    companion object {
        const val BAND_COUNT = 10

        /**
         * Ten bands, one per octave from 31 Hz up.
         *
         * The top band sits at 16 kHz rather than 20 kHz because at a 44.1 kHz
         * sample rate 20 kHz is above the Nyquist limit where a peaking filter
         * stops behaving like one. 16 kHz is safe at every rate this receiver
         * will see.
         */
        val CENTRES_HZ = floatArrayOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
        )

        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        const val DEFAULT_Q = 1.41f

        fun defaultBands(): List<EqBand> =
            CENTRES_HZ.map { EqBand(it, 0f, DEFAULT_Q) }

        /** A curve from ten gains, in the order of [CENTRES_HZ]. */
        fun fromGains(gains: List<Float>): List<EqBand> =
            CENTRES_HZ.mapIndexed { i, hz ->
                EqBand(hz, (gains.getOrNull(i) ?: 0f).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB), DEFAULT_Q)
            }
    }
}
