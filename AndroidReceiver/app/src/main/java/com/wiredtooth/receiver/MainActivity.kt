package com.wiredtooth.receiver

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wiredtooth.receiver.ui.ConnectScreen
import com.wiredtooth.receiver.ui.ConnectError
import com.wiredtooth.receiver.ui.Device
import com.wiredtooth.receiver.ui.LocalPalette
import com.wiredtooth.receiver.ui.NowPlayingScreen
import com.wiredtooth.receiver.ui.OutputRoute
import com.wiredtooth.receiver.ui.Radius
import com.wiredtooth.receiver.ui.SavedDevicesScreen
import com.wiredtooth.receiver.ui.ScanState
import com.wiredtooth.receiver.ui.Space
import com.wiredtooth.receiver.ui.Type
import com.wiredtooth.receiver.ui.WiredToothTheme
import com.wiredtooth.receiver.ui.WtText
import com.wiredtooth.receiver.ui.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Tab { Connect, Playing, Saved }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Streaming with the screen off needs a foreground service, which does
        // not exist yet. Keeping the screen awake is the honest stopgap: it
        // stops a demo dying halfway through without pretending to support
        // background playback.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefilledIp = intent?.data
            ?.takeIf { it.scheme == "wiredtooth" }
            ?.host

        setContent { WiredToothTheme { App(prefilledIp) } }
    }
}

@Composable
private fun App(prefilledIp: String?) {
    val p = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = rememberHaptics()

    var tab by remember { mutableStateOf(Tab.Connect) }
    var scan by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    var receiver by remember { mutableStateOf<AudioReceiver?>(null) }
    var status by remember { mutableStateOf<ReceiverStatus?>(null) }
    var saved by remember { mutableStateOf(SavedStore.load(context)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    fun connect(ip: String) {
        receiver?.stop()
        val r = AudioReceiver(ip)
        runCatching { r.start() }
            .onSuccess {
                receiver = r
                saved = SavedStore.remember(context, ip)
                haptic()                      // success, on the causal event
                tab = Tab.Playing
            }
            .onFailure { scan = ScanState.Failed(ConnectError.Refused) }
    }

    // One 500ms poll drives every live number on screen. Cheaper and calmer
    // than pushing events from the audio threads into composition.
    LaunchedEffect(receiver) {
        while (true) {
            status = receiver?.status()
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    DisposableEffect(Unit) { onDispose { receiver?.stop() } }

    LaunchedEffect(prefilledIp) { if (prefilledIp != null) connect(prefilledIp) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Connect -> ConnectScreen(
                    state = scan,
                    onFindPc = {
                        scan = ScanState.Scanning
                    },
                    onSelect = { connect(it.ip) },
                    onManualConnect = { connect(it) },
                )

                Tab.Playing -> status?.let {
                    NowPlayingScreen(
                        status = it,
                        route = OutputRoute.unknownDefault,
                        onDisconnect = {
                            receiver?.stop()
                            receiver = null
                            status = null
                            tab = Tab.Connect
                            scan = ScanState.Idle
                        },
                    )
                } ?: ConnectScreen(
                    state = scan,
                    onFindPc = { scan = ScanState.Scanning },
                    onSelect = { connect(it.ip) },
                    onManualConnect = { connect(it) },
                )

                Tab.Saved -> SavedDevicesScreen(
                    devices = saved,
                    nowMs = now,
                    onReconnect = { connect(it.ip) },   // one tap, no confirm
                    onRemove = { saved = SavedStore.forget(context, it.ip) },
                )
            }
        }

        TabBar(current = tab, connected = receiver != null, onSelect = { tab = it })
    }

    // The sweep runs off the main thread; the screen shows its own scanning
    // state meanwhile.
    LaunchedEffect(scan) {
        if (scan !is ScanState.Scanning) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { Discovery.sweep() }
        scan = if (found.isEmpty()) {
            ScanState.Empty
        } else {
            ScanState.Found(found.map { Device(ip = it.ip) })
        }
    }
}

/**
 * Three destinations, named for their contents rather than a vague umbrella.
 * "Now Playing" is only reachable while something is playing, so the bar never
 * offers a door into an empty room.
 */
@Composable
private fun TabBar(current: Tab, connected: Boolean, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.surface)
            .padding(horizontal = Space.s2, vertical = Space.s1),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TabItem("Connect", current == Tab.Connect) { onSelect(Tab.Connect) }
        if (connected) TabItem("Now Playing", current == Tab.Playing) { onSelect(Tab.Playing) }
        TabItem("Saved", current == Tab.Saved) { onSelect(Tab.Saved) }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.button))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = Space.s2, vertical = 12.dp)
            .semantics {
                contentDescription = if (selected) "$label, selected" else label
            },
        contentAlignment = Alignment.Center,
    ) {
        WtText(label, Type.caption, if (selected) p.accent else p.textTertiary)
    }
}

/**
 * Saved PCs, in SharedPreferences.
 *
 * Deliberately not a database. This is a short list of strings that must
 * survive a restart; Room would be three dependencies and a migration story
 * for something a preferences file does correctly.
 */
private object SavedStore {
    private const val FILE = "saved_devices"
    private const val KEY = "ips"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): List<Device> =
        prefs(c).getString(KEY, "")!!
            .split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split("@")
                if (parts.isEmpty()) return@mapNotNull null
                Device(
                    ip = parts[0],
                    lastConnectedEpochMs = parts.getOrNull(1)?.toLongOrNull(),
                )
            }
            .sortedByDescending { it.lastConnectedEpochMs ?: 0 }

    fun remember(c: Context, ip: String): List<Device> {
        val others = load(c).filter { it.ip != ip }
        val all = listOf(Device(ip, lastConnectedEpochMs = System.currentTimeMillis())) + others
        save(c, all)
        return all
    }

    fun forget(c: Context, ip: String): List<Device> {
        val all = load(c).filter { it.ip != ip }
        save(c, all)
        return all
    }

    private fun save(c: Context, devices: List<Device>) {
        prefs(c).edit()
            .putString(
                KEY,
                devices.joinToString("|") { "${it.ip}@${it.lastConnectedEpochMs ?: 0}" },
            )
            .apply()
    }
}
