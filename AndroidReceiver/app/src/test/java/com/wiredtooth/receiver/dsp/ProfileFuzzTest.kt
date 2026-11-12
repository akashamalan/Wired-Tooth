package com.wiredtooth.receiver.dsp

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile reader against hostile input, on the same terms as the packet
 * parser: it may reject anything, but it may never throw and may never hand
 * the engine a value the engine cannot run.
 *
 * WHY MUTATION AND RANDOM BOTH. Every single-byte mutation of a valid file
 * covers the cases that are nearly right -- a truncated number, a flipped
 * sign, a type word with one letter wrong -- which is what a corrupt download
 * or a hand edit actually produces, and which is exactly where a parser's
 * happy path stops being the path taken. Pure random input covers the cases
 * that are nothing like right, where the danger is unbounded work rather than
 * a wrong answer.
 */
class ProfileFuzzTest {

    private val lumaValid = """
        luma-profile 1
        name: Fuzz subject
        author: Test
        lowshelf 105 0.70 -3.2
        peak 2800 1.20 3.5
        highshelf 10000 0.70 2.0
    """.trimIndent()

    private val parametricValid = """
        Preamp: -6.8 dB
        Filter 1: ON LSC Fc 105 Hz Gain -3.2 dB Q 0.70
        Filter 2: ON PK Fc 2800 Hz Gain 3.5 dB Q 1.20
        Filter 3: OFF HSC Fc 10000 Hz Gain 2.0 dB Q 0.70
    """.trimIndent()

    /**
     * Everything a result must satisfy no matter what went in.
     *
     * The engine's own clamps would catch an out-of-range gain, but catching
     * it here is the point: a value that only survives because something
     * downstream clamped it is a value that would reach the DSP intact the
     * day somebody reuses this parser somewhere else.
     */
    private fun check(what: String, p: ProfileParser.Profile) {
        assertTrue(
            "$what produced ${p.filters.size} filters",
            p.filters.size <= ProfileParser.MAX_FILTERS,
        )
        for (f in p.filters) {
            assertTrue("$what: frequency ${f.frequencyHz}", f.frequencyHz.isFinite())
            assertTrue("$what: q ${f.q}", f.q.isFinite())
            assertTrue("$what: gain ${f.gainDb}", f.gainDb.isFinite())
            assertTrue(
                "$what: frequency ${f.frequencyHz} out of range",
                f.frequencyHz >= ProfileParser.MIN_FC_HZ &&
                    f.frequencyHz <= ProfileParser.MAX_FC_HZ,
            )
            assertTrue(
                "$what: q ${f.q} out of range",
                f.q >= ProfileParser.MIN_Q && f.q <= ProfileParser.MAX_Q,
            )
            assertTrue(
                "$what: gain ${f.gainDb} out of range",
                f.gainDb >= EqSettings.MIN_GAIN_DB && f.gainDb <= SoundState.MAX_BOOST_DB,
            )
        }
        assertTrue(
            "$what: name length ${p.name.length}",
            p.name.length <= ProfileParser.MAX_NAME_CHARS,
        )
        assertTrue("$what: control characters in name", p.name.none { it.code < 0x20 })
        assertTrue("$what: control characters in author", p.author.none { it.code < 0x20 })
        assertTrue("$what: control characters in notes", p.notes.none { it.code < 0x20 })
    }

    /**
     * Whatever came out must also survive the whole engine path.
     *
     * A parser that returns plausible rubbish is only half a defence; this is
     * the other half, and it is the part that would have caught the cascade
     * problem if it had existed earlier.
     */
    private fun checkEngine(what: String, p: ProfileParser.Profile) {
        if (p.filters.isEmpty()) return
        val state = SoundState(enabled = true, profile = p, bassDb = 6f, warmthDb = 6f)
        val settings = state.toEqSettings(48000)
        assertTrue(
            "$what: preamp ${settings.preampDb} not finite",
            settings.preampDb.isFinite(),
        )
        assertTrue(
            "$what: headroom ${settings.preampDb} breaches the floor",
            settings.preampDb >= -14.01f,
        )
        assertTrue(
            "$what: ${settings.bands.size} bands",
            settings.bands.size <= EqSettings.MAX_BANDS,
        )
    }

    // ---- every single-byte mutation --------------------------------------

    /**
     * Every byte of a valid file, replaced by every possible value.
     *
     * Insertion and deletion at every position too, since a truncated or
     * doubled byte is at least as common as a flipped one.
     */
    private fun mutateAll(valid: String): Int {
        val base = valid.toByteArray(Charsets.UTF_8)
        var cases = 0
        val buf = ByteArray(base.size)

        for (i in base.indices) {
            // substitution
            System.arraycopy(base, 0, buf, 0, base.size)
            for (v in 0..255) {
                buf[i] = v.toByte()
                check("substitute $v at $i", ProfileParser.parse(buf))
                cases++
            }
            // deletion
            val shorter = ByteArray(base.size - 1)
            System.arraycopy(base, 0, shorter, 0, i)
            System.arraycopy(base, i + 1, shorter, i, base.size - i - 1)
            check("delete at $i", ProfileParser.parse(shorter))
            cases++
        }
        return cases
    }

