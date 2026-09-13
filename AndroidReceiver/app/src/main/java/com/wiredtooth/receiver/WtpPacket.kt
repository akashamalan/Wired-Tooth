package com.wiredtooth.receiver

/**
 * Wired Tooth Protocol v1 (WTP1), Kotlin side.
 *
 * This is the second independent implementation of the wire format; the first
 * is WiredTooth.Protocol/WtpPacket.cs. The byte layout is specified in
 * docs/PROTOCOL.md and that document, not either implementation, is the
 * contract. Anything changed here must be changed there and in the C#.
 *
 * Little-endian throughout, including the magic.
 */
object Wtp {

    /** "WTP1". A wrong-magic datagram is someone else's traffic on our port. */
    val MAGIC = byteArrayOf(0x57, 0x54, 0x50, 0x31)

    const val COMMON_HEADER_SIZE = 20
    const val AUDIO_SUB_HEADER_SIZE = 8
    const val AUDIO_HEADER_SIZE = COMMON_HEADER_SIZE + AUDIO_SUB_HEADER_SIZE

    const val MAX_DATAGRAM_SIZE = 1400

    const val CODEC_PCM16: Int = 0

    /**
     * How much silence a silence-flagged AUDIO packet with an empty payload
     * stands for. WASAPI loopback stops firing entirely when the PC is quiet,
     * so the sender emits one of these every 100 ms to keep this receiver's
     * buffer fed. The duration cannot be derived from the packet -- the
     * payload is empty by design -- so it is a shared constant and both
     * implementations must agree on it.
     */
    const val SILENCE_KEEPALIVE_MS = 100

    const val TYPE_AUDIO = 1
    const val TYPE_HELLO = 2
    const val TYPE_HELLO_ACK = 3
    const val TYPE_PING = 4
    const val TYPE_PONG = 5
    const val TYPE_BYE = 6

    const val FLAG_SILENCE = 0x01

    private val startNanos = System.nanoTime()

    /**
     * Microseconds since this process started, from a monotonic clock.
     *
     * Microseconds rather than milliseconds because at 48 kHz one millisecond
     * is 48 frames, far too coarse for the latency figure this project exists
     * to report. Monotonic because an NTP correction stepping a wall clock
     * backwards mid-stream would produce negative deltas.
     */
    fun timestampMicros(): Long = (System.nanoTime() - startNanos) / 1000L

    // ---- reading -------------------------------------------------------

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun i64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
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

    /**
     * A parsed header. [payloadOffset] and [payloadLength] index into the
     * caller's own receive buffer, so parsing copies nothing.
     */
    data class Header(
        val type: Int,
        val flags: Int,
        val payloadLength: Int,
        val sequence: Long,
        val timestampMicros: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val codec: Int,
        val payloadOffset: Int,
    ) {
        val isSilence: Boolean get() = (flags and FLAG_SILENCE) != 0
    }

    /**
     * Parses a datagram, or returns null.
     *
     * NEVER throws. This runs on data straight off a public socket, and an
     * exception here would take the stream down on a corrupt or hostile
     * packet. Same contract as the C# TryParse, and verified against the same
     * cases in WtpPacketTest.
     */
    fun parse(buf: ByteArray, length: Int): Header? {
        if (length < COMMON_HEADER_SIZE) return null
        if (length > buf.size) return null

        for (i in 0..3) if (buf[i] != MAGIC[i]) return null

        val type = buf[4].toInt() and 0xFF
        if (type < TYPE_AUDIO || type > TYPE_BYE) return null

        val flags = buf[5].toInt() and 0xFF
        val payloadLength = u16(buf, 6)
        val sequence = u32(buf, 8)
        val timestamp = i64(buf, 12)

        val headerSize = if (type == TYPE_AUDIO) AUDIO_HEADER_SIZE else COMMON_HEADER_SIZE
        if (length < headerSize) return null

        // The declared length must match what actually arrived. A mismatch
        // means truncation or a forged header, and trusting it would read past
        // the end of valid data.
        if (payloadLength != length - headerSize) return null

        var rate = 0
        var channels = 0
        var bits = 0
        var codec = 0

        if (type == TYPE_AUDIO) {
            rate = u32(buf, 20).toInt()
            channels = u16(buf, 24)
            bits = buf[26].toInt() and 0xFF
            codec = buf[27].toInt() and 0xFF

            // A zero rate or channel count would make frame-size arithmetic
            // divide by zero further down.
            if (rate <= 0 || channels <= 0 || bits <= 0) return null
        }

        return Header(
            type = type,
            flags = flags,
            payloadLength = payloadLength,
            sequence = sequence,
            timestampMicros = timestamp,
            sampleRate = rate,
            channels = channels,
            bitsPerSample = bits,
            codec = codec,
            payloadOffset = headerSize,
        )
    }

    // ---- writing -------------------------------------------------------

    /**
     * Builds a control packet (HELLO / PING / BYE). [payload] is appended
     * after the common header; PONG is the only type that uses one, and this
     * receiver never sends PONGs.
     */
    fun writeControl(
        type: Int,
        sequence: Long,
        timestampMicros: Long,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArray(COMMON_HEADER_SIZE + payload.size)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = type.toByte()
        out[5] = 0
        putU16(out, 6, payload.size)
        putU32(out, 8, sequence)
        putI64(out, 12, timestampMicros)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, COMMON_HEADER_SIZE, payload.size)
        }
        return out
    }

    /** Reads the 8-byte echoed timestamp out of a PONG payload. */
    fun readEchoedTimestamp(buf: ByteArray, offset: Int, length: Int): Long? {
        if (length < 8) return null
        return i64(buf, offset)
    }
}
