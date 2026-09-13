package com.wiredtooth.receiver.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for Wired Tooth.
 *
 * Every number here is derived from a rule, not chosen by eye. The rules come
 * from Apple's design guidance: an 8pt spatial grid, size-specific tracking
 * and leading, springs described by damping and response rather than
 * duration, and translucency used sparingly as a hierarchy signal rather
 * than decoration.
 */

// ---------------------------------------------------------------- spacing

/**
 * 8pt grid, with a 4pt half-step for optical corrections only (icon to label,
 * never structural). Layout that cannot be expressed on this grid is a sign
 * the hierarchy is wrong, not that the grid needs another value.
 */
object Space {
    val half = 4.dp     // optical only
    val s1 = 8.dp       // inside a chip, label to value
    val s2 = 16.dp      // card padding, list item gutter
    val s3 = 24.dp      // between grouped elements
    val s4 = 32.dp      // between sections
    val s6 = 48.dp      // screen top padding, around the hero
    val s8 = 64.dp      // reserved for the Now Playing hero only
}

/**
 * Corner radii. Three values, each tied to a surface class, so radius alone
 * tells you what kind of thing you are looking at.
 */
object Radius {
    val button = 10.dp
    val card = 12.dp
    val sheet = 20.dp
    val pill = 999.dp   // status chips only
}

/** Minimum 48dp touch target, per Android accessibility guidance. */
object Hit {
    val min = 48.dp
}

// ----------------------------------------------------------------- colour

/**
 * Dark first: this is an audio app used at night, on a phone, often with the
 * lights off. Light mode is supported but is the secondary target.
 *
 * Backgrounds are near-black rather than pure black. Pure black against OLED
 * makes the translucent status panel's edge disappear entirely, and the
 * material needs something to sit on to read as a material.
 */
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val glassTint: Color,
    val glassBorder: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val isDark: Boolean,
)

val DarkPalette = Palette(
    background = Color(0xFF0B0B0F),
    surface = Color(0xFF15151B),
    surfaceRaised = Color(0xFF1E1E26),
    // The status panel is the ONLY translucent surface in the app. A low
    // alpha over a dark base plus blur reads as glass; higher alpha reads as
    // plastic.
    glassTint = Color(0x1FFFFFFF),
    // A brighter top edge is light catching the material. Without it a blurred
    // panel looks like a smudge rather than a pane.
    glassBorder = Color(0x24FFFFFF),
    hairline = Color(0x14FFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF9E9EA8),
    textTertiary = Color(0xFF6B6B76),
    accent = Color(0xFF3B9EFF),
    good = Color(0xFF32D673),
    warn = Color(0xFFFFB340),
    bad = Color(0xFFFF5F52),
    isDark = true,
)

val LightPalette = Palette(
    background = Color(0xFFF2F2F6),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    glassTint = Color(0x99FFFFFF),
    glassBorder = Color(0x33FFFFFF),
    hairline = Color(0x14000000),
    textPrimary = Color(0xFF0B0B0F),
    textSecondary = Color(0xFF5C5C66),
    textTertiary = Color(0xFF8E8E96),
    accent = Color(0xFF0A7CFF),
    good = Color(0xFF1DA85A),
    warn = Color(0xFFC77700),
    bad = Color(0xFFD93B30),
    isDark = false,
)

// ------------------------------------------------------------- typography

/**
 * The system font, deliberately. On Android that is Roboto; on iOS it would
 * be SF Pro. Both ship optical sizing, tracking tables and legibility tuning
 * that a bundled webfont would throw away. Overriding the platform face needs
 * a reason, and "it looks more designed" is not one.
 *
 * Two rules run through this ramp:
 *
 *   Tracking is size-specific. Large text reads too loose as it grows, so it
 *   tightens; small text needs a little air to stay legible, so it opens up.
 *   One letter-spacing value for every size is wrong somewhere.
 *
 *   Leading moves inversely to size. Tight on the hero, generous on body.
 *
 * Sizes are in sp, so they scale with the user's font-size setting. Layout
 * spacing that surrounds text is in dp on the 8pt grid, and the components
 * are built to grow rather than clip when type scales up.
 */
