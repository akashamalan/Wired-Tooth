package com.wiredtooth.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the multi-stream path.
 *
 * These cover the parts that only exist because there is now more than one
 * stream: per-stream pre-roll, draining a fixed quantum out of variable-sized
 * blocks, and the mixing arithmetic. The drift controller and the resampler
 * are deliberately not retested -- they were not changed, only given one
 * instance each.
 */
class StreamChannelTest {

    private val rate = 48000
    private val ch = 2
    private val frameBytes = ch * 2

    private fun channel(target: Double = 60.0) =
        StreamChannel(0, target).also { it.configure(rate, ch) }

    /** A valid AUDIO packet for [streamId] carrying [frames] frames of [value]. */
    private fun audioPacket(streamId: Int, sequence: Long, frames: Int, value: Short): ByteArray {
        val payload = frames * frameBytes
        val pkt = ByteArray(Wtp.AUDIO_HEADER_SIZE + payload)
        System.arraycopy(Wtp.MAGIC, 0, pkt, 0, 4)
        pkt[4] = Wtp.TYPE_AUDIO.toByte()
        pkt[5] = (streamId shl Wtp.STREAM_ID_SHIFT).toByte()
        pkt[6] = (payload and 0xFF).toByte()
        pkt[7] = ((payload shr 8) and 0xFF).toByte()
        for (i in 0..3) pkt[8 + i] = ((sequence shr (8 * i)) and 0xFF).toByte()
        pkt[20] = (rate and 0xFF).toByte()
        pkt[21] = ((rate shr 8) and 0xFF).toByte()
        pkt[22] = ((rate shr 16) and 0xFF).toByte()
        pkt[24] = ch.toByte()
        pkt[26] = 16
        pkt[27] = Wtp.CODEC_PCM16.toByte()
        for (i in 0 until frames * ch) {
            val o = Wtp.AUDIO_HEADER_SIZE + i * 2
            pkt[o] = (value.toInt() and 0xFF).toByte()
            pkt[o + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return pkt
    }

    private fun feed(c: StreamChannel, pkt: ByteArray) {
        val h = Wtp.parse(pkt, pkt.size)!!
        c.accept(h, pkt, pkt.size, ShortArray(4096), ShortArray(8192))
    }

    // ---- pre-roll ------------------------------------------------------

    /**
     * A stream must not be mixed until it holds its target depth. A second app
     * starting mid-session otherwise plays its first moments out of an empty
     * buffer, which stutters.
     */
    @Test
    fun doesNotPlayUntilPreRolled() {
        val c = channel()
        assertFalse("a fresh channel has nothing buffered", c.started)

        val dest = ByteArray(1024)
        assertEquals("drain before pre-roll must yield nothing", 0, c.drain(dest, dest.size))

        // 60 ms at 48 kHz stereo is 5760 frames. Feed a little over.
        var seq = 1L
        while (!c.started && seq < 100) feed(c, audioPacket(0, seq++, 343, 1000))

        assertTrue("target depth should have started the stream", c.started)
        assertTrue("drain after pre-roll must yield audio", c.drain(dest, dest.size) > 0)
    }

    /** Pre-roll is per stream: one being ready says nothing about another. */
    @Test
    fun preRollIsIndependentPerStream() {
        val a = channel()
        val b = channel()

        var seq = 1L
        while (!a.started && seq < 100) feed(a, audioPacket(0, seq++, 343, 500))

        assertTrue(a.started)
        assertFalse("the second stream has received nothing", b.started)
    }

    // ---- draining ------------------------------------------------------

    /**
     * The riskiest new code. The mixer asks for a fixed quantum; the blocks in
     * the queue are whatever size the resampler produced. Draining across that
     * boundary must reassemble the byte stream exactly -- no loss, no
     * duplication, no reordering. A bug here is a click on every quantum edge.
     *
     * The quantum size deliberately divides none of the block sizes.
     */
    @Test
    fun drainPreservesByteOrder() {
        val c = channel(target = 0.0)
        val source = ByteArray(600) { ((it * 7) % 256).toByte() }

        c.enqueueForTest(source.copyOfRange(0, 13))
        c.enqueueForTest(source.copyOfRange(13, 200))
        c.enqueueForTest(source.copyOfRange(200, 600))

        val out = ByteArray(source.size)
        var filled = 0
        val chunk = ByteArray(41)
        while (filled < source.size) {
            val n = c.drain(chunk, minOf(chunk.size, source.size - filled))
            if (n == 0) break
            System.arraycopy(chunk, 0, out, filled, n)
            filled += n
        }

        assertEquals(source.size, filled)
        assertArrayEquals("reassembled stream must equal the source", source, out)
    }

    /** A drain that cannot be fully satisfied counts one underrun. */
    @Test
    fun shortDrainCountsAnUnderrun() {
        val c = channel(target = 0.0)
        c.enqueueForTest(ByteArray(40))

        val before = c.underruns
        c.drain(ByteArray(100), 100)
        assertEquals("one short read is one underrun", before + 1, c.underruns)
    }

    // ---- silence keepalive ---------------------------------------------

    /**
     * WASAPI loopback goes silent when the PC plays nothing, so the sender
     * emits an empty silence-flagged packet every 100 ms. Each must expand to
     * exactly 100 ms of zeros, or the buffer starves during a quiet passage.
     */
    @Test
    fun silenceKeepaliveExpandsToItsFullDuration() {
        val c = channel(target = 0.0)
        val pkt = audioPacket(0, 1, 0, 0)
        pkt[5] = (pkt[5].toInt() or Wtp.FLAG_SILENCE).toByte()

        feed(c, pkt)

        val expected = rate * frameBytes * Wtp.SILENCE_KEEPALIVE_MS / 1000
        val dest = ByteArray(expected * 2)
        assertEquals("one keepalive is one keepalive of silence", expected, c.drain(dest, dest.size))
        for (i in 0 until expected) assertEquals("byte $i must be silent", 0, dest[i].toInt())
    }

    // ---- mixing --------------------------------------------------------

    @Test
    fun mixingSumsStreamsAndAppliesGain() {
        val mix = IntArray(2)
        Mixing.accumulate(pcm(1000, 1000), 4, 1.0f, mix)
        Mixing.accumulate(pcm(500, -500), 4, 1.0f, mix)
        assertArrayEquals(intArrayOf(1500, 500), mix)

        val halved = IntArray(2)
        Mixing.accumulate(pcm(1000, -1000), 4, 0.5f, halved)
        assertArrayEquals(intArrayOf(500, -500), halved)
    }

    /** Volume 0 must contribute nothing, not a rounded-down whisper. */
    @Test
    fun zeroGainContributesSilence() {
        val mix = IntArray(2)
        Mixing.accumulate(pcm(32767, -32768), 4, 0f, mix)
        assertArrayEquals(intArrayOf(0, 0), mix)
    }

    /**
     * The failure mode that matters. Three loud streams sum past int16; the
     * mix must clamp rather than wrap. A wrap turns a loud passage into a
     * full-scale click, which is both louder than the music and capable of
     * hurting someone wearing earbuds.
     */
    @Test
    fun mixingClampsInsteadOfWrapping() {
        val mix = IntArray(2)
        repeat(3) { Mixing.accumulate(pcm(30000, -30000), 4, 1.0f, mix) }
        assertArrayEquals("accumulator holds the true sum", intArrayOf(90000, -90000), mix)

        val out = ByteArray(4)
        Mixing.clampInto(mix, out)
        assertEquals("positive clamp", 32767, readShort(out, 0))
        assertEquals("negative clamp", -32768, readShort(out, 2))
    }

    @Test
    fun clampRoundTripsValuesInRange() {
        for (v in intArrayOf(0, 1, -1, 12345, -12345, 32767, -32768)) {
            val out = ByteArray(2)
            Mixing.clampInto(intArrayOf(v), out)
            assertEquals("value $v must survive", v, readShort(out, 0))
        }
    }

    private fun pcm(vararg values: Int): ByteArray {
        val b = ByteArray(values.size * 2)
        for (i in values.indices) {
            b[i * 2] = (values[i] and 0xFF).toByte()
            b[i * 2 + 1] = ((values[i] shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun readShort(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
}
