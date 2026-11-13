package com.wiredtooth.receiver.dsp

import java.io.InputStream

/**
 * Reads and writes earbud profiles: our own "luma-profile 1" text format, and
 * the parametric-EQ text format that correction tools emit.
 *
 * WHY THIS NEVER THROWS. A profile is a file the user picked, which means it
 * is the one input to this app that is neither ours nor the sender's. The
 * packet parser is held to the same rule for the same reason: a malformed
 * input must cost you the input, not the app. So every failure here is a
 * value in the result -- an empty filter list, a warning, a clamped number --
 * and there is no path that propagates an exception to the caller. The fuzz
 * tests assert that across every single-byte mutation of a valid file and
 * twenty thousand random inputs.
 *
 * WHY THERE ARE NO REGULAR EXPRESSIONS. A backtracking regex on hostile input
 * is a denial of service: the input is 64 KB and the work is exponential. All
 * of the scanning below is a single forward pass with bounded inner loops --
 * line splitting by index, a whitespace tokeniser with a token cap, and a
 * hand-written number reader. Nothing here can take more than linear time in
 * the length of the input.
 *
 * WHAT IS DELIBERATELY NOT SUPPORTED. Exponent notation (1e3), hexadecimal,
 * NaN and Infinity are all rejected as numbers rather than coerced, because
 * every one of them is far more likely to be a corrupt file or an attack than
 * a frequency somebody meant to type. A decimal comma is accepted, because
 * that one is just a keyboard layout.
 *
 * NO PROFILE DATA IS BUNDLED WITH THIS APP. This file knows how to read a
 * profile; it does not contain one.
 */
object ProfileParser {

    /** Hard input ceiling. A real profile is a few hundred bytes. */
    const val MAX_BYTES = 64 * 1024

    /** Lines longer than this are skipped unread. */
    const val MAX_LINE_CHARS = 1000

    /** Tokens taken from any one line. Both formats use at most twelve. */
    private const val MAX_TOKENS = 32

    /** The most filters a profile may contribute. */
    const val MAX_FILTERS = GainBudget.MAX_PROFILE_FILTERS

    const val MAX_NAME_CHARS = 64
    const val MAX_NOTES_CHARS = 200

    const val FORMAT_ID = "luma-profile"
    const val FORMAT_VERSION = 1

    /** Engine limits every imported value is brought inside. */
    const val MIN_FC_HZ = 20f
    const val MAX_FC_HZ = 20000f
    const val MIN_Q = 0.1f
    const val MAX_Q = 18f

    /**
     * A profile as read, plus everything that went wrong reading it.
     *
     * The counts are separate from [warnings] because the sheet needs the
     * numbers ("2 filters skipped") and the messages are what it shows. One
     * is derived from the other, but keeping both means the UI never has to
     * parse a sentence to find out what happened.
     */
    data class Profile(
        val name: String = "",
        val author: String = "",
        val notes: String = "",
        val filters: List<EqBand> = emptyList(),
        val warnings: List<String> = emptyList(),
        /** Filters whose type this engine has no shape for. */
        val skippedUnsupported: Int = 0,
        /** Filters past [MAX_FILTERS]. */
        val droppedOverLimit: Int = 0,
        /** Individual numbers brought inside the engine's limits. */
        val clampedValues: Int = 0,
        /** True when the input was refused outright rather than read. */
        val rejected: Boolean = false,
    ) {
        val isEmpty: Boolean get() = filters.isEmpty()
    }

    // ---- entry points ----------------------------------------------------

    /**
     * Reads at most [MAX_BYTES] + 1 bytes, so an oversized file is detected
     * without being loaded.
     *
     * Returns null only if the stream itself failed; an oversized file comes
     * back as a rejected [Profile] so the caller has a message to show.
     */
    fun readLimited(input: InputStream): ByteArray? {
        val buf = ByteArray(MAX_BYTES + 1)
        var n = 0
        try {
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
        } catch (_: Throwable) {
            return null
        }
        return buf.copyOf(n)
    }

