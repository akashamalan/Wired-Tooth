package com.wiredtooth.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.wiredtooth.receiver.dsp.EqEngine
import com.wiredtooth.receiver.dsp.EqSettings
import com.wiredtooth.receiver.dsp.SoundState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Everything the UI shows. Snapshotted once per refresh, never held. */
data class ReceiverStatus(
    val state: String,
    val senderIp: String,
    val format: String,
    val packetsReceived: Long,
    val packetsLost: Long,
    val packetsDropped: Long,
    val underruns: Long,
    val lossPercent: Double,
    val rttMs: Double,
    val bufferMs: Double,
    val correctionRatio: Double,
    val kbitPerSecond: Double,
    val streams: List<StreamStatus> = emptyList(),
    /**
     * Samples the final hard clamp had to saturate since connecting.
     *
     * Should stay near zero. A rising count means the preamp and the limiter
     * are not holding the signal and the listener is hearing clipping.
     */
    val clampedSamples: Long = 0,
    /** Auto-preamp currently applied, in dB. Zero or negative. */
    val headroomDb: Float = 0f,
    /**
     * The open output rate, or 0 before the first packet.
     *
     * Exposed because the UI needs it to show a headroom figure that matches
     * the one applied: the preamp is derived from the filter response, and the
     * response depends on the rate. Recomputing in the UI against the same
     * rate is what lets the number track a slider immediately instead of
     * waiting for the next status poll.
     */
    val sampleRate: Int = 0,
    /** Filter-state resets forced by a non-finite sample. Should stay 0. */
    val eqResets: Long = 0,
    /**
     * Cost of the mix-to-bytes stage per 10 ms quantum, in nanoseconds, split
     * by whether the equalizer was running.
     *
     * The pair is the point: the difference between them is what the
     * equalizer actually costs, and each on its own says little. Worst matters
     * more than average, because one quantum slower than the quantum itself is
     * a late write to AudioTrack, which is a dropout.
     */
    val eqOnAvgNanos: Long = 0,
    val eqOnWorstNanos: Long = 0,
    val eqOnQuanta: Long = 0,
    val eqOffAvgNanos: Long = 0,
    val eqOffWorstNanos: Long = 0,
    val eqOffQuanta: Long = 0,
)

/** One row of the stream list. */
data class StreamStatus(
    val id: Int,
    val volume: Float,
    val bufferMs: Double,
    val packetsReceived: Long,
    val packetsLost: Long,
    val underruns: Long,
    val active: Boolean,
)

/**
 * The Wired Tooth Android receiver.
 *
 * Mirrors NetworkTest (the Windows receiver) rather than inventing a second
 * design: same handshake, same 60 ms jitter buffer, same drift controller,
 * same concealment. The only genuinely Android-specific part is AudioTrack
 * standing in for WasapiOut.
 *
 * Threading:
 *   receive   parses audio datagrams and fills the jitter buffer
 *   control   HELLO keepalive, PING probe, and PONG/HELLO_ACK handling
 *   playback  drains the jitter buffer into AudioTrack
 *   drift     every 100 ms, measures depth and commands a speed ratio
 *
 * UDP only, unicast only. No TCP, no multicast, no discovery -- the sender's
 * address arrives by QR code or is typed in.
 */
