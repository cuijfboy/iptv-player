package ilab.iptv.player.epg

import androidx.work.Data
import androidx.work.ListenableWorker

/**
 * "What does one EPG run answer WorkManager?" — the retry policy of P3-6 as a pure mapping, so it can
 * be unit-tested without a device. It is the same table as P2-5's
 * [ilab.iptv.player.refresh.RefreshWorkOutcome], with the EPG job's own arms:
 *
 * | run result | attempts left | answer |
 * |---|---|---|
 * | completed | – | `success`; `interrupted` is set when the run stopped early because playback started |
 * | skipped (EPG off / still fresh) | – | `success` — there was nothing to do, and asking WorkManager to retry would defeat the freshness gate |
 * | deferred (playback on) | any | `retry` → LINEAR backoff 30 min; the policy caps deferrals at 3, after which it stops deferring and runs |
 * | failed (threw / budget) | < max | `retry` → LINEAR backoff 30 min |
 * | failed (threw / budget) | ≥ max | `success` + `gaveUp` — **deliberately not `failure()`**: a failed one-time job is still recorded, but "give up" is the honest state and a future trigger should be able to queue a fresh one without a stale FAILED entry in the way. |
 */
object EpgRefreshWorkOutcome {

    const val KEY_RESULT = "result"
    const val KEY_PROVIDERS = "providers"
    const val KEY_PROGRAMMES = "programmes"
    const val KEY_COVERAGE = "coverageRatio"
    /** EPG-BIND: the same run's honest ratio — channels whose binding actually holds programmes. */
    const val KEY_COVERAGE_PROGRAMMED = "coverageProgrammedRatio"
    const val KEY_INTERRUPTED = "interrupted"
    const val KEY_REASON = "reason"
    const val KEY_GAVE_UP = "gaveUp"

    const val RESULT_COMPLETED = "completed"
    const val RESULT_SKIPPED = "skipped"
    const val RESULT_DEFERRED = "deferred"
    const val RESULT_GAVE_UP = "gaveUp"

    fun toResult(result: EpgRunResult, runAttempt: Int, maxAttempts: Int): ListenableWorker.Result =
        when (result) {
            is EpgRunResult.Completed -> ListenableWorker.Result.success(
                Data.Builder()
                    .putString(KEY_RESULT, RESULT_COMPLETED)
                    .putInt(KEY_PROVIDERS, result.report.providers)
                    .putInt(KEY_PROGRAMMES, result.report.programmes)
                    .putFloat(KEY_COVERAGE, result.report.coverage.ratio.toFloat())
                    .putFloat(KEY_COVERAGE_PROGRAMMED, result.report.coverage.programmedRatio.toFloat())
                    .putString(KEY_INTERRUPTED, result.report.interrupted)
                    .build(),
            )

            is EpgRunResult.Skipped -> ListenableWorker.Result.success(
                Data.Builder()
                    .putString(KEY_RESULT, RESULT_SKIPPED)
                    .putString(KEY_REASON, result.reason)
                    .build(),
            )

            is EpgRunResult.Deferred -> if (runAttempt + 1 < maxAttempts) {
                ListenableWorker.Result.retry()
            } else {
                // The policy already ran the job on the last permitted attempt, so this arm is only
                // reachable if a deferral raced the cap; treat it as "no run this time".
                ListenableWorker.Result.success(
                    Data.Builder().putString(KEY_RESULT, RESULT_DEFERRED).build(),
                )
            }

            is EpgRunResult.Failed -> if (runAttempt + 1 < maxAttempts) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.success(
                    Data.Builder()
                        .putString(KEY_RESULT, RESULT_GAVE_UP)
                        .putString(KEY_REASON, result.reason)
                        .putBoolean(KEY_GAVE_UP, true)
                        .build(),
                )
            }
        }
}
