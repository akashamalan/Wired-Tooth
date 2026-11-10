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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wiredtooth.receiver.ReceiverStatus
import com.wiredtooth.receiver.dsp.EqSettings
import com.wiredtooth.receiver.dsp.Preset
import com.wiredtooth.receiver.dsp.SoundState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SOUND
 *
 * The one screen in the app that has to teach something, because the
 * equalizer has a behaviour nobody expects: turning a band up makes the
 * overall level go DOWN. That is not a bug and it is not avoidable. A 16-bit
 * sample cannot exceed full scale, so the only way to add 8 dB at 60 Hz is to
 * take 8 dB off everything first and let the boost happen in the space that
 * freed. Every equalizer does this; most of them hide it, and the user
 * concludes the feature is broken and turns it off.
 *
 * So the number is shown, in plain words, with the reason. "Headroom -8.5 dB"
 * and one sentence saying why. It is the difference between a control that
 * feels broken and one that feels honest.
 *
 * STRUCTURE, and why it is in this order:
 *
 *   1. The switch, because "off" has to be one tap from the top.
 *   2. Four presets, because a tap that describes the whole sound beats two
 *      sliders for most people most of the time.
 *   3. Two macros, capped at 6 dB. Enough to fix a recording, not enough to
 *      spend the entire dynamic range on preamp.
 *   4. The headroom explanation, directly under the controls that cause it.
 *   5. Advanced, collapsed, holding the ten real bands and the debug counters.
 *
 * The ten bands are behind a disclosure rather than on the sheet because a
 * sheet that opens showing ten sliders has already failed the person who
 * wanted more bass. They are present because the person who does want band 7
 * at -3 dB should not have to use a different app.
 *
 * TRANSLUCENCY. None. Now Playing already spends its one translucent surface
 * on the live stats panel, and a glass sheet stacked over a glass panel is two
 * layers of blur with text on both. This is a solid raised surface over a
 * scrim, the same as ConfirmSheet.
 */
