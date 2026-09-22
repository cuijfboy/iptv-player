package ilab.iptv.player.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.PlaybackActivity
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.epg.EpgRefreshScheduler

/**
 * The scheduled refresh, as a WorkManager long-running worker (docs/04 P2-5 items 1, 2, 5).
 *
 * WHY A WORKER AND NOT A HAND-STARTED SERVICE: from API 31 a foreground service cannot be started
 * from the background, and a 06:00 job runs in the background by definition. WorkManager's
 * `setForeground` is the supported path — it starts [androidx.work.impl.foreground.SystemForegroundService]
 * with our low-priority notification and tears it down when this method returns. docs/05's WBS names
 * this component `:app RefreshService`; the deviation is reported to god (one code path instead of two,
 * and the only one Android 12+ allows).
 *
 * WHAT IT LOGS (docs/03 §3.3, docs/04 P2-5 item 5):
 * - `SERVICE_REFRESH_START` / `SERVICE_REFRESH_STOP` — the foreground half (the notification's life);
 * - `WORK_RUN` three times per attempt: start, and then the conclusion (`result=success|retry|give_up`,
 *   `phase`, `attempt`, `elapsedMs`). `WORK_SCHEDULE` is logged by [RefreshScheduler] when the job is
 *   queued, so "scheduled → ran → what happened" reads off one filtered log.
 *
 * P3-6 hangs the EPG follow-up off the end of a completed run: after the sources are refreshed the
 * worker asks [EpgRefreshScheduler] for one EPG refresh (`trigger=SCHEDULED`), which is how
 * "每日刷新源之后拉 EPG" happens without a second schedule. It is fire-and-forget on purpose — the
 * EPG decision has its own gate (freshness, playback) and its own retry policy, and a guide that
 * cannot be fetched must never change what this worker reports about the source refresh.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * `EntryPointAccessors` rather than `@HiltWorker`: the Hilt worker integration needs
     * `androidx.hilt:hilt-work` plus a `WorkerFactory`, and this worker needs three objects — the same
     * trade P1-7's `PlaybackService` made for `PlaybackSession` (a plain `Service` cannot be
     * `@AndroidEntryPoint` either).
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RefreshEntryPoint {
        fun logger(): Logger

        fun clock(): Clock

        fun refreshRunCoordinator(): RefreshRunCoordinator

        fun epgRefreshScheduler(): EpgRefreshScheduler
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, RefreshEntryPoint::class.java)
        val logger = deps.logger()
        val trigger = triggerFrom(inputData)
        val startedAtMs = deps.clock().nowMs()
        val playing = PlaybackActivity.isActive()

        logger.i(
            LogCategory.WORK,
            EventCodes.WORK_RUN,
            "refresh worker started",
            mapOf(
                "trigger" to trigger.name,
                "attempt" to runAttemptCount,
                "playing" to playing,
                "constraints" to "network+batteryNotLow",
            ),
        )

        postForeground(RefreshNotificationContent.starting())
        logger.i(
            LogCategory.SERVICE,
            EventCodes.SERVICE_REFRESH_START,
            "refresh foreground service started",
            mapOf("notificationId" to RefreshNotifications.NOTIFICATION_ID, "trigger" to trigger.name),
        )

        val result = deps.refreshRunCoordinator().run(trigger = trigger, deferrals = runAttemptCount) { progress ->
            // The notification is the progress display: one update per pipeline phase (deep/score/select
            // emit their own), which is also how "阶段" reaches the user (docs/04 P2-5 item 2).
            postForeground(RefreshNotificationContent.of(progress))
        }

        if (result is RefreshRunResult.Completed) {
            requestEpgFollowUp(deps, trigger)
        }

        logConclusion(logger, result, trigger, deps.clock().nowMs() - startedAtMs)
        return RefreshWorkOutcome.toResult(
            result = result,
            runAttempt = runAttemptCount,
            maxAttempts = RefreshWorkSpec.MAX_ATTEMPTS,
        )
    }

    /**
     * The P3-6 follow-up. Deliberately wrapped in a catch: this runs after the refresh has already
     * succeeded, and an EPG booking failure (WorkManager unavailable, a locked table) is not a reason
     * to report the refresh as failed. The next trigger — the next app start, the next refresh, or the
     * panel's button — will ask again.
     */
    private suspend fun requestEpgFollowUp(deps: RefreshEntryPoint, trigger: RefreshTrigger) {
        try {
            deps.epgRefreshScheduler().request(RefreshTrigger.SCHEDULED)
        } catch (e: Throwable) {
            deps.logger().w(
                LogCategory.WORK,
                EventCodes.WORK_SCHEDULE,
                "epg follow-up could not be requested",
                mapOf("job" to "epg", "trigger" to RefreshTrigger.SCHEDULED.name, "afterTrigger" to trigger.name),
                e,
            )
        }
    }

    /**
     * Post (or update) the foreground notification.
     *
     * FAILING TO GO FOREGROUND IS NOT FATAL: on devices where the user disabled the notification or
     * the OS refuses the type, `setForeground` throws; a refresh that then runs without a notification
     * is still a refresh, and killing the job would be worse. The failure is logged, not swallowed.
     */
    private suspend fun postForeground(content: RefreshNotificationContent) {
        try {
            setForeground(RefreshNotifications.foregroundInfo(applicationContext, content))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Nothing to log with here (the logger is not in scope); WorkManager records the failure
            // and the run continues notification-less. See the verification file's 未做项.
            android.util.Log.w(TAG, "refresh foreground notification failed", e)
        }
    }

    private fun logConclusion(
        logger: Logger,
        result: RefreshRunResult,
        trigger: RefreshTrigger,
        elapsedMs: Long,
    ) {
        val fields = mutableMapOf<String, Any?>(
            "trigger" to trigger.name,
            "attempt" to runAttemptCount,
            "elapsedMs" to elapsedMs,
        )
        when (result) {
            is RefreshRunResult.Completed -> {
                fields["result"] = "success"
                fields["phase"] = result.last.phase.name
                fields["interrupted"] = result.interrupted?.reason?.name
                fields["okCount"] = result.last.okCount
                fields["failCount"] = result.last.failCount
            }

            is RefreshRunResult.Deferred -> {
                fields["result"] = "retry"
                fields["reason"] = "playback_priority"
                fields["deferrals"] = result.deferrals
            }

            is RefreshRunResult.Failed -> {
                fields["result"] = if (runAttemptCount + 1 < RefreshWorkSpec.MAX_ATTEMPTS) "retry" else "give_up"
                fields["error"] = result.error::class.simpleName
            }
        }
        logger.i(LogCategory.WORK, EventCodes.WORK_RUN, "refresh worker finished", fields)
        logger.i(
            LogCategory.SERVICE,
            EventCodes.SERVICE_REFRESH_STOP,
            "refresh foreground service stopped",
            mapOf("notificationId" to RefreshNotifications.NOTIFICATION_ID, "result" to fields["result"]),
        )
    }

    companion object {

        private const val TAG = "RefreshWorker"

        /** The trigger travels in `inputData`; an unknown value means a scheduled run. */
        const val KEY_TRIGGER = "trigger"

        fun inputFor(trigger: RefreshTrigger): Data =
            Data.Builder().putString(KEY_TRIGGER, trigger.name).build()

        fun triggerFrom(data: Data): RefreshTrigger =
            data.getString(KEY_TRIGGER)
                ?.let { name -> RefreshTrigger.entries.firstOrNull { it.name == name } }
                ?: RefreshTrigger.SCHEDULED
    }
}
