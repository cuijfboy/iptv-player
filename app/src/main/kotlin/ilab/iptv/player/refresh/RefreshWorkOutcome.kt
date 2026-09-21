package ilab.iptv.player.refresh

import androidx.work.Data
import androidx.work.ListenableWorker

/**
 * "What does one refresh run answer WorkManager?" — the retry policy of docs/04 P2-5 item 1, as a
 * pure mapping so it can be unit-tested without a device.
 *
 * THE RULE (thresholds come from [RefreshWorkSpec] / [PlaybackAvoidancePolicy] and are written down
 * there):
 *
 * | run result | attempts left | answer |
 * |---|---|---|
 * | completed (DONE) | – | `success` (the schedule continues) |
 * | completed, budget-interrupted | – | `success` + `interrupted` — the run stopped *by design*; the rows it did write stay, the TTLs make the next run pick up the rest, and retrying immediately would just burn the budget again |
 * | deferred (playback on) | any | `retry` → LINEAR backoff 30 min; the policy caps deferrals at 3, after which it stops deferring and runs |
 * | failed (threw) | < max | `retry` → LINEAR backoff 30 min |
 * | failed (threw) | ≥ max | `success` + `gaveUp` — **deliberately not `failure()`**: a `PeriodicWorkRequest` that reports failure can stop the daily schedule, and losing every future 06:00 because one morning had no network is the worse failure. The give-up is recorded in `WORK_RUN`. |
 */
object RefreshWorkOutcome {

    const val KEY_RESULT = "result"
    const val KEY_PHASE = "phase"
    const val KEY_INTERRUPTED = "interrupted"
    const val KEY_GAVE_UP = "gaveUp"

    const val RESULT_COMPLETED = "completed"
    const val RESULT_DEFERRED = "deferred"
    const val RESULT_GAVE_UP = "gaveUp"

    fun toResult(result: RefreshRunResult, runAttempt: Int, maxAttempts: Int): ListenableWorker.Result =
        when (result) {
            is RefreshRunResult.Completed -> ListenableWorker.Result.success(
                Data.Builder()
                    .putString(KEY_RESULT, RESULT_COMPLETED)
                    .putString(KEY_PHASE, result.last.phase.name)
                    .putString(KEY_INTERRUPTED, result.interrupted?.reason?.name)
                    .build(),
            )

            is RefreshRunResult.Deferred -> if (runAttempt + 1 < maxAttempts) {
                ListenableWorker.Result.retry()
            } else {
                // The policy already ran the job on the last permitted attempt, so this arm is only
                // reachable if a deferral raced the cap; treat it as "no run this period".
                ListenableWorker.Result.success(
                    Data.Builder().putString(KEY_RESULT, RESULT_DEFERRED).build(),
                )
            }

            is RefreshRunResult.Failed -> if (runAttempt + 1 < maxAttempts) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.success(
                    Data.Builder()
                        .putString(KEY_RESULT, RESULT_GAVE_UP)
                        .putBoolean(KEY_GAVE_UP, true)
                        .build(),
                )
            }
        }
}