@Composable
fun SoundSheet(
    state: SoundState,
    status: ReceiverStatus,
    onChange: (SoundState) -> Unit,
    onDismiss: () -> Unit,
) {
    val sampleRate = status.sampleRate
    val p = LocalPalette.current
    val a11y = LocalA11y.current

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = motionMomentum(),
        label = "soundsheet",
    )

    var advanced by remember { mutableStateOf(false) }

    // The figure actually applied to the signal, recomputed here rather than
    // read from the 500ms status poll so that it tracks a dragging finger.
    //
    // It is the same function the receiver calls, against the same sample
    // rate, so the two cannot disagree -- and it already includes the 0.5 dB
    // headroom margin, which means the number on screen is the whole
    // reduction the listener hears and not a rounded-off version of it.
    val preampDb = remember(state, sampleRate) {
        state.toEqSettings(sampleRate).preampDb
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(progress.coerceIn(0f, 1f))
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .semantics { contentDescription = "Close sound settings" },
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    // offset, NOT padding. motionMomentum is a low-bouncy
                    // spring, so progress overshoots past 1.0 on the way in --
                    // which is the entire point of using it for a sheet. A
                    // negative padding throws ("Padding must be non-negative")
                    // and took the app down the first time the sheet was ever
                    // opened on a device; an offset simply renders the
                    // overshoot as the slight over-travel it is meant to be.
                    // Alpha is clamped for the same reason.
                    if (a11y.reduceMotion) Modifier.alpha(progress.coerceIn(0f, 1f))
                    else Modifier
                        .offset(y = ((1f - progress) * 40).dp)
                        .alpha(progress.coerceIn(0f, 1f)),
                )
                .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                .background(p.surfaceRaised),
        ) {
            // Capped so the sheet never covers the whole screen: some of what
            // is behind it must stay visible, or it is a screen pretending to
            // be a sheet and Back should have closed it, not backgrounded the
            // app.
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(Space.s3),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        WtText("Sound", Type.heading, p.textPrimary)
                        WtText(
                            text = if (state.enabled) summarise(state) else "Off",
                            style = Type.subhead,
                            color = p.textSecondary,
                        )
                    }
                    WtSwitch(
                        checked = state.enabled,
                        onChange = { onChange(state.copy(enabled = it)) },
                        spokenLabel = if (state.enabled) {
                            "Sound adjustment is on, set to ${summarise(state)}. " +
                                "Double tap to turn it off."
                        } else {
                            "Sound adjustment is off. Double tap to turn it on."
                        },
                    )
                }

                // Dimmed when off, but NOT inert: touching any control turns
                // Sound on and applies the change in one gesture.
                //
                // The alternative -- dim and ignore -- is the conventional
                // choice and it is worse here. A tap that does nothing teaches
                // nothing, and for a screen reader it is strictly broken: the
                // sliders would still announce three adjust actions each,
                // every one of them a no-op. Dragging Bass is not an ambiguous
                // signal about whether you want the equalizer on, and the
                // switch flipping under your finger is the feedback that says
                // what happened.
                val live = state.enabled

                Spacer(Modifier.height(Space.s3))

                Column(Modifier.alpha(if (live) 1f else 0.4f)) {
                    SectionHeader("Presets")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s1),
                    ) {
                        for (preset in Preset.entries) {
                            PresetChip(
                                label = preset.label,
                                selected = !state.isCustom && state.preset == preset,
                                onClick = { onChange(state.withPreset(preset).copy(enabled = true)) },
                                spokenLabel = presetSpoken(preset, state),
                            )
                        }
                    }

                    Spacer(Modifier.height(Space.s2))

                    DbSlider(
                        label = "Bass",
                        value = state.bassDb,
                        onChange = { onChange(state.withBass(it).copy(enabled = true)) },
                        spokenNoun = "Adds weight below 125 hertz. " +
                            "Swipe with one finger to adjust.",
                        min = -SoundState.MACRO_LIMIT_DB,
                        max = SoundState.MACRO_LIMIT_DB,
                    )

                    Spacer(Modifier.height(Space.s1))

                    DbSlider(
                        label = "Warmth",
                        value = state.warmthDb,
                        onChange = { onChange(state.withWarmth(it).copy(enabled = true)) },
                        spokenNoun = "Lifts the lower mid-range and softens the treble. " +
                            "Swipe with one finger to adjust.",
                        min = -SoundState.MACRO_LIMIT_DB,
                        max = SoundState.MACRO_LIMIT_DB,
                    )

                    // The cap is only worth mentioning when it is invisible:
                    // a preset and a macro pushed the same band, the sum was
                    // trimmed, and without this the slider would read +6 while
                    // the band sat at +8 with no explanation for the
                    // difference.
                    val capped = state.cappedBandCount()
                    if (capped > 0) {
                        Spacer(Modifier.height(Space.s1))
                        WtText(
                            text = "Boost limited to keep the sound clean.",
                            style = Type.caption,
                            color = p.warn,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription =
                                    "Boost limited to keep the sound clean. " +
                                    (if (capped == 1) "One band has" else "$capped bands have") +
                                    " reached the ${SoundState.MAX_BOOST_DB.toInt()} decibel " +
                                    "ceiling, so turning these sliders up further will not " +
                                    "raise them any more."
                            },
                        )
                    }

                    if (state.isCustom) {
                        Spacer(Modifier.height(Space.s1))
                        WtText(
                            text = "Bands edited by hand. Choosing a preset or moving a " +
                                "slider above goes back to these controls.",
                            style = Type.caption,
                            color = p.textTertiary,
                        )
                    }

                    Spacer(Modifier.height(Space.s3))
                    Hairline()
                    Spacer(Modifier.height(Space.s2))

                    HeadroomNote(preampDb)

                    Spacer(Modifier.height(Space.s2))
                    Hairline()
                    Spacer(Modifier.height(Space.half))

                    Disclosure(
                        label = "Advanced",
                        expanded = advanced,
                        onToggle = { advanced = !advanced },
                        spokenLabel = if (advanced) {
                            "Advanced, showing ten frequency bands. Double tap to hide."
                        } else {
                            "Advanced. Ten individual frequency bands. Double tap to show."
                        },
                    )

                    if (advanced) {
                        Spacer(Modifier.height(Space.s1))
                        for (i in 0 until EqSettings.BAND_COUNT) {
                            val hz = EqSettings.CENTRES_HZ[i]
                            DbSlider(
                                label = bandLabel(hz),
                                value = state.gains()[i],
                                onChange = { onChange(state.withBandGain(i, it).copy(enabled = true)) },
                                spokenNoun = "The band at ${spokenHz(hz)}. " +
                                    "Swipe with one finger to adjust.",
                                min = EqSettings.MIN_GAIN_DB,
                                // The right-hand end IS the cap, so the knob
                                // cannot be dragged somewhere it will not stay.
                                max = SoundState.MAX_BOOST_DB,
                            )
                        }

                        Spacer(Modifier.height(Space.s1))
                        SecondaryButton(
                            label = "Back to presets",
                            onClick = { onChange(state.withoutCustom()) },
                            spokenLabel = "Discard the hand-edited bands and go back to " +
                                "the preset and the two sliders.",
                        )

                        Spacer(Modifier.height(Space.s3))

                        // The clamp counter lives here and nowhere else.
                        //
                        // On the main sheet it would be a number nobody can
                        // act on, next to a control they can: it only ever
                        // reads 0, and the one time it does not, the fix is
                        // the thing the headroom line already explains. Down
                        // here it is what it actually is -- an assertion that
                        // the preamp and the limiter are holding.
                        SectionHeader("Diagnostics")
                        DetailRow(
                            label = "Clipped samples",
                            value = status.clampedSamples.toString(),
                            spoken = if (status.clampedSamples == 0L) {
                                "Clipped samples: none. The preamp is holding."
                            } else {
                                "Clipped samples: ${status.clampedSamples} since connecting."
                            },
                        )
                        Spacer(Modifier.height(Space.half))
                        DetailRow(
                            label = "Filter resets",
                            value = status.eqResets.toString(),
                            spoken = if (status.eqResets == 0L) {
                                "Filter resets: none. The equalizer is stable."
                            } else {
                                "Filter resets: ${status.eqResets}. This should be zero."
                            },
                        )
                        Spacer(Modifier.height(Space.half))

                        // The cost of the stage, with and without the filters.
                        // The pair is the point: the difference is the price of
                        // the feature, and either number alone says little.
                        DetailRow(
                            label = "Mix with EQ",
                            value = micros(status.eqOnAvgNanos, status.eqOnWorstNanos),
                            spoken = "Mix with equalizer: average " +
                                "${status.eqOnAvgNanos} nanoseconds, worst " +
                                "${status.eqOnWorstNanos} nanoseconds, over " +
                                "${status.eqOnQuanta} quanta.",
                        )
                        Spacer(Modifier.height(Space.half))
                        DetailRow(
                            label = "Mix bypassed",
                            value = micros(status.eqOffAvgNanos, status.eqOffWorstNanos),
                            spoken = "Mix bypassed: average " +
                                "${status.eqOffAvgNanos} nanoseconds, worst " +
                                "${status.eqOffWorstNanos} nanoseconds, over " +
                                "${status.eqOffQuanta} quanta.",
                        )
                        Spacer(Modifier.height(Space.half))
                        DetailRow(
                            label = "Underruns",
                            value = status.underruns.toString(),
                            spoken = "Underruns: ${status.underruns}.",
                        )
                        Spacer(Modifier.height(Space.half))
                        DetailRow(
                            label = "Rate",
                            value = if (sampleRate > 0) "$sampleRate Hz" else "--",
                            spoken = if (sampleRate > 0) {
                                "Rate: output running at $sampleRate hertz."
                            } else {
                                "Rate: no output open yet."
                            },
                        )
                    }
                }

                Spacer(Modifier.height(Space.s3))
            }

            Column(Modifier.padding(horizontal = Space.s3).padding(bottom = Space.s2)) {
                PrimaryButton(
                    label = "Done",
                    onClick = onDismiss,
                    spokenLabel = "Done. Closes sound settings. " +
                        "Your choices are already applied and saved.",
                )
            }
        }
    }
}