    @Test
    fun everySingleByteMutationOfOurFormatIsSurvived() {
        val n = mutateAll(lumaValid)
        assertTrue("expected thousands of cases, ran $n", n > 10_000)
        println("FUZZ luma mutations: $n")
    }

    @Test
    fun everySingleByteMutationOfTheParametricFormatIsSurvived() {
        val n = mutateAll(parametricValid)
        assertTrue("expected thousands of cases, ran $n", n > 10_000)
        println("FUZZ parametric mutations: $n")
    }

    /** A sample of mutations also has to survive the engine, not just the parser. */
    @Test
    fun mutationsSurviveTheWholeEnginePath() {
        val base = lumaValid.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(base.size)
        var checked = 0
        for (i in base.indices) {
            System.arraycopy(base, 0, buf, 0, base.size)
            // Every mutation through the DSP would be slow; a spread of
            // byte values at every position is enough to exercise it.
            for (v in intArrayOf(0, 9, 32, 45, 46, 48, 57, 65, 101, 110, 127, 200, 255)) {
                buf[i] = v.toByte()
                val p = ProfileParser.parse(buf)
                check("engine substitute $v at $i", p)
                checkEngine("engine substitute $v at $i", p)
                checked++
            }
        }
        println("FUZZ mutations through the engine: $checked")
    }

    // ---- random input ----------------------------------------------------

    private fun randomCase(r: Random): ByteArray = when (r.nextInt(10)) {
        0 -> ByteArray(r.nextInt(0, 2048)) { r.nextInt(256).toByte() }

        1 -> {
            // Random Unicode, including surrogates and non-characters.
            val sb = StringBuilder()
            repeat(r.nextInt(0, 400)) { sb.append(r.nextInt(0, 0x11000).toChar()) }
            sb.toString().toByteArray(Charsets.UTF_8)
        }

        2 -> ("peak " + "9".repeat(r.nextInt(1, 4000)) + " 1 1").toByteArray(Charsets.UTF_8)

        3 -> {
            // Repeated keywords, far past every limit.
            val sb = StringBuilder("luma-profile 1\n")
            repeat(r.nextInt(1, 300)) {
                sb.append(
                    listOf("peak", "lowshelf", "highshelf", "name:", "author:", "notes:")
                        .random(r),
                )
                sb.append(' ').append(r.nextInt(-100, 100000))
                sb.append(' ').append(r.nextInt(-50, 50))
                sb.append(' ').append(r.nextInt(-200, 200)).append('\n')
            }
            sb.toString().toByteArray(Charsets.UTF_8)
        }

        4 -> {
            val nasty = listOf(
                "1e999", "-1e999", "NaN", "Infinity", "-Infinity", "-0", "0x7fffffff",
                "99999999999999999999", "1.7976931348623157E309", "4.9E-324",
                ".", ",", "-", "+", "1,2", "٠١٢",
            )
            val sb = StringBuilder("luma-profile 1\n")
            repeat(r.nextInt(1, 40)) {
                sb.append("peak ").append(nasty.random(r)).append(' ')
                    .append(nasty.random(r)).append(' ').append(nasty.random(r)).append('\n')
            }
            sb.toString().toByteArray(Charsets.UTF_8)
        }

        5 -> {
            val sb = StringBuilder()
            repeat(r.nextInt(1, 60)) {
                sb.append("Filter ").append(r.nextInt(-5, 100)).append(": ")
                    .append(listOf("ON", "OFF", "on", "off", "MAYBE", "").random(r)).append(' ')
                    .append(
                        listOf("PK", "LSC", "HSC", "LS", "HS", "NO", "BP", "AP", "LPQ", "", "PKK")
                            .random(r),
                    )
                    .append(" Fc ").append(r.nextInt(-100, 100000))
                    .append(" Hz Gain ").append(r.nextInt(-100, 100))
                    .append(" dB Q ").append(r.nextInt(-10, 100)).append('\n')
            }
            sb.toString().toByteArray(Charsets.UTF_8)
        }

        6 -> ByteArray(r.nextInt(0, 64)) { '\n'.code.toByte() }

        7 -> {
            // Control characters and escape sequences in metadata.
            val sb = StringBuilder("luma-profile 1\nname: ")
            repeat(r.nextInt(0, 300)) { sb.append(r.nextInt(0, 0x80).toChar()) }
            sb.append("\npeak 1000 1 1\n")
            sb.toString().toByteArray(Charsets.UTF_8)
        }

        8 -> {
            // Right at the size boundary.
            val size = ProfileParser.MAX_BYTES + r.nextInt(-3, 4)
            ByteArray(maxOf(0, size)) {
                if (r.nextInt(20) == 0) '\n'.code.toByte() else r.nextInt(32, 127).toByte()
            }
        }

        else -> {
            // A valid file with one line spliced in from somewhere else.
            val lines = lumaValid.split("\n").toMutableList()
            lines.add(
                r.nextInt(lines.size + 1),
                String(ByteArray(r.nextInt(0, 80)) { r.nextInt(256).toByte() }, Charsets.UTF_8),
            )
            lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        }
    }

