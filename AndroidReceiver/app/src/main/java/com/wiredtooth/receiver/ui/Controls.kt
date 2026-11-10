package com.wiredtooth.receiver.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * CONTROLS
 * --------
 * The second half of the component set: the pieces the Sound sheet needed and
 * nothing before it did. Separate file, same package, same three rules as
 * Components.kt -- feedback on press-down, 48dp targets whatever the glyph
 * size, and one spoken sentence per control rather than a pile of nodes.
 *
 * Still foundation only. Material3 would supply Switch, FilterChip and Slider,
 * and each would arrive with its own elevation, ripple and colour roles to
 * override before it stopped looking like a different application.
 */

// ------------------------------------------------------------------- switch

/**
 * SWITCH
 *   track 50x30, knob 26, tap target 48 square
 *
 * The visible track is 30dp tall and the tap target is 48, so what you aim at
 * is larger than what you see. A 30dp control at the right edge of a sheet is
 * otherwise among the easiest things in an interface to miss.
 */
@Composable
fun WtSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    spokenLabel: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val haptic = rememberHaptics()

    val t by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = motionFast(),
        label = "switch",
    )

    Box(
        modifier
            .size(width = Hit.min, height = Hit.min)
            .pointerInput(checked) {
                detectTapGestures {
                    haptic()                 // on the cause, not on the result
                    onChange(!checked)
                }
            }
            .clearAndSetSemantics { contentDescription = spokenLabel },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(50.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(if (checked) p.accent else p.surfaceRaised),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (2f + 18f * t).dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

// --------------------------------------------------------------- preset chip

/**
 * PRESET CHIP
 *   pill, inside a 48dp tap row
 *
 * Selection is carried by fill rather than by a tick or a heavier border,
 * because four chips in a row get compared at a glance and a filled one wins
 * that comparison without being read. The unselected state keeps a hairline so
 * the target is still visible on a row that would otherwise look empty.
 */
@Composable
fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    spokenLabel: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val haptic = rememberHaptics()
    var pressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = motionFast(),
        label = "chip",
    )

    Box(
        modifier
            .defaultMinSize(minHeight = Hit.min)
            .pointerInput(selected) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic()
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .clearAndSetSemantics { contentDescription = spokenLabel },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .scale(scale)
                .clip(RoundedCornerShape(Radius.pill))
                .background(if (selected) p.accent else Color.Transparent)
                .then(
                    if (selected) Modifier
                    else Modifier.border(1.dp, p.glassBorder, RoundedCornerShape(Radius.pill)),
                )
                .padding(horizontal = Space.s2, vertical = Space.s1),
        ) {
            WtText(
                text = label,
                style = Type.button,
                color = if (selected) Color.White else p.textSecondary,
            )
        }
    }
}

// ----------------------------------------------------------------- dB slider

/** "+4.0 dB", "0 dB", "-2.5 dB". Signed, because the sign is the whole point. */
fun formatDb(db: Float): String = when {
    abs(db) < 0.05f -> "0 dB"
    db > 0f -> "+" + String.format("%.1f", db) + " dB"
    else -> String.format("%.1f", db) + " dB"
}

/**
 * The same value for a screen reader: "plus 4.0 decibels", not "+4.0 dB".
 *
 * TalkBack reads "+" as "plus" only sometimes and "-" as "minus" almost never
 * -- a leading hyphen is usually dropped, which turns a cut into a boost. The
 * sign is the most important part of the value, so it is spelled out.
 */
private fun spokenDb(db: Float): String = when {
    abs(db) < 0.05f -> "no change"
    db > 0f -> "plus " + String.format("%.1f", db) + " decibels"
    else -> "minus " + String.format("%.1f", -db) + " decibels"
}

/**
 * BIPOLAR dB SLIDER
 *   track 4dp, knob 20dp, tick at zero, row min-height 48dp
 *
 * A cut and a boost are opposite actions, so the control has to read as
 * bipolar: the fill grows out of the centre instead of up from the left, and
 * there is a tick at zero. On a left-filling slider "flat" sits a third of the
 * way along and looks like a setting; here it is the middle and looks like
 * nothing, which is exactly what it is.
 *
 * MOTION. While a finger is down the knob is pinned to the finger with no
 * animation at all, because a spring between the touch point and the knob is
 * the one place smoothing reads as lag. A change the user did not make with
 * that finger -- choosing a preset, which moves ten of these at once -- does
 * animate, so you can see where the bands went. Under "remove animations" the
 * animation is dropped rather than shortened: a slider moving on its own is
 * precisely the unrequested motion that setting exists to stop, and the value
 * text beside it already communicates the change.
 *
 * ACCESSIBILITY. Dragging is not available to a screen reader user, so the
 * track carries one spoken sentence and three explicit actions, and the value
 * is spoken in words.
 */
