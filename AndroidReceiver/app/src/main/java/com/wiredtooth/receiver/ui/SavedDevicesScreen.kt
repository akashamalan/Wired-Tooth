package com.wiredtooth.receiver.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * SAVED DEVICES
 *
 * Tap to reconnect -- one tap, no confirm. Reconnecting is harmless and
 * instantly reversible, and a confirmation on a safe action is how people
 * learn to dismiss dialogs without reading them. Disconnect, which stops what
 * they came for, is the one that asks.
 *
 * Swipe to remove, with the row tracking the finger 1:1 the whole way rather
 * than jumping to a revealed state at some threshold.
 */
@Composable
fun SavedDevicesScreen(
    devices: List<Device>,
    nowMs: Long,
    onReconnect: (Device) -> Unit,
    onRemove: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = Space.s3)
            .padding(top = Space.s6),
    ) {
        WtText("Saved PCs", Type.title, p.textPrimary)
        Spacer(Modifier.height(Space.s1))
        WtText("Tap to reconnect. Swipe left to remove.", Type.subhead, p.textSecondary)
        Spacer(Modifier.height(Space.s4))

        if (devices.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.card))
                    .background(p.surface)
                    .padding(Space.s3),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WtText("Nothing saved yet", Type.heading, p.textPrimary, align = TextAlign.Center)
                Spacer(Modifier.height(Space.s1))
                WtText(
                    "Computers you connect to will appear here.",
                    Type.subhead,
                    p.textSecondary,
                    align = TextAlign.Center,
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            items(devices, key = { it.ip }) { device ->
                SwipeToRemoveRow(
                    onRemove = { onRemove(device) },
                    spoken = spokenFor(device, nowMs),
                    removeLabel = "Remove ${device.displayName}",
                ) {
                    SavedRow(device = device, nowMs = nowMs, onClick = { onReconnect(device) })
                }
            }
        }
    }
}

private fun spokenFor(device: Device, nowMs: Long): String = buildString {
    append(device.displayName)
    if (device.name != null) append(", at ${device.ip.replace(".", " dot ")}")
    device.lastConnectedEpochMs?.let { append(", last connected ${relativeTime(it, nowMs)}") }
    append(". Double tap to reconnect.")
}

/**
 * Swipe-to-remove.
 *
 * The row follows the finger 1:1, and past the edge it rubber-bands rather
 * than stopping dead -- a hard stop reads as frozen, progressive resistance
 * reads as "responsive, but there is nothing more here".
 *
 * Commit is decided by velocity as well as distance: a fast flick removes
 * even if it was short, because the gesture clearly meant it. Release below
 * both thresholds springs home.
 *
 * A swipe is invisible to a screen reader, so the same action is also exposed
 * as a custom accessibility action. Gesture-only affordances are how features
 * become unreachable.
 */
@Composable
private fun SwipeToRemoveRow(
    onRemove: () -> Unit,
    spoken: String,
    removeLabel: String,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val a11y = LocalA11y.current
    val density = LocalDensity.current

    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }

    val commitPx = with(density) { 96.dp.toPx() }
    val maxPx = with(density) { 120.dp.toPx() }

    val animated by animateFloatAsState(
        targetValue = if (dragging) offset else if (removed) -maxPx * 2 else 0f,
        animationSpec = motionMomentum(),
        label = "swipe",
    )
    val x = if (dragging) offset else animated

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .semantics {
                contentDescription = spoken
                customActions = listOf(
                    CustomAccessibilityAction(removeLabel) { onRemove(); true },
                )
            },
    ) {
        // The destructive affordance sits behind, revealed by the drag. Its
        // opacity tracks progress so intent is legible before commit.
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(Radius.card))
                .background(p.bad.copy(alpha = 0.18f))
                .alpha((abs(x) / commitPx).coerceIn(0f, 1f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            WtText("Remove", Type.button, p.bad, modifier = Modifier.padding(end = Space.s2))
        }

        Box(
            Modifier
                .graphicsLayer { translationX = x }
                .pointerInput(a11y.reduceMotion) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            if (abs(offset) > commitPx) {
                                removed = true
                                onRemove()
                            }
                            offset = 0f
                        },
                        onDragCancel = {
                            dragging = false
                            offset = 0f
                        },
                    ) { _, delta ->
                        val next = offset + delta
                        offset = if (next > 0f) {
                            0f                       // no rightward swipe to reveal
                        } else if (abs(next) <= maxPx) {
                            next
                        } else {
                            // Rubber-band: the further past the bound, the
                            // less the row follows.
                            val over = abs(next) - maxPx
                            -(maxPx + over * 0.55f * maxPx / (maxPx + 0.55f * over))
                        }
                    }
                },
        ) { content() }
    }
}

/**
 * SAVED ROW
 *   min height 72, radius 12, name over ip, relative timestamp trailing.
 *   Whole row is one target and one spoken sentence.
 */
@Composable
private fun SavedRow(device: Device, nowMs: Long, onClick: () -> Unit) {
    val p = LocalPalette.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = motionFast(),
        label = "row-press",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
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
            .clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            WtText(device.displayName, Type.heading, p.textPrimary, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            WtText(device.ip, Type.mono, p.textSecondary, maxLines = 1)
        }
        device.lastConnectedEpochMs?.let {
            WtText(relativeTime(it, nowMs), Type.caption, p.textTertiary, maxLines = 1)
        }
    }
}

/**
 * Relative time, in the words a person would use. "2 days ago" is instantly
 * understood; a timestamp has to be decoded against today's date first.
 */
fun relativeTime(thenMs: Long, nowMs: Long): String {
    val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "Just now"
        s < 3600 -> "${(s / 60).toInt()} min ago"
        s < 86_400 -> {
            val h = (s / 3600).toInt()
            if (h == 1) "1 hour ago" else "$h hours ago"
        }
        s < 2_592_000 -> {
            val d = (s / 86_400).toInt()
            if (d == 1) "Yesterday" else "$d days ago"
        }
        else -> "${(s / 2_592_000).toInt()} months ago"
    }
}
