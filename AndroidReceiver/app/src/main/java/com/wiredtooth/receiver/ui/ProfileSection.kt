package com.wiredtooth.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.wiredtooth.receiver.dsp.GainBudget
import com.wiredtooth.receiver.dsp.ProfileParser
import com.wiredtooth.receiver.dsp.SoundState
import kotlin.math.roundToInt

/**
 * The earbud-correction half of the Sound sheet: the three hints on the main
 * surface, the controls inside Advanced, and the preview that stands between
 * a file and the sound.
 *
 * WHY A PREVIEW AT ALL. Importing is the one action in this app that takes an
 * instruction from outside and applies it to what you are listening to. A
 * file can be truncated, be the wrong file, or ask for things this engine
 * will quietly clamp, and all three look identical until you hear them. So
 * the parse result is shown first -- the name, the count, and every warning
 * -- and nothing reaches the engine until the user says so. Cancel is always
 * the cheaper mistake.
 */

// ---------------------------------------------------------------- the hints

/**
 * What the sheet says when something was limited, and nothing when it was not.
 *
 * Three separate sentences rather than one combined one, because they have
 * three different causes and three different remedies: a single filter asked
 * for too much, the whole profile asked for too much, or the macros were the
 * ones that had to give way. Collapsing them into "some limits were applied"
 * would be shorter and useless.
 *
 * Plain text in the secondary colour, not a warning tone. None of these is a
 * fault -- they are the app doing the arithmetic the user cannot -- and
 * colouring them as problems would make a working feature look broken.
 */
@Composable
fun ProfileHints(state: SoundState, layered: GainBudget.Layered) {
    val p = LocalPalette.current
    val lines = buildList {
        if (state.cappedBandCount() > 0 || layered.cappedFilterCount > 0) {
            add("Boost limited to keep the sound clean")
        }
        if (layered.profileReduced) add("Profile strength reduced to keep the sound clean")
        if (layered.macrosReduced) add("Bass and Warmth limited by your profile")
    }
    if (lines.isEmpty()) return

    Spacer(Modifier.height(Space.s1))
    Column {
        for (line in lines) {
            WtText(line, Type.caption, p.textSecondary)
        }
    }
}

// --------------------------------------------------- the Advanced section

/**
 * The controls, inside Advanced.
 *
 * Deliberately not on the main sheet. Almost nobody will ever load a profile;
 * everybody will open this sheet to turn the bass up. Putting four buttons
 * and a file picker in front of that would make the common case worse to
 * serve the rare one.
 */
