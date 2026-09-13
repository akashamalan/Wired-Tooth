package com.wiredtooth.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
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
        private const val RESYNC_THRESHOLD_MS = 150.0

        private const val RTT_WINDOW = 20
    }

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var reconnecting = false
    @Volatile private var everHadAudio = false

    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()

    private val packetsReceived = AtomicLong()
    private val packetsLost = AtomicLong()
    private val packetsDropped = AtomicLong()
    private val underruns = AtomicLong()
    private val bytesReceived = AtomicLong()
    private val controlSeq = AtomicInteger()
    private val lastAudioAt = AtomicLong(System.currentTimeMillis())

    private val rtts = ArrayDeque<Double>()
    private val rttLock = Any()

    // Jitter buffer. A plain deque of PCM byte blocks guarded by one lock:
    // the receive thread appends, the playback thread removes. Exactly the
    // producer/consumer shape BufferedWaveProvider has on Windows.
    private val queue = ArrayDeque<ByteArray>()
    private val queueLock = Any()
    private var queuedBytes = 0

    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var channels = 0
    private var frameBytes = 0
    private var formatLabel = "waiting"

    private val drift = DriftController(targetBufferMs)
    private var resampler: LinearResampler? = null

    private var lastSeq = 0L
    private var haveSeq = false
    private var lastPayloadFrames = 0
    private var lastFrame = ShortArray(0)
    private var resyncing = false

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
                haveSeq = false
                resampler?.reset()
            }
            lastAudioAt.set(now)
            everHadAudio = true

            if (track == null || sampleRate != h.sampleRate || channels != h.channels) {
                openTrack(h.sampleRate, h.channels, h.bitsPerSample, h.codec)
                inShorts = ShortArray(Wtp.MAX_DATAGRAM_SIZE / 2 + 16)
                outShorts = ShortArray(
                    LinearResampler.maxOutputFrames(Wtp.MAX_DATAGRAM_SIZE / frameBytes + 2) * channels,
                )
            }

            // ---- concealment ------------------------------------------
            if (haveSeq && h.sequence > lastSeq + 1) {
                val missing = (h.sequence - lastSeq - 1).toInt()
                packetsLost.addAndGet(missing.toLong())
                concealGap(missing)
            }
            lastSeq = h.sequence
            haveSeq = true

            packetsReceived.incrementAndGet()
            bytesReceived.addAndGet(p.length.toLong())

            if (h.isSilence) {
                // An empty silence-flagged packet stands for a fixed span of
                // quiet. Expanding it here is what keeps the buffer fed while
                // the PC is producing nothing at all.
                var bytes = sampleRate * frameBytes * Wtp.SILENCE_KEEPALIVE_MS / 1000
                bytes -= bytes % frameBytes
                if (bytes > 0) {
                    enqueue(ByteArray(bytes))
                    resampler?.reset()
                }
                continue
            }
            if (h.payloadLength == 0) continue

            // bytes -> shorts, little-endian
            val frames = h.payloadLength / frameBytes
            val samples = frames * channels
            for (i in 0 until samples) {
                val o = h.payloadOffset + i * 2
                inShorts[i] = ((buf[o].toInt() and 0xFF) or (buf[o + 1].toInt() shl 8)).toShort()
            }

            val depth = bufferMs()
            if (!resyncing && depth > targetBufferMs + RESYNC_THRESHOLD_MS) {
                resyncing = true
            } else if (resyncing && depth <= targetBufferMs) {
                resyncing = false
            }

            val outFrames = resampler!!.resample(inShorts, frames, drift.ratio, outShorts)
            if (outFrames > 0 && !resyncing) {
                val outBytes = ByteArray(outFrames * frameBytes)
                for (i in 0 until outFrames * channels) {
                    val v = outShorts[i].toInt()
                    outBytes[i * 2] = (v and 0xFF).toByte()
                    outBytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                enqueue(outBytes)
            }

            lastPayloadFrames = frames
            if (lastFrame.size != channels) lastFrame = ShortArray(channels)
            for (c in 0 until channels) lastFrame[c] = inShorts[(frames - 1) * channels + c]
        }
    }

    /**
     * Fills a loss gap by fading the last received frame out over 5 ms and
     * then going silent. Letting the buffer simply go short would starve
     * playback and also mislead the drift controller, which is measuring
     * depth and cannot see a hole it was never given.
     */
    private fun concealGap(missing: Int) {
        if (lastPayloadFrames <= 0 || channels <= 0) return
        // Capped: beyond a couple of hundred ms the stream is gone, not
        // dropping packets, and synthesising seconds of filler just adds
        // latency to the recovery.
        val maxFrames = sampleRate / 5
        val frames = minOf(missing * lastPayloadFrames, maxFrames)
        if (frames <= 0) return

        val out = ByteArray(frames * frameBytes)
        val fade = minOf(sampleRate / 200, frames)     // 5 ms
        for (f in 0 until frames) {
            val g = if (f < fade) 1f - f.toFloat() / fade else 0f
            for (c in 0 until channels) {
                val v = (lastFrame.getOrElse(c) { 0 } * g).toInt()
                val o = (f * channels + c) * 2
                out[o] = (v and 0xFF).toByte()
                out[o + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        enqueue(out)
        resampler?.reset()
    }

    private fun enqueue(block: ByteArray) {
        synchronized(queueLock) {
            queue.addLast(block)
            queuedBytes += block.size
            (queueLock as Object).notifyAll()
        }
    }

    // ---- audio out -----------------------------------------------------

    private fun openTrack(rate: Int, ch: Int, bits: Int, codec: Int) {
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }

        sampleRate = rate
        channels = ch
        frameBytes = ch * 2
        resampler = LinearResampler(ch)
        lastFrame = ShortArray(ch)
        synchronized(queueLock) {
            queue.clear()
            queuedBytes = 0
        }

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

    private fun playbackLoop() {
        // Pre-roll: wait until the buffer holds the target depth before
        // starting, so the control loop begins at its setpoint instead of
        // having to climb to it.
        while (running && track == null) Thread.sleep(20)

        while (running) {
            var block: ByteArray? = null
            synchronized(queueLock) {
                if (queue.isEmpty()) {
                    (queueLock as Object).wait(100)
                } else if (queuedBytes >= preBufferBytes() || everHadAudio) {
                    block = queue.removeFirst()
                    queuedBytes -= block!!.size
                }
            }

            val b = block
            if (b == null) {
                if (everHadAudio && track != null) underruns.incrementAndGet()
                continue
            }
            runCatching { track?.write(b, 0, b.size) }
        }
    }

    private fun preBufferBytes(): Int =
        if (frameBytes == 0) 0 else (sampleRate * frameBytes * targetBufferMs / 1000.0).toInt()

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

            if (track != null && frameBytes > 0) drift.update(bufferMs())

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

    private fun bufferMs(): Double {
        if (sampleRate == 0 || frameBytes == 0) return 0.0
        val bytes = synchronized(queueLock) { queuedBytes }
        return bytes.toDouble() / (sampleRate * frameBytes) * 1000.0
    }

    // ---- status --------------------------------------------------------

    fun status(): ReceiverStatus {
        val rx = packetsReceived.get()
        val lost = packetsLost.get()
        val total = rx + lost
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
            underruns = underruns.get(),
            lossPercent = if (total > 0) lost * 100.0 / total else 0.0,
            rttMs = medianRtt(),
            bufferMs = bufferMs(),
            correctionRatio = drift.ratio,
            kbitPerSecond = kbps,
        )
    }
}
