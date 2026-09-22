package ilab.iptv.player.epg

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.epg.EpgSourceStatusReader
import ilab.iptv.player.core.domain.refresh.EpgRefreshAction
import ilab.iptv.player.core.domain.refresh.EpgRefreshDecision
import ilab.iptv.player.core.domain.refresh.EpgRefreshPolicy
import ilab.iptv.player.core.domain.refresh.EpgRefreshPort
import ilab.iptv.player.core.domain.refresh.EpgRefreshRequest
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.domain.refresh.EpgRefreshStatus
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.model.RefreshTrigger

/**
 * "Make EPG happen without being asked" (P3-6): the two producers of EPG jobs that are not the user.
 *
 * - [RefreshTrigger.FIRST_RUN] — every app start asks once. The gate makes that cheap: inside
 *   [EpgRefreshSettings.minIntervalMs] of the last fetch nothing is queued at all, so twenty launches a
 *   day still mean roughly four downloads.
 * - [RefreshTrigger.SCHEDULED] — the follow-up the daily refresh fires when its own run completes
 *   (`RefreshWorker`), which is how "每日刷新源之后拉 EPG" is implemented without a second schedule.
 *
 * WHY THE GATE IS HERE AND THE PLAYBACK DECISION IS NOT: a wake-up that decides to do nothing is still
 * a wake-up. Freshness and the on/off switch are known at enqueue time, so they are checked here;
 * whether the TV is *busy* is only meaningful at run time (and the attempt counter that bounds the
 * deferrals lives in WorkManager), so that half is [EpgRefreshCoordinator]'s.
 *
 * The user's own button does not come through here — [EpgRefreshGateway] sends it straight to the
 * manual queue, so a tap is never silently dropped by the freshness gate.
 */
class EpgRefreshScheduler(
    private val enqueuer: EpgWorkEnqueuer,
    private val policy: EpgRefreshPolicy,
    private val settings: EpgRefreshSettings,
    private val status: EpgSourceStatusReader,
    private val logger: Logger,
    private val clock: Clock,
) {

    /** Decides and, when it decides to run, queues the job. Returns the decision it acted on. */
    suspend fun request(trigger: RefreshTrigger): EpgRefreshDecision {
        val nowMs = clock.nowMs()
        val lastFetchAtMs = status.read().lastFetchAtMs
        val decision = policy.gate(trigger, lastFetchAtMs, nowMs, settings)
        val fields = mapOf(
            "job" to EpgRefreshCoordinator.JOB,
            "trigger" to trigger.name,
            "decision" to decision.action.name,
            "reason" to decision.reason,
            "lastFetchAtMs" to lastFetchAtMs,
            "ageMs" to lastFetchAtMs?.let { nowMs - it },
            "minIntervalMs" to settings.minIntervalMs,
        )
        if (decision.action == EpgRefreshAction.SKIP) {
            logger.i(LogCategory.WORK, EventCodes.WORK_SCHEDULE, "epg refresh not scheduled", fields)
            return decision
        }

        val spec = EpgRefreshWorkSpec.immediate(trigger)
        enqueuer.enqueue(spec)
        logger.i(
            LogCategory.WORK,
            EventCodes.WORK_SCHEDULE,
            "epg refresh requested",
            fields + mapOf(
                "name" to spec.uniqueName,
                "backoffMs" to spec.backoffMs,
                "maxAttempts" to spec.maxAttempts,
                "requiresNetwork" to spec.requiresNetwork,
                "requiresBatteryNotLow" to spec.requiresBatteryNotLow,
            ),
        )
        return decision
    }
}

/**
 * The settings / diagnostics panel's face on the same machinery.
 *
 * It exists so a screen can act and read without seeing WorkManager, `:core:data`'s tables or
 * `:core:epg`: [requestNow] queues the manual job, [status] joins the `epg_source` freshness
 * (`EpgSourceStatusReader`) with the coverage of the current channel list (`EpgRepository.coverage()`),
 * which are the two things a tester needs to answer "is EPG working?" without a log dump.
 */
class EpgRefreshGateway(
    private val scheduler: EpgRefreshScheduler,
    private val statusReader: EpgSourceStatusReader,
    private val repository: EpgRepository,
    private val settings: EpgRefreshSettings,
) : EpgRefreshPort {

    override suspend fun requestNow(): EpgRefreshRequest {
        val decision = scheduler.request(RefreshTrigger.MANUAL)
        return EpgRefreshRequest(
            accepted = decision.action != EpgRefreshAction.SKIP,
            reason = decision.reason,
        )
    }

    override suspend fun status(): EpgRefreshStatus = EpgRefreshStatus(
        enabled = settings.enabled,
        settings = settings,
        sources = statusReader.read(),
        coverage = repository.coverage(),
    )
}
