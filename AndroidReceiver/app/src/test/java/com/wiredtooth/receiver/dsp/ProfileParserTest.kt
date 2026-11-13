package com.wiredtooth.receiver.dsp

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile reader, rule by rule.
 *
 * Every profile text in this file was written here for the test. None of it
 * is third-party measurement data.
 */
class ProfileParserTest {

    // ---- our own format --------------------------------------------------

    private val luma = """
        luma-profile 1
        # a comment
        name: My earbuds by ear
        author: Akash
        notes: tuned on a quiet evening
        lowshelf 105 0.70 -3.2
        peak 2800 1.20 3.5
        highshelf 10000 0.70 2.0
    """.trimIndent()

    @Test
    fun readsOurOwnFormat() {
        val p = ProfileParser.parse(luma)
        assertFalse(p.rejected)
        assertEquals("My earbuds by ear", p.name)
        assertEquals("Akash", p.author)
        assertEquals("tuned on a quiet evening", p.notes)
        assertEquals(3, p.filters.size)

        assertEquals(BandType.LowShelf, p.filters[0].type)
        assertEquals(105f, p.filters[0].frequencyHz, 1e-4f)
        assertEquals(0.70f, p.filters[0].q, 1e-4f)
        assertEquals(-3.2f, p.filters[0].gainDb, 1e-4f)

        assertEquals(BandType.Peaking, p.filters[1].type)
        assertEquals(BandType.HighShelf, p.filters[2].type)
        assertEquals(10000f, p.filters[2].frequencyHz, 1e-4f)
        assertTrue("no warnings expected, got ${p.warnings}", p.warnings.isEmpty())
    }

    @Test
    fun acceptsBomAndCrlfAndBlankLines() {
        val crlf = "﻿" + luma.replace("\n", "\r\n") + "\r\n\r\n"
        val p = ProfileParser.parse(crlf)
        assertEquals(3, p.filters.size)
        assertEquals("My earbuds by ear", p.name)
    }

