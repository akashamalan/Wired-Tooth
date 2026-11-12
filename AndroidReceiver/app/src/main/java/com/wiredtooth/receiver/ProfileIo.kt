package com.wiredtooth.receiver

import android.content.Context
import android.net.Uri
import com.wiredtooth.receiver.dsp.ProfileParser

/**
 * Reading and writing profile files through the system picker.
 *
 * WHY THERE IS NO PERMISSION HERE. The Storage Access Framework hands back a
 * URI the user chose in the system picker, and the grant travels with that
 * URI. Android's own documentation is explicit that "because the user is
 * involved in selecting the files or directories that your app can access,
 * this mechanism doesn't require any system permissions". So the manifest
 * still asks for nothing but INTERNET and ACCESS_NETWORK_STATE, and an
 * earbud profile cannot become a reason to hold the user's whole filesystem.
 *
 * The grant is also not persisted. We read the file once, parse it, and keep
 * the parsed profile in our own preferences, so there is nothing to retain
 * access to afterwards and no stale permission sitting on the app.
 *
 * NOTHING LEAVES THE DEVICE. Both calls below are local file I/O. There is no
 * network code anywhere in this file and no profile is ever uploaded,
 * fetched, validated against a service or looked up remotely.
 */
internal object ProfileIo {

    /**
     * The outcome of reading a file the user picked.
     *
     * Separate from the parse result because the two failures are different
     * in kind and need different words: a file we could not read at all is
     * not the same as a file we read and found nothing in.
     */
    sealed interface ReadResult {
        data class Parsed(val profile: ProfileParser.Profile) : ReadResult
        data class Failed(val message: String) : ReadResult
    }

    /**
     * Reads at most [ProfileParser.MAX_BYTES] + 1 bytes and parses them.
     *
     * The extra byte is the whole trick: reading one past the ceiling is how
     * "too large" is detected without ever holding an oversized file in
     * memory. Call this off the main thread -- the URI may point at a cloud
     * provider, where opening the stream is a network round trip inside the
     * provider's own process and can block for seconds.
     */
    fun read(context: Context, uri: Uri): ReadResult {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { ProfileParser.readLimited(it) }
        } catch (t: Throwable) {
            // Any provider can fail in any way: a revoked grant, a file
            // deleted between picking and opening, a cloud provider that is
            // offline. None of them should look like a crash.
            null
        } ?: return ReadResult.Failed("Luma could not read that file.")

        if (bytes.isEmpty()) return ReadResult.Failed("That file is empty.")

        val profile = ProfileParser.parse(bytes)
        if (profile.rejected) {
            return ReadResult.Failed(
                profile.warnings.firstOrNull()
                    ?: "That file is too big to be a profile.",
            )
        }
        if (!looksLikeText(bytes)) {
            return ReadResult.Failed("That does not look like a text file.")
        }
        return ReadResult.Parsed(profile)
    }

    /**
     * Writes a profile to a URI the user chose, in our own format.
     *
     * Returns false on any failure, which the caller reports rather than
     * swallowing: a silent export is worse than a failed one, because the
     * user walks away believing they have a backup.
     */
    fun write(context: Context, uri: Uri, profile: ProfileParser.Profile): Boolean = try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(ProfileParser.export(profile).toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } ?: false
    } catch (t: Throwable) {
        false
    }

    /**
     * Whether these bytes are plausibly text a person typed.
     *
     * Only consulted when the parse found nothing, to tell "this is a JPEG"
     * apart from "this is a text file with no filters in it" -- two cases
     * that deserve different sentences. A NUL byte is the giveaway: no text
     * encoding this reads produces one, and every binary format is full of
     * them.
     */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 2048)
        var controls = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false
            // Tab, newline and carriage return are the only control codes a
            // text file has any business containing.
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) controls++
        }
        return controls * 20 < n || controls == 0
    }

    /**
     * A file name safe to suggest to the picker.
     *
     * Letters, digits, dash and space only, so there is nothing in it that
     * could be read as a path separator, a parent-directory hop or a shell
     * character by whatever the user exports into. The profile name comes
     * from a file we did not write, which makes it exactly the kind of string
     * that should not be handed to another program unexamined.
     */
    fun suggestedFileName(profile: ProfileParser.Profile): String {
        val sb = StringBuilder(40)
        for (ch in profile.name) {
            if (sb.length >= 40) break
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == '-' || ch == ' ' -> sb.append(ch)
                else -> {}
            }
        }
        val cleaned = sb.toString().trim().ifEmpty { "earbud profile" }
        return "$cleaned.luma.txt"
    }
}
