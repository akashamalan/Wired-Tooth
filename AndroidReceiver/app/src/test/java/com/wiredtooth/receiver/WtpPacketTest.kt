package com.wiredtooth.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation tests for the WTP1 parser.
 *
 * The fixture below is the worked example from docs/PROTOCOL.md, byte for
 * byte. That document is the contract between this parser and the C# one, so
 * testing against the spec's own bytes checks both implementations against
 * the same thing rather than against each other.
 *
 * The malformed cases mirror the ones the C# parser was verified against.
 * The requirement is not that they are rejected gracefully -- it is that
 * parsing NEVER throws, because this runs on data straight off a public
 * socket and an exception would take the stream down.
 */
class WtpPacketTest {

    /**
     * docs/PROTOCOL.md worked example: AUDIO, seq 1, t = 1,000,000 us,
     * 48 kHz stereo PCM16, four frames of audio. 44 bytes.
     */
    private fun workedExample(): ByteArray = byteArrayOf(
        0x57, 0x54, 0x50, 0x31,                         // "WTP1"
        0x01,                                           // type AUDIO
        0x00,                                           // flags
        0x10, 0x00,                                     // payloadLen 16
        0x01, 0x00, 0x00, 0x00,                         // sequence 1
        0x40, 0x42, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, // timestamp 1000000
        0x80.toByte(), 0xBB.toByte(), 0x00, 0x00,       // sampleRate 48000
        0x02, 0x00,                                     // channels 2
        0x10,                                           // bits 16
        0x00,                                           // codec PCM16
        0x00, 0x00, 0x00, 0x00,                         // frame 0:      0,      0
        0xE8.toByte(), 0x03, 0x18, 0xFC.toByte(),       // frame 1:   1000,  -1000
        0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(),       // frame 2:  32767, -32768
        0x64, 0x00, 0x9C.toByte(), 0xFF.toByte(),       // frame 3:    100,   -100
    )

    @Test
    fun parsesTheSpecWorkedExample() {
        val pkt = workedExample()
        assertEquals("fixture must match docs/PROTOCOL.md", 44, pkt.size)

        val h = Wtp.parse(pkt, pkt.size)
        assertNotNull("the spec's own example must parse", h)
        h!!
        assertEquals(Wtp.TYPE_AUDIO, h.type)
        assertEquals(0, h.flags)
        assertEquals(16, h.payloadLength)
        assertEquals(1L, h.sequence)
        assertEquals(1_000_000L, h.timestampMicros)
        assertEquals(48000, h.sampleRate)
        assertEquals(2, h.channels)
        assertEquals(16, h.bitsPerSample)
        assertEquals(Wtp.CODEC_PCM16, h.codec)
        assertEquals(Wtp.AUDIO_HEADER_SIZE, h.payloadOffset)
        assertFalse(h.isSilence)
    }

    @Test
    fun decodesPayloadSamplesLittleEndian() {
        val pkt = workedExample()
        val h = Wtp.parse(pkt, pkt.size)!!
        val expected = intArrayOf(0, 0, 1000, -1000, 32767, -32768, 100, -100)
        for (i in expected.indices) {
            val o = h.payloadOffset + i * 2
            val v = ((pkt[o].toInt() and 0xFF) or (pkt[o + 1].toInt() shl 8)).toShort().toInt()
            assertEquals("sample $i", expected[i], v)
        }
    }

    @Test
    fun malformedInputIsRejectedAndNeverThrows() {
        val good = workedExample()

        fun mutate(i: Int, v: Int): ByteArray =
            good.copyOf().also { it[i] = v.toByte() }

        val cases = listOf<Pair<String, ByteArray>>(
            "empty" to ByteArray(0),
            "one byte" to byteArrayOf(0x57),
            "19 bytes" to ByteArray(19),
            "all zeros x28" to ByteArray(28),
            "wrong magic" to mutate(0, 0x58),
            "magic WTP2" to mutate(3, '2'.code),
            "type 0" to mutate(4, 0),
            "type 99" to mutate(4, 99),
            "payloadLen too big" to mutate(6, 0xFF),
            "truncated body" to good.copyOf(good.size - 3),
            "header only, no payload" to good.copyOf(28),
            "sampleRate 0" to good.copyOf().also { for (i in 20..23) it[i] = 0 },
            "channels 0" to good.copyOf().also { it[24] = 0; it[25] = 0 },
            "bits 0" to mutate(26, 0),
            "0xFF x 1400" to ByteArray(1400) { 0xFF.toByte() },
        )

        for ((name, data) in cases) {
            val h = try {
                Wtp.parse(data, data.size)
            } catch (e: Throwable) {
                throw AssertionError("parse threw on '$name': ${e.javaClass.simpleName}", e)
            }
            assertNull("'$name' must be rejected", h)
        }
    }