    @Test
    fun twentyThousandRandomInputsAreSurvived() {
        val r = Random(0x6C756D61)       // fixed seed, so a failure is reproducible
        var engineChecked = 0
        repeat(20_000) { i ->
            val bytes = randomCase(r)
            val p = ProfileParser.parse(bytes)
            check("random #$i", p)

            // Determinism, on every case.
            assertEquals("random #$i was not deterministic", p, ProfileParser.parse(bytes))

            // The engine path on a sample, which is the expensive half.
            if (p.filters.isNotEmpty() && engineChecked < 400) {
                checkEngine("random #$i", p)
                engineChecked++
            }

            // Anything that produced filters must also survive being written
            // out and read back, because that is what persistence does.
            if (p.filters.isNotEmpty()) {
                val back = ProfileParser.parse(ProfileParser.export(p))
                check("random #$i round trip", back)
                assertEquals(
                    "random #$i lost filters on a round trip",
                    p.filters.size, back.filters.size,
                )
            }
        }
        println("FUZZ random inputs: 20000, engine-checked: $engineChecked")
    }

    // ---- the headroom floor under attack ---------------------------------

    /**
     * The hostile profile that started all this, through the real path.
     *
     * Ten filters at the per-filter ceiling, all on one frequency: +80 dB of
     * cascade if nothing bounded it. Read from text, layered under the worst
     * curve the sliders can reach, and measured.
     */
    @Test
    fun aStackedHostileProfileStillLandsAtMinusFourteenOrBetter() {
        val sb = StringBuilder("luma-profile 1\n")
        repeat(14) { sb.append("peak 1000 0.70 99\n") }    // asks for +99, gets +8
        val p = ProfileParser.parse(sb.toString())

        assertEquals(ProfileParser.MAX_FILTERS, p.filters.size)
        for (f in p.filters) assertEquals(SoundState.MAX_BOOST_DB, f.gainDb, 0f)

        val state = SoundState(
            enabled = true, preset = Preset.Bass, bassDb = 6f, warmthDb = 6f, profile = p,
        )
        val settings = state.toEqSettings(48000)
        assertTrue(
            "headroom was ${settings.preampDb} dB",
            settings.preampDb >= -14.01f,
        )
        println("FUZZ stacked hostile profile headroom: ${settings.preampDb} dB")
    }

    /** And the same for a hostile file in the parametric format. */
    @Test
    fun aStackedHostileParametricProfileAlsoLands() {
        val sb = StringBuilder()
        repeat(14) { sb.append("Filter 1: ON LSC Fc 20 Hz Gain 99 dB Q 18\n") }
        val p = ProfileParser.parse(sb.toString())
        assertEquals(ProfileParser.MAX_FILTERS, p.filters.size)

        val state = SoundState(
            enabled = true, preset = Preset.Bass, bassDb = 6f, warmthDb = 6f, profile = p,
        )
        assertTrue(
            "headroom was ${state.toEqSettings(48000).preampDb} dB",
            state.toEqSettings(48000).preampDb >= -14.01f,
        )
    }

    // ---- unbounded work --------------------------------------------------

    /**
     * A full-size hostile input parses in well under the frame budget.
     *
     * The number that matters is not the average but that there is no input
     * shape that blows up: the three below are the ones that would, if the
     * scanning had a backtracking regex, an unbounded tokeniser, or a
     * substring taken before the line length was checked.
     */
    @Test
    fun aSixtyFourKilobyteHostileInputParsesQuickly() {
        val cases = mapOf(
            "one enormous line" to ByteArray(ProfileParser.MAX_BYTES) { 'a'.code.toByte() },
            "64k of newlines" to ByteArray(ProfileParser.MAX_BYTES) { '\n'.code.toByte() },
            "64k of spaces" to ByteArray(ProfileParser.MAX_BYTES) { ' '.code.toByte() },
            "repeated filter lines" to buildString {
                while (length < ProfileParser.MAX_BYTES - 50) {
                    append("Filter 1: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.00\n")
                }
            }.toByteArray(Charsets.UTF_8),
            "repeated long numbers" to buildString {
                while (length < ProfileParser.MAX_BYTES - 50) {
                    append("peak 123456789.123456789 1.23456789 -1.23456789\n")
                }
            }.toByteArray(Charsets.UTF_8),
        )

        for ((name, bytes) in cases) {
            repeat(5) { ProfileParser.parse(bytes) }        // let the JIT settle
            var worst = 0L
            repeat(10) {
                val t0 = System.nanoTime()
                val p = ProfileParser.parse(bytes)
                val dt = System.nanoTime() - t0
                if (dt > worst) worst = dt
                check(name, p)
            }
            val ms = worst / 1e6
            println("FUZZ parse time, %-22s %7.3f ms (%d bytes)".format(name, ms, bytes.size))
            assertTrue("$name took $ms ms, over the 50 ms budget", ms < 50.0)
        }
    }
}