@Composable
fun DbSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    spokenNoun: String,
    modifier: Modifier = Modifier,
    min: Float = -12f,
    max: Float = 12f,
    step: Float = 0.5f,
) {
    val p = LocalPalette.current
    val a11y = LocalA11y.current
    val density = LocalDensity.current
    var width by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val knob = 20.dp
    val knobPx = with(density) { knob.toPx() }
    val span = (max - min).coerceAtLeast(0.001f)

    fun quantise(v: Float): Float = ((v / step).roundToInt() * step).coerceIn(min, max)

    fun valueFor(x: Float): Float {
        val travel = (width - knobPx).coerceAtLeast(1f)
        val f = ((x - knobPx / 2f) / travel).coerceIn(0f, 1f)
        return quantise(min + f * span)
    }

    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = motionFast(),
        label = "db",
    )
    val shown = if (dragging || a11y.reduceMotion) value else animated

    // The track CANNOT be p.surfaceRaised, which is what every other slider
    // in this app uses. This one lives on a sheet, and the sheet IS
    // surfaceRaised, so the track would be drawn in the background's own
    // colour and vanish -- which is exactly what the first emulator
    // screenshot showed. In the light palette surfaceRaised is pure white,
    // so it would be invisible there against almost anything. Deriving the
    // track from the text colour at low alpha works on any surface in either
    // palette, because it is an overlay rather than a fixed colour.
    val trackColour = p.textPrimary.copy(alpha = 0.18f)
    val tickColour = p.textPrimary.copy(alpha = 0.40f)

    val t = ((shown - min) / span).coerceIn(0f, 1f)
    val tZero = ((0f - min) / span).coerceIn(0f, 1f)
    val lo = minOf(t, tZero)
    val hi = maxOf(t, tZero)

    Column(modifier.fillMaxWidth()) {
        // The label and value are read as part of the track's sentence below,
        // so they are hidden from the accessibility tree rather than repeated.
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WtText(label, Type.subhead, p.textSecondary)
            WtText(
                text = formatDb(value),
                style = Type.mono,
                color = if (abs(value) < 0.05f) p.textTertiary else p.textPrimary,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Hit.min)
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(step, min, max) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            onChange(valueFor(offset.x))
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        onChange(valueFor(change.position.x))
                    }
                }
                .pointerInput(step, min, max) {
                    detectTapGestures { offset -> onChange(valueFor(offset.x)) }
                }
                .semantics {
                    contentDescription =
                        "$label, ${spokenDb(value)}. $spokenNoun"
                    customActions = listOf(
                        CustomAccessibilityAction("Increase $label") {
                            onChange(quantise(value + step * 2)); true
                        },
                        CustomAccessibilityAction("Decrease $label") {
                            onChange(quantise(value - step * 2)); true
                        },
                        CustomAccessibilityAction("Set $label to no change") {
                            onChange(0f); true
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(trackColour),
            )

            val travelDp = with(density) { (width - knobPx).coerceAtLeast(0f).toDp() }

            // Zero marker, drawn under the fill so that it disappears when the
            // knob is at zero -- at that point there is nothing to mark.
            Box(
                Modifier
                    .offset(x = knob / 2 + travelDp * tZero - 1.dp)
                    .width(2.dp)
                    .height(10.dp)
                    .background(tickColour),
            )

            Box(
                Modifier
                    .offset(x = knob / 2 + travelDp * lo)
                    .width(travelDp * (hi - lo))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.accent),
            )

            Box(
                Modifier
                    .offset(x = travelDp * t)
                    .size(knob)
                    .clip(CircleShape)
                    .background(p.textPrimary)
                    .alpha(if (dragging) 0.85f else 1f),
            )
        }
    }
}

// --------------------------------------------------------------- nav + chevron

/**
 * A whole row that behaves as a button, with a value and a chevron.
 *
 * The entry point into a sheet. The target is the entire row rather than the
 * chevron, because a 12dp glyph at the right edge is not a target.
 */
@Composable
fun NavRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    spokenLabel: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val haptic = rememberHaptics()
    var pressed by remember { mutableStateOf(false) }

    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Hit.min)
            .clip(RoundedCornerShape(Radius.card))
            .background(if (pressed) p.surfaceRaised else p.surface)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic()
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .padding(horizontal = Space.s2, vertical = Space.s1)
            .clearAndSetSemantics { contentDescription = spokenLabel },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WtText(label, Type.body, p.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WtText(value, Type.subhead, p.textSecondary)
            Spacer(Modifier.width(Space.s1))
            Chevron(p.textTertiary)
        }
    }
}

/**
 * A chevron, drawn.
 *
 * Two strokes on a Canvas rather than a vector drawable or an Icons
 * dependency, because importing the Material icons artifact for one 12dp glyph
 * would pull in more than the glyph is worth.
 */
@Composable
private fun Chevron(colour: Color, pointingDown: Boolean = false) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val path = Path()
        if (pointingDown) {
            path.moveTo(w * 0.22f, h * 0.38f)
            path.lineTo(w * 0.50f, h * 0.66f)
            path.lineTo(w * 0.78f, h * 0.38f)
        } else {
            path.moveTo(w * 0.38f, h * 0.22f)
            path.lineTo(w * 0.66f, h * 0.50f)
            path.lineTo(w * 0.38f, h * 0.78f)
        }
        drawPath(
            path = path,
            color = colour,
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * DISCLOSURE
 *
 * A header that opens a section, collapsed wherever it is used. That is the
 * whole point of putting the ten bands behind one: a sheet that opens showing
 * ten sliders has already failed the person who only wanted more bass.
 */
@Composable
fun Disclosure(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    spokenLabel: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val haptic = rememberHaptics()

    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Hit.min)
            .pointerInput(expanded) { detectTapGestures { haptic(); onToggle() } }
            .clearAndSetSemantics { contentDescription = spokenLabel },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WtText(label.uppercase(), Type.overline, p.textTertiary)
        Chevron(p.textTertiary, pointingDown = expanded)
    }
}