/**
 * The headroom line, and the sentence that stops it being alarming.
 *
 * Phrased as what the app did and why, not as a warning. Nothing is wrong
 * when this reads -8.5 dB; that is the equalizer working. A warning tone here
 * would teach the user to avoid the feature.
 */
@Composable
private fun HeadroomNote(preampDb: Float) {
    val p = LocalPalette.current
    val amount = abs(preampDb)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = if (amount < 0.05f) {
                        "Headroom: no reduction. Nothing in this curve boosts, so the " +
                            "level is unchanged."
                    } else {
                        "Headroom: everything is turned down by " +
                            "${fmtHeadroom(amount)} decibels to make room for the boost."
                    }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WtText("Headroom", Type.subhead, p.textSecondary)
            WtText(
                text = if (amount < 0.05f) "none" else "-${fmtHeadroom(amount)} dB",
                style = Type.mono,
                color = if (amount < 0.05f) p.textTertiary else p.warn,
            )
        }

        Spacer(Modifier.height(Space.half))

        WtText(
            text = if (amount < 0.05f) {
                "Nothing here boosts, so the level is unchanged."
            } else {
                "Audio cannot go louder than full scale, so turning a band up means " +
                    "turning everything else down first. That is why a big boost makes " +
                    "the whole thing quieter -- the shape changed, not the loudness. " +
                    "Turn your volume up afterwards."
            },
            style = Type.caption,
            color = p.textTertiary,
        )
    }
}

