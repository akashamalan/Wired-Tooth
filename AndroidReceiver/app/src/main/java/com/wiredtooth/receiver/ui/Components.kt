package com.wiredtooth.receiver.ui

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * COMPONENT SPECS
 * ---------------
 * Every component here follows three rules taken from Apple's guidance:
 *
 *   1. Feedback happens on press-DOWN, not on release. Waiting for the lift
 *      to acknowledge a tap is the single cheapest way to make an interface
 *      feel dead.
 *   2. Motion is a spring, so it can be interrupted and re-targeted from
 *      wherever it currently is.
 *   3. Every number a user reads has a spoken label that is a sentence, not
 *      a variable name.
 */

/** Text helper so the whole app funnels through one styling path. */
@Composable
fun WtText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = align ?: TextAlign.Unspecified),
        maxLines = maxLines,
    )
}

/**
 * Press feedback: scale to 0.97 on pointer-DOWN, spring back on release.
 *
 * The press is applied to the whole surface rather than a ripple, because a
 * ripple is a Material idiom and this interface is not Material. Scale also
 * survives being watched frame by frame, which a ripple on a large card does
 * not.
 *
 * Tap commits on release and cancels if the finger leaves, which is the
 * behaviour people already expect from every button they have used.
 */
@Composable
private fun Modifier.pressable(
    enabled: Boolean,
    onClick: () -> Unit,
    pressedScale: Float = 0.97f,
): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = motionFast(),
        label = "press-scale",
    )
    return this
        .scale(scale)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true              // instant, on down
                    val released = tryAwaitRelease()
                    pressed = false
                    if (released) onClick()     // commit on up
                },
            )
        }
}

/** Success haptic, fired on the causal event and on the same frame as the visual. */
@Composable
fun rememberHaptics(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            view.performHapticFeedback(
                android.view.HapticFeedbackConstants.CONFIRM,
            )
        }
    }
}

// ------------------------------------------------------------------ buttons

/**
 * PRIMARY BUTTON
 *   height   56dp (min 48 hit target, 56 for a hero action)
 *   radius   10dp
 *   type     button (17sp semibold)
 *   press    scale 0.97, spring response 0.30
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color? = null,
    spokenLabel: String = label,
) {
    val p = LocalPalette.current
    val bg = tone ?: p.accent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .pressable(enabled, onClick)
            .clip(RoundedCornerShape(Radius.button))
            .background(if (enabled) bg else p.surfaceRaised)
            .padding(horizontal = Space.s3, vertical = Space.s2)
            .semantics { contentDescription = spokenLabel },
        contentAlignment = Alignment.Center,
    ) {
        WtText(
            text = label,
            style = Type.button,
            color = if (enabled) Color.White else p.textTertiary,
            align = TextAlign.Center,
        )
    }
}

/** Quieter action: same geometry, no fill. Used for Disconnect and Cancel. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    spokenLabel: String = label,
) {
    val p = LocalPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .pressable(true, onClick)
            .clip(RoundedCornerShape(Radius.button))
            .background(p.surface)
            .border(1.dp, p.hairline, RoundedCornerShape(Radius.button))
            .padding(horizontal = Space.s3, vertical = Space.s2)
            .semantics { contentDescription = spokenLabel },
        contentAlignment = Alignment.Center,
    ) {
        WtText(
            text = label,
            style = Type.button,
            color = if (destructive) p.bad else p.textPrimary,
            align = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------- glass

/**
 * GLASS PANEL -- the only translucent surface in the app.
 *
 * Translucency is a hierarchy signal here, not decoration. Exactly one
 * surface floats above the content, so the eye knows instantly which layer is
 * live status and which is page content. Making cards, buttons and sheets all
 * glassy would destroy that distinction and, per Apple's guidance, stacking
 * translucent layers collapses legibility.
 *
 * Falls back to a solid surface when blur is unavailable (below API 31) or
 * when the user has reduced motion/transparency. That fallback is not a
 * degradation to apologise for -- a solid panel with the same border and
 * padding reads as the same component.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val a11y = LocalA11y.current

    if (a11y.reduceTransparency) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(Radius.card))
                .background(p.surfaceRaised)
                .border(1.dp, p.hairline, RoundedCornerShape(Radius.card)),
        ) { content() }
        return
    }

    Box(modifier = modifier.clip(RoundedCornerShape(Radius.card))) {
        // The blur layer sits behind the content so the content stays sharp.
        Box(
            Modifier
                .matchParentSize()
                .blur(24.dp)
                .background(p.glassTint),
        )
        Box(
            Modifier
                .matchParentSize()
                .border(1.dp, p.glassBorder, RoundedCornerShape(Radius.card)),
        )
        content()
    }
}

// -------------------------------------------------------------------- stats

/**
 * STAT TILE
 *   The brief asks for these to be beautiful rather than a debug dump, so:
 *   the value is the largest thing in the tile, the unit is separated and
 *   dimmed so the number reads first, and the label sits underneath in
 *   caption. Tabular-feeling weight keeps digits from jittering as they
 *   change twice a second.
 *
 *   The tile carries ONE spoken label for the whole group -- "Latency, 10.5
 *   milliseconds" -- rather than letting a screen reader read "10.5", "ms",
 *   "latency" as three separate stops.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String,
    spoken: String,
    modifier: Modifier = Modifier,
    tone: Color? = null,
) {
    val p = LocalPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(p.surface)
            .padding(Space.s2)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(Space.half),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            WtText(value, Type.statValue, tone ?: p.textPrimary, maxLines = 1)
            if (unit.isNotEmpty()) {
                WtText(
                    text = unit,
                    style = Type.caption,
                    color = p.textTertiary,
                    modifier = Modifier.padding(start = Space.half, bottom = 5.dp),
                    maxLines = 1,
                )
            }
        }
        WtText(label, Type.caption, p.textSecondary, maxLines = 1)
    }
}

/** Small status dot. Colour is never the only signal -- it always sits beside text. */
@Composable
fun StatusDot(colour: Color, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val a11y = LocalA11y.current
    val alpha = if (pulsing && !a11y.reduceMotion) {
        val t = rememberInfiniteTransition(label = "dot")
        val v by t.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                // 1.6s cycle. Deliberately not near 0.2Hz, which is the
                // frequency range that reads as uncomfortable flicker.
                animation = tween(800),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "dot-alpha",
        )
        v
    } else {
        1f
    }
    Box(
        modifier
            .size(10.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(colour),
    )
}

/** Row of label and value used inside cards and sheets. */
@Composable
fun DetailRow(label: String, value: String, spoken: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WtText(label, Type.subhead, p.textSecondary)
        WtText(value, Type.subhead, p.textPrimary)
    }
}

/** Hairline separator. Used between list rows only, never around cards. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .size(1.dp)
            .background(p.hairline),
    )
}

/**
 * Section header. The only all-caps text in the app -- caps are hard to read
 * in quantity, so they are confined to three-word group labels.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    WtText(
        text = text.uppercase(),
        style = Type.overline,
        color = p.textTertiary,
        modifier = modifier.padding(bottom = Space.s1),
    )
}