object Type {
    /** 56sp. The connection state on Now Playing, and nothing else. */
    val hero = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 56.sp,
        lineHeight = 58.sp,          // 1.04 -- tight, large text needs no air
        letterSpacing = (-1.2).sp,   // ~-0.021em
        fontWeight = FontWeight.Bold,
    )

    /** 34sp. Screen titles. */
    val title = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,          // 1.18
        letterSpacing = (-0.6).sp,   // ~-0.018em
        fontWeight = FontWeight.Bold,
    )

    /** 22sp. Section headings, device name on a card. */
    val heading = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,          // 1.27
        letterSpacing = (-0.2).sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 28sp. A single statistic's value. Tabular so digits do not jitter. */
    val statValue = TextStyle(
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        fontWeight = FontWeight.Medium,
    )

    /** 17sp. Body, and the iOS default body size. */
    val body = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,          // 1.41 -- generous, this is read not scanned
        letterSpacing = 0.sp,        // body sits at zero
        fontWeight = FontWeight.Normal,
    )

    /** 17sp semibold. Button labels. */
    val button = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 15sp. Supporting copy under a heading. */
    val subhead = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 13sp. Stat labels, timestamps. Positive tracking for legibility. */
    val caption = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,          // 1.38
        letterSpacing = 0.3.sp,      // small text opens up
        fontWeight = FontWeight.Medium,
    )

    /** 12sp, wide. The one all-caps style: group headers only. */
    val overline = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.9.sp,      // caps need the most tracking of all
        fontWeight = FontWeight.SemiBold,
    )

    /** 15sp monospace. IP addresses only -- they are read digit by digit. */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    )
}

// ----------------------------------------------------------------- motion

/**
 * Springs, not durations.
 *
 * A fixed-duration animation cannot respond to new input: it has already
 * decided where it is going and when it will arrive. A spring re-targets from
 * wherever it currently is, carrying its velocity, which is what makes an
 * interface interruptible.
 *
 * Apple describes springs as damping plus response rather than mass,
 * stiffness and damping. Compose wants dampingRatio and stiffness, so the
 * stiffness values below are derived: stiffness = (2*PI / response)^2.
 *
 *   response 0.30s -> ~438
 *   response 0.40s -> ~247
 *
 * Damping 1.0 (no overshoot) is the default for everything. Bounce is
 * reserved for motion that followed a real gesture -- a flick or a drag
 * release. Overshoot on a panel that merely appeared feels wrong; overshoot
 * on a card you threw feels right.
 */
object Motion {
    const val RESPONSE_FAST = 438f    // 0.30s
    const val RESPONSE_STANDARD = 247f // 0.40s

    /** Default. Critically damped, settles without overshoot. */
    fun <T> standard(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = RESPONSE_STANDARD,
    )

    /** Snappier, still no overshoot. Press states, small reveals. */
    fun <T> fast(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = RESPONSE_FAST,
    )

    /** Momentum only: something the user threw, or a sheet they dragged. */
    fun <T> momentum(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,   // ~0.75
        stiffness = RESPONSE_FAST,
    )

    /**
     * The reduced-motion substitute. Not "no feedback" -- a short cross-fade,
     * which still communicates the change without vestibular motion.
     */
    fun <T> reduced(): FiniteAnimationSpec<T> = tween(durationMillis = 180)

    /** Staggered reveal step for device cards. 40ms reads as a cascade. */
    const val STAGGER_MS = 40
}

/**
 * Accessibility preferences, read once and provided to the tree.
 *
 * Android has no `prefers-reduced-motion` media query. The real signal is the
 * developer-options / accessibility animation scale: when the user sets
 * animations off, ANIMATOR_DURATION_SCALE is 0. Honouring that is the correct
 * platform behaviour, and it is what "Remove animations" in Accessibility
 * settings actually sets.
 */
data class A11yPrefs(
    val reduceMotion: Boolean,
    val reduceTransparency: Boolean,
)

@Composable
fun rememberA11yPrefs(): A11yPrefs {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)

        val reduceMotion = scale == 0f

        // Blur needs RenderEffect (API 31). Below that there is no real
        // translucency to reduce, so the same flag also stands in for
        // "this device cannot draw glass" -- the panel falls back to a solid
        // surface either way, which is the correct reduced-transparency
        // behaviour as well.
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        A11yPrefs(
            reduceMotion = reduceMotion,
            reduceTransparency = !canBlur || reduceMotion,
        )
    }
}

val LocalPalette: ProvidableCompositionLocal<Palette> = staticCompositionLocalOf { DarkPalette }
val LocalA11y: ProvidableCompositionLocal<A11yPrefs> =
    compositionLocalOf { A11yPrefs(reduceMotion = false, reduceTransparency = false) }

/** Picks the right spec for the current accessibility preference. */
@Composable
fun <T> motionStandard(): FiniteAnimationSpec<T> =
    if (LocalA11y.current.reduceMotion) Motion.reduced() else Motion.standard()

@Composable
fun <T> motionFast(): FiniteAnimationSpec<T> =
    if (LocalA11y.current.reduceMotion) Motion.reduced() else Motion.fast()

@Composable
fun <T> motionMomentum(): FiniteAnimationSpec<T> =
    if (LocalA11y.current.reduceMotion) Motion.reduced() else Motion.momentum()

/** Centre-aligned variant used by the hero and empty states. */
val TextAlignCenter = TextAlign.Center
