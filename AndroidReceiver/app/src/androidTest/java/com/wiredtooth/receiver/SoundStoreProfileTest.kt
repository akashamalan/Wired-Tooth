package com.wiredtooth.receiver

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wiredtooth.receiver.dsp.BandType
import com.wiredtooth.receiver.dsp.Preset
import com.wiredtooth.receiver.dsp.ProfileParser
import com.wiredtooth.receiver.dsp.SoundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The profile survives being written to preferences and read back.
 *
 * This is the part of step (b) the JVM tests could not reach: SoundStore
 * needs a Context, so the export-then-reparse half was covered on the desktop
 * but the SharedPreferences write and read were not covered anywhere.
 *
 * HOW THE PROCESS DEATH IS REAL. An instrumented test cannot outlive its own
 * runner, so the crossing is done with two tests and two runs: [z1_write]
 * stores a profile and asserts nothing, then the runner exits and the process
 * dies, then a second "am instrument" invocation runs [z2_readBack] in a
 * brand new process and checks what came off disk. Only that pair proves the
 * disk round trip; the other tests here share one process, where
 * SharedPreferences serves the cached string -- which still exercises the
 * export-and-reparse path, just not the file.
 *
 * Every profile here is synthetic.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SoundStoreProfileTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(line: String) = Log.i(TAG, line)

    /**
     * Re-reads the stored state.
     *
     * Within one process SharedPreferences hands back its cached string, so
     * this exercises the export-then-reparse path rather than the file. The
     * file itself is covered by [z1_write] and [z2_readBack], which run in
     * two different processes.
     */
    private fun reload(): SoundState = SoundStore.load(context)

    private fun clear() {
        context.getSharedPreferences("sound", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun aProfileSurvivesSaveAndReload() {
        clear()
        val source = "luma-profile 1\n" +
            "name: Bench Test Buds\n" +
            "author: Step 6\n" +
            "notes: synthetic, written for this test\n" +
            "lowshelf 105 0.70 -3.2\n" +
            "peak 2800 1.20 3.5\n" +
            "peak 5500 2.00 -4.0\n" +
            "highshelf 10000 0.70 2.0\n"
        val parsed = ProfileParser.parse(source)
        assertEquals(4, parsed.filters.size)

        val saved = SoundState(
            enabled = true,
            preset = Preset.Flat,
            bassDb = 4.5f,
            warmthDb = -2f,
            profile = parsed,
        )
        SoundStore.save(context, saved)

        val back = reload()
        val got = back.profile
        assertNotNull("the profile must come back", got)
        got!!

        assertEquals("name", parsed.name, got.name)
        assertEquals("author", parsed.author, got.author)
        assertEquals("notes", parsed.notes, got.notes)
        assertEquals("filter count", parsed.filters.size, got.filters.size)
        for (i in parsed.filters.indices) {
            val a = parsed.filters[i]
            val b = got.filters[i]
            assertEquals("type $i", a.type, b.type)
            assertEquals("freq $i", a.frequencyHz, b.frequencyHz, 0.005f)
            assertEquals("q $i", a.q, b.q, 0.0005f)
            assertEquals("gain $i", a.gainDb, b.gainDb, 0.0005f)
        }

        // The rest of the state has to survive alongside it.
        assertEquals(true, back.enabled)
        assertEquals(4.5f, back.bassDb, 0f)
        assertEquals(-2f, back.warmthDb, 0f)

        // And the curve it produces must be identical, which is the thing
        // that actually matters: the same filters in the same order at the
        // same sample rate.
        assertEquals(
            saved.toEqSettings(48000).bands,
            back.toEqSettings(48000).bands,
        )
        assertEquals(
            saved.toEqSettings(48000).preampDb,
            back.toEqSettings(48000).preampDb,
            0f,
        )
        log("RELOAD ${got.filters.size} filters, headroom ${back.toEqSettings(48000).preampDb} dB")
    }

    @Test
    fun noProfileReloadsAsNoProfile() {
        clear()
        SoundStore.save(context, SoundState(enabled = true, preset = Preset.Bass))
        val back = reload()
        assertNull(back.profile)
        assertEquals(Preset.Bass, back.preset)
    }

    @Test
    fun aProfileIsClearedWhenItIsRemoved() {
        clear()
        val p = ProfileParser.parse("luma-profile 1\npeak 1000 1.0 3.0\n")
        SoundStore.save(context, SoundState(enabled = true, profile = p))
        assertNotNull(reload().profile)

        SoundStore.save(context, reload().withoutProfile())
        assertNull("removing must persist too", reload().profile)
    }

    /**
     * A preferences file corrupted by anything at all degrades to "no
     * profile" rather than to a crash on launch.
     *
     * Preferences survive app upgrades, so a value written by an older build
     * -- or by nothing at all -- can arrive in a shape this build does not
     * expect. Routing the stored string back through the parser is what makes
     * that safe, and this is the test that says so.
     */
    @Test
    fun corruptStoredTextDegradesToNoProfile() {
        clear()
        for (junk in listOf(
            "", "   ", "not a profile", "luma-profile 1\n", "\u0000\u0001\u0002",
            "luma-profile 1\npeak NaN Infinity 1e999\n",
            "Filter 1: ON NO Fc 1000 Hz Gain 3 dB Q 1\n",
        )) {
            context.getSharedPreferences("sound", Context.MODE_PRIVATE)
                .edit().putString("profile", junk).commit()
            val back = reload()
            assertNull("\"$junk\" should leave no profile", back.profile)
        }
    }

    /**
     * A stored profile that asks for too much still lands inside the budget
     * after a reload.
     *
     * The clamps are applied by the parser, so a reload re-applies them --
     * but only because the stored form goes back through the parser. If it
     * were ever stored as a band list instead, this is the test that would
     * fail.
     */
    @Test
    fun aStoredHostileProfileStillRespectsTheHeadroomFloor() {
        clear()
        val sb = StringBuilder("luma-profile 1\nname: Hostile\n")
        repeat(14) { sb.append("peak 1000 0.70 99\n") }
        val p = ProfileParser.parse(sb.toString())

        SoundStore.save(
            context,
            SoundState(
                enabled = true, preset = Preset.Bass,
                bassDb = 6f, warmthDb = 6f, profile = p,
            ),
        )
        val back = reload()
        assertEquals(ProfileParser.MAX_FILTERS, back.profile!!.filters.size)
        val headroom = back.toEqSettings(48000).preampDb
        assertTrue("headroom was $headroom dB", headroom >= -14.01f)
        log("RELOAD hostile headroom $headroom dB")
    }

    @Test
    fun shelfTypesSurviveTheRoundTrip() {
        clear()
        val p = ProfileParser.parse(
            "luma-profile 1\nlowshelf 105 0.70 -3.2\nhighshelf 10000 0.70 2.0\n",
        )
        SoundStore.save(context, SoundState(enabled = true, profile = p))
        val got = reload().profile!!
        assertEquals(BandType.LowShelf, got.filters[0].type)
        assertEquals(BandType.HighShelf, got.filters[1].type)
    }

    // ---- the real process crossing --------------------------------------

    /**
     * Writes the profile and stops. Run this, let the process die, then run
     * [z2_readBack] in a second invocation.
     *
     * commit, not apply: apply returns before the file is written, and the
     * process is about to end.
     */
    @Test
    fun z1_write() {
        clear()
        val parsed = ProfileParser.parse(CROSSING_SOURCE)
        assertEquals(4, parsed.filters.size)
        SoundStore.save(
            context,
            SoundState(
                enabled = true, preset = Preset.Flat,
                bassDb = 4.5f, warmthDb = -2f, profile = parsed,
            ),
        )
        // SoundStore uses apply(); force it out before the process exits.
        context.getSharedPreferences("sound", Context.MODE_PRIVATE)
            .edit().putLong("flush", System.currentTimeMillis()).commit()
        log("CROSSING wrote ${parsed.filters.size} filters, pid ${android.os.Process.myPid()}")
    }

    /**
     * Reads it back in a process that never saw the write.
     *
     * Deliberately does not clear the store first: the whole point is what
     * was left on disk by a process that no longer exists.
     */
    @Test
    fun z2_readBack() {
        log("CROSSING reading in pid ${android.os.Process.myPid()}")
        val expected = ProfileParser.parse(CROSSING_SOURCE)
        val back = SoundStore.load(context)
        val got = back.profile
        assertNotNull("nothing survived the process death", got)
        got!!

        assertEquals("Bench Test Buds", got.name)
        assertEquals("Step 6", got.author)
        assertEquals(expected.filters.size, got.filters.size)
        for (i in expected.filters.indices) {
            assertEquals("type $i", expected.filters[i].type, got.filters[i].type)
            assertEquals(
                "freq $i", expected.filters[i].frequencyHz, got.filters[i].frequencyHz, 0.005f,
            )
            assertEquals("q $i", expected.filters[i].q, got.filters[i].q, 0.0005f)
            assertEquals("gain $i", expected.filters[i].gainDb, got.filters[i].gainDb, 0.0005f)
        }
        assertEquals(true, back.enabled)
        assertEquals(4.5f, back.bassDb, 0f)
        assertEquals(-2f, back.warmthDb, 0f)

        val reference = SoundState(
            enabled = true, preset = Preset.Flat,
            bassDb = 4.5f, warmthDb = -2f, profile = expected,
        )
        assertEquals(
            "the curve must be identical after a process death",
            reference.toEqSettings(48000).bands,
            back.toEqSettings(48000).bands,
        )
        log("CROSSING identical, headroom ${back.toEqSettings(48000).preampDb} dB")
    }

    companion object {
        private const val TAG = "LumaStep6"

        /** Synthetic, written for this test. */
        private const val CROSSING_SOURCE = "luma-profile 1\n" +
            "name: Bench Test Buds\n" +
            "author: Step 6\n" +
            "lowshelf 105 0.70 -3.2\n" +
            "peak 2800 1.20 3.5\n" +
            "peak 5500 2.00 -4.0\n" +
            "highshelf 10000 0.70 2.0\n"
    }
}