    fun parse(bytes: ByteArray): Profile {
        if (bytes.size > MAX_BYTES) return tooLarge()
        // A UTF-8 byte-order mark would otherwise become part of the first
        // token and stop the format header from matching.
        var off = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            off = 3
        }
        // Malformed byte sequences decode to U+FFFD rather than throwing.
        return parse(String(bytes, off, bytes.size - off, Charsets.UTF_8))
    }

    fun parse(text: String): Profile {
        // UTF-8 can only grow, never shrink, so more chars than the byte
        // ceiling means more bytes than the byte ceiling.
        if (text.length > MAX_BYTES) return tooLarge()

        val body = if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text
        val b = Builder()
        val luma = looksLikeLuma(body)

        var start = 0
        val n = body.length
        while (start <= n) {
            var nl = body.indexOf('\n', start)
            if (nl < 0) nl = n
            var stop = nl
            if (stop > start && body[stop - 1] == '\r') stop--   // CRLF

            if (stop - start > MAX_LINE_CHARS) {
                b.overlongLines++
            } else if (stop > start) {
                // Only now is a substring allocated, so a hostile 64 KB
                // single line costs a scan and not a copy.
                b.line(body.substring(start, stop), luma)
            }

            if (nl == n) break
            start = nl + 1
        }
        return b.build()
    }

    /**
     * Writes a profile back out in our own format.
     *
     * Four decimal places on gain and Q, two on frequency: enough that a
     * value survives a round trip inside Float's own precision, which is what
     * makes this safe to use as the persisted form.
     */
    fun export(p: Profile): String {
        val sb = StringBuilder(256)
        sb.append(FORMAT_ID).append(' ').append(FORMAT_VERSION).append('\n')
        sb.append("# Written by Luma. One filter per line: type, frequency in Hz, Q, gain in dB.\n")
        if (p.name.isNotEmpty()) sb.append("name: ").append(p.name).append('\n')
        if (p.author.isNotEmpty()) sb.append("author: ").append(p.author).append('\n')
        if (p.notes.isNotEmpty()) sb.append("notes: ").append(p.notes).append('\n')
        for (f in p.filters) {
            sb.append(keyword(f.type)).append(' ')
                .append(fixed(f.frequencyHz.toDouble(), 2)).append(' ')
                .append(fixed(f.q.toDouble(), 4)).append(' ')
                .append(fixed(f.gainDb.toDouble(), 4)).append('\n')
        }
        return sb.toString()
    }

    private fun keyword(t: BandType): String = when (t) {
        BandType.Peaking -> "peak"
        BandType.LowShelf -> "lowshelf"
        BandType.HighShelf -> "highshelf"
    }

    /**
     * Fixed-point formatting without String.format, which is locale-dependent
     * -- on a French or German phone it writes a decimal comma, and a file
     * written there would then read differently somewhere else. Our own
     * reader accepts a comma, but other tools' readers do not.
     */
    private fun fixed(v: Double, places: Int): String {
        if (!v.isFinite()) return "0"
        var scale = 1L
        for (i in 0 until places) scale *= 10
        val scaled = Math.round(Math.abs(v) * scale)
        val whole = scaled / scale
        val frac = scaled % scale
        val sb = StringBuilder(16)
        if (v < 0 && scaled != 0L) sb.append('-')
        sb.append(whole)
        if (places > 0) {
            sb.append('.')
            val digits = frac.toString()
            for (i in 0 until places - digits.length) sb.append('0')
            sb.append(digits)
        }
        return sb.toString()
    }

    private fun tooLarge() = Profile(
        rejected = true,
        warnings = listOf("That file is bigger than 64 KB, so it is not a profile Luma can read."),
    )

    // ---- format detection ------------------------------------------------

    /** True if the first line that says anything names our own format. */
    private fun looksLikeLuma(text: String): Boolean {
        var start = 0
        val n = text.length
        var examined = 0
        while (start <= n && examined < 8) {
            var nl = text.indexOf('\n', start)
            if (nl < 0) nl = n
            var stop = nl
            if (stop > start && text[stop - 1] == '\r') stop--
            if (stop - start in 1..MAX_LINE_CHARS) {
                val line = text.substring(start, stop).trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    examined++
                    if (line.length >= FORMAT_ID.length &&
                        line.regionMatches(0, FORMAT_ID, 0, FORMAT_ID.length, ignoreCase = true)
                    ) {
                        return true
                    }
                    // The first meaningful line decides. Anything else is
                    // treated as the parametric format, which is the one that
                    // arrives from outside and has no header of its own.
                    return false
                }
            }
            if (nl == n) break
            start = nl + 1
        }
        return false
    }

    // ---- scanning --------------------------------------------------------

    private class Builder {
        val filters = ArrayList<EqBand>(MAX_FILTERS)
        var overlongLines = 0
        var unsupported = 0
        var dropped = 0
        var clamped = 0
        var unreadable = 0
        var preampSeen = false
        var name = ""
        var author = ""
        var notes = ""

        fun line(raw: String, luma: Boolean) {
            val tokens = tokenize(raw)
            if (tokens.isEmpty()) return
            if (tokens[0].startsWith("#") || tokens[0].startsWith(";")) return
            if (luma) lumaLine(tokens) else parametricLine(tokens)
        }

        // -- our own format --
        private fun lumaLine(t: List<String>) {
            val head = t[0].lowercase()
            if (head == FORMAT_ID) return                       // the header itself
            if (head.endsWith(":")) {
                val value = joinFrom(t, 1)
                when (head.dropLast(1)) {
                    "name" -> name = clean(value, MAX_NAME_CHARS)
                    "author" -> author = clean(value, MAX_NAME_CHARS)
                    "notes" -> notes = clean(value, MAX_NOTES_CHARS)
                    else -> unreadable++
                }
                return
            }
            val type = typeOf(head)
            if (type == null) {
                unreadable++
                return
            }
            if (t.size < 4) {
                unreadable++
                return
            }
            val fc = number(t[1])
            val q = number(t[2])
            val gain = number(t[3])
            if (fc == null || q == null || gain == null) {
                unreadable++
                return
            }
            add(type, fc, q, gain)
        }

        // -- the parametric format other tools write --
        private fun parametricLine(t: List<String>) {
            val head = t[0].lowercase().trimEnd(':')
            if (head == "preamp") {
                // Accepted and discarded: our own true-peak measurement
                // decides the preamp, and it knows the whole cascade
                // including the macros, which this number cannot.
                preampSeen = true
                return
            }
            if (head != "filter") {
                unreadable++
                return
            }

            // "Filter 3: ON PK Fc 105 Hz Gain -3.2 dB Q 0.70"
            var i = 1
            var state: String? = null
            while (i < t.size) {
                val u = t[i].uppercase()
                if (u == "ON" || u == "OFF") {
                    state = u
                    break
                }
                i++
            }
            if (state == null) {
                unreadable++
                return
            }
            if (state == "OFF") return                          // deliberately off; silent

            val typeToken = t.getOrNull(i + 1)
            if (typeToken == null) {
                unreadable++
                return
            }
            val type = typeOf(typeToken.lowercase())
            if (type == null) {
                // A real type we have no shape for -- never approximated as
                // another, because a peak standing in for a shelf is wrong by
                // about 3 dB in the sub-bass.
                unsupported++
                return
            }

            var fc: Double? = null
            var gain: Double? = null
            var q: Double? = null
            var k = i + 2
            while (k < t.size) {
                when (t[k].lowercase().trimEnd(':')) {
                    "fc" -> fc = fc ?: number(t.getOrNull(k + 1) ?: "")
                    "gain" -> gain = gain ?: number(t.getOrNull(k + 1) ?: "")
                    "q" -> q = q ?: number(t.getOrNull(k + 1) ?: "")
                }
                k++
            }
            if (fc == null || gain == null || q == null) {
                // Incomplete, or a bandwidth given instead of a Q. Either way
                // there is no honest filter to build.
                unreadable++
                return
            }
            add(type, fc, q, gain)
        }

        private fun add(type: BandType, fcIn: Double, qIn: Double, gainIn: Double) {
            if (filters.size >= MAX_FILTERS) {
                dropped++
                return
            }
            val fc = fcIn.coerceIn(MIN_FC_HZ.toDouble(), MAX_FC_HZ.toDouble())
            val q = qIn.coerceIn(MIN_Q.toDouble(), MAX_Q.toDouble())
            val gain = gainIn.coerceIn(
                EqSettings.MIN_GAIN_DB.toDouble(), SoundState.MAX_BOOST_DB.toDouble(),
            )
            if (fc != fcIn) clamped++
            if (q != qIn) clamped++
            if (gain != gainIn) clamped++
            filters.add(EqBand(fc.toFloat(), gain.toFloat(), q.toFloat(), type))
        }

        fun build(): Profile {
            val w = ArrayList<String>(6)
            // Fixed order, so the same input always produces the same result.
            if (overlongLines > 0) {
                w.add("$overlongLines line${s(overlongLines)} too long to read, skipped.")
            }
            if (unsupported > 0) {
                w.add("$unsupported filter${s(unsupported)} skipped: unsupported type.")
            }
            if (dropped > 0) {
                w.add(
                    "$dropped filter${s(dropped)} dropped: Luma uses the first " +
                        "$MAX_FILTERS.",
                )
            }
            if (clamped > 0) {
                w.add("$clamped value${s(clamped)} brought inside Luma's limits.")
            }
            if (preampSeen) {
                w.add("The file's own preamp line was ignored. Luma measures its own.")
            }
            if (unreadable > 0) {
                w.add("$unreadable line${s(unreadable)} not understood, skipped.")
            }
            if (filters.isEmpty() && w.isEmpty()) {
                w.add("No filters found in that file.")
            }
            return Profile(
                name = name,
                author = author,
                notes = notes,
                filters = filters.toList(),
                warnings = w.toList(),
                skippedUnsupported = unsupported,
                droppedOverLimit = dropped,
                clampedValues = clamped,
            )
        }

        private fun s(n: Int) = if (n == 1) "" else "s"
    }

    // ---- primitives ------------------------------------------------------

    private fun typeOf(lower: String): BandType? = when (lower) {
        "peak", "peaking", "pk" -> BandType.Peaking
        "lowshelf", "low-shelf", "low_shelf", "lsc" -> BandType.LowShelf
        "highshelf", "high-shelf", "high_shelf", "hsc" -> BandType.HighShelf
        else -> null
    }

    /**
     * Whitespace-separated tokens, capped at [MAX_TOKENS].
     *
     * The cap is what bounds the work on a hostile line: a thousand
     * characters of single-character tokens stops at thirty-two rather than
     * allocating five hundred strings. Both formats use at most twelve, so
     * nothing legitimate is lost.
     */
    private fun tokenize(line: String): List<String> {
        val out = ArrayList<String>(12)
        var i = 0
        val n = line.length
        while (i < n && out.size < MAX_TOKENS) {
            while (i < n && line[i].isWhitespace()) i++
            if (i >= n) break
            val s = i
            while (i < n && !line[i].isWhitespace()) i++
            out.add(line.substring(s, i))
        }
        return out
    }

    private fun joinFrom(t: List<String>, from: Int): String {
        if (from >= t.size) return ""
        val sb = StringBuilder()
        for (i in from until t.size) {
            if (i > from) sb.append(' ')
            sb.append(t[i])
        }
        return sb.toString()
    }

    /**
     * Control characters out, then capped.
     *
     * A name goes into a "key: value" line on export and onto a screen, so a
     * newline or an escape sequence in it would either break the format or
     * confuse a terminal. Stripping is better than rejecting: the rest of the
     * profile is still perfectly good.
     */
    private fun clean(raw: String, max: Int): String {
        val sb = StringBuilder(minOf(raw.length, max))
        for (ch in raw) {
            if (sb.length >= max) break
            val c = ch.code
            if (c < 0x20 || c == 0x7F || c in 0x80..0x9F) continue
            sb.append(ch)
        }
        return sb.toString().trim()
    }

    /**
     * A plain decimal number, or null.
     *
     * Written out rather than handed to toDoubleOrNull because that accepts
     * things this format should not: "1e999" becomes Infinity, "NaN" becomes
     * NaN, "0x1p3" is a valid hexadecimal float on the JVM, and a leading "d"
     * suffix is tolerated. Every one of those would then have to be caught
     * downstream, and a value that is merely clamped rather than rejected
     * hides the fact that the file was wrong.
     *
     * The fractional part accumulates as an integer and divides once, rather
     * than adding digit * 0.1^k, because the repeated multiply drifts and the
     * whole point of a fixed-point round trip is that it does not.
     */
    fun number(s: String): Double? {
        val n = s.length
        if (n == 0 || n > 24) return null

        var i = 0
        var negative = false
        when (s[0]) {
            '+' -> i = 1
            '-' -> { negative = true; i = 1 }
        }

        var whole = 0L
        var wholeDigits = 0
        while (i < n && s[i] in '0'..'9') {
            if (wholeDigits >= 10) return null          // absurd magnitude
            whole = whole * 10 + (s[i] - '0')
            wholeDigits++
            i++
        }

        var frac = 0L
        var scale = 1L
        var fracDigits = 0
        if (i < n && (s[i] == '.' || s[i] == ',')) {    // a decimal comma is a keyboard, not an error
            i++
            while (i < n && s[i] in '0'..'9') {
                if (fracDigits < 9) {
                    frac = frac * 10 + (s[i] - '0')
                    scale *= 10
                    fracDigits++
                }
                i++                                     // further digits read and ignored
            }
        }

        if (i != n) return null                         // trailing anything: e, x, d, junk
        if (wholeDigits == 0 && fracDigits == 0) return null

        var v = whole.toDouble()
        if (fracDigits > 0) v += frac.toDouble() / scale.toDouble()
        if (v == 0.0) return 0.0                        // never hand back -0.0
        return if (negative) -v else v
    }
}
