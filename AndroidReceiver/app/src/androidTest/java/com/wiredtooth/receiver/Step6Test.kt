package com.wiredtooth.receiver

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
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
        repeat(5) {
            val o = device.wait(Until.findObject(By.descContains(fragment)), 2_500)
            if (o != null) {
                o.click()
                Thread.sleep(500)
                return
            }
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe(w / 2, (h * 0.78).toInt(), w / 2, (h * 0.45).toInt(), 20)
            Thread.sleep(500)
        }
        throw AssertionError("$label must be findable by description")
    }

    private fun findByDesc(fragment: String, timeoutMs: Long = 2_500) =
        device.wait(Until.findObject(By.descContains(fragment)), timeoutMs)

    /** Scrolls until a description appears, without tapping it. */
    private fun scrollToDesc(fragment: String): Boolean {
        repeat(5) {
            if (findByDesc(fragment, 1_500) != null) return true
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe(w / 2, (h * 0.78).toInt(), w / 2, (h * 0.45).toInt(), 20)
            Thread.sleep(500)
        }
        return findByDesc(fragment, 1_000) != null
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