    @Test
    fun acceptsAByteOrderMarkInBytes() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            luma.toByteArray(Charsets.UTF_8)
        assertEquals(3, ProfileParser.parse(bytes).filters.size)
    }

    // ---- the parametric format -------------------------------------------

    private val parametric = """
        Preamp: -6.8 dB
        Filter 1: ON LSC Fc 105 Hz Gain -3.2 dB Q 0.70
        Filter 2: ON PK Fc 2800 Hz Gain 3.5 dB Q 1.20
        Filter 3: ON HSC Fc 10000 Hz Gain 2.0 dB Q 0.70
    """.trimIndent()

    @Test
    fun readsTheParametricFormat() {
        val p = ProfileParser.parse(parametric)
        assertEquals(3, p.filters.size)
        assertEquals(BandType.LowShelf, p.filters[0].type)
        assertEquals(105f, p.filters[0].frequencyHz, 1e-4f)
        assertEquals(-3.2f, p.filters[0].gainDb, 1e-4f)
        assertEquals(0.70f, p.filters[0].q, 1e-4f)
        assertEquals(BandType.Peaking, p.filters[1].type)
        assertEquals(BandType.HighShelf, p.filters[2].type)
    }

    /** The file's own preamp is read, discarded, and mentioned. */
    @Test
    fun thePreampLineIsAcceptedAndIgnored() {
        val p = ProfileParser.parse(parametric)
        assertTrue(
            "the ignored preamp should be disclosed, got ${p.warnings}",
            p.warnings.any { it.contains("preamp", ignoreCase = true) },
        )
        // And it must not have become a filter.
        assertEquals(3, p.filters.size)
    }

    /**
     * An LSC imports as a shelf; LS and NO are skipped and counted.
     *
     * This is the rule that stops an unsupported type being approximated as a
     * supported one.
     */
    @Test
    fun unsupportedTypesAreSkippedAndCounted() {
        val text = """
            Filter 1: ON LSC Fc 105 Hz Gain -3.2 dB Q 0.70
            Filter 2: ON LS Fc 200 Hz Gain 2.0 dB Q 0.70
            Filter 3: ON NO Fc 400 Hz Gain -5.0 dB Q 2.00
            Filter 4: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.00
        """.trimIndent()
        val p = ProfileParser.parse(text)

        assertEquals("only LSC and PK should survive", 2, p.filters.size)
        assertEquals(BandType.LowShelf, p.filters[0].type)
        assertEquals(BandType.Peaking, p.filters[1].type)
        assertEquals("LS and NO should both be counted", 2, p.skippedUnsupported)
        assertTrue(
            p.warnings.any { it.contains("2 filters skipped: unsupported type") },
        )
    }

    /** An OFF filter is skipped without a word, because it was meant to be off. */
    @Test
    fun offFiltersAreSkippedSilently() {
        val text = """
            Filter 1: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.00
            Filter 2: OFF PK Fc 2000 Hz Gain 9.0 dB Q 1.00
            Filter 3: OFF LSC Fc 105 Hz Gain -3.0 dB Q 0.70
        """.trimIndent()
        val p = ProfileParser.parse(text)

        assertEquals(1, p.filters.size)
        assertEquals(0, p.skippedUnsupported)
        assertTrue("OFF must not warn, got ${p.warnings}", p.warnings.isEmpty())
    }

    @Test
    fun anIncompleteFilterLineIsSkipped() {
        // A bandwidth instead of a Q: no honest filter can be built.
        val text = "Filter 1: ON PK Fc 1000 Hz Gain 1.0 dB BW Oct 0.5"
        val p = ProfileParser.parse(text)
        assertEquals(0, p.filters.size)
        assertTrue(p.warnings.any { it.contains("not understood") })
    }

    // ---- limits ----------------------------------------------------------

    @Test
    fun inputOverSixtyFourKilobytesIsRejectedWithAMessage() {
        val big = ByteArray(ProfileParser.MAX_BYTES + 1) { 'a'.code.toByte() }
        val p = ProfileParser.parse(big)
        assertTrue(p.rejected)
        assertTrue(p.filters.isEmpty())
        assertTrue(p.warnings.any { it.contains("64 KB") })

        val justUnder = ByteArray(ProfileParser.MAX_BYTES) { '\n'.code.toByte() }
        assertFalse(ProfileParser.parse(justUnder).rejected)
    }

    @Test
    fun theStreamReaderStopsOneBytePastTheLimit() {
        val big = ByteArray(ProfileParser.MAX_BYTES + 5000) { 'a'.code.toByte() }
        val got = ProfileParser.readLimited(ByteArrayInputStream(big))!!
        assertEquals(ProfileParser.MAX_BYTES + 1, got.size)
        assertTrue(ProfileParser.parse(got).rejected)
    }

    @Test
    fun aShortStreamIsReadWhole() {
        val got = ProfileParser.readLimited(
            ByteArrayInputStream(luma.toByteArray(Charsets.UTF_8)),
        )!!
        assertEquals(3, ProfileParser.parse(got).filters.size)
    }

    @Test
    fun overLongLinesAreSkippedAndCounted() {
        val long = "peak " + "9".repeat(ProfileParser.MAX_LINE_CHARS) + " 1 1"
        val text = "luma-profile 1\n$long\npeak 1000 1 1\n"
        val p = ProfileParser.parse(text)
        assertEquals("only the short line survives", 1, p.filters.size)
        assertTrue(p.warnings.any { it.contains("too long") })
    }

    @Test
    fun aLineExactlyAtTheLimitIsStillRead() {
        val pad = " ".repeat(ProfileParser.MAX_LINE_CHARS - "peak 1000 1 1".length)
        val line = "peak 1000 1 1$pad"
        assertEquals(ProfileParser.MAX_LINE_CHARS, line.length)
        val p = ProfileParser.parse("luma-profile 1\n$line\n")
        assertEquals(1, p.filters.size)
    }

    @Test
    fun moreThanTenFiltersKeepsTheFirstTenAndSaysSo() {
        val sb = StringBuilder("luma-profile 1\n")
        for (i in 1..17) sb.append("peak ").append(100 * i).append(" 1.0 1.0\n")
        val p = ProfileParser.parse(sb.toString())

        assertEquals(ProfileParser.MAX_FILTERS, p.filters.size)
        assertEquals(7, p.droppedOverLimit)
        assertTrue(p.warnings.any { it.contains("7 filters dropped") })
        // The first ten, in order.
        assertEquals(100f, p.filters[0].frequencyHz, 1e-4f)
        assertEquals(1000f, p.filters[9].frequencyHz, 1e-4f)
    }

    @Test
    fun valuesAreClampedToEngineLimitsAndCounted() {
        val text = """
            luma-profile 1
            peak 5 0.001 40
            peak 999999 999 -99
        """.trimIndent()
        val p = ProfileParser.parse(text)

        assertEquals(2, p.filters.size)
        assertEquals(ProfileParser.MIN_FC_HZ, p.filters[0].frequencyHz, 0f)
        assertEquals(ProfileParser.MIN_Q, p.filters[0].q, 0f)
        assertEquals(SoundState.MAX_BOOST_DB, p.filters[0].gainDb, 0f)
        assertEquals(ProfileParser.MAX_FC_HZ, p.filters[1].frequencyHz, 0f)
        assertEquals(ProfileParser.MAX_Q, p.filters[1].q, 0f)
        assertEquals(EqSettings.MIN_GAIN_DB, p.filters[1].gainDb, 0f)
        assertEquals("six values were out of range", 6, p.clampedValues)
        assertTrue(p.warnings.any { it.contains("6 values brought inside") })
    }

    @Test
    fun theNameIsStrippedOfControlCharactersAndCapped() {
        val nasty = "name: A\u0000B\u0007C\u001b[31m" + "x".repeat(200)
        val p = ProfileParser.parse("luma-profile 1\n$nasty\npeak 1000 1 1\n")
        assertTrue("no control characters", p.name.none { it.code < 0x20 })
        assertTrue("capped", p.name.length <= ProfileParser.MAX_NAME_CHARS)
        assertTrue(p.name.startsWith("ABC"))
    }

    // ---- numbers ---------------------------------------------------------

    @Test
    fun numbersAcceptPlainDecimalsAndADecimalComma() {
        assertEquals(105.0, ProfileParser.number("105")!!, 1e-12)
        assertEquals(-3.2, ProfileParser.number("-3.2")!!, 1e-12)
        assertEquals(3.2, ProfileParser.number("+3.2")!!, 1e-12)
        assertEquals(0.7, ProfileParser.number("0,70")!!, 1e-12)
        assertEquals(0.5, ProfileParser.number(".5")!!, 1e-12)
        assertEquals(7.0, ProfileParser.number("7.")!!, 1e-12)
        assertEquals("negative zero must normalise", 0.0, ProfileParser.number("-0")!!, 0.0)
        assertTrue(1.0 / ProfileParser.number("-0")!! > 0)   // +0.0, not -0.0
    }

    @Test
    fun numbersRejectEverythingElse() {
        for (bad in listOf(
            "", " ", "abc", "NaN", "nan", "Infinity", "-Infinity", "inf",
            "1e999", "1E5", "1e-5", "0x10", "0x1p3", "1d", "1f", "1.2.3",
            "--5", "+-5", "-", "+", ".", ",", "1,2,3", "12345678901",
            "1 2", "0b101", "٠١",
        )) {
            assertNull("\"$bad\" should be rejected", ProfileParser.number(bad))
        }
    }

    @Test
    fun aRejectedNumberSkipsItsLineRatherThanGuessing() {
        val text = """
            luma-profile 1
            peak 1e999 1 1
            peak NaN 1 1
            peak 1000 1 1
        """.trimIndent()
        val p = ProfileParser.parse(text)
        assertEquals(1, p.filters.size)
        assertEquals(1000f, p.filters[0].frequencyHz, 1e-4f)
        assertTrue(p.warnings.any { it.contains("2 lines not understood") })
    }

    // ---- export and round trip -------------------------------------------

    @Test
    fun exportRoundTripsWithinRounding() {
        val original = ProfileParser.parse(luma)
        val text = ProfileParser.export(original)
        val back = ProfileParser.parse(text)

        assertEquals(original.name, back.name)
        assertEquals(original.author, back.author)
        assertEquals(original.notes, back.notes)
        assertEquals(original.filters.size, back.filters.size)
        for (i in original.filters.indices) {
            val a = original.filters[i]
            val b = back.filters[i]
            assertEquals("type $i", a.type, b.type)
            assertEquals("freq $i", a.frequencyHz, b.frequencyHz, 0.005f)
            assertEquals("q $i", a.q, b.q, 0.0005f)
            assertEquals("gain $i", a.gainDb, b.gainDb, 0.0005f)
        }
        assertTrue("a round trip should be clean, got ${back.warnings}", back.warnings.isEmpty())
    }

    /** A second trip changes nothing at all: the written form is a fixed point. */
    @Test
    fun exportIsIdempotent() {
        val once = ProfileParser.export(ProfileParser.parse(luma))
        val twice = ProfileParser.export(ProfileParser.parse(once))
        assertEquals(once, twice)
    }

    @Test
    fun aParametricFileRoundTripsThroughOurOwnFormat() {
        val original = ProfileParser.parse(parametric)
        val back = ProfileParser.parse(ProfileParser.export(original))
        assertEquals(original.filters.size, back.filters.size)
        for (i in original.filters.indices) {
            assertEquals(original.filters[i].type, back.filters[i].type)
            assertEquals(original.filters[i].gainDb, back.filters[i].gainDb, 0.0005f)
        }
        // The preamp note belongs to the source file, not to our own form.
        assertTrue(back.warnings.isEmpty())
    }

    @Test
    fun exportWritesADecimalPointWhateverTheLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val text = ProfileParser.export(ProfileParser.parse(luma))
            // Only the filter lines carry numbers; the header comment is prose
            // and is allowed its commas.
            val numeric = text.lineSequence().filter {
                it.startsWith("peak") || it.startsWith("lowshelf") || it.startsWith("highshelf")
            }.toList()
            assertEquals(3, numeric.size)
            for (line in numeric) {
                assertTrue("a comma would break other tools' readers: $line", !line.contains(","))
            }
            assertTrue(text.contains("105.00"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // ---- empty and odd inputs --------------------------------------------

    @Test
    fun emptyAndJunkInputsComeBackEmptyNotBroken() {
        for (text in listOf("", "\n\n\n", "   ", "#only a comment", "hello world")) {
            val p = ProfileParser.parse(text)
            assertTrue("\"$text\" should yield nothing", p.filters.isEmpty())
            assertFalse(p.rejected)
        }
        assertTrue(ProfileParser.parse("").warnings.isNotEmpty())
    }

    @Test
    fun theHeaderAloneIsAValidButEmptyProfile() {
        val p = ProfileParser.parse("luma-profile 1\n")
        assertTrue(p.filters.isEmpty())
        assertFalse(p.rejected)
    }

    @Test
    fun parsingIsDeterministic() {
        for (text in listOf(luma, parametric, "junk\n\u0000�")) {
            assertEquals(ProfileParser.parse(text), ProfileParser.parse(text))
        }
    }
}
