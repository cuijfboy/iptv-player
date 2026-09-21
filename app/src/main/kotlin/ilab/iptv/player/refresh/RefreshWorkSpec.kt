package ilab.iptv.player.refresh

import ilab.iptv.player.core.domain.refresh.PlaybackAvoidancePolicy

/**
 * What a refresh job asks the scheduler for, as plain data (docs/04 P2-5 item 1).
 *
 * WHY IT IS DATA: the WorkManager request is Android-heavy and cannot be driven from a JVM unit test,
 * so the *decisions* — the interval, the first-run delay, the constraints, the retry policy — live in
 * this object and are asserted directly. [WorkManagerEnqueuer] is then a mechanical translation with
 * nothing left to get wrong.
 *
 * THE CONSTRAINTS (docs/04 P2-5 "Constraints（网络可用、电量不低）"):
 * - `requiresNetwork` → `NetworkType.CONNECTED`: a refresh with no network can only fail; note that
 *   docs/02 §6.1's row says "非计量网络" (`UNMETERED`) — the dispatch for this round says 网络可用,
 *   and the difference is reported to god/arch rather than silently chosen;
 * - `requiresBatteryNotLow` → the TV must not be in battery-saver territory when a 45 min
 *   network-heavy job starts.
 *
 * THE RETRY POLICY (docs/04 P2-5 "失败重试策略要写清"):
 * - WorkManager `LINEAR` backoff with [BACKOFF_MS] = 30 min → attempt n waits n × 30 min;
 * - at most [MAX_ATTEMPTS] = 3 attempts per job run, so ≤ 90 min of retrying (the §6.1 budget is
 *   45 min per attempt, and the daily period is untouched by a retry).
 */
data class RefreshWorkSpec(
    val uniqueName: String,
    val periodMs: Long,
    val flexMs: Long,
    val initialDelayMs: Long,
    val requiresNetwork: Boolean,
    val requiresBatteryNotLow: Boolean,
    val backoffMs: Long,
    val maxAttempts: Int,
    val linearBackoff: Boolean,
) {
    companion object {

        /** Unique names: one daily job, one immediate job (WorkManager dedupes by name). */
        const val PERIODIC_NAME: String = "refresh-daily"
        const val MANUAL_NAME: String = "refresh-manual"

        const val MAX_ATTEMPTS: Int = 3

        /** The same 30 min [PlaybackAvoidancePolicy] uses to wait out a playing session. */
        const val BACKOFF_MS: Long = PlaybackAvoidancePolicy.DEFER_BACKOFF_MS

        /** The daily job: first run at the next occurrence of the configured time-of-day. */
        fun periodic(minuteOfDay: Int, nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()) =
            RefreshWorkSpec(
                uniqueName = PERIODIC_NAME,
                periodMs = RefreshSchedule.PERIOD_MS,
                flexMs = RefreshSchedule.FLEX_MS,
                initialDelayMs = RefreshSchedule.nextDelayMs(nowMs, minuteOfDay, zone),
                requiresNetwork = true,
                requiresBatteryNotLow = true,
                backoffMs = BACKOFF_MS,
                maxAttempts = MAX_ATTEMPTS,
                linearBackoff = true,
            )

        /**
         * The immediate job (manual trigger, or a user-visible "refresh now"). It runs as soon as the
         * constraints allow; the retry policy is the daily one so a transient network failure still
         * retries instead of silently doing nothing.
         */
        fun immediate() = RefreshWorkSpec(
            uniqueName = MANUAL_NAME,
            periodMs = 0L,
            flexMs = 0L,
            initialDelayMs = 0L,
            requiresNetwork = true,
            requiresBatteryNotLow = false,
            backoffMs = BACKOFF_MS,
            maxAttempts = MAX_ATTEMPTS,
            linearBackoff = true,
        )
    }
}
