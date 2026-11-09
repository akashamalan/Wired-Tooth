package com.wiredtooth.receiver

/**
 * One stream's worth of receive state.
 *
 * Everything here used to be a field on AudioReceiver, back when there was
 * exactly one stream. Nothing about the ALGORITHMS changed in the move: the
 * jitter buffer is the same deque of PCM blocks, the drift controller is the
 * same controller at the same 60 ms setpoint, the resampler is the same
 * resampler, and concealment is the same 5 ms fade. Each stream simply gets
 * its own copy, because each stream arrives over its own sequence space and
 * drifts against the output clock independently.
 *
 * That independence is the whole reason this is a class rather than a set of
 * parallel arrays. Two apps on the PC start and stop at different moments, so
 * their buffers fill and drain at different times; one shared controller would
 * be steering the average of two things that are not related.
 */
class StreamChannel(
    val id: Int,
    private val targetBufferMs: Double,
) {
    /** Per-stream gain, 0.0 to 1.0. Read by the mixer on every quantum. */
    @Volatile var volume: Float = 1f

    /** Unchanged: same controller, same setpoint, same clamp. */
    val drift = DriftController(targetBufferMs)

    private val queue = ArrayDeque<ByteArray>()
    private val queueLock = Any()
    private var queuedBytes = 0

    /** Bytes left at the head of the block currently being drained. */
    private var headOffset = 0

    private var resampler: LinearResampler? = null
    private var sampleRate = 0
    private var channels = 0
    private var frameBytes = 0

    private var lastSeq = 0L
    private var haveSeq = false
    private var lastPayloadFrames = 0
    private var lastFrame = ShortArray(0)
    private var resyncing = false

    /**
     * False until the buffer has reached its target depth once.
     *
     * Pre-roll is per stream on purpose. A second app starting three minutes
     * in must fill its own 60 ms before it is mixed, or its first moments
     * play out of an empty buffer and stutter -- while the stream that has
     * been running all along carries on untouched.
     */
    @Volatile var started = false
        private set

    @Volatile var packetsReceived = 0L
        private set
    @Volatile var packetsLost = 0L
        private set
    @Volatile var underruns = 0L
        private set
    @Volatile var lastPacketAtMs = System.currentTimeMillis()
        private set

    fun configure(rate: Int, ch: Int) {
        if (sampleRate == rate && channels == ch && resampler != null) return
        sampleRate = rate
        channels = ch
        frameBytes = ch * 2
        resampler = LinearResampler(ch)
        lastFrame = ShortArray(ch)
        reset()
    }

    fun reset() {
        synchronized(queueLock) {
            queue.clear()
            queuedBytes = 0
            headOffset = 0
        }
        resampler?.reset()
        haveSeq = false
        resyncing = false
        started = false
    }

    fun bufferMs(): Double {
        if (sampleRate == 0 || frameBytes == 0) return 0.0
        val bytes = synchronized(queueLock) { queuedBytes }
        return bytes.toDouble() / (sampleRate * frameBytes) * 1000.0
    }

    private fun preBufferBytes(): Int =
        if (frameBytes == 0) 0 else (sampleRate * frameBytes * targetBufferMs / 1000.0).toInt()

    // ---- ingest --------------------------------------------------------

    /**
     * Accepts one parsed AUDIO packet for this stream. [inShorts] and
     * [outShorts] are the caller's scratch buffers, shared across all streams
     * so the receive thread allocates nothing per packet.
     */
    fun accept(
        h: Wtp.Header,
        buf: ByteArray,
        datagramLength: Int,
        inShorts: ShortArray,
        outShorts: ShortArray,
    ) {
        lastPacketAtMs = System.currentTimeMillis()

        // A gap longer than the timeout is a dropped link, not lost packets.
        if (!haveSeq) {
            lastSeq = h.sequence
            haveSeq = true
        } else if (h.sequence > lastSeq + 1) {
            val missing = (h.sequence - lastSeq - 1).toInt()
            packetsLost += missing.toLong()
            concealGap(missing)
        }
        lastSeq = h.sequence
        packetsReceived++

        if (h.isSilence) {
            // An empty silence-flagged packet stands for a fixed span of
            // quiet. Expanding it is what keeps the buffer fed while the PC
            // produces nothing at all.
            var bytes = sampleRate * frameBytes * Wtp.SILENCE_KEEPALIVE_MS / 1000
            bytes -= bytes % maxOf(frameBytes, 1)
            if (bytes > 0) {
                enqueue(ByteArray(bytes))
                resampler?.reset()
            }
            return
        }
        if (h.payloadLength == 0 || frameBytes == 0) return

        val frames = h.payloadLength / frameBytes
        if (frames <= 0) return
        val samples = frames * channels
        if (samples > inShorts.size) return

        for (i in 0 until samples) {
            val o = h.payloadOffset + i * 2
            if (o + 1 >= datagramLength) return
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

    /**
     * Fills a loss gap by fading the last received frame out over 5 ms and
     * then going silent. Unchanged from the single-stream version: letting the
     * buffer simply go short would starve playback and also mislead the drift
     * controller, which measures depth and cannot see a hole it was never
     * given.
     */
    private fun concealGap(missing: Int) {
        if (lastPayloadFrames <= 0 || channels <= 0 || frameBytes == 0) return
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

    /**
     * Test seam. The drain path has to be exercised with known bytes, and
     * driving it through accept() would mean going via the resampler, whose
     * output is deliberately not byte-predictable.
     */
    internal fun enqueueForTest(block: ByteArray) = enqueue(block)

    private fun enqueue(block: ByteArray) {
        synchronized(queueLock) {
            queue.addLast(block)
            queuedBytes += block.size
            if (!started && queuedBytes >= preBufferBytes()) started = true
        }
    }

    // ---- drain ---------------------------------------------------------

    /**
     * Drains up to [wanted] bytes into [dest], returning how many were
     * actually available. The mixer treats the remainder as silence.
     *
     * Partial blocks are tracked with [headOffset] rather than being requeued,
     * because the mixer asks for a fixed quantum and the blocks it is draining
     * are whatever size the resampler produced. Without that, every quantum
     * boundary that fell mid-block would either lose audio or need an
     * allocation.
     */
    fun drain(dest: ByteArray, wanted: Int): Int {
        if (!started) return 0
        var filled = 0
        synchronized(queueLock) {
            while (filled < wanted && queue.isNotEmpty()) {
                val head = queue.first()
                val available = head.size - headOffset
                val take = minOf(available, wanted - filled)
                System.arraycopy(head, headOffset, dest, filled, take)
                filled += take
                headOffset += take
                queuedBytes -= take
                if (headOffset >= head.size) {
                    queue.removeFirst()
                    headOffset = 0
                }
            }
        }
        if (filled < wanted) underruns++
        return filled
    }

    companion object {
        /**
         * Depth above target at which the stream stops accepting audio until
         * it has drained back down. Unchanged from the single-stream receiver.
         */
        const val RESYNC_THRESHOLD_MS = 150.0
    }
}

/**
 * The mixing arithmetic, pulled out of the playback thread so it can be
 * tested.
 *
 * It is four lines of shifting and clamping, which is exactly the kind of code
 * that is wrong in a way nobody notices until it is a click in someone's ear.
 * Inside a while(running) loop on an audio thread it is unreachable from a
 * test; out here it is two assertions.
 */
object Mixing {

    /** Adds [bytes] of little-endian PCM16 from [src] into [into], scaled by [gain]. */
    fun accumulate(src: ByteArray, bytes: Int, gain: Float, into: IntArray) {
        val samples = minOf(bytes / 2, into.size)
        for (i in 0 until samples) {
            val lo = src[i * 2].toInt() and 0xFF
            val hi = src[i * 2 + 1].toInt()
            val v = ((lo or (hi shl 8)).toShort()).toInt()
            into[i] += (v * gain).toInt()
        }
    }

    /**
     * Clamps the accumulator into little-endian PCM16.
     *
     * Summing several streams at full gain overflows int16 long before it
     * sounds loud. Clamping is audible as distortion, which is the correct
     * failure here: wrapping would turn a loud passage into a full-scale
     * click, which is both louder and capable of damaging hearing.
     */
    fun clampInto(mix: IntArray, out: ByteArray) {
        for (i in mix.indices) {
            var v = mix[i]
            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }
}
