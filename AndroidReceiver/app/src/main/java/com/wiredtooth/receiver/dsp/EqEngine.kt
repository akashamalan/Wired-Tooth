package com.wiredtooth.receiver.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow

/**
 * The equalizer. Ten peaking bands per channel, an automatic preamp in front
 * and a limiter behind, running on the playback thread with no allocation and
 * no locks.
 *
 * HOW SETTINGS CROSS THREADS. The UI writes a whole [EqSettings] into an
 * atomic reference; the audio thread reads it once per call. There is no lock
 * anywhere on the render path, because a lock on an audio thread means the UI
 * thread can stall playback, and a stalled playback thread is a dropout. An
 * atomic swap of an immutable object gives the audio thread a coherent view
 * for free.
 *
 * WHY PARAMETERS ARE SMOOTHED AND COEFFICIENTS ARE NOT. Moving a slider must
 * not click. The obvious fix, fading between the old and new coefficient sets,
 * is wrong: the set of stable biquads is not convex, so a blend of two stable
 * filters can be unstable, and the failure mode is a burst of noise rather
 * than a quiet glitch. Instead the PARAMETERS are rate-limited towards their
 * targets, and a fresh coefficient set is designed from the smoothed values
 * every [CONTROL_BLOCK] samples. Every set is therefore a real filter that
 * was designed properly, and consecutive sets are close together.
 *
 * The control block is 32 samples, 0.67 ms at 48 kHz, so a full 12 dB move
 * takes about a tenth of a second across roughly 150 redesigns. Bands whose
 * smoothed parameters did not move are not redesigned at all, so a curve at
 * rest costs nothing beyond the filtering itself.
 */
class EqEngine {

    private val settingsRef = AtomicReference(EqSettings())

    private var sampleRate = 0
    private var channels = 0

    private var coef = DoubleArray(0)
    private var state = DoubleArray(0)

    private val curGain = DoubleArray(EqSettings.BAND_COUNT)
    private val curFreq = DoubleArray(EqSettings.BAND_COUNT)
    private val curQ = DoubleArray(EqSettings.BAND_COUNT)

    private var curPreampDb = 0.0
    private var preampGain = 1.0

    private val limiter = Limiter()

    /** True until the first control update after a reset, which snaps rather than glides. */
    private var snapNext = true

    /**
     * How many times a non-finite sample forced a state reset.
     *
     * Should always be zero. It is counted rather than ignored because a
     * poisoned filter state never recovers on its own: once a NaN enters the
     * feedback path every later sample is NaN, and the stream is silent or
     * noise until the app restarts. The guard is cheap because it runs once
     * per control block, not once per sample.
     */
    @Volatile
    var instabilityResets = 0L
        private set