class AudioReceiver(
    private val senderIp: String,
    private val targetBufferMs: Double = 60.0,
) {
    companion object {
        const val AUDIO_PORT = 5000
        const val CONTROL_PORT = 5001
        private const val KEEPALIVE_MS = 1000L
        private const val PING_INTERVAL_MS = 500L

        /** No audio for this long means the stream is gone, not merely jittery. */
        private const val AUDIO_TIMEOUT_MS = 2000L

        /**
         * Above this much excess over target, stop trying to resample it away
         * and drop instead. The +/-0.5% clamp is sized to stay inaudible,
         * which makes it far too slow to recover a large transient.
         */

        private const val RTT_WINDOW = 20

        /**
         * How many streams may be mixed at once.
         *
         * Each costs one jitter buffer of about 11 kB at the 60 ms setpoint,
         * so the ceiling is about taste rather than memory: past a handful,
         * nobody can tell the streams apart by ear anyway. The rewind ring
         * buffers in step 2 carry their own, much tighter, cap of 3.
         */
        const val MAX_ACTIVE_STREAMS = 8
    }

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var reconnecting = false
    @Volatile private var everHadAudio = false

    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()

    private val packetsReceived = AtomicLong()
    private val packetsDropped = AtomicLong()
    private val bytesReceived = AtomicLong()
    private val controlSeq = AtomicInteger()
    private val lastAudioAt = AtomicLong(System.currentTimeMillis())

    private val rtts = ArrayDeque<Double>()
    private val rttLock = Any()

    /**
     * The equalizer, on the mixed output.
     *
     * It lives here and not in StreamChannel because it is one curve for what
     * the listener hears, not one per app. Running ten biquads per stream
     * would also multiply the cost by the stream count for no audible gain.
     */
    private val eq = EqEngine()

    /**
     * The user's choice, kept so it can be re-derived on a format change.
     *
     * Volatile rather than atomic: it is written by whoever moved the slider
     * and read by the receive thread on a reopen, and a torn read is not
     * possible for a reference. The thing the audio thread reads is the
     * EqSettings snapshot inside [eq], not this.
     */
    @Volatile
    private var sound = SoundState()

    /**
     * Per-quantum cost of turning the mix into bytes, with and without the
     * equalizer.
     *
     * Kept as two separate accumulators rather than one, because the useful
     * figure is the difference: the bypass number is what this loop costs with
     * no equalizer in the path at all, so subtracting it from the on number is
     * the real price of the feature and nothing else.
     */
    private val eqOnNanos = NanoStats()
    private val eqOffNanos = NanoStats()

    /** Counts how often the last line of defence actually fired. */
    private val clampedSamples = AtomicLong()

    // One channel per stream id. Each owns its own jitter buffer, drift
    // controller and resampler -- see StreamChannel for why that has to be
    // per stream rather than shared.
    //
    // Concurrent because the receive thread creates entries while the
    // playback and drift threads iterate. Iteration over a
    // ConcurrentHashMap's values is weakly consistent, which is exactly
    // right here: a stream that appears mid-iteration is simply mixed from
    // the next quantum instead.
    private val streams = ConcurrentHashMap<Int, StreamChannel>()

    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var channels = 0
    private var frameBytes = 0
    private var formatLabel = "waiting"

    private var lastRateSampleAt = System.currentTimeMillis()
    private var lastRateBytes = 0L
    @Volatile private var kbps = 0.0

    fun start() {
        if (running) return
        running = true

        val addr = InetAddress.getByName(senderIp)
        audioSocket = DatagramSocket(null).apply {
            reuseAddress = true
            receiveBufferSize = 1 shl 20
            bind(InetSocketAddress(AUDIO_PORT))
            soTimeout = 500
        }
        controlSocket = DatagramSocket().apply { soTimeout = 500 }

        threads += thread(name = "wt-receive") { receiveLoop() }
        threads += thread(name = "wt-control") { controlLoop(addr) }
        threads += thread(name = "wt-playback") { playbackLoop() }
        threads += thread(name = "wt-drift") { driftLoop(addr) }
    }

    fun stop() {
        if (!running) return
        running = false

        // Say goodbye so the sender drops us immediately instead of waiting
        // out its full three-second client timeout.
        try {
            val bye = Wtp.writeControl(
                Wtp.TYPE_BYE,
                controlSeq.incrementAndGet().toLong(),
                Wtp.timestampMicros(),
            )
            val addr = InetAddress.getByName(senderIp)
            controlSocket?.send(DatagramPacket(bye, bye.size, addr, CONTROL_PORT))
        } catch (_: Exception) {
        }

        threads.forEach { runCatching { it.join(1500) } }
        threads.clear()

        runCatching { audioSocket?.close() }
        runCatching { controlSocket?.close() }
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
        track = null
        connected = false
        streams.clear()
    }

    // ---- control -------------------------------------------------------

    private fun controlLoop(addr: InetAddress) {
        var lastHello = 0L
        var lastPing = 0L
        var backoffMs = 500L
        val buf = ByteArray(2048)

        while (running) {
            val now = System.currentTimeMillis()
            try {
                // While connected this is a plain 1s keepalive. Once audio
                // stops it becomes a reconnect probe that backs off to 5s and
                // stays there -- a fixed fast retry would hold the radio awake
                // for nothing while the sender is gone.
                val helloInterval = if (reconnecting) backoffMs else KEEPALIVE_MS
                if (now - lastHello >= helloInterval) {
                    val hello = Wtp.writeControl(
                        Wtp.TYPE_HELLO,
                        controlSeq.incrementAndGet().toLong(),
                        Wtp.timestampMicros(),
                    )
                    controlSocket?.send(DatagramPacket(hello, hello.size, addr, CONTROL_PORT))
                    lastHello = now
                    if (reconnecting) backoffMs = minOf(backoffMs * 2, 5000L)
                }

                if (now - lastPing >= PING_INTERVAL_MS) {
                    // The send time is the packet's own header timestamp; the
                    // sender copies it back verbatim, so no state is needed
                    // here to match a reply to a probe.
                    val ping = Wtp.writeControl(
                        Wtp.TYPE_PING,
                        controlSeq.incrementAndGet().toLong(),
                        Wtp.timestampMicros(),
                    )
                    controlSocket?.send(DatagramPacket(ping, ping.size, addr, CONTROL_PORT))
                    lastPing = now
                }
                if (!reconnecting) backoffMs = 500L
            } catch (_: Exception) {
            }

            try {
                val p = DatagramPacket(buf, buf.size)
                controlSocket?.receive(p)
                val h = Wtp.parse(buf, p.length) ?: continue
                when (h.type) {
                    Wtp.TYPE_HELLO_ACK -> connected = true
                    Wtp.TYPE_PONG -> {
                        // Both ends of this subtraction are our own clock, so
                        // the unknown offset between the two machines never
                        // enters it.
                        val sent = Wtp.readEchoedTimestamp(buf, h.payloadOffset, h.payloadLength)
                        if (sent != null) {
                            val rtt = (Wtp.timestampMicros() - sent) / 1000.0
                            if (rtt >= 0) synchronized(rttLock) {
                                rtts.addLast(rtt)
                                while (rtts.size > RTT_WINDOW) rtts.removeFirst()
                            }
                        }
                    }
                    Wtp.TYPE_BYE -> connected = false
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
            }
        }
    }

    /** Median, not mean: one Wi-Fi retransmission is a large outlier. */
    private fun medianRtt(): Double = synchronized(rttLock) {
        if (rtts.isEmpty()) return 0.0
        val s = rtts.toDoubleArray()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    // ---- audio in ------------------------------------------------------

    private fun receiveLoop() {
        val buf = ByteArray(Wtp.MAX_DATAGRAM_SIZE + 64)
        var outShorts = ShortArray(0)
        var inShorts = ShortArray(0)

        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                audioSocket?.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                continue
            }

            val h = Wtp.parse(buf, p.length)
            if (h == null) {
                packetsDropped.incrementAndGet()
                continue
            }
            if (h.type != Wtp.TYPE_AUDIO) continue
            if (h.codec != Wtp.CODEC_PCM16) {
                packetsDropped.incrementAndGet()
                continue
            }

            // A gap longer than the audio timeout is a dropped link, not lost
            // packets. Re-baselining stops the reconnect being charged
            // thousands of packets it never had a chance to receive.
            val now = System.currentTimeMillis()
            if (now - lastAudioAt.get() > AUDIO_TIMEOUT_MS) {
                streams.values.forEach { it.reset() }
            }
            lastAudioAt.set(now)
            everHadAudio = true

            // The first stream to arrive fixes the output format. While it is
            // the only one, a format change reopens the track exactly as
            // before -- that is the WP5 behaviour and it still has to work.
            // Once a second stream exists, reopening would tear down audio
            // that is playing fine, so a mismatched stream is dropped instead.
            // In practice they always match: the sender converts every
            // per-process capture to the same PCM16 format before sending.
            if (track == null) {
                openTrack(h.sampleRate, h.channels, h.bitsPerSample, h.codec)
                inShorts = ShortArray(Wtp.MAX_DATAGRAM_SIZE / 2 + 16)
                outShorts = ShortArray(
                    LinearResampler.maxOutputFrames(Wtp.MAX_DATAGRAM_SIZE / frameBytes + 2) * channels,
                )
            } else if (sampleRate != h.sampleRate || channels != h.channels) {
                if (streams.size <= 1) {
                    openTrack(h.sampleRate, h.channels, h.bitsPerSample, h.codec)
                    streams.values.forEach { it.configure(sampleRate, channels) }
                } else {
                    packetsDropped.incrementAndGet()
                    continue
                }
            }

            val channel = streams[h.streamId] ?: run {
                if (streams.size >= MAX_ACTIVE_STREAMS) {
                    packetsDropped.incrementAndGet()
                    null
                } else {
                    StreamChannel(h.streamId, targetBufferMs)
                        .also { it.configure(sampleRate, channels) }
                        .also { streams[h.streamId] = it }
                }
            } ?: continue

            packetsReceived.incrementAndGet()
            bytesReceived.addAndGet(p.length.toLong())
            channel.accept(h, buf, p.length, inShorts, outShorts)
        }
    }

    private fun openTrack(rate: Int, ch: Int, bits: Int, codec: Int) {
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }

        sampleRate = rate
        channels = ch
        frameBytes = ch * 2

        // A reopen is the one moment where clearing filter memory is correct:
        // the old signal has already stopped, so there is nothing to click.
        eq.configure(rate, ch)
        eq.reset()

        // Rebuild the curve against the new rate. The preamp is the negative
        // of the true peak of the response, and a filter's response depends on
        // the sample rate, so a format change invalidates a figure computed
        // for the old rate -- silently, and in the direction of too little
        // headroom. Recomputing here is also what keeps the number shown in
        // the UI equal to the gain actually applied.
        pushSound()

        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)

        // Deliberately close to the minimum. AudioTrack's own buffer is
        // latency we cannot see or control; the jitter buffer above it is the
        // one the drift loop steers, so this is kept as small as the device
        // will accept.
        val size = if (min > 0) min * 2 else rate * frameBytes / 10

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        t.play()
        track = t
        formatLabel = "$rate Hz, $ch ch, $bits-bit, codec $codec"
    }

    /**
     * The mixer. Pulls a fixed 10 ms quantum from every started stream, sums
     * them with per-stream gain, clamps, and writes one block to AudioTrack.
     *
     * A FIXED quantum is the point. The old loop wrote whatever block came off
     * the queue, which was fine with one producer; with several, blocks arrive
     * at different sizes and moments and there is no shared grid to add them
     * on. Asking each stream for the same number of frames creates that grid.
     *
     * Silence is written when nothing is available, rather than skipping the
     * write. AudioTrack in MODE_STREAM blocks until its buffer has room, so
     * writing every quantum is what paces this loop against the output clock.
     * Skipping writes would hand pacing to Thread.sleep and let the device
     * clock and this loop wander apart -- which is the exact failure the drift
     * controller exists to fix, reintroduced one layer up.
     */
    private fun playbackLoop() {
        while (running && track == null) Thread.sleep(20)

        var quantumBytes = 0
        var scratch = ByteArray(0)
        var mix = IntArray(0)
        var mixFloat = FloatArray(0)
        var out = ByteArray(0)

        while (running) {
            val t = track
            if (t == null || frameBytes == 0 || sampleRate == 0) {
                Thread.sleep(10)
                continue
            }

            val frames = maxOf(sampleRate / 100, 1)          // 10 ms
            val needed = frames * frameBytes
            if (needed != quantumBytes) {
                quantumBytes = needed
                scratch = ByteArray(needed)
                mix = IntArray(frames * channels)
                mixFloat = FloatArray(frames * channels)
                out = ByteArray(needed)
            }

            // Nothing has pre-rolled yet: do not start the track's clock on
            // silence, or the first real audio arrives already late.
            if (streams.values.none { it.started }) {
                Thread.sleep(5)
                continue
            }

            java.util.Arrays.fill(mix, 0)

            for (ch in streams.values) {
                val got = ch.drain(scratch, quantumBytes)
                if (got <= 0) continue
                val gain = ch.volume
                if (gain <= 0f) continue
                Mixing.accumulate(scratch, got, gain, mix)
            }

            // One snapshot drives both the decision and the processing, so
            // they cannot disagree if the user moves a slider mid-block.
            val eqSettings = eq.snapshot()

            // Timed on both sides of the branch, into separate accumulators,
            // because the question is not "how long does the equalizer take"
            // but "how much longer than not having it". Timing only the on
            // path would give a number with nothing to compare it against.
            val startNanos = System.nanoTime()
            val clamped = if (eqSettings.enabled) {
                val samples = frames * channels
                Mixing.toFloat(mix, mixFloat, samples)
                eq.process(eqSettings, mixFloat, frames)
                Mixing.clampInto(mixFloat, out, samples)
            } else {
                // Bypass is the original path, untouched, so off means
                // bit-identical to having no equalizer in the build at all.
                Mixing.clampInto(mix, out)
            }
            val elapsedNanos = System.nanoTime() - startNanos
            if (eqSettings.enabled) eqOnNanos.record(elapsedNanos)
            else eqOffNanos.record(elapsedNanos)

            if (clamped > 0) clampedSamples.addAndGet(clamped.toLong())

            runCatching { t.write(out, 0, out.size) }
        }
    }

    // ---- drift ---------------------------------------------------------

    private fun driftLoop(addr: InetAddress) {
        while (running) {
            Thread.sleep(100)

            // Link state is evaluated here rather than in the control loop,
            // so that once the HELLO backoff reaches 5s the UI does not keep
            // saying "reconnecting" for seconds after audio has resumed.
            val since = System.currentTimeMillis() - lastAudioAt.get()
            val lost = everHadAudio && since > AUDIO_TIMEOUT_MS
            reconnecting = lost
            if (lost) connected = false

            // Each stream steers its own controller from its own depth. The
            // algorithm and the 60 ms setpoint are untouched; there are just
            // several of them now.
            if (track != null && frameBytes > 0) {
                for (ch in streams.values) ch.drift.update(ch.bufferMs())
            }

            val now = System.currentTimeMillis()
            val dt = now - lastRateSampleAt
            if (dt >= 1000) {
                val db = bytesReceived.get() - lastRateBytes
                kbps = db * 8.0 / dt
                lastRateBytes = bytesReceived.get()
                lastRateSampleAt = now
            }
        }
    }

    /**
     * Depth of the PRIMARY stream, not an average.
     *
     * Stream 0 is the whole-system mix and the one a single-stream sender
     * produces, so it is the number that means the same thing it always did.
     * Averaging several streams would produce a figure that is true of none of
     * them, and the per-stream depths are available separately.
     */
    private fun primary(): StreamChannel? =
        streams[0] ?: streams.values.firstOrNull()

    private fun bufferMs(): Double = primary()?.bufferMs() ?: 0.0

    // ---- status --------------------------------------------------------

    /** The equalizer, for the UI to read and update. */
    fun equalizer(): EqEngine = eq

    /**
     * Applies the user's sound choice.
     *
     * The conversion from choice to filter coefficients happens HERE rather
     * than in the UI, because it needs the sample rate that is actually open
     * and the UI does not know it -- the rate comes from the stream and can
     * change mid-session. Keeping the choice and converting on demand means a
     * format change re-derives the preamp instead of inheriting a stale one.
     *
     * Costs a few thousand trig calls, on the caller's thread, which is the UI
     * thread reacting to a slider or the receive thread handling a format
     * change. Never the playback thread: that one only ever reads the finished
     * number out of the immutable snapshot.
     */
    fun applySound(s: SoundState) {
        sound = s
        pushSound()
    }

    fun soundState(): SoundState = sound

    /**
     * Clears the timing accumulators.
     *
     * Needed for an honest on-versus-off comparison: the first few quanta
     * after the output opens include the JIT warming up on code that has
     * never run, and a worst-case figure that is really a first-run figure
     * says nothing about steady state.
     */
    fun resetTimings() {
        eqOnNanos.reset()
        eqOffNanos.reset()
    }

    private fun pushSound() {
        eq.apply(sound.toEqSettings(sampleRate))
    }

    /** Sets one stream's gain. Takes effect on the next 10 ms quantum. */
    fun setVolume(streamId: Int, volume: Float) {
        streams[streamId]?.volume = volume.coerceIn(0f, 1f)
    }

    fun volumeOf(streamId: Int): Float = streams[streamId]?.volume ?: 1f

    fun status(): ReceiverStatus {
        val rx = packetsReceived.get()
        val channels = streams.values.sortedBy { it.id }
        // Loss is now counted per stream, because a gap in one stream's
        // sequence says nothing about the others.
        val lost = channels.sumOf { it.packetsLost }
        val total = rx + lost
        val now = System.currentTimeMillis()
        return ReceiverStatus(
            state = when {
                !running -> "stopped"
                reconnecting -> "reconnecting"
                connected && rx > 0 -> "streaming"
                connected -> "connected, waiting for audio"
                else -> "connecting to $senderIp"
            },
            senderIp = senderIp,
            format = formatLabel,
            packetsReceived = rx,
            packetsLost = lost,
            packetsDropped = packetsDropped.get(),
            underruns = channels.sumOf { it.underruns },
            lossPercent = if (total > 0) lost * 100.0 / total else 0.0,
            rttMs = medianRtt(),
            bufferMs = bufferMs(),
            correctionRatio = primary()?.drift?.ratio ?: 1.0,
            clampedSamples = clampedSamples.get(),
            headroomDb = eq.snapshot().let { if (it.enabled && it.autoPreamp) it.preampDb else 0f },
            sampleRate = sampleRate,
            eqResets = eq.instabilityResets,
            eqOnAvgNanos = eqOnNanos.average,
            eqOnWorstNanos = eqOnNanos.worst,
            eqOnQuanta = eqOnNanos.samples,
            eqOffAvgNanos = eqOffNanos.average,
            eqOffWorstNanos = eqOffNanos.worst,
            eqOffQuanta = eqOffNanos.samples,
            kbitPerSecond = kbps,
            streams = channels.map {
                StreamStatus(
                    id = it.id,
                    volume = it.volume,
                    bufferMs = it.bufferMs(),
                    packetsReceived = it.packetsReceived,
                    packetsLost = it.packetsLost,
                    underruns = it.underruns,
                    // Silence keepalives arrive every 100 ms, so a stream that
                    // has said nothing for a second has genuinely stopped
                    // rather than merely gone quiet.
                    active = now - it.lastPacketAtMs < 1000,
                )
            },
        )
    }
}
