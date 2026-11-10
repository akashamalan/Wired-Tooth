package com.wiredtooth.receiver

import java.util.concurrent.atomic.AtomicLong

/**
 * Average and worst duration of a repeated operation, in nanoseconds.
 *
 * WHY WORST AND NOT JUST AVERAGE. The average answers "can this keep up", and
 * for a 10 ms quantum the answer is always yes by a wide margin. The number
 * that decides whether anyone HEARS a problem is the worst one, because a
 * single quantum that takes longer than the quantum itself is a late write to
 * AudioTrack, and a late write is a dropout. An average of 40 microseconds
 * hides a 12 millisecond outlier perfectly.
 *
 * WHY THIS IS SAFE ON THE AUDIO THREAD. One writer only -- the playback loop.
 * [record] does two atomic adds and one plain comparison, which is why [worst]
 * is a plain volatile rather than an atomic maximum: with a single writer the
 * read-compare-write cannot interleave with itself, and readers are a status
 * poll that does not care about the last 10 ms. No allocation, no locks, and
 * nothing that can block.
 *
 * The measurement itself costs two System.nanoTime calls per quantum, roughly
 * 50 ns against a 10 ms budget. That is reported rather than hidden, because a
 * timing harness that distorts what it measures is worse than no harness.
 */
internal class NanoStats {
    private val count = AtomicLong()
    private val total = AtomicLong()

    @Volatile
    var worst = 0L
        private set

    /**
     * The very first duration recorded since the last [reset].
     *
     * Separate from [worst] because they answer different questions. The
     * worst is usually scheduling noise somewhere in the middle; the first is
     * specifically the cost of running code that has never run before, which
     * on Android means ART interpreting it while the JIT decides whether to
     * compile it. That cost lands at one exact, predictable moment -- the
     * instant the user switches the equalizer on -- and averaging it away
     * hides the one glitch they will actually hear.
     */
    @Volatile
    var first = 0L
        private set

    fun record(nanos: Long) {
        if (nanos < 0) return               // a clock that went backwards
        val n = count.incrementAndGet()
        total.addAndGet(nanos)
        if (n == 1L) first = nanos
        if (nanos > worst) worst = nanos
    }

    val samples: Long get() = count.get()

    val average: Long
        get() {
            val n = count.get()
            return if (n == 0L) 0L else total.get() / n
        }

    fun reset() {
        count.set(0)
        total.set(0)
        worst = 0
        first = 0
    }
}