    // ---- configuration -------------------------------------------------

    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate <= 0 || channels <= 0) return
        if (this.sampleRate == sampleRate && this.channels == channels) return

        this.sampleRate = sampleRate
        this.channels = channels
        coef = DoubleArray(EqSettings.BAND_COUNT * Biquad.COEFFS)
        state = DoubleArray(EqSettings.BAND_COUNT * channels * 2)
        limiter.configure(sampleRate)
        reset()
    }

    /**
     * Clears filter memory.
     *
     * Call this where the signal genuinely restarts: a format change, the
     * output being reopened, a disconnect. Deliberately NOT called on a
     * per-stream packet gap. Clearing an IIR filter's state mid-stream makes
     * its output jump from whatever it had settled to down to zero, which is
     * a click -- precisely the thing the parameter smoothing exists to avoid.
     * The filters are also shared by every stream, so a gap in one stream
     * would punch a hole in all the others.
     */
    fun reset() {
        java.util.Arrays.fill(state, 0.0)
        limiter.reset()
        snapNext = true
    }

    fun snapshot(): EqSettings = settingsRef.get()

    fun apply(settings: EqSettings) {
        settingsRef.set(settings)
    }

    /** 1.0 means the limiter is not working; lower means it is pulling the level down. */
    fun limiterGain(): Double = limiter.minGainSeen

    fun resetLimiterMetering() = limiter.resetMetering()

    // ---- render path ---------------------------------------------------

    /**
     * Filters [frames] interleaved frames in place.
     *
     * Pass the same snapshot the caller used to decide whether to call at all,
     * so the decision and the processing cannot disagree.
     */
    fun process(s: EqSettings, samples: FloatArray, frames: Int) {
        if (!s.enabled) return                       // bypass leaves the buffer untouched
        if (sampleRate <= 0 || channels <= 0) return
        if (frames <= 0) return
        if (frames * channels > samples.size) return

        var frame = 0
        while (frame < frames) {
            val block = minOf(CONTROL_BLOCK, frames - frame)
            updateControl(s)

            val pre = preampGain
            val bands = EqSettings.BAND_COUNT
            var f = 0
            while (f < block) {
                val base = (frame + f) * channels
                var c = 0
                while (c < channels) {
                    var x = samples[base + c].toDouble() * pre
                    var b = 0
                    while (b < bands) {
                        x = Biquad.processSample(
                            x, coef, b * Biquad.COEFFS, state, (b * channels + c) * 2,
                        )
                        b++
                    }
                    samples[base + c] = x.toFloat()
                    c++
                }
                if (s.limiter) limiter.processFrame(samples, base, channels)
                f++
            }

            guardAgainstInstability(samples, (frame + block - 1) * channels)
            frame += block
        }
    }

    /**
     * One finiteness check per control block.
     *
     * A stable biquad driven by finite input cannot produce NaN, so this
     * should never fire. It exists because the cost of being wrong is
     * permanent: NaN in the feedback state poisons every subsequent sample.
     */
    private fun guardAgainstInstability(samples: FloatArray, base: Int) {
        var c = 0
        while (c < channels) {
            val v = samples[base + c]
            if (!v.isFinite()) {
                java.util.Arrays.fill(state, 0.0)
                limiter.reset()
                instabilityResets++
                var k = 0
                while (k < channels) {
                    samples[base + k] = 0f
                    k++
                }
                return
            }
            c++
        }
    }

    // ---- control rate --------------------------------------------------

    private fun updateControl(s: EqSettings) {
        val dt = CONTROL_BLOCK.toDouble() / sampleRate
        val maxGainStep = if (snapNext) Double.MAX_VALUE else MAX_GAIN_DB_PER_SEC * dt
        val maxQStep = if (snapNext) Double.MAX_VALUE else MAX_Q_PER_SEC * dt
        val maxOctaves = if (snapNext) Double.MAX_VALUE else MAX_OCTAVES_PER_SEC * dt

        var b = 0
        while (b < EqSettings.BAND_COUNT) {
            val band = s.bands.getOrNull(b)
            val targetGain = (band?.gainDb ?: 0f).toDouble()
                .coerceIn(EqSettings.MIN_GAIN_DB.toDouble(), EqSettings.MAX_GAIN_DB.toDouble())
            val targetFreq = (band?.frequencyHz ?: EqSettings.CENTRES_HZ[b]).toDouble()
            val targetQ = (band?.q ?: EqSettings.DEFAULT_Q).toDouble()

            val oldGain = curGain[b]
            val oldFreq = curFreq[b]
            val oldQ = curQ[b]

            curGain[b] = approach(oldGain, targetGain, maxGainStep)
            curQ[b] = approach(oldQ, targetQ, maxQStep)
            curFreq[b] = approachByRatio(oldFreq, targetFreq, maxOctaves)

            if (snapNext ||
                curGain[b] != oldGain || curFreq[b] != oldFreq || curQ[b] != oldQ
            ) {
                Biquad.designPeaking(
                    coef, b * Biquad.COEFFS,
                    curFreq[b], curQ[b], curGain[b], sampleRate.toDouble(),
                )
            }
            b++
        }

        val targetPreampDb = if (s.autoPreamp) -s.maxBoostDb().toDouble() else 0.0
        curPreampDb = approach(curPreampDb, targetPreampDb, maxGainStep)
        preampGain = 10.0.pow(curPreampDb / 20.0)

        snapNext = false
    }

    private fun approach(current: Double, target: Double, maxStep: Double): Double {
        val delta = target - current
        if (abs(delta) <= maxStep) return target
        return current + if (delta > 0) maxStep else -maxStep
    }

    /** Frequency glides in octaves, because pitch is heard logarithmically. */
    private fun approachByRatio(current: Double, target: Double, maxOctaves: Double): Double {
        if (current <= 0.0 || maxOctaves == Double.MAX_VALUE) return target
        val limit = 2.0.pow(maxOctaves)
        val ratio = target / current
        return when {
            ratio > limit -> current * limit
            ratio < 1.0 / limit -> current / limit
            else -> target
        }
    }

    companion object {
        /** Samples between coefficient redesigns. 0.67 ms at 48 kHz. */
        const val CONTROL_BLOCK = 32

        private const val MAX_GAIN_DB_PER_SEC = 120.0
        private const val MAX_Q_PER_SEC = 8.0
        private const val MAX_OCTAVES_PER_SEC = 4.0
    }
}