@Composable
fun EarbudCorrection(
    profile: ProfileParser.Profile?,
    layered: GainBudget.Layered,
    onImportFile: () -> Unit,
    onPasteText: (String) -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalPalette.current
    var pasteOpen by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    SectionHeader("Earbud correction")

    WtText(
        text = if (profile == null) {
            "A correction made for one pair of earbuds. It becomes the base of " +
                "the curve, and Bass and Warmth still work on top of it."
        } else {
            "The base of the curve. Bass and Warmth work on top of it."
        },
        style = Type.caption,
        color = p.textTertiary,
    )
    Spacer(Modifier.height(Space.s2))

    DetailRow(
        label = "Profile",
        value = profile?.name?.ifEmpty { "Loaded" } ?: "None",
        spoken = if (profile == null) {
            "No earbud profile is loaded."
        } else {
            val named = profile.name.ifEmpty { "an unnamed profile" }
            "Earbud profile $named is loaded, with ${profile.filters.size} filters."
        },
    )
    if (profile != null) {
        Spacer(Modifier.height(Space.half))
        DetailRow(
            label = "Filters",
            value = "${profile.filters.size}",
            spoken = "${profile.filters.size} filters in the profile.",
        )
        if (layered.profileReduced) {
            Spacer(Modifier.height(Space.half))
            DetailRow(
                label = "Profile strength",
                value = "${(layered.profileScale * 100).roundToInt()}%",
                spoken = "Profile strength is " +
                    "${(layered.profileScale * 100).roundToInt()} percent, " +
                    "reduced to keep the sound clean.",
            )
        }
        if (layered.macrosReduced) {
            Spacer(Modifier.height(Space.half))
            DetailRow(
                label = "Bass and Warmth",
                value = "${(layered.macroScale * 100).roundToInt()}%",
                spoken = "Bass and Warmth are at " +
                    "${(layered.macroScale * 100).roundToInt()} percent, " +
                    "limited by the profile.",
            )
        }
        for (note in profileNotes(profile, layered)) {
            Spacer(Modifier.height(Space.half))
            WtText(note, Type.caption, p.textTertiary)
        }
    }

    Spacer(Modifier.height(Space.s2))

    SecondaryButton(
        label = "Import file",
        onClick = onImportFile,
        spokenLabel = "Import an earbud profile from a file. Opens the system file picker.",
    )
    Spacer(Modifier.height(Space.s1))

    Disclosure(
        label = "Paste text",
        expanded = pasteOpen,
        onToggle = { pasteOpen = !pasteOpen },
        spokenLabel = if (pasteOpen) {
            "Paste profile text, open. Double tap to hide the text box."
        } else {
            "Paste profile text. Double tap to show a text box."
        },
    )
    if (pasteOpen) {
        Spacer(Modifier.height(Space.s1))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 220.dp)
                .clip(RoundedCornerShape(Radius.button))
                .background(p.surface)
                .border(1.dp, p.hairline, RoundedCornerShape(Radius.button))
                .padding(Space.s2),
        ) {
            if (pasted.isEmpty()) {
                WtText("peak 2800 1.2 3.5", Type.mono, p.textTertiary)
            }
            BasicTextField(
                value = pasted,
                // Capped at the same ceiling the file reader uses, so a
                // pasted profile and an imported one cannot disagree about
                // what is too big.
                onValueChange = {
                    pasted = if (it.length > ProfileParser.MAX_BYTES) {
                        it.substring(0, ProfileParser.MAX_BYTES)
                    } else {
                        it
                    }
                },
                textStyle = Type.mono.copy(color = p.textPrimary),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .semantics {
                        contentDescription = "Profile text. Paste the contents of a " +
                            "profile file here."
                    },
            )
        }
        Spacer(Modifier.height(Space.s1))
        SecondaryButton(
            label = "Read this text",
            onClick = { if (pasted.isNotBlank()) onPasteText(pasted) },
            modifier = Modifier.alpha(if (pasted.isNotBlank()) 1f else 0.4f),
            spokenLabel = if (pasted.isNotBlank()) {
                "Read the pasted text as a profile. Shows a preview before anything changes."
            } else {
                "Read this text. Unavailable until you paste something."
            },
        )
    }

    if (profile != null) {
        Spacer(Modifier.height(Space.s1))
        SecondaryButton(
            label = "Export",
            onClick = onExport,
            spokenLabel = "Export the loaded profile to a file. Opens the system file picker.",
        )
        Spacer(Modifier.height(Space.s1))
        SecondaryButton(
            label = "Remove profile",
            onClick = onRemove,
            destructive = true,
            spokenLabel = "Remove the earbud profile. Asks you to confirm first.",
        )
    }
}

/**
 * The things worth saying about a loaded profile that are not numbers.
 *
 * Kept as full sentences rather than counters with labels, because each one
 * is a fact about the file rather than a property of the sound, and a reader
 * who sees "Skipped 2" in a table has to guess what was skipped and why.
 */
private fun profileNotes(
    profile: ProfileParser.Profile,
    layered: GainBudget.Layered,
): List<String> = buildList {
    if (profile.droppedOverLimit > 0) {
        add(
            "Using the first ${profile.filters.size} of " +
                "${profile.filters.size + profile.droppedOverLimit} filters in the file.",
        )
    }
    if (profile.skippedUnsupported > 0) {
        add(
            "${profile.skippedUnsupported} filter" +
                (if (profile.skippedUnsupported == 1) "" else "s") +
                " skipped: unsupported type.",
        )
    }
    if (profile.clampedValues > 0) {
        add(
            "${profile.clampedValues} value" +
                (if (profile.clampedValues == 1) "" else "s") +
                " brought inside Luma's limits.",
        )
    }
    if (layered.cappedFilterCount > 0) {
        add(
            "${layered.cappedFilterCount} filter" +
                (if (layered.cappedFilterCount == 1) "" else "s") +
                " asked for more than 8 dB of boost.",
        )
    }
    if (profile.warnings.any { it.contains("preamp", ignoreCase = true) }) {
        add("Preamp line ignored: Luma sets its own.")
    }
}

// -------------------------------------------------------------- the preview

