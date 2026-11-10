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
import com.wiredtooth.receiver.dsp.SoundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The acceptance run, on a device or emulator.
 *
 * Every test here uses [TestSender] on 127.0.0.1 inside the guest, so none of
 * it depends on what owns UDP 5001 on the development machine.
 *
 * Method order is fixed and the names are prefixed, because these tests are
 * not independent in one specific way: the receiver binds UDP 5000, and two
 * receivers alive at once would both be bound to it. Each test stops what it
 * started, and the ordering makes a leak show up as a failure in the next
 * test rather than as a confusing flake somewhere later.
 *
 * Results are written to logcat under [TAG] as well as asserted, because the
 * numbers are the point of the exercise and an assertion that merely passes
 * tells you nothing about the margin it passed by.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Step5Test {

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

    // ---- (a) Back must not stop the stream -----------------------------

    /**
     * Back backgrounds the task instead of finishing the activity.
     *
     * The failure this guards against is silent: without the BackHandler,
     * Back finishes the activity, composition leaves, the DisposableEffect
     * fires and the receiver stops -- so the music ends with no confirmation,
     * from the gesture people use to glance at something else.
     *
     * Checked from the SENDER's side on purpose. The receiver claiming to be
     * fine proves nothing if the sender has already dropped it, and the
     * sender's client table plus its PING traffic is what the real product
     * would show in its status window.
     */
    @Test
    fun a_backKeepsTheStreamAlive() {
        val sender = TestSender()
        sender.start()
        var activity: android.app.Activity? = null
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("wiredtooth://127.0.0.1")).apply {
                setPackage(instr.targetContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity = instr.startActivitySync(intent)

            waitFor(15_000, "the receiver to say hello") { sender.clientCount == 1 }
            waitFor(15_000, "audio to start flowing") { sender.audioPacketsSent.get() > 50 }

            val clientsBefore = sender.clientCount
            val pingsBefore = sender.pings.get()
            val packetsBefore = sender.audioPacketsSent.get()
            val byesBefore = sender.byes.get()

            device.pressBack()
            Thread.sleep(5_000)

            val clientsAfter = sender.clientCount
            val pingsAfter = sender.pings.get()
            val packetsAfter = sender.audioPacketsSent.get()
            val byesAfter = sender.byes.get()

            log("BACK clientsBefore=$clientsBefore clientsAfter=$clientsAfter")
            log("BACK byesBefore=$byesBefore byesAfter=$byesAfter")
            log("BACK pingsBefore=$pingsBefore pingsAfter=$pingsAfter")
            log("BACK audioPacketsBefore=$packetsBefore audioPacketsAfter=$packetsAfter")

            assertEquals("the sender should have had exactly one client", 1, clientsBefore)
            assertEquals("the client must survive Back", 1, clientsAfter)
            assertEquals("Back must not send BYE", byesBefore, byesAfter)
            assertTrue(
                "the receiver's control loop must still be running after Back " +
                    "(pings went $pingsBefore -> $pingsAfter)",
                pingsAfter > pingsBefore,
            )
            assertTrue("audio must still be flowing", packetsAfter > packetsBefore)
        } finally {
            // moveTaskToBack leaves the activity alive and holding UDP 5000,
            // so it has to be finished explicitly or the next test cannot bind.
            activity?.let { a -> instr.runOnMainSync { a.finish() } }
            Thread.sleep(1_500)
            sender.stop()
            Thread.sleep(500)
        }
    }

    // ---- (b) silence keepalives must not read as underruns -------------

    /**
     * Thirty seconds of silence-flagged keepalives, and no underruns.
     *
     * WASAPI loopback stops delivering buffers when nothing is playing, so a
     * quiet passage looks exactly like a dead sender from the receiver's side.
     * Counting those quanta as underruns is what put a rising Dropouts number
     * in front of the user while nothing was wrong.
     */
    @Test
    fun b_thirtySecondsOfSilenceCausesNoUnderruns() {
        val sender = TestSender()
        sender.start()
        val receiver = AudioReceiver("127.0.0.1")
        try {
            receiver.start()
            waitFor(15_000, "connection") { receiver.status().packetsReceived > 200 }
            waitFor(15_000, "playback to start") { receiver.status().bufferMs > 0 }

            // Let it settle before the measurement window opens.
            Thread.sleep(3_000)
            val before = receiver.status().underruns
            val keepalivesBefore = sender.keepalivesSent.get()

            sender.silence = true
            Thread.sleep(30_000)
            val after = receiver.status().underruns
            val keepalives = sender.keepalivesSent.get() - keepalivesBefore
            sender.silence = false

            log("SILENCE underrunsBefore=$before underrunsAfter=$after keepalives=$keepalives")
            log("SILENCE status=${receiver.status()}")

            assertTrue(
                "the sender must actually have sent keepalives, or this proves " +
                    "nothing (sent $keepalives)",
                keepalives > 250,
            )
            assertEquals(
                "30s of silence keepalives must not count as underruns",
                before, after,
            )
        } finally {
            receiver.stop()
            Thread.sleep(1_000)
            sender.stop()
            Thread.sleep(500)
        }
    }

    // ---- (c) and (e) EQ on versus off ----------------------------------

    /**
     * The same stream, the same receiver, EQ off then on.
     *
     * One continuous stream rather than two runs, because two runs differ in
     * everything that matters -- JIT state, buffer fill, whatever else the
     * device was doing -- and the question is specifically what the equalizer
     * costs, not what two separate fifteen-second windows cost.
     */
    @Test
    fun c_eqOnVersusOffUnderrunsAndTiming() {
        val sender = TestSender()
        sender.start()
        val receiver = AudioReceiver("127.0.0.1")
        try {
            receiver.applySound(SoundState(enabled = false))
            receiver.start()
            waitFor(15_000, "connection") { receiver.status().packetsReceived > 200 }
            waitFor(15_000, "playback to start") { receiver.status().bufferMs > 0 }

            // Warm up, then zero the timers: the first quanta through either
            // path include the JIT compiling code that has never run, and a
            // worst case that is really a first-run case says nothing about
            // steady state.
            Thread.sleep(5_000)
            receiver.resetTimings()
            val underrunsAtStart = receiver.status().underruns

            Thread.sleep(15_000)
            val offStatus = receiver.status()
            val underrunsAfterOff = offStatus.underruns

            receiver.applySound(
                SoundState(enabled = true, preset = Preset.Bass).withBass(6f),
            )
            Thread.sleep(15_000)
            val onStatus = receiver.status()

            val offUnderruns = underrunsAfterOff - underrunsAtStart
            val onUnderruns = onStatus.underruns - underrunsAfterOff

            log("EQOFF underruns=$offUnderruns quanta=${offStatus.eqOffQuanta} " +
                "avgNanos=${offStatus.eqOffAvgNanos} worstNanos=${offStatus.eqOffWorstNanos}")
            log("EQON  underruns=$onUnderruns quanta=${onStatus.eqOnQuanta} " +
                "avgNanos=${onStatus.eqOnAvgNanos} worstNanos=${onStatus.eqOnWorstNanos}")
            log("EQON  clamped=${onStatus.clampedSamples} resets=${onStatus.eqResets} " +
                "headroomDb=${onStatus.headroomDb} rate=${onStatus.sampleRate}")
            log("EQON  fullStatus=$onStatus")

            assertTrue("the EQ-off window must have run", offStatus.eqOffQuanta > 500)
            assertTrue("the EQ-on window must have run", onStatus.eqOnQuanta > 500)

            // The real acceptance criterion: the filters must not make the
            // playback loop miss its deadline. A quantum is 10ms; anything
            // close to that is a dropout waiting to happen.
            assertTrue(
                "worst EQ quantum was ${onStatus.eqOnWorstNanos}ns, which is over " +
                    "half of a 10ms quantum",
                onStatus.eqOnWorstNanos < 5_000_000L,
            )
            assertEquals("the filters must stay stable", 0L, onStatus.eqResets)
            assertTrue(
                "EQ on added underruns: off=$offUnderruns on=$onUnderruns",
                onUnderruns <= offUnderruns + 1,
            )
        } finally {
            receiver.stop()
            Thread.sleep(1_000)
            sender.stop()
            Thread.sleep(500)
        }
    }

    // ---- (d) screenshots -----------------------------------------------

    /**
     * The Sound sheet, photographed in four states.
     *
     * Driven through the accessibility tree rather than by coordinates, which
     * means the test also proves every control it touches is actually
     * reachable by a screen reader. A tap that cannot find its target by
     * description is a control TalkBack could not have found either.
     */
    @Test
    fun d_soundSheetScreenshots() {
        val sender = TestSender()
        sender.start()
        var activity: android.app.Activity? = null
        val dir = File(instr.targetContext.filesDir, "shots").apply { mkdirs() }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("wiredtooth://127.0.0.1")).apply {
                setPackage(instr.targetContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity = instr.startActivitySync(intent)
            waitFor(15_000, "connection") { sender.clientCount == 1 }
            waitFor(15_000, "audio") { sender.audioPacketsSent.get() > 50 }
            Thread.sleep(2_000)

            shoot(dir, "00-now-playing")

            // Open the sheet.
            val row = device.wait(Until.findObject(By.descContains("Opens presets")), 10_000)
            assertTrue("the Sound row must be findable by description", row != null)
            row!!.click()
            Thread.sleep(1_200)

            // Set Flat explicitly. Settings persist across launches by design,
            // so whatever the previous run left behind is still in effect --
            // which is how the first attempt at this produced a "Flat"
            // screenshot showing Bass, and then a Bass screenshot identical to
            // it because the chip was already selected.
            tapByDesc("Flat preset", "the Flat chip")
            Thread.sleep(1_500)
            shoot(dir, "01-sound-flat")

            tapByDesc("Bass preset", "the Bass chip")
            Thread.sleep(1_500)
            shoot(dir, "02-sound-bass")

            // The Compare control sits under the headroom note, so it needs a
            // scroll to come into frame.
            run {
                val w0 = device.displayWidth
                val h0 = device.displayHeight
                device.swipe(w0 / 2, (h0 * 0.80).toInt(), w0 / 2, (h0 * 0.55).toInt(), 20)
                Thread.sleep(1_000)
                shoot(dir, "06-sound-compare")
            }

            tapByDesc("Advanced", "the Advanced disclosure")
            Thread.sleep(1_200)
            shoot(dir, "03-sound-advanced-collapsed-view")

            // The sheet is height-capped and scrolls, so expanding Advanced
            // puts the ten bands below the fold. Scroll them into view, or the
            // screenshot shows a disclosure that appears to have done nothing.
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe(w / 2, (h * 0.80).toInt(), w / 2, (h * 0.35).toInt(), 20)
            Thread.sleep(1_200)
            shoot(dir, "03-sound-advanced")

            device.swipe(w / 2, (h * 0.80).toInt(), w / 2, (h * 0.30).toInt(), 20)
            Thread.sleep(1_200)
            shoot(dir, "05-sound-diagnostics")

            log("SHOTS dir=${dir.absolutePath} files=${dir.list()?.joinToString(",")}")
        } finally {
            activity?.let { a -> instr.runOnMainSync { a.finish() } }
            Thread.sleep(1_500)
            sender.stop()
            Thread.sleep(500)
        }
    }

    /**
     * The same sheet with "remove animations" on.
     *
     * Separate test because it has to change a global setting and put it back,
     * and a failure midway through must not leave the device altered for
     * everything that runs after it.
     *
     * A still image cannot show the absence of motion. What it CAN show is
     * that the app still lays out and renders correctly with the setting on,
     * which is the part that breaks in practice when animation is special-cased
     * away.
     */
    @Test
    fun e_reduceMotionScreenshot() {
        val sender = TestSender()
        val dir = File(instr.targetContext.filesDir, "shots").apply { mkdirs() }
        var activity: android.app.Activity? = null
        device.executeShellCommand("settings put global animator_duration_scale 0")
        try {
            sender.start()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("wiredtooth://127.0.0.1")).apply {
                setPackage(instr.targetContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity = instr.startActivitySync(intent)
            waitFor(15_000, "connection") { sender.clientCount == 1 }
            Thread.sleep(2_500)

            val row = device.wait(Until.findObject(By.descContains("Opens presets")), 10_000)
            assertTrue("the Sound row must be findable with reduce motion on", row != null)
            row!!.click()
            Thread.sleep(1_200)
            tapByDesc("Bass preset", "the Bass chip")
            Thread.sleep(1_200)
            shoot(dir, "04-sound-reduce-motion")
            log("REDUCEMOTION captured")
        } finally {
            device.executeShellCommand("settings put global animator_duration_scale 1")
            activity?.let { a -> instr.runOnMainSync { a.finish() } }
            Thread.sleep(1_500)
            sender.stop()
            Thread.sleep(500)
        }
    }

    // ---- the first-quantum cost of switching EQ on --------------------

    /**
     * Streams with the equalizer OFF, then switches it ON and records how long
     * the very first filtered quantum took.
     *
     * The two halves of this measurement CANNOT share a process. Measuring
     * "cold" twice in one process is impossible by definition -- the first
     * measurement is what warms the runtime for the second. So this is two
     * test methods, each meaningful only as the first EQ work its process has
     * ever done, and each is run in its own `am instrument` invocation.
     *
     * Both skip rather than fail if something already warmed the path, which
     * is what happens when the whole class runs in one process after the
     * screenshot tests have launched the real activity.
     */
    private fun measureFirstEqQuantum(label: String, warmFirst: Boolean): Long {
        val sender = TestSender()
        sender.start()
        val receiver = AudioReceiver("127.0.0.1")
        try {
            receiver.applySound(SoundState(enabled = false))
            receiver.start()
            waitFor(15_000, "connection") { receiver.status().packetsReceived > 200 }
            waitFor(15_000, "playback to start") { receiver.status().bufferMs > 0 }

            // Five seconds of BYPASS traffic. This compiles the playback loop,
            // the mixer and the int overload of clampInto -- everything except
            // the equalizer -- which is exactly the state a real user is in
            // when they open the sheet and tap the switch.
            Thread.sleep(5_000)
            receiver.resetTimings()

            if (warmFirst) {
                EqWarmup.warm()
                log("$label warmupTookNanos=${EqWarmup.tookNanos}")
            }

            receiver.applySound(
                SoundState(enabled = true, preset = Preset.Bass).withBass(6f),
            )
            waitFor(10_000, "the first EQ quantum") { receiver.status().eqOnQuanta > 0 }
            Thread.sleep(2_000)

            val st = receiver.status()
            log("$label firstNanos=${st.eqOnFirstNanos} worstNanos=${st.eqOnWorstNanos} " +
                "avgNanos=${st.eqOnAvgNanos} quanta=${st.eqOnQuanta} warmed=${EqWarmup.warmed}")
            return st.eqOnFirstNanos
        } finally {
            receiver.stop()
            Thread.sleep(1_000)
            sender.stop()
            Thread.sleep(500)
        }
    }

    /** Baseline: no warm-up anywhere in this process. */
    @Test
    fun f_firstEqQuantumWithoutWarmup() {
        assumeFalse(
            "something already warmed the EQ path in this process; " +
                "run this test on its own",
            EqWarmup.warmed,
        )
        val first = measureFirstEqQuantum("COLD", warmFirst = false)
        assertTrue("no first quantum was recorded", first > 0)
    }

    /** The same measurement with [EqWarmup] run first, as the app now does. */
    @Test
    fun g_firstEqQuantumWithWarmup() {
        assumeFalse(
            "something already warmed the EQ path in this process; " +
                "run this test on its own",
            EqWarmup.warmed,
        )
        val first = measureFirstEqQuantum("WARM", warmFirst = true)
        assertTrue("no first quantum was recorded", first > 0)
        // A quantum is 10ms. The whole point is that the first one no longer
        // blows through it.
        assertTrue(
            "first EQ quantum after warm-up was ${first}ns, still over a 10ms quantum",
            first < 10_000_000L,
        )
    }

    // ---- Compare mode, end to end --------------------------------------

    /**
     * Compare engages, toggles both ways, disengages, and costs nothing
     * permanent.
     *
     * The headroom check is the important one. The level match is a property
     * of the comparison and not of the sound, so leaving Compare has to put
     * the listener back exactly where they were -- the preamp must be the
     * number it was before, and the dry trim must be gone.
     */
    @Test
    fun h_compareModeTogglesCleanlyAndLeavesNoTrace() {
        // The real app warms this at launch; this test builds the receiver
        // directly, so it has to do the same or it is measuring a path the
        // shipping app never takes.
        EqWarmup.warm()

        val sender = TestSender()
        sender.start()
        val receiver = AudioReceiver("127.0.0.1")
        try {
            receiver.applySound(
                SoundState(enabled = true, preset = Preset.Bass).withBass(6f),
            )
            receiver.start()
            waitFor(15_000, "connection") { receiver.status().packetsReceived > 200 }
            waitFor(15_000, "playback to start") { receiver.status().bufferMs > 0 }
            Thread.sleep(4_000)
            receiver.resetTimings()

            val headroomBefore = receiver.status().headroomDb
            val underrunsBefore = receiver.status().underruns

            receiver.setCompare(active = true, showEqualized = true)
            waitFor(5_000, "compare to engage") { receiver.compareActive() }
            Thread.sleep(1_500)

            // Four toggles, each well longer than the 50 ms fade.
            repeat(4) { i ->
                receiver.setCompare(active = true, showEqualized = i % 2 == 1)
                Thread.sleep(1_200)
            }

            val duringStatus = receiver.status()

            receiver.setCompare(active = false, showEqualized = true)
            waitFor(5_000, "compare to release") { !receiver.compareActive() }
            Thread.sleep(2_000)

            val after = receiver.status()
            log("COMPARE headroomBefore=$headroomBefore headroomAfter=${after.headroomDb}")
            log("COMPARE underrunsBefore=$underrunsBefore underrunsAfter=${after.underruns}")
            log("COMPARE duringAvgNanos=${duringStatus.eqOnAvgNanos} " +
                "duringWorstNanos=${duringStatus.eqOnWorstNanos} " +
                "clamped=${after.clampedSamples} resets=${after.eqResets}")

            assertEquals(
                "leaving Compare must not move the preamp",
                headroomBefore, after.headroomDb, 0f,
            )
            assertEquals("Compare must not cost underruns", underrunsBefore, after.underruns)
            assertEquals("the filters must stay stable", 0L, after.eqResets)
            // The criterion that matters is the quantum budget: 10 ms. A
            // quantum slower than that is a late write to AudioTrack, which
            // is a dropout. 8 ms keeps a margin without being so tight that
            // emulator scheduling noise decides the result.
            assertTrue(
                "worst quantum during Compare was ${duringStatus.eqOnWorstNanos}ns, " +
                    "against a 10ms quantum",
                duringStatus.eqOnWorstNanos < 8_000_000L,
            )
        } finally {
            receiver.stop()
            Thread.sleep(1_000)
            sender.stop()
            Thread.sleep(500)
        }
    }

    /**
     * Finds a control by description, scrolling the sheet if it is off screen.
     *
     * The sheet is height-capped and scrolls, and it grows as features are
     * added, so a control that was in frame last month may not be today.
     * Hunting for it is what a person would do, and it keeps the test from
     * failing for a reason that has nothing to do with the thing it tests.
     */
    private fun tapByDesc(fragment: String, label: String) {
        repeat(4) { attempt ->
            val o = device.wait(Until.findObject(By.descContains(fragment)), 3_000)
            if (o != null) {
                o.click()
                return
            }
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe(w / 2, (h * 0.78).toInt(), w / 2, (h * 0.48).toInt(), 20)
            Thread.sleep(600)
        }
        throw AssertionError("$label must be findable by description")
    }

    /**
     * Written to the app's INTERNAL files directory, not external cache.
     *
     * Two reasons, both learned the hard way. Scoped storage hides
     * Android/data from the adb shell user, and `run-as` sees a different
     * mount namespace for external storage, so a screenshot in the external
     * cache cannot be retrieved by either route. Internal files are readable
     * via `run-as` on a debuggable build, which is the one path that works.
     *
     * Note also that `gradle connectedAndroidTest` uninstalls both APKs when
     * it finishes, taking the app's storage with it -- so retrieving these
     * means installing the APKs and driving `am instrument` directly.
     */
    private fun shoot(dir: File, name: String) {
        val f = File(dir, "$name.png")
        val ok = device.takeScreenshot(f)
        log("SHOT $name ok=$ok path=${f.absolutePath} bytes=${f.length()}")
    }

    companion object {
        private const val TAG = "LumaStep5"
    }
}
