package com.wiredtooth.receiver.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow

/**
 * The equalizer. Up to [EqSettings.MAX_BANDS] peaking or shelving sections
 * per channel, an automatic preamp in front and a limiter behind, running on
 * the playback thread with no allocation and no locks.
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
 *
 * WHY IDENTITY SECTIONS ARE DROPPED, AND WHY THAT IS BIT-EXACT. Twenty
 * sections are allocated but a curve rarely uses them all, and at 0 dB a
 * section is the identity filter: b0 = 1 and every other coefficient 0. Feed
 * that through the difference equation below and the state empties itself
 * within two samples --
 *
 *     y  = x + s0      s0 = s1      s1 = 0
 *
 * so after the first sample s1 is 0, after the second s0 is 0 too, and from
 * then on y = x with both state words at exactly zero. A section in that
 * condition contributes nothing and holds nothing, so removing it from the
 * cascade changes not one bit of the output. [FLUSH_SAMPLES] is that
 * two-sample wait, counted in samples rather than control blocks because the
 * last block of a buffer can be shorter than one.
 *
 * The saving is real in both directions: a profile loaded with six filters
 * and the macros at flat runs six sections, not twenty, and not the ten it
 * would have run before profiles existed.
 */
class EqEngine {

    private val settingsRef = AtomicReference(EqSettings())

    private var sampleRate = 0
    private var channels = 0

    private var coef = DoubleArray(0)
    private var state = DoubleArray(0)

    private val curGain = DoubleArray(EqSettings.MAX_BANDS)
    private val curFreq = DoubleArray(EqSettings.MAX_BANDS)
    private val curQ = DoubleArray(EqSettings.MAX_BANDS)
    private val curType = arrayOfNulls<BandType>(EqSettings.MAX_BANDS)

    /**
     * The sections the render loop runs, in cascade order, and how many.
     *
     * Rebuilt once per control block, which is where the gain smoothing has
     * just decided which bands are at zero. Ascending order always, because
     * cascaded biquads commute in exact arithmetic but not in floating point,
     * and a cascade that silently reorders itself would make the regression
     * tests non-reproducible.
     */
    private val activeBands = IntArray(EqSettings.MAX_BANDS)
    private var activeCount = 0

    /**
     * Samples each band has been processed for since its gain reached zero,
     * capped at [FLUSH_SAMPLES]. Reaching the cap is what makes it safe to
     * drop the band from the cascade.
     */
    private val flushedSamples = IntArray(EqSettings.MAX_BANDS)

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
        coef = DoubleArray(EqSettings.MAX_BANDS * Biquad.COEFFS)
        state = DoubleArray(EqSettings.MAX_BANDS * channels * 2)
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
        // Every state word is zero, so any band that is at 0 dB is already in
        // the flushed condition and can be dropped on the very first block.
        java.util.Arrays.fill(flushedSamples, FLUSH_SAMPLES)
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
            val active = activeCount
            var f = 0
            while (f < block) {
                val base = (frame + f) * channels
                var c = 0
                while (c < channels) {
                    var x = samples[base + c].toDouble() * pre
                    var i = 0
                    while (i < active) {
                        val b = activeBands[i]
                        x = Biquad.processSample(
                            x, coef, b * Biquad.COEFFS, state, (b * channels + c) * 2,
                        )
                        i++
                    }
                    samples[base + c] = x.toFloat()
                    c++
                }
                if (s.limiter) limiter.processFrame(samples, base, channels)
                f++
            }

            noteBlockProcessed(block)
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

        activeCount = 0
        var b = 0
        while (b < EqSettings.MAX_BANDS) {
            val band = s.bands.getOrNull(b)
            val targetGain = (band?.gainDb ?: 0f).toDouble()
                .coerceIn(EqSettings.MIN_GAIN_DB.toDouble(), EqSettings.MAX_GAIN_DB.toDouble())
            // A band past the end of the list is on its way out: its gain
            // glides to zero, and holding its frequency where it already was
            // keeps that glide from also sweeping the filter across the
            // spectrum on the way down.
            val targetFreq = band?.frequencyHz?.toDouble()
                ?: if (curFreq[b] > 0.0) curFreq[b] else ABSENT_HZ
            val targetQ = (band?.q ?: EqSettings.DEFAULT_Q).toDouble()
            val targetType = band?.type ?: BandType.Peaking

            val oldGain = curGain[b]
            val oldFreq = curFreq[b]
            val oldQ = curQ[b]

            curGain[b] = approach(oldGain, targetGain, maxGainStep)
            curQ[b] = approach(oldQ, targetQ, maxQStep)
            curFreq[b] = approachByRatio(oldFreq, targetFreq, maxOctaves)

            if (snapNext ||
                curGain[b] != oldGain || curFreq[b] != oldFreq || curQ[b] != oldQ ||
                curType[b] != targetType
            ) {
                // The shape changes the instant the type does. There is no
                // smoothing between shapes, because there is no meaningful
                // half-way filter between a peak and a shelf -- but a type
                // only ever changes when a profile is loaded or cleared, and
                // the band's gain is smoothed across that same moment, so the
                // audible transition is the gain's and not the shape's.
                curType[b] = targetType
                Biquad.design(
                    coef, b * Biquad.COEFFS, targetType,
                    curFreq[b], curQ[b], curGain[b], sampleRate.toDouble(),
                )
            }

            if (curGain[b] != 0.0) {
                flushedSamples[b] = 0
                activeBands[activeCount++] = b
            } else if (flushedSamples[b] < FLUSH_SAMPLES) {
                // At zero but not yet emptied: keep running it precisely so
                // that it does empty.
                activeBands[activeCount++] = b
            }
            b++
        }

        // Already measured off the audio thread; this only glides towards it.
        val targetPreampDb = if (s.autoPreamp) s.preampDb.toDouble() else 0.0
        curPreampDb = approach(curPreampDb, targetPreampDb, maxGainStep)
        preampGain = 10.0.pow(curPreampDb / 20.0)

        snapNext = false
    }

    /**
     * Credits the block just processed towards each identity band's flush.
     *
     * Only bands that actually ran are credited, which is what keeps the
     * two-sample guarantee honest when a buffer ends mid-block.
     */
    private fun noteBlockProcessed(block: Int) {
        var i = 0
        while (i < activeCount) {
            val b = activeBands[i]
            if (curGain[b] == 0.0 && flushedSamples[b] < FLUSH_SAMPLES) {
                flushedSamples[b] = minOf(FLUSH_SAMPLES, flushedSamples[b] + block)
            }
            i++
        }
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

        /**
         * Samples an identity section must run for before it is safe to drop.
         *
         * Two, exactly: see the class comment for the two lines of algebra
         * that prove the state is empty by then.
         */
        const val FLUSH_SAMPLES = 2

        /** Where a band with no entry in the list parks. Inaudible at 0 dB. */
        private const val ABSENT_HZ = 1000.0

        private const val MAX_GAIN_DB_PER_SEC = 120.0
        private const val MAX_Q_PER_SEC = 8.0
        private const val MAX_OCTAVES_PER_SEC = 4.0
    }
}
