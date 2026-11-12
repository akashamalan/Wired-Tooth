package com.wiredtooth.receiver

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.wiredtooth.receiver.dsp.Preset
import com.wiredtooth.receiver.dsp.ProfileParser
import com.wiredtooth.receiver.dsp.SoundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The earbud-profile interface, on a device or emulator.
 *
 * Same arrangement as Step5Test: [TestSender] runs inside the guest on
 * 127.0.0.1, so nothing here depends on what owns UDP 5001 on the
 * development machine, and the method order is fixed because the receiver
 * binds UDP 5000 and two live receivers would collide on it.
 *
 * EVERY PROFILE IN THIS FILE IS SYNTHETIC. The text below was written for
 * these tests. No third-party measurement data is used, bundled, downloaded
 * or referenced.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Step6Test {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instr)

    private fun log(line: String) = Log.i(TAG, line)

    private fun waitFor(timeoutMs: Long, label: String, test: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (test()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for: $label")
    }

    private fun shotDir(): File =
        File(instr.targetContext.filesDir, "shots").apply { mkdirs() }

    private fun shoot(name: String) {
        val f = File(shotDir(), "$name.png")
        val ok = device.takeScreenshot(f)
        log("SHOT $name ok=$ok bytes=${f.length()}")
    }

    /** Scrolls the sheet looking for a description, then taps it. */
    private fun tapByDesc(fragment: String, label: String) {
        if (!scrollToDesc(fragment)) {
            throw AssertionError("$label must be findable by description")
        }
        val o = findByDesc(fragment, 2_000)
            ?: throw AssertionError("$label vanished between finding and tapping")
        o.click()
        Thread.sleep(500)
    }

    private fun swipeContentUp() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, (h * 0.78).toInt(), w / 2, (h * 0.45).toInt(), 20)
        Thread.sleep(450)
    }

    private fun swipeContentDown() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, (h * 0.45).toInt(), w / 2, (h * 0.78).toInt(), 20)
        Thread.sleep(450)
    }

    private fun findByDesc(fragment: String, timeoutMs: Long = 2_500) =
        device.wait(Until.findObject(By.descContains(fragment)), timeoutMs)

    /**
     * Scrolls until a description appears, without tapping it.
     *
     * Searches upward before downward, because the sheet is long and the
     * caller may be anywhere in it: Headroom sits above Advanced, and
     * Earbud correction sits below it, so a one-directional search finds
     * whichever half it happens to start in and fails on the other.
     */
    private fun scrollToDesc(fragment: String): Boolean {
        if (findByDesc(fragment, 1_200) != null) return true
        repeat(5) {
            swipeContentDown()
            if (findByDesc(fragment, 700) != null) return true
        }
        repeat(9) {
            swipeContentUp()
            if (findByDesc(fragment, 700) != null) return true
        }
        return findByDesc(fragment, 1_000) != null
    }

    /** Scrolls either way looking for on-screen TEXT rather than a description. */
    private fun scrollToText(fragment: String): Boolean {
        fun here() = device.wait(Until.findObject(By.textContains(fragment)), 600) != null
        if (here()) return true
        repeat(5) { swipeContentDown(); if (here()) return true }
        repeat(9) { swipeContentUp(); if (here()) return true }
        return here()
    }

    /** True if any text on screen contains this fragment. */
    private fun textPresent(fragment: String): Boolean =
        device.wait(Until.findObject(By.textContains(fragment)), 2_000) != null

    private fun startStream(sender: TestSender) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("wiredtooth://127.0.0.1")).apply {
            setPackage(instr.targetContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instr.startActivitySync(intent)
        waitFor(15_000, "the receiver to say hello") { sender.clientCount == 1 }
        waitFor(15_000, "audio to start flowing") { sender.audioPacketsSent.get() > 50 }
        Thread.sleep(1_000)
    }

    /**
     * Crashes reported since the app started.
     *
     * Checked explicitly rather than relying on the test failing, because a
     * Compose exception inside a sheet can leave the activity alive with the
     * sheet simply absent -- which looks to UiAutomator like a control that
     * could not be found, and reads in a report as a layout problem rather
     * than the crash it is.
     */
    private fun crashLines(): List<String> {
        val out = device.executeShellCommand("logcat -d -s AndroidRuntime:E")
        return out.lineSequence()
            .filter { it.contains("FATAL") || it.contains("Exception") }
            .filter { it.contains("wiredtooth", ignoreCase = true) || it.contains("FATAL") }
            .toList()
    }

    private fun assertNoCrash(stage: String) {
        val lines = crashLines()
        assertTrue(
            "$stage produced a crash:\n" + lines.joinToString("\n"),
            lines.isEmpty(),
        )
    }

    // ---- (1) the render check -------------------------------------------

    /**
     * Opening every new surface, one at a time, checking for a crash after
     * each.
     *
     * This is the test that would have caught the negative-padding crash in
     * step 5, and the reason it runs first: a UI that has never been
     * rendered is exactly where that class of fault hides.
     */
    @Test
    fun a_everyNewSurfaceRendersWithoutCrashing() {
        device.executeShellCommand("logcat -c")
        val sender = TestSender()
        sender.start()
        try {
            startStream(sender)
            shoot("10-now-playing")
            assertNoCrash("connecting")

            tapByDesc("Sound", "the Sound row")
            Thread.sleep(800)
            shoot("11-sound-sheet")
            assertNoCrash("opening the Sound sheet")

            tapByDesc("Advanced", "the Advanced disclosure")
            Thread.sleep(800)
            shoot("12-advanced")
            assertNoCrash("opening Advanced")

            assertTrue(
                "the Earbud correction section must be reachable",
                scrollToDesc("No earbud profile is loaded"),
            )
            shoot("13-earbud-correction")
            assertNoCrash("scrolling to Earbud correction")

            tapByDesc("Paste profile text", "the Paste text disclosure")
            Thread.sleep(800)
            shoot("14-paste-field")
            assertNoCrash("opening the paste field")

            // The field and its button must both be on screen, not clipped
            // off the bottom of the sheet.
            assertNotNull(
                "the paste text field must be present",
                findByDesc("Profile text"),
            )
            assertTrue(
                "the Read this text button must be reachable",
                scrollToDesc("Read this text"),
            )
            shoot("15-paste-field-with-button")
            assertNoCrash("the paste field")

            log("RENDER all five surfaces rendered with no crash")
        } finally {
            sender.stop()
            Thread.sleep(500)
        }
    }


    /** The headroom row's spoken description, which carries the number. */
    private fun headroomSpoken(): String {
        scrollToDesc("Headroom")
        val o = findByDesc("Headroom", 3_000)
            ?: throw AssertionError("the Headroom row must be findable")
        return o.contentDescription ?: ""
    }

    /** Writes a profile straight into the store, before the activity starts. */
    private fun seedProfile(source: String?) {
        val ctx = instr.targetContext
        val base = SoundState(enabled = true, bassDb = 0f, warmthDb = 0f)
        val state = if (source == null) base
        else base.withProfile(ProfileParser.parse(source))
        SoundStore.save(ctx, state)
    }

    private fun openSoundAdvanced() {
        tapByDesc("Sound", "the Sound row")
        Thread.sleep(800)
        tapByDesc("Advanced", "the Advanced disclosure")
        Thread.sleep(800)
    }

    private fun pasteAndRead(text: String) {
        // The disclosure keeps its state, so a second paste in the same
        // session finds it already open -- and tapping it then would close
        // it. Open it only when the field is not already there.
        if (!scrollToDesc("Profile text")) {
            tapByDesc("Paste profile text", "the Paste text disclosure")
            Thread.sleep(600)
        }
        val field = findByDesc("Profile text", 4_000)
            ?: throw AssertionError("the paste field must be findable")
        field.text = text
        Thread.sleep(600)
        tapByDesc("Read the pasted text", "the Read this text button")
        Thread.sleep(1_200)
    }

    // ---- (3) the UI, driven through the accessibility tree --------------

    /**
     * Paste a valid profile, see the preview, apply it, and watch the
     * headroom move.
     *
     * The headroom assertion is the one that matters: a name on screen only
     * proves the UI stored something, while a changed headroom proves the
     * filters the audio thread is running actually changed.
     */
    @Test
    fun b_pasteValidProfileShowsPreviewThenApplies() {
        device.executeShellCommand("logcat -c")
        seedProfile(null)
        val sender = TestSender()
        sender.start()
        try {
            startStream(sender)
            openSoundAdvanced()

            val before = headroomSpoken()
            log("HEADROOM before: $before")

            pasteAndRead(VALID_PROFILE)

            // The preview, not the sound.
            assertTrue("the preview must appear", textPresent("Use this profile?"))
            assertTrue("the profile name must show", textPresent("Bench Test Buds"))
            assertTrue("3 filters must be stated", textPresent("3 filter"))
            assertTrue(
                "the preview must say nothing has changed yet",
                textPresent("Nothing changes until you apply"),
            )
            shoot("20-preview")
            assertNoCrash("the preview")

            // Still unchanged behind the preview.
            tapByDesc("Apply this profile", "the Apply button")
            Thread.sleep(1_500)
            assertNoCrash("applying")

            val after = headroomSpoken()
            log("HEADROOM after: $after")
            assertNotEquals("the headroom must change when a profile is applied", before, after)

            assertTrue(
                "the profile must now be reported as loaded",
                scrollToDesc("Bench Test Buds"),
            )
            shoot("21-applied")
        } finally {
            sender.stop()
            Thread.sleep(500)
        }
    }

    /**
     * Junk gets plain words and leaves the profile alone.
     *
     * The second half is the important one: an import that fails must not
     * also destroy what was already working.
     */
    @Test
    fun c_pasteJunkShowsErrorAndKeepsTheProfile() {
        device.executeShellCommand("logcat -c")
        seedProfile(VALID_PROFILE)
        val sender = TestSender()
        sender.start()
        try {
            startStream(sender)
            openSoundAdvanced()
            val before = headroomSpoken()

            pasteAndRead(JUNK)

            assertTrue("the error must appear", textPresent("Cannot use that file"))
            assertTrue(
                "the error must be in plain words",
                textPresent("Nothing usable in this text"),
            )
            assertTrue(
                "the error must say the settings are untouched",
                textPresent("Your sound settings have not changed"),
            )
            shoot("22-error")
            assertNoCrash("the error sheet")

            tapByDesc("Close. Your sound settings", "the Close button")
            Thread.sleep(1_000)

            assertEquals(
                "a failed import must not change the sound",
                before, headroomSpoken(),
            )
            assertTrue(
                "the original profile must still be loaded",
                scrollToDesc("Bench Test Buds"),
            )
        } finally {
            sender.stop()
            Thread.sleep(500)
        }
    }

    /** Remove asks first, then clears. */
    @Test
    fun d_removeProfileConfirmsThenClears() {
        device.executeShellCommand("logcat -c")
        seedProfile(VALID_PROFILE)
        val sender = TestSender()
        sender.start()
        try {
            startStream(sender)
            openSoundAdvanced()
            assertTrue("profile should start loaded", scrollToDesc("Bench Test Buds"))

            tapByDesc("Remove the earbud profile", "the Remove button")
            Thread.sleep(1_000)
            assertTrue("the confirm must appear", textPresent("Remove the profile?"))
            shoot("23-remove-confirm")
            assertNoCrash("the remove confirm")

            tapByDesc("Remove. The curve goes back", "the confirm Remove button")
            Thread.sleep(1_200)

            assertTrue(
                "the profile must be gone",
                scrollToDesc("No earbud profile is loaded"),
            )
            assertNoCrash("removing")
            shoot("24-removed")
        } finally {
            sender.stop()
            Thread.sleep(500)
        }
    }

    /**
     * Compare still works with a profile loaded, over the whole chain.
     *
     * The profile is the base of a twenty-section cascade, so this is the
     * path where a blend between the dry copy and the filtered buffer has
     * the most to go wrong. Checked from the sender's side as well, because
     * a receiver that has stopped consuming would look fine on screen.
     */
    @Test
    fun e_compareWorksWithAProfileLoaded() {
        device.executeShellCommand("logcat -c")
        seedProfile(VALID_PROFILE)
        val sender = TestSender()
        sender.start()
        try {
            startStream(sender)
            tapByDesc("Sound", "the Sound row")
            Thread.sleep(1_000)

            val packetsBefore = sender.audioPacketsSent.get()
            repeat(3) {
                tapByDesc("Original", "the Original chip")
                Thread.sleep(700)
                tapByDesc("Equalized", "the Equalized chip")
                Thread.sleep(700)
            }
            shoot("25-compare-with-profile")
            assertNoCrash("comparing with a profile")

            val packetsAfter = sender.audioPacketsSent.get()
            assertTrue(
                "the stream must keep flowing through Compare: " +
                    "$packetsBefore then $packetsAfter",
                packetsAfter > packetsBefore + 100,
            )
            log("COMPARE packets $packetsBefore -> $packetsAfter")
        } finally {
            sender.stop()
            Thread.sleep(500)
        }
    }

    // ---- (4) screenshots in both palettes -------------------------------

    /**
     * The four states that matter, captured in whichever palette the system
     * is currently in. Run twice, once per palette.
     *
     * Both palettes because the last two interface bugs in this project were
     * each invisible in one of them: a slider track drawn in the sheet's own
     * background colour vanished in dark and was pure white on white in
     * light. A screenshot of one palette is half a check.
     *
     * The suffix comes from an instrumentation argument rather than being
     * read off the configuration, so the file name records what the runner
     * intended and a mismatch shows up as two identical-looking images
     * rather than as a silently mislabelled one.
     */
    @Test
    fun f_screenshotsInThisPalette() {
        val suffix = InstrumentationRegistry.getArguments().getString("suffix") ?: "unknown"
        device.executeShellCommand("logcat -c")

        // A profile that trips all three hints at once: every filter asks
        // for far more than the per-filter ceiling, the profile as a whole
        // peaks well past its own ceiling, and the macros are at maximum so
        // they are the ones squeezed out of what is left.
        val nl = 10.toChar().toString()
        val hot = StringBuilder("luma-profile 1" + nl + "name: All Three Hints" + nl)
        for (i in 0 until 10) {
            val hz = 20.0 * Math.pow(500.0, i / 9.0)
            hot.append("peak ").append(String.format("%.2f", hz)).append(" 0.70 99").append(nl)
        }
        val ctx = instr.targetContext
        SoundStore.save(
            ctx,
            SoundState(
                enabled = true, preset = Preset.Bass, bassDb = 6f, warmthDb = 6f,
            ).withProfile(ProfileParser.parse(hot.toString()))
                .copy(preset = Preset.Bass, bassDb = 6f, warmthDb = 6f),
        )

        val sender = TestSender()
        sender.start()
        try {
            startStream(sender)
            tapByDesc("Sound", "the Sound row")
            Thread.sleep(1_200)

            // All three hints live on the main surface, above Advanced.
            assertTrue("hint 1 missing", scrollToText("Boost limited"))
            shoot("30-hints-$suffix")
            val hintsOk = textPresent("Boost limited to keep the sound clean") &&
                textPresent("Profile strength reduced to keep the sound clean") &&
                textPresent("Bass and Warmth limited by your profile")
            log("HINTS all three visible in $suffix: $hintsOk")
            assertTrue("all three hints must be on screen at once", hintsOk)
            assertNoCrash("the hint stack")

            // Advanced numbers.
            tapByDesc("Advanced", "the Advanced disclosure")
            Thread.sleep(800)
            assertTrue("strength row missing", scrollToDesc("Profile strength is"))
            shoot("31-advanced-numbers-$suffix")
            assertNoCrash("the Advanced numbers")

            // The preview.
            pasteAndRead(VALID_PROFILE)
            assertTrue("preview missing", textPresent("Use this profile?"))
            shoot("32-preview-$suffix")
            assertNoCrash("the preview")
            tapByDesc("Cancel. Leaves your sound settings", "the Cancel button")
            Thread.sleep(900)

            // The error.
            pasteAndRead(JUNK)
            assertTrue("error missing", textPresent("Cannot use that file"))
            shoot("33-error-$suffix")
            assertNoCrash("the error sheet")
            tapByDesc("Close. Your sound settings", "the Close button")
            Thread.sleep(700)
        } finally {
            sender.stop()
            Thread.sleep(500)
        }
    }

    companion object {
        private const val TAG = "LumaStep6"

        /** A synthetic profile, written here for this test. */
        const val VALID_PROFILE = "luma-profile 1\n" +
            "name: Bench Test Buds\n" +
            "author: Step 6\n" +
            "lowshelf 105 0.70 -3.2\n" +
            "peak 2800 1.20 3.5\n" +
            "highshelf 10000 0.70 2.0\n"

        /** A synthetic profile that asks for far more than the engine allows. */
        const val HOT_PROFILE = "luma-profile 1\n" +
            "name: Too Much\n" +
            "peak 60 0.70 99\n" +
            "peak 90 0.70 99\n" +
            "peak 130 0.70 99\n" +
            "peak 200 0.70 99\n" +
            "nonsense 1 2 3\n" +
            "peak 300 0.70 99\n"

        const val JUNK = "this is not a profile at all, just a sentence.\n"
    }
}
