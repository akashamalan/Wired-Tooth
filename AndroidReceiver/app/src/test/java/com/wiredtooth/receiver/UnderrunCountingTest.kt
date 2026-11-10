package com.wiredtooth.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a dropout.
 *
 * The counter used to fire about eighteen times a second on a completely
 * idle PC, because the playback queue empties for a few milliseconds near
 * the end of every silence keepalive period. That buried real dropouts in
 * noise and described silence as a fault.
 *
 * The clock is injected rather than real so "thirty seconds of silence" is
 * a test that runs instantly instead of one that takes thirty seconds.
 */
class UnderrunCountingTest {

    private val rate = 48000
    private val channels = 2
    private val frameBytes = channels * 2
    private val quantumMs = 10L
    private val quantumBytes = rate * frameBytes * quantumMs.toInt() / 1000

    private var now = 0L
    private fun channel() = StreamChannel(0, 60.0) { now }.apply { configure(rate, channels) }

    private fun packet(sequence: Long, silence: Boolean, frames: Int): ByteArray {
        val payload = if (silence) 0 else frames * frameBytes
        val pkt = ByteArray(Wtp.AUDIO_HEADER_SIZE + payload)
        System.arraycopy(Wtp.MAGIC, 0, pkt, 0, 4)
        pkt[4] = Wtp.TYPE_AUDIO.toByte()
        pkt[5] = if (silence) Wtp.FLAG_SILENCE.toByte() else 0
        pkt[6] = (payload and 0xFF).toByte()
        pkt[7] = ((payload shr 8) and 0xFF).toByte()
        for (i in 0..3) pkt[8 + i] = ((sequence shr (8 * i)) and 0xFF).toByte()
        pkt[20] = (rate and 0xFF).toByte()
        pkt[21] = ((rate shr 8) and 0xFF).toByte()
        pkt[22] = ((rate shr 16) and 0xFF).toByte()
        pkt[24] = channels.toByte()
        pkt[26] = 16
        pkt[27] = Wtp.CODEC_PCM16.toByte()
        // Non-zero audio so a real packet is distinguishable from silence.
        for (i in 0 until payload step 2) {
            pkt[Wtp.AUDIO_HEADER_SIZE + i] = 0x40
            if (i + 1 < payload) pkt[Wtp.AUDIO_HEADER_SIZE + i + 1] = 0x10
        }
        return pkt
    }

    private fun feed(c: StreamChannel, pkt: ByteArray) {
        val h = Wtp.parse(pkt, pkt.size)!!
        c.accept(h, pkt, pkt.size, ShortArray(8192), ShortArray(16384))
    }

    /**
     * An idle PC. Keepalives arrive on the cadence a real sender produces,
     * which is later than the nominal period because the sender polls on a
     * timer. The queue runs dry near the end of every period, and none of
     * that is a dropout.
     */
    @Test
    fun thirtySecondsOfKeepalivesReportsNoUnderruns() {
        val c = channel()
        val dest = ByteArray(quantumBytes)
        var seq = 1L
        var nextKeepalive = 0L

        // 125 ms is what this sender actually produced when measured, not the
        // nominal 100 ms, because of Windows timer granularity.
        val cadence = 125L

        while (now < 30_000) {
            if (now >= nextKeepalive) {
                feed(c, packet(seq++, silence = true, frames = 0))
                nextKeepalive = now + cadence
            }
            c.drain(dest, quantumBytes)
            now += quantumMs
        }

        assertEquals("silence is not a dropout", 0L, c.underruns)
    }

    /** The control: without the fix this same run counted hundreds. */
    @Test
    fun keepaliveOnlyRunDoesProduceShortDrains() {
        val c = channel()
        val dest = ByteArray(quantumBytes)
        var seq = 1L
        var nextKeepalive = 0L
        var short = 0

        while (now < 5_000) {
            if (now >= nextKeepalive) {
                feed(c, packet(seq++, silence = true, frames = 0))
                nextKeepalive = now + 125L
            }
            if (c.drain(dest, quantumBytes) < quantumBytes) short++
            now += quantumMs
        }

        assertTrue(
            "the test would prove nothing if the queue never ran short; it ran short $short times",
            short > 0,
        )
        assertEquals(0L, c.underruns)
    }

    /** Real audio that stops arriving is a real hole, and must still count. */
    @Test
    fun aGapInRealAudioStillCounts() {
        val c = channel()
        val dest = ByteArray(quantumBytes)
        var seq = 1L

        // Fill with real audio until playback has pre-rolled.
        while (!c.started && seq < 200) {
            feed(c, packet(seq++, silence = false, frames = 343))
            now += 7
        }
        assertTrue("stream should have started", c.started)

        // Then the sender goes away entirely: no audio, no keepalives.
        val before = c.underruns
        repeat(300) {
            c.drain(dest, quantumBytes)
            now += quantumMs
        }

        assertTrue("a silent sender with no keepalive is a dropout", c.underruns > before)
    }

    /**
     * A keepalive that is merely jittery is covered. One that is genuinely
     * late is not: past the grace window the sender has stopped telling us
     * it is alive, and the hole is real again.
     */
    @Test
    fun aLateKeepaliveStillCounts() {
        val c = channel()
        val dest = ByteArray(quantumBytes)

        feed(c, packet(1, silence = true, frames = 0))
        assertTrue(c.started)

        // Drain past the 100 ms the keepalive stands for, while still inside
        // the grace window. Nothing should be counted.
        while (now < Wtp.SILENCE_KEEPALIVE_MS + StreamChannel.KEEPALIVE_GRACE_MS) {
            c.drain(dest, quantumBytes)
            now += quantumMs
        }
        assertEquals("inside the grace window this is ordinary jitter", 0L, c.underruns)

        // Now go well past it with no further keepalive.
        val before = c.underruns
        repeat(20) {
            c.drain(dest, quantumBytes)
            now += quantumMs
        }
        assertTrue("past the grace window a short drain is a real dropout", c.underruns > before)
    }

    /** Silence followed by real audio must not leave the channel permanently forgiving. */
    @Test
    fun realAudioAfterSilenceRestoresCounting() {
        val c = channel()
        val dest = ByteArray(quantumBytes)

        feed(c, packet(1, silence = true, frames = 0))
        now += 10
        feed(c, packet(2, silence = false, frames = 343))
        now += 10

        // Drain the little that is queued, then keep asking with nothing arriving.
        val before = c.underruns
        repeat(100) {
            c.drain(dest, quantumBytes)
            now += quantumMs
        }
        assertTrue("the last packet was audio, so these are real", c.underruns > before)
    }
}
