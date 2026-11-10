package com.wiredtooth.receiver

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * A WTP1 sender, inside the test process, on the loopback interface.
 *
 * WHY THIS EXISTS RATHER THAN THE REAL SENDER. The real sender binds UDP 5001
 * on the development machine, and so does any copy of the tray app that is
 * already running. Pointing the emulator at the host therefore tests whatever
 * happens to own that port rather than a known quantity, and it cannot be
 * asked to hold silence for thirty seconds or to stream the identical signal
 * twice. This one binds 127.0.0.1:5001 INSIDE the guest, so the host's port
 * is not involved at all, and every variable the tests care about -- silence,
 * pacing, loss, client count -- is set here.
 *
 * It is a faithful sender, not a stub: real headers, real sequence numbers,
 * real 1400-byte chunking, real HELLO_ACK and PONG, paced against a real
 * clock. The receiver under test cannot tell the difference, which is the
 * only property that makes the results worth anything.
 *
 * WHAT IT DELIBERATELY IS NOT. It does not capture audio, it does not do
 * per-app capture, and it never advertises MULTI_STREAM. Everything it sends
 * is stream 0, which is what an older sender produces and therefore the path
 * most worth regression-testing.
 */
class TestSender(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
) {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        soTimeout = 200
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), AudioReceiver.CONTROL_PORT))
    }

    /** Control addresses that have said HELLO and not yet said BYE. */
    private val clients = ConcurrentHashMap<String, InetSocketAddress>()

    @Volatile
    private var audioTarget: InetAddress? = null

    @Volatile
    var running = false
        private set

    /**
     * When true, every packet is a header-only keepalive with FLAG_SILENCE.
     *
     * This is the case the sender has to get right in the real product:
     * WASAPI loopback simply stops delivering buffers when nothing is
     * playing, so without keepalives the receiver's buffer and RTT tracking
     * starve during a quiet passage and it counts underruns that are not
     * really underruns.
     */
    @Volatile
    var silence = false

    val clientCount: Int get() = clients.size

    val hellos = AtomicLong()
    val pings = AtomicLong()
    val byes = AtomicLong()
    val audioPacketsSent = AtomicLong()
    val keepalivesSent = AtomicLong()

    private var controlThread: Thread? = null
    private var audioThread: Thread? = null

    // 1400 bytes is the ceiling, so the payload is the largest whole number of
    // frames that fits under it once the 28-byte audio header is subtracted.
    private val frameBytes = channels * 2
    private val payloadFrames = (Wtp.MAX_DATAGRAM_SIZE - Wtp.AUDIO_HEADER_SIZE) / frameBytes
    private val payloadBytes = payloadFrames * frameBytes

    fun start() {
        running = true
        controlThread = thread(name = "test-sender-control") { controlLoop() }
        audioThread = thread(name = "test-sender-audio") { audioLoop() }
    }

    fun stop() {
        running = false
        controlThread?.join(1500)
        audioThread?.join(1500)
        runCatching { socket.close() }
    }

    // ---- control -------------------------------------------------------

    private fun controlLoop() {
        val buf = ByteArray(2048)
        var seq = 0L
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                val h = Wtp.parse(buf, p.length) ?: continue
                val from = InetSocketAddress(p.address, p.port)
                val key = "${p.address.hostAddress}:${p.port}"

                when (h.type) {
                    Wtp.TYPE_HELLO -> {
                        hellos.incrementAndGet()
                        clients[key] = from
                        audioTarget = p.address
                        send(Wtp.writeControl(Wtp.TYPE_HELLO_ACK, seq++, Wtp.timestampMicros()), from)
                    }

                    Wtp.TYPE_PING -> {
                        pings.incrementAndGet()
                        // PONG echoes the sender's original timestamp, so the
                        // receiver measures the round trip entirely on its own
                        // clock and the offset between the two never enters it.
                        val echo = ByteArray(8)
                        for (i in 0..7) {
                            echo[i] = ((h.timestampMicros ushr (8 * i)) and 0xFF).toByte()
                        }
                        send(
                            Wtp.writeControl(Wtp.TYPE_PONG, seq++, Wtp.timestampMicros(), echo),
                            from,
                        )
                    }

                    Wtp.TYPE_BYE -> {
                        byes.incrementAndGet()
                        clients.remove(key)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Expected: the loop has to wake up to notice `running`.
            } catch (_: Exception) {
                if (!running) return
            }
        }
    }

    private fun send(bytes: ByteArray, to: InetSocketAddress) {
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, to.address, to.port)) }
    }

    // ---- audio ---------------------------------------------------------

    /**
     * Paced against a running deadline rather than by sleeping a fixed amount.
     *
     * Sleep-per-packet accumulates error: every oversleep is permanent, so the
     * stream drifts slower than real time and the receiver's buffer drains --
     * which this test would then report as the receiver's fault. Holding an
     * absolute next-send time means a late wake-up is corrected by the next
     * packet instead of being added to the total.
     */
    private fun audioLoop() {
        var seq = 0L
        var phase = 0.0
        val payload = ByteArray(payloadBytes)
        val toneStep = 2.0 * PI * 440.0 / sampleRate

        val nanosPerPacket = payloadFrames.toLong() * 1_000_000_000L / sampleRate
        var next = System.nanoTime()

        while (running) {
            val target = audioTarget
            if (target == null) {
                Thread.sleep(10)
                next = System.nanoTime()
                continue
            }

            if (silence) {
                // Header only, FLAG_SILENCE, no payload. The real sender does
                // the same: there is no audio to carry, and the point of the
                // packet is to keep the receiver's clocks and buffer alive.
                val pkt = audioHeader(seq++, Wtp.FLAG_SILENCE, 0)
                runCatching {
                    socket.send(DatagramPacket(pkt, pkt.size, target, AudioReceiver.AUDIO_PORT))
                }
                keepalivesSent.incrementAndGet()
                sleepUntil(next + Wtp.SILENCE_KEEPALIVE_MS * 1_000_000L)
                next = System.nanoTime()
                continue
            }

            var i = 0
            while (i < payloadBytes) {
                val v = (sin(phase) * 8000).toInt()
                phase += toneStep
                var c = 0
                while (c < channels) {
                    payload[i] = (v and 0xFF).toByte()
                    payload[i + 1] = ((v shr 8) and 0xFF).toByte()
                    i += 2
                    c++
                }
            }

            val pkt = audioHeader(seq++, 0, payloadBytes)
            System.arraycopy(payload, 0, pkt, Wtp.AUDIO_HEADER_SIZE, payloadBytes)
            runCatching {
                socket.send(DatagramPacket(pkt, pkt.size, target, AudioReceiver.AUDIO_PORT))
            }
            audioPacketsSent.incrementAndGet()

            next += nanosPerPacket
            sleepUntil(next)
        }
    }

    private fun sleepUntil(deadlineNanos: Long) {
        val wait = deadlineNanos - System.nanoTime()
        if (wait <= 0) return
        Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
    }

    private fun audioHeader(seq: Long, flags: Int, payloadLen: Int): ByteArray {
        val out = ByteArray(Wtp.AUDIO_HEADER_SIZE + payloadLen)
        System.arraycopy(Wtp.MAGIC, 0, out, 0, 4)
        out[4] = Wtp.TYPE_AUDIO.toByte()
        out[5] = flags.toByte()
        putU16(out, 6, payloadLen)
        putU32(out, 8, seq)
        putI64(out, 12, Wtp.timestampMicros())
        putU32(out, 20, sampleRate.toLong())
        putU16(out, 24, channels)
        out[26] = 16
        out[27] = Wtp.CODEC_PCM16.toByte()
        return out
    }

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        for (i in 0..3) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putI64(b: ByteArray, o: Int, v: Long) {
        for (i in 0..7) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}
