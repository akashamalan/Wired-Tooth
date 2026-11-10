package com.wiredtooth.receiver

import com.wiredtooth.receiver.dsp.EqEngine
import com.wiredtooth.receiver.dsp.EqSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the equalizer's code once, at app start, on a throwaway engine, so that
 * the first real quantum is not also the first quantum ever.
 *
 * THE PROBLEM THIS SOLVES. Measured on the emulator, the first 10 ms quantum
 * after switching the equalizer on mid-stream took about 140 ms. Nothing is
 * wrong with the DSP: that quantum is the first time the runtime has ever
 * executed the biquad inner loop, the limiter or the float conversion, so it
 * runs interpreted while the JIT decides whether to compile it. A 140 ms
 * quantum against a 10 ms budget is a late write to AudioTrack, which is a
 * dropout -- at exactly the moment the user taps the control and is listening
 * for a change.
 *
 * WHY A THROWAWAY ENGINE AND NOT THE REAL ONE. The real engine's filter state
 * is part of the audio signal path. Pushing a hundred blocks of anything
 * through it would leave that state primed with whatever was pushed, and
 * clearing it afterwards would be one more thing to get wrong. A separate
 * instance that is dropped on the floor cannot affect playback by
 * construction.
 *
 * WHY THE LOUD BURST. The brief said silent blocks, and silence is most of
 * what runs here, but silence leaves the limiter's gain-reduction branch cold:
 * with a zero signal the limiter never engages, so the code that handles a
 * peak is still uncompiled when the first loud passage arrives. A short
 * full-scale burst at the end compiles that branch too. It is an extra few
 * milliseconds on a background thread and it closes the one hole silence
 * leaves.
 *
 * WHAT THIS CANNOT PROMISE. ART is not the desktop JVM and the emulator's
 * x86 JIT is not a phone's. On a real device the same code may be
 * ahead-of-time compiled from a baseline profile, in which case there was
 * never much of a spike and this is cheap insurance; or the profile may be
 * absent on first launch, in which case this is doing real work. Which of
 * those is true on any given phone is not knowable from here.
 */
internal object EqWarmup {

    private val started = AtomicBoolean(false)

    /** True once [warm] has run. Only the first call does anything. */
    @Volatile
    var warmed = false
        private set

    /** Nanoseconds the warm-up itself took, for reporting. */
    @Volatile
    var tookNanos = 0L
        private set

    /**
     * Quanta of silence to push through.
     *
     * One second of audio at 48 kHz in 10 ms blocks. ART's JIT promotes a
     * method after it has been entered enough times, and the biquad inner
     * loop runs 9,600 times per quantum at 10 bands and 2 channels, so the
     * hot path crosses any plausible threshold within the first few blocks.
     * The rest is margin, and it costs a few tens of milliseconds on a
     * thread nobody is waiting for.
     */
    const val SILENT_QUANTA = 100

    /** Quanta of full-scale signal, purely to compile the limiter's reduction path. */
    const val LOUD_QUANTA = 20

    /**
     * Must not be called on the playback thread or the main thread.
     *
     * It deliberately does a second or so of DSP work; the whole point is to
     * spend that time somewhere nobody is listening.
     */
    fun warm(sampleRate: Int = 48000, channels: Int = 2) {
        if (!started.compareAndSet(false, true)) return
        val begun = System.nanoTime()
        runCatching {
            val engine = EqEngine()
            engine.configure(sampleRate, channels)

            // Constructing this also runs the 256-point FrequencyResponse scan,
            // which is the other piece of cold code the UI hits the instant
            // somebody moves a slider.
            val settings = EqSettings(
                enabled = true,
                autoPreamp = true,
                limiter = true,
                bands = EqSettings.fromGains(List(EqSettings.BAND_COUNT) { 6f }),
                sampleRateForPreamp = sampleRate,
            )

            val frames = maxOf(sampleRate / 100, 1)
            val samples = frames * channels
            val mix = IntArray(samples)
            val buf = FloatArray(samples)
            val out = ByteArray(samples * 2)

            repeat(SILENT_QUANTA) {
                java.util.Arrays.fill(mix, 0)
                // The same three calls the playback loop makes, in the same
                // order, so the whole EQ branch is compiled and not just the
                // filters. toFloat and the float overload of clampInto run
                // ONLY on the EQ path, so the bypass stream never warms them.
                Mixing.toFloat(mix, buf, samples)
                engine.process(settings, buf, frames)
                Mixing.clampInto(buf, out, samples)
            }

            repeat(LOUD_QUANTA) {
                var i = 0
                while (i < samples) {
                    buf[i] = if (i and 1 == 0) 1.0f else -1.0f
                    i++
                }
                engine.process(settings, buf, frames)
                Mixing.clampInto(buf, out, samples)
            }

            // A second settings object, so the UI's slider path is compiled
            // rather than only the audio one.
            EqSettings(
                enabled = true,
                bands = EqSettings.fromGains(List(EqSettings.BAND_COUNT) { -3f }),
                sampleRateForPreamp = sampleRate,
            )
        }
        tookNanos = System.nanoTime() - begun
        warmed = true
    }
}
