package ilab.iptv.player.refresh

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.refresh.RefreshScheduleSettings
import ilab.iptv.player.core.model.RefreshTrigger
import java.util.TimeZone

/**
 * "Make the refresh happen on schedule" (docs/04 P2-5 item 1, docs/01 F2).
 *
 * Two entry points, one shape: [scheduleDaily] is called on every app start (idempotent — the unique
 * periodic work is replaced, not duplicated), [enqueueNow] is the user-triggered run.
 *
 * Observability (docs/04 P2-5 item 5): every scheduling decision logs `WORK_SCHEDULE` (docs/03 §3.3)
 * with the resolved time-of-day, the first-run delay and the constraints, so "why did it not run at
 * 06:00?" is answerable from the diagnostic page instead of by guessing. The run itself logs
 * `WORK_RUN` (see [RefreshWorker]).
 */
class RefreshScheduler(
    private val enqueuer: WorkEnqueuer,
    private val settings: RefreshScheduleSettings,
    private val logger: Logger,
    private val clock: Clock,
    private val ledger: RefreshRunLedger,
    private val zone: TimeZone = TimeZone.getDefault(),
) {

    /** (Re)schedule the daily refresh; returns the spec that was handed to WorkManager. */
    fun scheduleDaily(): RefreshWorkSpec {
        val minuteOfDay = RefreshScheduleSettings.sanitize(settings.refreshAtMinuteOfDay())
        val spec = RefreshWorkSpec.periodic(minuteOfDay, clock.nowMs(), zone)
        enqueuer.enqueuePeriodic(spec)
        logger.i(
            LogCategory.WORK,
            EventCodes.WORK_SCHEDULE,
            "daily refresh scheduled",
            mapOf(
                "name" to spec.uniqueName,
                "atMinuteOfDay" to minuteOfDay,
                "periodMs" to spec.periodMs,
                "flexMs" to spec.flexMs,
                "initialDelayMs" to spec.initialDelayMs,
                "requiresNetwork" to spec.requiresNetwork,
                "requiresBatteryNotLow" to spec.requiresBatteryNotLow,
                "backoffMs" to spec.backoffMs,
                "maxAttempts" to spec.maxAttempts,
            ),
        )
        return spec
    }

    /**
     * A run the user asked for; it must not wait for 06:00 — and it must not wait out the backoff of a
     * run the process death interrupted either (NEW-004).
     *
     * The job it queues is the same `refresh-manual` unique work as before; what the card changes is
     * *which policy* asks for it. [RefreshReclaim] answers that from two inputs — the state of the
     * queued job and [RefreshRunLedger] — so the three rules in that file hold (no duplicate run, no
     * cancelled live run, ordinary backoff untouched).
     */
    suspend fun enqueueNow(trigger: RefreshTrigger = RefreshTrigger.MANUAL): RefreshWorkSpec {
        val spec = RefreshWorkSpec.immediate()
        val existing = enqueuer.existing(spec.uniqueName)
        val unconcluded = ledger.isRunUnconcluded()
        val policy = RefreshReclaim.plan(existing, unconcluded)
        enqueuer.enqueueOnce(spec, policy)
        logger.i(
            LogCategory.WORK,
            EventCodes.WORK_SCHEDULE,
            "refresh run requested",
            mapOf(
                "name" to spec.uniqueName,
                "trigger" to trigger.name,
                // The NEW-004 observability: one line says whether the request was honored and why,
                // so "the tap did nothing" is answerable without `dumpsys jobscheduler`.
                "queuedState" to (existing?.state?.name ?: "NONE"),
                "interruptedRunOnRecord" to unconcluded,
                "policy" to policy.name,
                "requiresNetwork" to spec.requiresNetwork,
                "backoffMs" to spec.backoffMs,
                "maxAttempts" to spec.maxAttempts,
            ),
        )
        return spec
    }

    companion object {

        /** Tag on both requests, so `adb shell dumpsys jobscheduler` / WorkManager inspection is scoped. */
        const val TAG: String = "refresh"
    }
}