    @Test
    fun extraTrailingBytesAreRejected() {
        // payloadLen must equal exactly what arrived; a longer datagram means
        // truncation or forgery somewhere.
        val good = workedExample()
        val padded = good.copyOf(good.size + 2)
        assertNull(Wtp.parse(padded, padded.size))
    }

    @Test
    fun everySingleByteMutationIsSurvivable() {
        // The C# parser was verified against all 11,220 single-byte mutations
        // of this packet with zero exceptions. Same check here.
        val good = workedExample()
        var accepted = 0
        var total = 0
        for (i in good.indices) {
            for (v in 0..255) {
                if ((good[i].toInt() and 0xFF) == v) continue
                val m = good.copyOf()
                m[i] = v.toByte()
                total++
                val h = try {
                    Wtp.parse(m, m.size)
                } catch (e: Throwable) {
                    throw AssertionError("threw on byte $i = $v", e)
                }
                if (h != null) accepted++
            }
        }
        assertEquals(44 * 255, total)
        // Most mutations land in the payload, timestamp or sequence, which are
        // valid data by definition, so a high accept rate is correct. The
        // claim under test is that none of them threw.
        assertTrue("some mutations should still parse", accepted > 0)
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rng = java.util.Random(42)
        repeat(20_000) {
            val b = ByteArray(rng.nextInt(1500))
            rng.nextBytes(b)
            try {
                Wtp.parse(b, b.size)
            } catch (e: Throwable) {
                throw AssertionError("threw on random input of ${b.size} bytes", e)
            }
        }
    }

    @Test
    fun silenceFlagIsReadFromAnEmptyAudioPacket() {
        val pkt = ByteArray(Wtp.AUDIO_HEADER_SIZE)
        System.arraycopy(Wtp.MAGIC, 0, pkt, 0, 4)
        pkt[4] = Wtp.TYPE_AUDIO.toByte()
        pkt[5] = Wtp.FLAG_SILENCE.toByte()
        // payloadLen 0
        pkt[20] = 0x80.toByte(); pkt[21] = 0xBB.toByte()   // 48000
        pkt[24] = 0x02                                      // 2 channels
        pkt[26] = 0x10                                      // 16 bits
        pkt[27] = 0x00                                      // PCM16

        val h = Wtp.parse(pkt, pkt.size)
        assertNotNull("silence keepalives carry no payload and must parse", h)
        assertTrue(h!!.isSilence)
        assertEquals(0, h.payloadLength)
    }

    @Test
    fun controlPacketsRoundTrip() {
        val hello = Wtp.writeControl(Wtp.TYPE_HELLO, 7, 123_456)
        assertEquals(Wtp.COMMON_HEADER_SIZE, hello.size)

        val h = Wtp.parse(hello, hello.size)
        assertNotNull(h)
        assertEquals(Wtp.TYPE_HELLO, h!!.type)
        assertEquals(7L, h.sequence)
        assertEquals(123_456L, h.timestampMicros)
        assertEquals(0, h.payloadLength)
    }

    @Test
    fun pongEchoIsReadBack() {
        // The sender copies the PING's timestamp into the PONG payload; RTT is
        // computed entirely on this side so the two clocks never have to agree.
        val sent = 9_876_543L
        val echo = ByteArray(8)
        for (i in 0..7) echo[i] = ((sent ushr (8 * i)) and 0xFF).toByte()

        val pong = Wtp.writeControl(Wtp.TYPE_PONG, 3, 1, echo)
        val h = Wtp.parse(pong, pong.size)!!
        assertEquals(Wtp.TYPE_PONG, h.type)
        assertEquals(sent, Wtp.readEchoedTimestamp(pong, h.payloadOffset, h.payloadLength))
    }