/**
 * "41 / 180 us" -- average and worst, in microseconds.
 *
 * Nanoseconds are the right unit to record and the wrong one to read: five
 * digits of them side by side cannot be compared at a glance. The spoken
 * version keeps the nanoseconds, because that is the one a diagnostic dump
 * should be precise about.
 */
private fun micros(avgNanos: Long, worstNanos: Long): String =
    if (worstNanos == 0L) "--"
    else "${avgNanos / 1000} / ${worstNanos / 1000} us"

/** One decimal, and never "-0.0 dB". */
private fun fmtHeadroom(amount: Float): String = String.format("%.1f", amount)

/** "31 Hz", "1 kHz", "16 kHz". Short enough to sit in a slider label. */
private fun bandLabel(hz: Float): String =
    if (hz >= 1000f) "${(hz / 1000f).let { if (it % 1f == 0f) it.roundToInt().toString() else String.format("%.1f", it) }} kHz"
    else "${hz.roundToInt()} Hz"

/** The same, spelled out, because TalkBack reads "kHz" as "k h z". */
private fun spokenHz(hz: Float): String =
    if (hz >= 1000f) "${(hz / 1000f).roundToInt()} kilohertz" else "${hz.roundToInt()} hertz"

/** What the Sound row and the sheet header say in one phrase. */
fun summarise(state: SoundState): String = when {
    !state.enabled -> "Off"
    state.isCustom -> "Custom"
    else -> {
        val extras = buildList {
            if (abs(state.bassDb) >= 0.05f) add("bass ${formatDb(state.bassDb)}")
            if (abs(state.warmthDb) >= 0.05f) add("warmth ${formatDb(state.warmthDb)}")
        }
        if (extras.isEmpty()) state.preset.label
        else "${state.preset.label}, ${extras.joinToString(", ")}"
    }
}

/**
 * The same thing as a sentence, for the row that opens the sheet.
 *
 * Built separately rather than lowercasing [summarise], because that string
 * contains "dB" and "+4.0", which TalkBack reads as "d b" and "plus four
 * point zero" at best and silently drops the sign at worst.
 */
fun summariseSpoken(state: SoundState): String {
    val head = when {
        !state.enabled -> "Sound adjustment is off."
        state.isCustom -> "Sound adjustment is on, with bands edited by hand."
        else -> "Sound adjustment is on, ${state.preset.label} preset."
    }
    return "$head Opens presets, bass and warmth."
}

private fun presetSpoken(preset: Preset, state: SoundState): String {
    val what = when (preset) {
        Preset.Flat -> "no adjustment at all"
        Preset.Bass -> "more weight at the bottom"
        Preset.Vocal -> "speech brought forward, rumble removed"
        Preset.Bright -> "more detail at the top"
    }
    val selected = !state.isCustom && state.preset == preset
    return if (selected) "${preset.label} preset, selected. $what."
    else "${preset.label} preset. $what. Double tap to use it."
}
