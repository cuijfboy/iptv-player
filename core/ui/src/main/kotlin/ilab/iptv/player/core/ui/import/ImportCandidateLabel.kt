package ilab.iptv.player.core.ui.import

import ilab.iptv.player.core.domain.playlist.ImportCandidate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How one pick-able file is spelled in the import dialog.
 *
 * Pure and separate from the activity so the two things that are easy to get wrong — a size a human
 * can read and a listing that tells two same-named files apart — are pinned by a unit test instead of
 * by squinting at a TV screenshot.
 *
 * Lives in `:core:ui` (not inside one feature) because **both** import entrances list files: the
 * browse screen and the source-management screen. One format means QA sees the same row text
 * whichever door they came through.
 *
 * `java.text` rather than `java.time`: the project's minSdk is 21 and there is no core-library
 * desugaring configured, so `DateTimeFormatter`/`Instant` are API-26 calls that lint (correctly)
 * fails the build on.
 */
object ImportCandidateLabel {

    /** `list.m3u · 12.3 KB · 09-22 08:15` */
    fun describe(candidate: ImportCandidate, zone: TimeZone = TimeZone.getDefault()): String {
        val modified = stamp(zone).format(Date(candidate.modifiedAtMs))
        return "${candidate.name} · ${size(candidate.sizeBytes)} · $modified"
    }

    /** Per call: `SimpleDateFormat` is not thread-safe, and this is not on a hot path. */
    private fun stamp(zone: TimeZone): SimpleDateFormat =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).apply { timeZone = zone }

    /** Binary units: a 1 MB playlist is 1024 KB, which is what a file manager shows too. */
    fun size(bytes: Long): String = when {
        bytes >= MEGABYTE -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / MEGABYTE)
        bytes >= KILOBYTE -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / KILOBYTE)
        else -> "$bytes B"
    }

    private const val KILOBYTE = 1024.0
    private const val MEGABYTE = 1024.0 * 1024.0
}
