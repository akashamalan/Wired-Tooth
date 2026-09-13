package com.wiredtooth.receiver.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * CONNECT
 *
 * Wayfinding: this screen answers "where am I, what can I do, what is here".
 * One obvious action, one collapsed escape hatch, one honestly-disabled row.
 *
 * IMPLEMENTATION NOTE. "Find PC" needs discovery, which does not exist yet.
 * The sender never announces itself, and CLAUDE.md forbids multicast/Bonjour.
 * The implementable path that needs no sender change is a unicast sweep:
 * HELLO to every address on the local /24 (or the /28 of a phone hotspot) and
 * collect whoever HELLO_ACKs. That is ordinary unicast, so it breaks no rule,
 * and on a hotspot it is 14 addresses. This screen is built against that
 * shape: onFindPc() is expected to take two to three seconds and return a list.
 */
@Composable
fun ConnectScreen(
    state: ScanState,
    onFindPc: () -> Unit,
    onSelect: (Device) -> Unit,
    onManualConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    var manualOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.s3)
            .padding(top = Space.s6, bottom = Space.s4),
    ) {
        WtText("Wired Tooth", Type.title, p.textPrimary)
        Spacer(Modifier.height(Space.s1))
        WtText(
            "Play your computer's audio through this phone.",
            Type.subhead,
            p.textSecondary,
        )

        Spacer(Modifier.height(Space.s6))

        // The one primary action. Becomes the scanning indicator in place,
        // rather than being replaced by a separate spinner screen -- the
        // control the user pressed is where they are still looking.
        ScanControl(state = state, onFindPc = onFindPc)

        Spacer(Modifier.height(Space.s4))

        when (state) {
            is ScanState.Found -> FoundList(state.devices, onSelect)
            ScanState.Empty -> EmptyState()
            is ScanState.Failed -> ErrorState(state.reason)
            else -> Unit
        }

        Spacer(Modifier.height(Space.s4))

        ManualEntry(
            open = manualOpen,
            onToggle = { manualOpen = !manualOpen },
            onConnect = onManualConnect,
        )

        Spacer(Modifier.height(Space.s3))

        BluetoothComingSoonRow()
    }
}

/**
 * Scanning shows as a slow concentric pulse behind the button, not a spinner.
 *
 * A spinner says "something is happening, I have no idea how long". A pulse
 * that radiates outward says "I am listening outward", which is what a scan
 * actually is -- the motion hints in the direction of the operation. Under
 * reduced motion it becomes a static ring plus the word Scanning, because the
 * pulse is decorative and the text is not.
 */
@Composable
private fun ScanControl(state: ScanState, onFindPc: () -> Unit) {
    val p = LocalPalette.current
    val a11y = LocalA11y.current
    val scanning = state is ScanState.Scanning

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        if (scanning && !a11y.reduceMotion) {
            val t = rememberInfiniteTransition(label = "scan")
            // Two rings, half a cycle apart, so the pulse never fully empties.
            listOf(0f, 0.5f).forEach { phase ->
                val v by t.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset =
                        androidx.compose.animation.core.StartOffset((2200 * phase).toInt()),
                    ),
                    label = "ring-$phase",
                )
                Box(
                    Modifier
                        .size(56.dp)
                        .scale(1f + v * 2.2f)
                        .alpha((1f - v) * 0.28f)
                        .clip(CircleShape)
                        .background(p.accent),
                )
            }
        }

        if (scanning) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .clip(RoundedCornerShape(Radius.button))
                    .background(p.surfaceRaised)
                    .border(1.dp, p.hairline, RoundedCornerShape(Radius.button))
                    .semantics { contentDescription = "Looking for computers on this network" },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(p.accent, pulsing = true)
                    Spacer(Modifier.size(Space.s1))
                    WtText("Looking for your PC", Type.button, p.textPrimary)
                }
            }
        } else {
            PrimaryButton(
                label = if (state is ScanState.Idle) "Find PC" else "Search again",
                onClick = onFindPc,
                spokenLabel = "Find PC. Searches this Wi-Fi network for computers " +
                    "running Wired Tooth.",
            )
        }
    }
}

/**
 * Found devices, revealed as a staggered cascade.
 *
 * The stagger is 40ms per card. Long enough to read as a sequence, short
 * enough that four cards are all present within 160ms, so nobody waits. Cards
 * rise 12dp as they fade -- the motion points in the direction they arrived
 * from, down the list.
 */
@Composable
private fun FoundList(devices: List<Device>, onSelect: (Device) -> Unit) {
    SectionHeader("Found on this network")
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        devices.forEachIndexed { index, device ->
            StaggeredIn(index = index) {
                DeviceCard(device = device, onClick = { onSelect(device) })
            }
        }
    }
}

@Composable
private fun StaggeredIn(index: Int, content: @Composable () -> Unit) {
    val a11y = LocalA11y.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index * Motion.STAGGER_MS).toLong())
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = motionStandard(),
        label = "stagger",
    )
    Box(
        Modifier
            .alpha(progress)
            // Reduced motion keeps the fade and drops the travel: opacity is
            // comprehension, translation is vestibular.
            .then(
                if (a11y.reduceMotion) Modifier
                else Modifier.padding(top = ((1f - progress) * 12).dp),
            ),
    ) { content() }
}

