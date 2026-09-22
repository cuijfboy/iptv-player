package ilab.iptv.player.epg

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

/**
 * The EPG job (P3-6): one WorkManager worker for all three triggers, and the only thing in the app
 * that starts an EPG fetch.
 *
 * WHY A PLAIN `CoroutineWorker` AND NOT P2-5'S FOREGROUND SHAPE: the source refresh is a 45-minute
 * network-heavy job whose progress the user must be able to see, so it goes foreground. A guide run is
 * 6–12 s of one sequential download (`EpgRefreshBudget` caps it at ten minutes) and its whole design
 * goal is "不打扰" — posting a notification for it would be the disturbance the card forbids. Inside
 * WorkManager's foreground-free window, no notification is the correct behaviour.
 *
 * WHAT IT LOGS (docs/03 §3.3; every field carries `job=epg` so the EPG story is one grep):
 * `WORK_RUN` at start with `trigger` / `attempt` / `playing`, and `WORK_RUN` again at the end with the
 * coordinator's `result` / `reason` / `elapsedMs` (and, on success, the coverage numbers). The
 * pipeline's own `EPG_*` codes come from the use case, and the scheduling decision comes from
 * [EpgRefreshScheduler]'s `WORK_SCHEDULE`.
 */
class EpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * `EntryPointAccessors` rather than `@HiltWorker`, for the same reason [ilab.iptv.player.refresh.RefreshWorker]
     * uses it: the Hilt worker integration needs `hilt-work` plus a `WorkerFactory`, and this worker
     * needs three objects.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EpgEntryPoint {

        fun logger(): Logger

        fun clock(): Clock

        fun epgRefreshCoordinator(): EpgRefreshCoordinator
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, EpgEntryPoint::class.java)
        val logger = deps.logger()
        val trigger = triggerFrom(inputData)
        val playing = PlaybackActivity.isActive()
        logger.i(
            LogCategory.WORK,
            EventCodes.WORK_RUN,
            "epg worker started",
            mapOf(
                "job" to EpgRefreshCoordinator.JOB,
                "trigger" to trigger.name,
                "attempt" to runAttemptCount,
                "playing" to playing,
                // No notification on purpose; see the class doc.
                "constraints" to "network",
            ),
        )

        val result = deps.epgRefreshCoordinator().run(
            trigger = trigger,
            deferrals = runAttemptCount,
        )
        return EpgRefreshWorkOutcome.toResult(
            result = result,
            runAttempt = runAttemptCount,
            maxAttempts = EpgRefreshWorkSpec.MAX_ATTEMPTS,
        )
    }

    companion object {

        /** The trigger travels in `inputData`; an unknown value means a background run. */
        const val KEY_TRIGGER = "trigger"

        fun inputFor(trigger: RefreshTrigger): Data =
            Data.Builder().putString(KEY_TRIGGER, trigger.name).build()

        fun triggerFrom(data: Data): RefreshTrigger =
            data.getString(KEY_TRIGGER)
                ?.let { name -> RefreshTrigger.entries.firstOrNull { it.name == name } }
                ?: RefreshTrigger.SCHEDULED
    }
}
