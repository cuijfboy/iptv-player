package ilab.iptv.player.epg

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.epg.EpgSourceStatusReader
import ilab.iptv.player.core.data.epg.EpgStoredGuideReader
import ilab.iptv.player.core.domain.refresh.EpgRefreshAction
import ilab.iptv.player.core.domain.refresh.EpgRefreshDecision
import ilab.iptv.player.core.domain.refresh.EpgRefreshPolicy
import ilab.iptv.player.core.domain.refresh.EpgRefreshPort
import ilab.iptv.player.core.domain.refresh.EpgRefreshRequest
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.domain.refresh.EpgRefreshStatus
import ilab.iptv.player.core.domain.refresh.EpgSettingsStore
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
 * The gate reads two things, not one (BUG-20260922-016): [EpgSourceStatusReader] for *when* the last
 * fetch happened and [EpgStoredGuideReader] for *whether it produced anything a viewer can see*. The
 * second one is what stops the cold-start white run — an app start before the channel table exists now
 * answers `DEFER(catalog_empty)` and waits, instead of fetching four guides into a table with nothing
 * to bind and stamping six hours of false freshness while it does it.
 *
 * The user's own button does not come through here — [EpgRefreshGateway] sends it straight to the
 * manual queue, so a tap is never silently dropped by the freshness gate.
 */
class EpgRefreshScheduler(
    private val enqueuer: EpgWorkEnqueuer,
    private val policy: EpgRefreshPolicy,
    private val settingsStore: EpgSettingsStore,
    private val status: EpgSourceStatusReader,
    private val guide: EpgStoredGuideReader,
    private val logger: Logger,
    private val clock: Clock,
) {

    /** Decides and, when it decides to run, queues the job. Returns the decision it acted on. */
    suspend fun request(trigger: RefreshTrigger): EpgRefreshDecision {
        // Read the setting per request (EPG-SETTINGS-1): the master switch and the freshness threshold
        // are user-editable, so "off" and a changed interval must take effect on the next trigger, not
        // the next process. One small in-memory `SharedPreferences` read.
        val settings = settingsStore.read()
        val nowMs = clock.nowMs()
        val lastFetchAtMs = status.read().lastFetchAtMs
        val stored = guide.read()
        val decision = policy.gate(trigger, lastFetchAtMs, nowMs, settings, stored)
        val fields = mapOf(
            "job" to EpgRefreshCoordinator.JOB,
            "trigger" to trigger.name,
            "decision" to decision.action.name,
            "reason" to decision.reason,
            "lastFetchAtMs" to lastFetchAtMs,
            "ageMs" to lastFetchAtMs?.let { nowMs - it },
            "minIntervalMs" to settings.minIntervalMs,
            // The second half of the gate's input: what the stored guide looks like right now, so
            // "we deferred because there is nothing to bind yet" is readable from this line alone.
            "channels" to stored.channels,
            "matched" to stored.matched,
            "programmed" to stored.programmed,
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
    private val settingsStore: EpgSettingsStore,
) : EpgRefreshPort {

    override suspend fun requestNow(): EpgRefreshRequest {
        val decision = scheduler.request(RefreshTrigger.MANUAL)
        return EpgRefreshRequest(
            accepted = decision.action != EpgRefreshAction.SKIP,
            reason = decision.reason,
        )
    }

    override suspend fun status(): EpgRefreshStatus {
        // One read for the whole snapshot (EPG-SETTINGS-1): the panel's switch state and its freshness
        // line must agree, and the store's read is cheap.
        val settings = settingsStore.read()
        return EpgRefreshStatus(
            enabled = settings.enabled,
            settings = settings,
            sources = statusReader.read(),
            coverage = repository.coverage(),
        )
    }
}