/**
 * DEVICE CARD
 *   radius 12, padding 16, min height 72
 *   name (heading) over ip (mono caption), signal bars trailing
 *   whole card is one tap target with one spoken sentence
 */
@Composable
fun DeviceCard(device: Device, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = motionFast(),
        label = "card-press",
    )

    val spoken = buildString {
        append(device.displayName)
        if (device.name != null) append(", at ${device.ip.replace(".", " dot ")}")
        device.signal?.let { append(", signal ${(it * 100).toInt()} percent") }
        append(". Double tap to connect.")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(Radius.card))
            .background(p.surface)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    val ok = tryAwaitRelease()
                    pressed = false
                    if (ok) onClick()
                })
            }
            .padding(Space.s2)
            .defaultMinSize(minHeight = 72.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            WtText(device.displayName, Type.heading, p.textPrimary, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            WtText(device.ip, Type.mono, p.textSecondary, maxLines = 1)
        }
        device.signal?.let { SignalBars(it) }
    }
}

/** Three bars. Shape carries the meaning so colour is never the only signal. */
@Composable
private fun SignalBars(strength: Float) {
    val p = LocalPalette.current
    val lit = (strength * 3).toInt().coerceIn(1, 3)
    Row(verticalAlignment = Alignment.Bottom) {
        (1..3).forEach { i ->
            Box(
                Modifier
                    .padding(start = 3.dp)
                    .size(width = 4.dp, height = (6 + i * 5).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= lit) p.textPrimary else p.hairline),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(p.surface)
            .padding(Space.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WtText("No PCs found", Type.heading, p.textPrimary, align = TextAlign.Center)
        Spacer(Modifier.height(Space.s1))
        WtText(
            "Make sure Wired Tooth is running on your computer.",
            Type.subhead,
            p.textSecondary,
            align = TextAlign.Center,
        )
    }
}

/** Errors name the fix, not the failure. */
@Composable
private fun ErrorState(reason: ConnectError) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(p.surface)
            .border(1.dp, p.bad.copy(alpha = 0.35f), RoundedCornerShape(Radius.card))
            .padding(Space.s3)
            .clearAndSetSemantics {
                contentDescription = "${reason.title}. ${reason.detail}"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(p.bad)
            Spacer(Modifier.size(Space.s1))
            WtText(reason.title, Type.heading, p.textPrimary)
        }
        Spacer(Modifier.height(Space.s1))
        WtText(reason.detail, Type.subhead, p.textSecondary)
    }
}

/**
 * Manual IP entry, collapsed by default.
 *
 * The common path is shown first and the advanced one lives one level deeper.
 * It is not hidden -- the row is always visible and always tappable -- it is
 * just not competing with the primary action.
 */
@Composable
private fun ManualEntry(open: Boolean, onToggle: () -> Unit, onConnect: (String) -> Unit) {
    val p = LocalPalette.current
    var ip by remember { mutableStateOf("") }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .pointerInput(Unit) { detectTapGestures { onToggle() } }
                .defaultMinSize(minHeight = Hit.min)
                .padding(vertical = Space.s1)
                .semantics {
                    contentDescription =
                        if (open) "Hide manual IP entry" else "Enter an IP address manually"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            WtText("Enter IP manually", Type.body, p.textSecondary)
            WtText(if (open) "Hide" else "Show", Type.caption, p.accent)
        }

        if (open) {
            Spacer(Modifier.height(Space.s1))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.button))
                    .background(p.surface)
                    .border(1.dp, p.hairline, RoundedCornerShape(Radius.button))
                    .padding(horizontal = Space.s2, vertical = Space.s2),
            ) {
                if (ip.isEmpty()) {
                    WtText("192.168.1.42", Type.mono, p.textTertiary)
                }
                BasicTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    singleLine = true,
                    textStyle = Type.mono.copy(color = p.textPrimary),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "IP address of the computer" },
                )
            }
            Spacer(Modifier.height(Space.s1))
            PrimaryButton(
                label = "Connect",
                onClick = { if (ip.isNotBlank()) onConnect(ip.trim()) },
                enabled = ip.isNotBlank(),
                spokenLabel = "Connect to the IP address you entered",
            )
        }
    }
}

/**
 * Bluetooth is not built. It is shown disabled rather than hidden, so the
 * app answers the question "can it do Bluetooth?" instead of leaving the user
 * to wonder. No flow is designed behind it, because there is nothing behind it.
 */
@Composable
private fun BluetoothComingSoonRow() {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(p.surface.copy(alpha = 0.5f))
            .padding(Space.s2)
            .defaultMinSize(minHeight = Hit.min)
            .clearAndSetSemantics {
                contentDescription = "Connect over Bluetooth. Coming soon, not available yet."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            WtText("Connect over Bluetooth", Type.body, p.textTertiary)
            Spacer(Modifier.height(2.dp))
            WtText("Wi-Fi only for now", Type.caption, p.textTertiary)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(p.surfaceRaised)
                .padding(horizontal = Space.s1, vertical = 4.dp),
        ) {
            WtText("Coming soon", Type.caption, p.textTertiary)
        }
    }
}
