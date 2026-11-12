package com.wiredtooth.receiver

import android.content.Context
import com.wiredtooth.receiver.dsp.EqSettings
import com.wiredtooth.receiver.dsp.Preset
import com.wiredtooth.receiver.dsp.ProfileParser
import com.wiredtooth.receiver.dsp.SoundState

/**
 * The sound settings, across launches.
 *
 * Same shape as SavedStore: SharedPreferences, its own file, loaded once into
 * composition state and written on every change. There is no database here
 * because there is nothing to query -- this is five values.
 *
 * WHY THE CHOICE IS STORED AND NOT THE CURVE. [SoundState] holds the preset
 * and the two macros; the ten band gains are derived from them. Storing the
 * derived curve would mean the sheet could not redraw its own macro sliders
 * after a restart, so it stores the inputs and recomputes. The hand-edited
 * curve is the one exception, because by definition it has no inputs to
 * recompute from, so it is written out band by band.
 *
 * Every read is defensive. Preferences survive app upgrades, so a value
 * written by an older build can arrive in a shape this build does not expect,
 * and the right answer to that is always a default rather than a crash on
 * launch.
 */
internal object SoundStore {
    private const val FILE = "sound"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_BASS = "bass"
    private const val KEY_WARMTH = "warmth"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_PROFILE = "profile"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): SoundState {
        val p = prefs(c)
        val custom = p.getString(KEY_CUSTOM, "")
            ?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull() }
            ?.takeIf { it.size == EqSettings.BAND_COUNT }

        // Stored as text in our own format and re-parsed, not as a band list.
        // The parser is the one thing that has already been proved to survive
        // arbitrary input, so routing a stored value back through it means a
        // preference file corrupted by anything at all degrades to "no
        // profile" instead of to a crash on launch.
        val profile = p.getString(KEY_PROFILE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { ProfileParser.parse(it) }
            ?.takeIf { !it.isEmpty }

        return SoundState(
            enabled = p.getBoolean(KEY_ENABLED, false),
            preset = Preset.byName(p.getString(KEY_PRESET, null)),
            bassDb = p.getFloat(KEY_BASS, 0f)
                .coerceIn(-SoundState.MACRO_LIMIT_DB, SoundState.MACRO_LIMIT_DB),
            warmthDb = p.getFloat(KEY_WARMTH, 0f)
                .coerceIn(-SoundState.MACRO_LIMIT_DB, SoundState.MACRO_LIMIT_DB),
            custom = custom,
            profile = profile,
        )
    }

    fun save(c: Context, s: SoundState) {
        prefs(c).edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putString(KEY_PRESET, s.preset.name)
            .putFloat(KEY_BASS, s.bassDb)
            .putFloat(KEY_WARMTH, s.warmthDb)
            .putString(KEY_CUSTOM, s.custom?.joinToString(",") ?: "")
            // Written in the canonical export form rather than the bytes the
            // user picked: it is a tenth of the size, already clamped to this
            // engine's limits, and round-tripping through the writer on every
            // save is what keeps the writer honest.
            .putString(KEY_PROFILE, s.profile?.let { ProfileParser.export(it) } ?: "")
            .apply()
    }
}
