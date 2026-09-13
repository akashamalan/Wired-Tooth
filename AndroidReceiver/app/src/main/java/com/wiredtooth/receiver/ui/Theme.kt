package com.wiredtooth.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

@Composable
fun WiredToothTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dark first. The light palette exists and is correct, but every value in
    // the dark one was chosen first and the light one derived from it, rather
    // than the other way round.
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalA11y provides rememberA11yPrefs(),
    ) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            content()
        }
    }
}

// ----------------------------------------------------------------- models

/**
 * A PC the app can connect to.
 *
 * NOTE ON WHAT IS REAL. `name` and `signal` are designed ahead of the
 * protocol: WTP1 has no field for a machine name and nothing in the stream
 * carries Wi-Fi signal strength. Both are nullable and every screen renders
 * correctly when they are null, so this compiles and behaves honestly against
 * today's implementation rather than requiring the protocol to change first.
 */
data class Device(
    val ip: String,
    val name: String? = null,
    /** 0..1, or null when unknown. Today it is always null. */
    val signal: Float? = null,
    val lastConnectedEpochMs: Long? = null,
) {
    val displayName: String get() = name ?: ip
}

/** What the Connect screen is doing. */
sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Found(val devices: List<Device>) : ScanState
    data object Empty : ScanState
    data class Failed(val reason: ConnectError) : ScanState
}

/**
 * The three failures worth distinguishing, because each has a different fix
 * and a generic "connection failed" tells the user nothing they can act on.
 */
enum class ConnectError {
    /** Host reachable, nothing listening. Sender app is not running. */
    Refused,

    /** No route. Phone and PC are on different networks. */
    WrongNetwork,

    /** Reachable but silent. Usually a firewall, or AP client isolation. */
    Timeout,
    ;

    val title: String
        get() = when (this) {
            Refused -> "That PC isn't listening"
            WrongNetwork -> "Different networks"
            Timeout -> "No answer"
        }

    /** Plain copy. Says what to do, not what went wrong internally. */
    val detail: String
        get() = when (this) {
            Refused -> "Wired Tooth reached the PC but nothing answered. " +
                "Start Wired Tooth on the computer, then try again."
            WrongNetwork -> "This phone and the PC aren't on the same Wi-Fi. " +
                "Join the same network, or use the PC's hotspot."
            Timeout -> "The PC didn't reply. A firewall may be blocking it, " +
                "or the network may not allow devices to talk to each other."
        }
}

/** The output the phone is actually playing through. */
enum class OutputRoute(val label: String) {
    Wired("Wired earbuds"),
    Bluetooth("Bluetooth"),
    Speaker("Speaker"),
    ;

    companion object {
        /**
         * Designed ahead: nothing queries AudioManager yet. Defaulting to
         * Speaker rather than guessing Wired keeps the UI from claiming
         * something it has not checked.
         */
        val unknownDefault = Speaker
    }
}
