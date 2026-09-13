package com.wiredtooth.receiver

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal UI: an address box, a connect button, and the numbers that matter.
 *
 * Built with plain views rather than Compose on purpose. The screen is a
 * status line and five figures; Compose would add a large dependency tree to
 * a project whose whole point is that the audio path is simple and auditable.
 */
class MainActivity : Activity() {

    private lateinit var address: EditText
    private lateinit var connectButton: Button
    private lateinit var state: TextView
    private lateinit var stats: TextView

    private var receiver: AudioReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Streaming with the screen off needs a foreground service; until
        // that exists, keeping the screen on is the honest way to stop a
        // demo dying halfway through.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
            setBackgroundColor(Color.parseColor("#101014"))
        }

        root.addView(label("Wired Tooth", 26f, Color.WHITE, bold = true))
        root.addView(label("Windows audio over Wi-Fi", 14f, Color.parseColor("#8A8A95")))
        root.addView(spacer(48))

        address = EditText(this).apply {
            hint = "sender IP, e.g. 172.20.10.14"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#55555F"))
            textSize = 17f
            typeface = Typeface.MONOSPACE
        }
        root.addView(address)
        root.addView(spacer(24))

        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { toggle() }
        }
        root.addView(connectButton)
        root.addView(spacer(40))

        state = label("idle", 19f, Color.parseColor("#5AC87A"), bold = true)
        root.addView(state)
        root.addView(spacer(20))

        stats = TextView(this).apply {
            setTextColor(Color.parseColor("#C8C8D2"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.35f)
        }
        root.addView(stats)

        setContentView(root)

        // Pairing by QR: the Windows tray app encodes wiredtooth://<ip>:5000
        // and the camera app hands it straight here, so the address is already
        // filled in and one tap connects.
        intent?.data?.let { uri ->
            if (uri.scheme == "wiredtooth" && uri.host != null) {
                address.setText(uri.host)
            }
        }

        render()
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver?.stop()
        receiver = null
    }

    private fun toggle() {
        val active = receiver
        if (active != null) {
            // Stop blocks briefly while it sends BYE and joins threads, so it
            // must not run on the UI thread.
            connectButton.isEnabled = false
            Thread {
                active.stop()
                handler.post {
                    receiver = null
                    connectButton.isEnabled = true
                    render()
                }
            }.start()
            return
        }

        val ip = address.text.toString().trim()
        if (ip.isEmpty()) {
            state.text = "enter the sender's IP address"
            state.setTextColor(Color.parseColor("#E0645A"))
            return
        }

        try {
            val r = AudioReceiver(ip)
            r.start()
            receiver = r
        } catch (e: Exception) {
            state.text = "could not start: ${e.javaClass.simpleName}"
            state.setTextColor(Color.parseColor("#E0645A"))
            return
        }
        render()
    }

    private fun render() {
        val r = receiver
        connectButton.text = if (r == null) "Connect" else "Disconnect"
        address.isEnabled = r == null

        if (r == null) {
            state.text = "idle"
            state.setTextColor(Color.parseColor("#8A8A95"))
            stats.text = "Start the sender on the PC, then connect.\n" +
                "Scanning the tray app's QR code fills the address in."
            return
        }

        val s = r.status()
        state.text = s.state
        state.setTextColor(
            when {
                s.state.startsWith("streaming") -> Color.parseColor("#5AC87A")
                s.state.startsWith("reconnecting") -> Color.parseColor("#E0A55A")
                else -> Color.parseColor("#8A8A95")
            },
        )

        stats.text = buildString {
            appendLine("sender    ${s.senderIp}")
            appendLine("format    ${s.format}")
            appendLine("")
            appendLine("rtt       ${fmt(s.rttMs)} ms")
            appendLine("buffer    ${fmt(s.bufferMs)} ms")
            appendLine("ratio     ${String.format("%.5f", s.correctionRatio)}")
            appendLine("bitrate   ${fmt(s.kbitPerSecond)} kbit/s")
            appendLine("")
            appendLine("received  ${s.packetsReceived}")
            appendLine("lost      ${s.packetsLost}  (${String.format("%.2f", s.lossPercent)}%)")
            appendLine("dropped   ${s.packetsDropped}")
            append("underruns ${s.underruns}")
        }
    }

    private fun fmt(v: Double) = String.format("%.2f", v)

    private fun label(text: String, size: Float, colour: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(colour)
            if (bold) setTypeface(null, Typeface.BOLD)
            gravity = Gravity.START
        }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }
}