/**
 * What a parse produced, waiting for a decision.
 *
 * [error] and [profile] are mutually exclusive: either there is something to
 * apply or there is a sentence explaining why there is not.
 */
data class ProfilePreview(
    val profile: ProfileParser.Profile? = null,
    val error: String? = null,
    val source: String = "",
)

/**
 * The preview sheet.
 *
 * An opaque surface over a scrim, not a second glass panel: exactly one
 * translucent surface exists per screen, and that one is the Sound sheet
 * underneath. Stacking a second material over it would collapse the
 * hierarchy that the translucency exists to signal.
 */
@Composable
fun ProfilePreviewSheet(
    preview: ProfilePreview,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val a11y = LocalA11y.current
    var warningsOpen by remember { mutableStateOf(false) }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = motionMomentum(),
        label = "profilepreview",
    )

    val pr = preview.profile
    val usable = pr != null && !pr.isEmpty

    // Dim to focus: deciding whether to change what you are hearing is a
    // modal task, so everything behind it goes back a layer.
    Box(
        Modifier
            .fillMaxSize()
            .alpha(progress.coerceIn(0f, 1f))
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .semantics { contentDescription = "Close without applying" },
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    // offset, NOT padding. motionMomentum overshoots past 1.0
                    // and a negative padding throws; the same crash that took
                    // the app down the first time the Sound sheet opened on a
                    // device. Alpha is clamped for the same reason.
                    if (a11y.reduceMotion) Modifier.alpha(progress.coerceIn(0f, 1f))
                    else Modifier
                        .offset(y = ((1f - progress) * 40).dp)
                        .alpha(progress.coerceIn(0f, 1f)),
                )
                .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                .background(p.surfaceRaised),
        ) {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(Space.s3),
            ) {
                WtText(
                    text = if (usable) "Use this profile?" else "Cannot use that file",
                    style = Type.heading,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(Space.s1))

                if (usable && pr != null) {
                    WtText(pr.name.ifEmpty { "Unnamed profile" }, Type.body, p.textPrimary)
                    WtText(
                        text = "${pr.filters.size} filter" +
                            (if (pr.filters.size == 1) "" else "s") +
                            if (pr.author.isNotEmpty()) " from ${pr.author}" else "",
                        style = Type.subhead,
                        color = p.textSecondary,
                    )
                    if (pr.notes.isNotEmpty()) {
                        Spacer(Modifier.height(Space.half))
                        WtText(pr.notes, Type.caption, p.textTertiary)
                    }
                    Spacer(Modifier.height(Space.s2))
                    WtText(
                        "Nothing changes until you apply this.",
                        Type.caption,
                        p.textTertiary,
                    )

                    if (pr.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(Space.s2))
                        Disclosure(
                            label = "${pr.warnings.size} note" +
                                (if (pr.warnings.size == 1) "" else "s"),
                            expanded = warningsOpen,
                            onToggle = { warningsOpen = !warningsOpen },
                            spokenLabel = if (warningsOpen) {
                                "${pr.warnings.size} notes about this file, showing. " +
                                    "Double tap to hide them."
                            } else {
                                "${pr.warnings.size} notes about this file. " +
                                    "Double tap to read them."
                            },
                        )
                        if (warningsOpen) {
                            Spacer(Modifier.height(Space.half))
                            Column {
                                for (w in pr.warnings) {
                                    WtText(w, Type.caption, p.textSecondary)
                                }
                            }
                        }
                    }
                } else {
                    WtText(
                        text = preview.error ?: "Nothing usable in this file",
                        style = Type.body,
                        color = p.textSecondary,
                    )
                    Spacer(Modifier.height(Space.s1))
                    WtText(
                        "Your sound settings have not changed.",
                        Type.caption,
                        p.textTertiary,
                    )
                }

                Spacer(Modifier.height(Space.s3))

                if (usable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(
                                label = "Cancel",
                                onClick = onDismiss,
                                spokenLabel = "Cancel. Leaves your sound settings as they are.",
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(
                                label = "Apply",
                                onClick = onApply,
                                spokenLabel = "Apply this profile as the base of the curve.",
                            )
                        }
                    }
                } else {
                    SecondaryButton(
                        label = "Close",
                        onClick = onDismiss,
                        spokenLabel = "Close. Your sound settings have not changed.",
                    )
                }
                Spacer(Modifier.height(Space.s2))
            }
        }
    }
}
