package com.wiredtooth.receiver.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wiredtooth.receiver.ReceiverStatus
import kotlin.math.roundToInt

/**
 * NOW PLAYING
 *
 * The screen people stare at. Hierarchy is deliberate and steep: the single
 * most important fact -- am I connected -- is 56sp and unmissable, and
 * everything else is deliberately quieter. If the stats competed with the
 * status, the screen would answer the wrong question first.
 *
 * The stats are the one place the brief asked for beauty rather than a debug
 * dump. What makes them readable is not decoration: it is that each number
 * has a unit split off and dimmed, a caption underneath, and a tone that only
 * changes when the number means something is wrong. A grid of four equal
 * tiles with no colour is calm; a grid that turns amber tells you where to
 * look without you having to read.
 */
@Composable
fun NowPlayingScreen(
    status: ReceiverStatus,
    route: OutputRoute,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    var confirming by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.s3)
                .padding(top = Space.s6, bottom = Space.s3),
        ) {
            ConnectionHero(status)

            Spacer(Modifier.height(Space.s4))

            RouteRow(route)

            Spacer(Modifier.height(Space.s3))

            // The glass panel: the only translucent surface in the app, and it
            // holds the only thing that changes twice a second. Translucency
            // marks it as a live layer floating over static content.
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.s2)) {
                    SectionHeader("Live")
                    StatGrid(status)
                }
            }

            Spacer(Modifier.weight(1f))

            SecondaryButton(
                label = "Disconnect",
                onClick = { confirming = true },
                destructive = true,
                spokenLabel = "Disconnect from ${status.senderIp.replace(".", " dot ")}",
            )
        }

        if (confirming) {
            ConfirmSheet(
                title = "Stop playing?",
                body = "Audio will stop on this phone. Your computer keeps running.",
                confirmLabel = "Disconnect",
                onConfirm = {
                    confirming = false
                    onDisconnect()
                },
                onCancel = { confirming = false },
            )
        }
    }
}

/**
 * The status, at 56sp. Plain words: "Connected", never "Session established".
 *
 * The dot pulses only while reconnecting -- motion means "working on it".
 * A steady dot means settled. That is the whole vocabulary, and it does not
 * need a legend.
 */
@Composable
private fun ConnectionHero(status: ReceiverStatus) {
    val p = LocalPalette.current

    val (headline, tone, pulsing) = when {
        status.state.startsWith("streaming") -> Triple("Connected", p.good, false)
        status.state.startsWith("reconnecting") -> Triple("Reconnecting", p.warn, true)
        status.state.startsWith("connected") -> Triple("Almost there", p.warn, true)
        else -> Triple("Connecting", p.textSecondary, true)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "$headline to ${status.senderIp.replace(".", " dot ")}"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone, pulsing = pulsing)
            Spacer(Modifier.size(Space.s1))
            WtText("From ${status.senderIp}", Type.caption, p.textSecondary)
        }
        Spacer(Modifier.height(Space.s1))
        WtText(headline, Type.hero, p.textPrimary)
    }
}

/**
 * Output route. Answers "where is this sound actually going", which on a
 * phone is genuinely ambiguous the moment earbuds are in the case.
 */
@Composable
private fun RouteRow(route: OutputRoute) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(p.surface)
            .padding(Space.s2)
            .clearAndSetSemantics { contentDescription = "Playing through ${route.label}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        WtText("Playing through", Type.subhead, p.textSecondary)
        WtText(route.label, Type.body, p.textPrimary)
    }
}

/**
 * Four stats, two by two. Latency leads because it is the project's claim.
 *
 * Tone rules, and the reason each threshold is where it is:
 *   latency  under 150ms good, under 250 warn, else bad -- 250 is roughly
 *            where Bluetooth sits, so past it the app has lost its argument
 *   loss     under 1% good, under 3% warn -- 1% is the project's own bar
 *   buffer   within 20ms of the 60ms target is good; drifting means the
 *            control loop is losing
 *   underrun any at all is worth showing amber; they are audible
 */
@Composable
private fun StatGrid(status: ReceiverStatus) {
    val p = LocalPalette.current

    // Same estimate the Windows receiver reports: half the round trip, plus
    // the buffer we are holding, plus the output path.
    val estimated = status.rttMs / 2.0 + status.bufferMs + 50.0

    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
            StatTile(
                label = "Latency",
                value = estimated.roundToInt().toString(),
                unit = "ms",
                spoken = "Latency, about ${estimated.roundToInt()} milliseconds",
                tone = when {
                    estimated < 150 -> p.good
                    estimated < 250 -> p.warn
                    else -> p.bad
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Packet loss",
                value = fmt2(status.lossPercent),
                unit = "%",
                spoken = "Packet loss, ${fmt2(status.lossPercent)} percent",
                tone = when {
                    status.lossPercent < 1.0 -> p.good
                    status.lossPercent < 3.0 -> p.warn
                    else -> p.bad
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
            StatTile(
                label = "Buffer",
                value = status.bufferMs.roundToInt().toString(),
                unit = "ms",
                spoken = "Audio buffer, ${status.bufferMs.roundToInt()} milliseconds",
                tone = if (kotlin.math.abs(status.bufferMs - 60.0) < 20) p.good else p.warn,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Round trip",
                value = fmt1(status.rttMs),
                unit = "ms",
                spoken = "Network round trip, ${fmt1(status.rttMs)} milliseconds",
                modifier = Modifier.weight(1f),
            )
        }

        if (status.underruns > 0) {
            Spacer(Modifier.height(Space.half))
            DetailRow(
                label = "Dropouts",
                value = status.underruns.toString(),
                spoken = "${status.underruns} audio dropouts since connecting",
            )
        }
    }
}

private fun fmt1(v: Double) = String.format("%.1f", v)
private fun fmt2(v: Double) = String.format("%.2f", v)

/**
 * CONFIRM SHEET
 *   radius 20 (sheet class), scrim behind, enters and exits downward.
 *
 * Disconnect gets a confirm because it stops the thing the user came for, and
 * an accidental tap while reaching for the phone is easy. Reconnect from
 * Saved does NOT get one -- it is one tap, instantly reversible, and asking
 * twice for a harmless action is how people learn to dismiss dialogs without
 * reading them.
 */
@Composable
fun ConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val p = LocalPalette.current
    val a11y = LocalA11y.current

    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        // Momentum spring: a sheet is a physical thing being pushed up, so a
        // little overshoot is right here where it would be wrong on a fade.
        animationSpec = motionMomentum(),
        label = "sheet",
    )

    // Dim to focus: this is a modal task, so the background is pushed back.
    Box(
        Modifier
            .fillMaxSize()
            .alpha(progress)
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onCancel() } }
            .semantics { contentDescription = "Dismiss" },
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    // Enters upward, leaves downward: the same path, mirrored.
                    if (a11y.reduceMotion) Modifier.alpha(progress)
                    else Modifier.padding(top = ((1f - progress) * 40).dp).alpha(progress),
                )
                .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                .background(p.surfaceRaised)
                .padding(Space.s3)
                .padding(bottom = Space.s2),
        ) {
            WtText(title, Type.heading, p.textPrimary)
            Spacer(Modifier.height(Space.s1))
            WtText(body, Type.subhead, p.textSecondary)
            Spacer(Modifier.height(Space.s3))
            PrimaryButton(
                label = confirmLabel,
                onClick = onConfirm,
                tone = p.bad,
                spokenLabel = "$confirmLabel. $body",
            )
            Spacer(Modifier.height(Space.s1))
            SecondaryButton(label = "Keep playing", onClick = onCancel)
        }
    }
}