    @Test
    fun timestampIsMonotonic() {
        var last = Wtp.timestampMicros()
        repeat(1000) {
            val now = Wtp.timestampMicros()
            assertTrue("timestamp went backwards", now >= last)
            last = now
        }
    }

    // ---- streamId, flags bits 1-7 -------------------------------------

    /**
     * The spec's own example has flags = 0, so it must read as stream 0. This
     * is the whole backward-compatibility story in one assertion: every packet
     * an older sender ever produced lands on stream 0 without negotiation.
     */
    @Test
    fun oldSenderPacketsReadAsStreamZero() {
        val h = Wtp.parse(workedExample(), 44)
        assertNotNull(h)
        assertEquals(0, h!!.streamId)
        assertFalse(h.isSilence)
    }

    /** Every id from 0 to 127 must survive a round trip through the flags byte. */
    @Test
    fun everyStreamIdRoundTrips() {
        for (id in 0..Wtp.MAX_STREAM_ID) {
            val pkt = workedExample()
            pkt[5] = (id shl Wtp.STREAM_ID_SHIFT).toByte()
            val h = Wtp.parse(pkt, pkt.size)
            assertNotNull("stream $id must parse", h)
            assertEquals("stream $id", id, h!!.streamId)
            assertFalse("stream $id must not read as silence", h.isSilence)
        }
    }

    /**
     * Silence and stream id share one byte, so the pair has to be decodable
     * independently. A bug that let the id leak into bit 0 would turn odd
     * stream numbers into permanent silence.
     */
    @Test
    fun silenceAndStreamIdDoNotCollide() {
        for (id in 0..Wtp.MAX_STREAM_ID) {
            val pkt = workedExample()
            pkt[5] = ((id shl Wtp.STREAM_ID_SHIFT) or Wtp.FLAG_SILENCE).toByte()
            val h = Wtp.parse(pkt, pkt.size)
            assertNotNull(h)
            assertEquals("stream $id with silence set", id, h!!.streamId)
            assertTrue("silence must survive stream $id", h.isSilence)
        }
    }

    /**
     * Mutation of the flags byte specifically. The existing suite mutates
     * every byte of a valid packet; this narrows in on the new field and
     * asserts the two properties that matter: parsing never throws, and a
     * decoded id is always in range, so it can index a stream table without
     * a bounds check at every call site.
     */
    @Test
    fun everyFlagsByteValueIsSafe() {
        for (v in 0..255) {
            val pkt = workedExample()
            pkt[5] = v.toByte()
            val h = try {
                Wtp.parse(pkt, pkt.size)
            } catch (e: Throwable) {
                throw AssertionError("flags=$v threw ${e.javaClass.simpleName}", e)
            }
            assertNotNull("flags=$v is structurally valid and must parse", h)
            val id = h!!.streamId
            assertTrue("flags=$v gave stream id $id out of range", id in 0..Wtp.MAX_STREAM_ID)
            assertEquals("flags=$v silence bit", (v and 1) != 0, h.isSilence)
        }
    }

    /**
     * The same exhaustive single-byte mutation the C# parser was verified
     * against, re-run now that a byte carries a new meaning. Reading a field
     * that did not exist before must not turn a survivable packet into a
     * crash.
     */
    @Test
    fun singleByteMutationsNeverThrowWithStreamIdsInPlay() {
        val base = workedExample()
        var parsed = 0
        for (i in base.indices) {
            for (v in 0..255) {
                val pkt = base.copyOf()
                pkt[i] = v.toByte()
                val h = try {
                    Wtp.parse(pkt, pkt.size)
                } catch (e: Throwable) {
                    throw AssertionError("byte $i = $v threw ${e.javaClass.simpleName}", e)
                }
                if (h != null) {
                    parsed++
                    assertTrue("byte $i = $v gave stream ${h.streamId}",
                        h.streamId in 0..Wtp.MAX_STREAM_ID)
                }
            }
        }
        // Most mutations are legitimately accepted -- a changed payload or
        // timestamp byte is still a structurally valid packet.
        assertTrue("expected many survivors, got $parsed", parsed > 1000)
    }

