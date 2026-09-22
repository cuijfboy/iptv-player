package ilab.iptv.player.core.model

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A half-open instant range `[fromMs, toMs]` — the shape of every EPG question about time. */
data class EpgTimeWindow(val fromMs: Long, val toMs: Long) {

    /** Does a programme that runs `[startMs, stopMs]` overlap this window? Same predicate the DAO uses. */
    fun overlaps(startMs: Long, stopMs: Long): Boolean = stopMs >= fromMs && startMs <= toMs
}

/**
 * **The one definition of "the window the EPG grid shows".**
 *
 * BUG-20260922-018 was a口径 split: the run counted "this channel has programmes" over the *retention*
 * window `[now−6h, now+48h]` (`ProgrammeWindows`, docs/02 §5.1) while the grid only draws the six
 * hours it opens on — so a channel could be reported as covered and still render an empty row
 * (`EPG_MATCH_HIT{programmesInWindow=84}` over a blank grid). The fix is this object: the grid's
 * opening window, the coverage question "does this binding have something to show?", the binding pick
 * and the trigger gate all derive their window from here, so the number and the picture cannot drift
 * apart again.
 *
 * **Why the opening window and not the 24 h the grid can scroll to.** "覆盖率" is a statement about
 * what a viewer sees when they open the guide, and that is the opening window; the retention window
 * stays what it always was (how much guide the database keeps), which is why [ProgrammeWindows] is
 * still the pruning rule and this is not.
 *
 * **Where it must not be used.** The retention/prune path and the now/next lookup are not grid
 * questions: pruning still keeps `[now−6h, now+48h]`, and `nowNext` still answers for the instant it
 * is asked about.
 */
object EpgGridWindow {

    /** How much time the grid draws when it opens: six hours (docs/02 §8.3, the S3 prototype's span). */
    const val SPAN_MS: Long = 6L * 60L * 60L * 1000L

    /** How much history the opening window keeps to the left of "now": half an hour. */
    const val HISTORY_MS: Long = 30L * 60L * 1000L

    /** The ruler step the window start is aligned down to, in minutes (`TimeAxis.stepMinutes`). */
    const val RULER_STEP_MINUTES: Int = 30

    /**
     * The window a grid opened at [nowMs] draws: `[floor(now − 30 min), +6 h)`, the start aligned down
     * to a ruler step so the leftmost tick is a real tick (docs/02 §8.3).
     */
    fun of(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): EpgTimeWindow {
        val from = floorToStep(nowMs - HISTORY_MS, RULER_STEP_MINUTES, zone)
        return EpgTimeWindow(from, from + SPAN_MS)
    }

    /**
     * The largest step instant ≤ [atMs], in [zone]. Lives here — not in the grid — because the coverage
     * window and the ruler must align identically; `TimeAxis.floorToStep` delegates to it, so there is
     * one implementation for both. `Calendar` rather than `java.time`: `minSdk 21` with no core-library
     * desugaring (the same call `XmltvTime` and `TimeAxis` record).
     */
    fun floorToStep(
        atMs: Long,
        stepMinutes: Int = RULER_STEP_MINUTES,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        require(stepMinutes > 0) { "stepMinutes must be positive, was $stepMinutes" }
        val calendar = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = atMs }
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val minute = calendar.get(Calendar.MINUTE)
        calendar.set(Calendar.MINUTE, minute - Math.floorMod(minute, stepMinutes))
        return calendar.timeInMillis
    }
}
