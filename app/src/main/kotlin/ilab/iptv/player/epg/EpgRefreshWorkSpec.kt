package ilab.iptv.player.epg

import ilab.iptv.player.core.domain.refresh.EpgRefreshPolicy
import ilab.iptv.player.core.model.RefreshTrigger

/**
 * What an EPG job asks WorkManager for (P3-6), as plain data — the same shape and the same reason as
 * P2-5's [ilab.iptv.player.refresh.RefreshWorkSpec]: the request itself is Android-heavy and cannot be
 * asserted in a JVM test, so the numbers live here and the builder in [EpgWorkEnqueuer] only translates.
 *
 * ONE job, three triggers. Cold start, the follow-up after a source refresh and the panel's button all
 * queue the same worker, which runs the same use case under the same budget; only the trigger name and
 * the queue policy differ:
 *
 * - a **background** trigger (cold start / post-refresh) goes to `epg-refresh` with `KEEP`: two app
 *   starts a minute apart must not queue two downloads;
 * - a **manual** trigger goes to its own name with `REPLACE`, because the user is waiting and a tap
 *   should neither be swallowed by a queued background job nor wait for it.
 *
 * THE CONSTRAINTS: a network (a guide without one can only fail) and, for the background triggers only,
 * "battery not low" — the same pair the daily refresh uses. The manual trigger drops the battery
 * constraint, exactly like `refresh-manual`: the user asked, and the job is seconds long, not 45
 * minutes.
 *
 * THE RETRY POLICY: LINEAR backoff of [EpgRefreshPolicy.DEFER_BACKOFF_MS] (30 min), at most
 * [MAX_ATTEMPTS] attempts, so the worst-case postponement of a guide is the same 3 h the source
 * refresh allows itself.
 */
data class EpgRefreshWorkSpec(
    val uniqueName: String,
    val trigger: RefreshTrigger,
    val initialDelayMs: Long,
    val requiresNetwork: Boolean,
    val requiresBatteryNotLow: Boolean,
    val backoffMs: Long,
    val maxAttempts: Int,
    val linearBackoff: Boolean,
    /** True for the manual trigger: replace a queued job instead of keeping it. */
    val replaceExisting: Boolean,
) {
    companion object {

        /** One background queue (cold start + post-refresh follow-up). */
        const val BACKGROUND_NAME: String = "epg-refresh"

        /** The user's own queue, so a tap is never swallowed by a pending background run. */
        const val MANUAL_NAME: String = "epg-refresh-manual"

        const val MAX_ATTEMPTS: Int = 3

        /** Tag on the request; scopes `adb shell dumpsys jobscheduler` to the EPG job. */
        const val TAG: String = "epg"

        val BACKGROUND_TRIGGERS: Set<RefreshTrigger> =
            setOf(RefreshTrigger.SCHEDULED, RefreshTrigger.FIRST_RUN, RefreshTrigger.ON_DEMAND_SINGLE_CHANNEL)

        fun immediate(trigger: RefreshTrigger) = EpgRefreshWorkSpec(
            uniqueName = if (trigger == RefreshTrigger.MANUAL) MANUAL_NAME else BACKGROUND_NAME,
            trigger = trigger,
            initialDelayMs = 0L,
            requiresNetwork = true,
            requiresBatteryNotLow = trigger != RefreshTrigger.MANUAL,
            backoffMs = EpgRefreshPolicy.DEFER_BACKOFF_MS,
            maxAttempts = MAX_ATTEMPTS,
            linearBackoff = true,
            replaceExisting = trigger == RefreshTrigger.MANUAL,
        )
    }
}
