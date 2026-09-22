package ilab.iptv.player.refresh

/**
 * "When the user asks for a refresh, what do we do with the job that is already queued?" — the
 * decision of card NEW-004 (docs/05-过程记录/67), as plain data so it is unit-testable without
 * WorkManager.
 *
 * THE BUG IT FIXES: `enqueueNow` asked WorkManager for `KEEP`. A run the process death interrupted is
 * rescheduled by WorkManager as ENQUEUED **with the 30 min backoff of its previous attempt**, and
 * `KEEP` then means "there is already a job under this name, drop the new request". The user's tap —
 * and the wizard's automatic re-trigger on the 更新 step — therefore changed nothing and the run sat
 * in the backoff (~29m27s in the QA evidence).
 *
 * WHAT MUST NOT CHANGE (the three rules the card spells out):
 * 1. **No concurrent or duplicated run** — both answers below enqueue under the *same* unique name, so
 *    there is still exactly one manual job;
 * 2. **A live run is never cancelled** — [ExistingRefreshRun.state] `RUNNING` keeps `KEEP`, so a
 *    re-trigger while the job is actually working stays a no-op (the old behaviour on that arm);
 * 3. **An ordinary retry keeps its backoff** — a run that ended and asked for a retry itself (state
 *    `QUEUED`, but with nothing on the [RefreshRunLedger]) is left alone, which is exactly the "既有
 *    退避语义不受影响" the QA round replays.
 */

/** How the manual unique work stands right now, as far as the decision needs to know. */
enum class QueuedRefreshState {

    /** ENQUEUED/BLOCKED: waiting for its constraints or its backoff. */
    QUEUED,

    /** RUNNING: a worker is doing the job right now. */
    RUNNING,

    /** SUCCEEDED/FAILED/CANCELLED: the name is free, the next enqueue starts a fresh run. */
    FINISHED,
}

/** One reading of the manual job, with the Android types stripped off. */
data class ExistingRefreshRun(val state: QueuedRefreshState)

/**
 * Which `ExistingWorkPolicy` the enqueuer should use — the same two answers WorkManager offers,
 * minus the other three (an immediate user-requested run never `APPEND`s or `APPEND_OR_REPLACE`s).
 */
enum class RefreshEnqueuePolicy {

    /** Leave whatever is queued alone; with nothing queued this is a plain enqueue. */
    KEEP,

    /** Cancel the queued job and enqueue this one in its place (a fresh, un-backed-off run). */
    REPLACE,
}

/** The decision, as a pure function of the queue's state and the interruption ledger. */
object RefreshReclaim {

    fun plan(existing: ExistingRefreshRun?, runUnconcluded: Boolean): RefreshEnqueuePolicy = when {
        // Nothing queued: KEEP and REPLACE behave identically (WorkManager has no job to keep), so the
        // answer stays KEEP and the normal path is untouched.
        existing == null -> RefreshEnqueuePolicy.KEEP

        // Rule 2: never cancel work that is actually running.
        existing.state == QueuedRefreshState.RUNNING -> RefreshEnqueuePolicy.KEEP

        // The fix: a queued job whose last run was killed by the process death is sitting in that
        // run's backoff. Replace it so the user's request (or the wizard's re-trigger) runs now.
        existing.state == QueuedRefreshState.QUEUED && runUnconcluded -> RefreshEnqueuePolicy.REPLACE

        // Rule 3: queued with a clear ledger is an ordinary retry/defer backoff — leave it.
        // FINISHED also lands here: the name is free, so KEEP enqueues a fresh run.
        else -> RefreshEnqueuePolicy.KEEP
    }
}