    // ---- control packets with trailing payload -------------------------
    //
    // These exist because Part B wants the sender to ship an app list to the
    // receiver, and extending HELLO_ACK with a payload is the obvious way to
    // do it without a new packet type. Whether that is SAFE for the receivers
    // already in the field had never actually been checked.

    /** A control packet of [type] carrying [payload], with a correct length field. */
    private fun controlPacket(type: Int, payload: ByteArray, declaredLen: Int = -1): ByteArray {
        val pkt = ByteArray(Wtp.COMMON_HEADER_SIZE + payload.size)
        System.arraycopy(Wtp.MAGIC, 0, pkt, 0, 4)
        pkt[4] = type.toByte()
        pkt[5] = 0
        val len = if (declaredLen >= 0) declaredLen else payload.size
        pkt[6] = (len and 0xFF).toByte()
        pkt[7] = ((len shr 8) and 0xFF).toByte()
        pkt[8] = 1
        System.arraycopy(payload, 0, pkt, Wtp.COMMON_HEADER_SIZE, payload.size)
        return pkt
    }

    /**
     * The answer to "does the receiver ignore a trailing HELLO_ACK payload?"
     *
     * Yes -- but only when payloadLen counts it. The parser reads the type and
     * the length and hands the payload back as an offset the caller is free to
     * ignore, which is exactly what the control loop does for HELLO_ACK.
     */
    @Test
    fun helloAckWithTrailingPayloadStillParses() {
        val payload = "app-list".toByteArray()
        val pkt = controlPacket(Wtp.TYPE_HELLO_ACK, payload)

        val h = Wtp.parse(pkt, pkt.size)
        assertNotNull("HELLO_ACK with a payload must still parse", h)
        assertEquals(Wtp.TYPE_HELLO_ACK, h!!.type)
        assertEquals(payload.size, h.payloadLength)
        assertEquals("payload starts after the 20-byte common header",
            Wtp.COMMON_HEADER_SIZE, h.payloadOffset)
    }

    /** Payload size is irrelevant to the receiver, so a large one must be fine too. */
    @Test
    fun helloAckPayloadMayBeLarge() {
        for (n in intArrayOf(1, 64, 512, Wtp.MAX_DATAGRAM_SIZE - Wtp.COMMON_HEADER_SIZE)) {
            val pkt = controlPacket(Wtp.TYPE_HELLO_ACK, ByteArray(n) { it.toByte() })
            val h = Wtp.parse(pkt, pkt.size)
            assertNotNull("HELLO_ACK with $n payload bytes must parse", h)
            assertEquals(n, h!!.payloadLength)
        }
    }

    /**
     * THE TRAP, and the reason this needed checking.
     *
     * Appending bytes while leaving payloadLen at 0 does not degrade
     * gracefully -- the length check rejects the packet outright, the control
     * loop drops it with `?: continue`, and the receiver simply never becomes
     * connected. Silently, with no error anywhere. A sender that extends
     * HELLO_ACK MUST update payloadLen.
     */
    @Test
    fun helloAckWithUndeclaredPayloadIsRejected() {
        val pkt = controlPacket(Wtp.TYPE_HELLO_ACK, "app-list".toByteArray(), declaredLen = 0)
        assertNull("undeclared trailing bytes must be rejected, not half-read",
            Wtp.parse(pkt, pkt.size))
    }

    /** An over-declared length is truncation or forgery and must be rejected. */
    @Test
    fun helloAckWithOverDeclaredLengthIsRejected() {
        val pkt = controlPacket(Wtp.TYPE_HELLO_ACK, ByteArray(4), declaredLen = 99)
        assertNull(Wtp.parse(pkt, pkt.size))
    }

    /**
     * The other Part B option: a brand new control packet type.
     *
     * Every type value beyond BYE is rejected by existing receivers, and
     * rejection here means "dropped", not "crashed". That makes adding a type
     * safe by construction for anything already in the field.
     */
    @Test
    fun unknownControlTypesAreDroppedNotFatal() {
        for (type in 7..255) {
            val pkt = controlPacket(type, ByteArray(8))
            val h = try {
                Wtp.parse(pkt, pkt.size)
            } catch (e: Throwable) {
                throw AssertionError("type $type threw ${e.javaClass.simpleName}", e)
            }
            assertNull("type $type is unknown and must be dropped", h)
        }
    }
}
