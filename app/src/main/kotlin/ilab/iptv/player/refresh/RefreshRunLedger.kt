package ilab.iptv.player.refresh

import android.content.Context

/**
 * "Is there a refresh run that started and never finished?" — one boolean, written by the worker and
 * read at enqueue time (card NEW-004, docs/05-过程记录/67).
 *
 * WHY THIS EXISTS AT ALL: `NEW-20260922-004` is "a manual refresh that the process death interrupted
 * comes back as a WorkManager backoff (~30 min), and the user's re-trigger is swallowed by `KEEP`".
 * The fix has to tell *this* case apart from a run that ended by itself, and WorkManager cannot:
 *
 * | the last run ended because … | WorkManager state | `runAttemptCount` |
 * |---|---|---|
 * | the worker returned `retry()` (a real failure) | ENQUEUED + backoff | ≥ 1 |
 * | the process was killed mid-run | ENQUEUED + backoff | ≥ 1 |
 *
 * The two rows are identical, so the signal has to come from us: [RefreshWorker] marks the run as in
 * flight *before* it does any work and clears the mark when it concludes. A process death never runs
 * the clearing half, so "mark still set" means exactly "the last run was killed".
 *
 * IT IS NOT A LOCK. Nothing reads it to decide whether a run *may* start — that is WorkManager's
 * unique-work name. It only decides whether a re-trigger should `REPLACE` the queued job instead of
 * leaving it in its backoff.
 */
interface RefreshRunLedger {

    /** The worker is starting; must survive a process kill, so the write is synchronous. */
    fun markRunStarted()

    /** The worker concluded (success, retry, give-up or cancellation): clear the mark. */
    fun markRunConcluded()

    /** `true` when a run began and never concluded — i.e. the last run was interrupted. */
    fun isRunUnconcluded(): Boolean
}

/**
 * Runs one refresh run with the mark set for exactly as long as it lasts.
 *
 * The `finally` is the whole point and is why this is one small function instead of two calls inside
 * [RefreshWorker]: **every** way a run can end — success, `retry()`, give-up, and the
 * `CancellationException` the wizard's 中断 button (or a `REPLACE`, or a dropped constraint) throws
 * into it — still clears the mark. Only the one ending that cannot run any code, the process death,
 * leaves it set, which is exactly the state [RefreshReclaim] reads. A stale mark would be "loop fuel":
 * every later trigger would see an interruption that is not there and `REPLACE` a job that did nothing
 * wrong.
 */
internal suspend fun <T> recordingRefreshRun(ledger: RefreshRunLedger, block: suspend () -> T): T {
    ledger.markRunStarted()
    return try {
        block()
    } finally {
        ledger.markRunConcluded()
    }
}

/**
 * The production store: one boolean in a one-file `SharedPreferences`, the same trade
 * `SharedPrefsFirstRunStore` and `SharedPrefsOverscanStore` already make for a single flag (no Room
 * schema change, no DataStore round trip).
 *
 * `commit()` rather than `apply()` **on both writes**, which is the opposite of those two stores and
 * is the point here:
 * - [markRunStarted] must be on disk *before* the run starts; a kill in the next millisecond is
 *   exactly the event this flag exists to record, and `apply()`'s asynchronous write can lose it;
 * - [markRunConcluded] is a disk write on the worker's own thread, once per run, and a stale `true`
 *   would silently turn a later ordinary re-trigger into a `REPLACE`.
 */
class SharedPrefsRefreshRunLedger(context: Context) : RefreshRunLedger {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun markRunStarted() {
        prefs.edit().putBoolean(KEY_UNCONCLUDED, true).commit()
    }

    override fun markRunConcluded() {
        prefs.edit().putBoolean(KEY_UNCONCLUDED, false).commit()
    }

    override fun isRunUnconcluded(): Boolean = prefs.getBoolean(KEY_UNCONCLUDED, false)

    companion object {

        /** The file name and key are read back by the Robolectric round-trip test. */
        const val FILE_NAME: String = "refresh-run"
        const val KEY_UNCONCLUDED: String = "run-unconcluded"
    }
}
