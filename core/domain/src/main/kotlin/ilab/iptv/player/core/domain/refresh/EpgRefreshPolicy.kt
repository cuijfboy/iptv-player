package ilab.iptv.player.core.domain.refresh

import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgSourceStatus
import ilab.iptv.player.core.model.RefreshTrigger

/** What one EPG trigger decided to do. */
enum class EpgRefreshAction {

    /** Fetch now (the run-time half may still stop early if a session starts playing). */
    RUN,

    /** Nothing to do — the stored guide is fresh enough, or EPG is switched off. */
    SKIP,

    /** The TV is busy; try again later instead of competing with what the user is watching. */
    DEFER,
}

/**
 * One trigger decision plus the [reason] it was made for. The reason is a stable token
 * (`disabled` / `fresh` / `manual` / `stale` / `playing`), not prose: it lands in `WORK_SCHEDULE` and
 * `WORK_RUN` as a field, so "why did EPG not run at 06:00?" is one grep.
 */
data class EpgRefreshDecision(val action: EpgRefreshAction, val reason: String)

/**
 * The EPG half of the settings (P3-6). A plain data class, not a stored preference yet: the panel
 * shows the value it is running with, and a later settings card can back [minIntervalMs] with Room
 * without touching the policy.
 *
 * [minIntervalMs] is what makes the three triggers cheap: a cold start, the daily refresh and the
 * panel's button all funnel into the same gate, and a trigger that finds data younger than this does
 * nothing at all. Six hours is the default because a daily-refresh cadence plus a handful of app
 * starts per day must not re-download 4.5 MiB of guides each time.
 */
data class EpgRefreshSettings(
    val enabled: Boolean = true,
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    companion object {

        /** 6 h — see the class doc. */
        const val DEFAULT_MIN_INTERVAL_MS: Long = 6 * 60 * 60_000L
    }
}

/**
 * The one place that answers "should EPG run, and when?" (P3-6). Pure, clock-free and Android-free:
 * the caller owns "now", the attempt counter and the stored freshness ([EpgSourceStatus]), so every
 * rule below is a table-driven unit test instead of a device experiment.
 *
 * THE POLICY:
 *
 * 1. `enabled = false` → `SKIP(disabled)`;
 * 2. a **user** trigger ([RefreshTrigger.MANUAL]) always `RUN`s — the user is waiting for a result,
 *    and an explicit tap outranks both the freshness gate and a playing session;
 * 3. anything else that finds data younger than `minIntervalMs` → `SKIP(fresh)`. This is the
 *    idempotency gate: repeated cold starts inside the window cost no network and write no rows;
 * 4. a background trigger (`SCHEDULED`, the post-refresh follow-up) while a session plays and
 *    `respectPlayback` is on → `DEFER(playing)`, **at most [MAX_DEFERRALS] times**, exactly like
 *    `PlaybackAvoidancePolicy` does for the source refresh: a TV left on all evening still gets its
 *    guide on the fourth attempt;
 * 5. otherwise `RUN` (`manual` / `stale`).
 *
 * [RefreshTrigger.FIRST_RUN] is deliberately *not* special: it is "the app started", which can happen
 * twenty times a day, and rule 3 is what keeps that cheap. Its only privilege is that it can `DEFER`
 * (rule 4 applies to every non-manual trigger) so a cold start during playback never competes.
 */
class EpgRefreshPolicy(private val maxDeferrals: Int = MAX_DEFERRALS) {

    /**
     * The enqueue gate: freshness and the on/off switch only. Used by the scheduler *before* a job is
     * queued, so a fresh dataset costs no WorkManager wake-up at all. Playback is deliberately not
     * consulted here — that decision belongs to the run itself ([decide]), where the attempt count
     * exists.
     */
    fun gate(
        trigger: RefreshTrigger,
        lastFetchAtMs: Long?,
        nowMs: Long,
        settings: EpgRefreshSettings,
    ): EpgRefreshDecision {
        if (!settings.enabled) return EpgRefreshDecision(EpgRefreshAction.SKIP, REASON_DISABLED)
        if (trigger != RefreshTrigger.MANUAL &&
            lastFetchAtMs != null &&
            nowMs - lastFetchAtMs < settings.minIntervalMs
        ) {
            return EpgRefreshDecision(EpgRefreshAction.SKIP, REASON_FRESH)
        }
        return EpgRefreshDecision(EpgRefreshAction.RUN, runReason(trigger))
    }

    /**
     * The full decision, including playback avoidance. [deferrals] is the attempt counter the caller
     * owns (WorkManager's `runAttemptCount`), which is what bounds rule 4.
     */
    fun decide(
        trigger: RefreshTrigger,
        playing: Boolean,
        respectPlayback: Boolean,
        deferrals: Int,
        lastFetchAtMs: Long?,
        nowMs: Long,
        settings: EpgRefreshSettings,
    ): EpgRefreshDecision {
        val gate = gate(trigger, lastFetchAtMs, nowMs, settings)
        if (gate.action != EpgRefreshAction.RUN) return gate
        if (respectPlayback &&
            playing &&
            trigger != RefreshTrigger.MANUAL &&
            deferrals < maxDeferrals
        ) {
            return EpgRefreshDecision(EpgRefreshAction.DEFER, REASON_PLAYING)
        }
        return gate
    }

    private fun runReason(trigger: RefreshTrigger): String =
        if (trigger == RefreshTrigger.MANUAL) REASON_MANUAL else REASON_STALE

    companion object {

        /** Three postponements (30 min apart), then the guide is fetched regardless. */
        const val MAX_DEFERRALS: Int = PlaybackAvoidancePolicy.MAX_DEFERRALS

        /** Same backoff as the source refresh, so one "TV is busy" story covers both jobs. */
        const val DEFER_BACKOFF_MS: Long = PlaybackAvoidancePolicy.DEFER_BACKOFF_MS

        const val REASON_DISABLED: String = "disabled"
        const val REASON_FRESH: String = "fresh"
        const val REASON_MANUAL: String = "manual"
        const val REASON_STALE: String = "stale"
        const val REASON_PLAYING: String = "playing"
    }
}

/**
 * The EPG run budget (P3-6): the same idea as the source refresh's 45-minute budget, sized for what
 * this job is. Four guides are 4.5 MiB and 6–12 s end to end (P3-5), so ten minutes is ~50× the
 * measured cost and still inside WorkManager's own foreground-free ceiling for a `CoroutineWorker` —
 * a run that has not finished by then is stuck on the network, not working, and is better reported as
 * a failure for the retry policy to handle.
 */
object EpgRefreshBudget {

    const val DEFAULT_BUDGET_MS: Long = 10 * 60_000L
}

/**
 * What the settings / diagnostics screen may ask about EPG automation (P3-6).
 *
 * A domain port rather than a direct call: `:feature:settings` may not see `:core:data` or
 * WorkManager, and it must not grow a second way to reach the network. The implementation lives in
 * `:app`, next to the job it queues.
 */
interface EpgRefreshPort {

    /** Queue a user-requested update now. [EpgRefreshRequest.accepted] is false only when EPG is off. */
    suspend fun requestNow(): EpgRefreshRequest

    /** The overview: what the stored guide looks like and what it covers. */
    suspend fun status(): EpgRefreshStatus
}

data class EpgRefreshRequest(val accepted: Boolean, val reason: String)

/**
 * The panel's EPG read model: the source table's freshness plus the coverage of the *current* channel
 * list. Both already exist (docs/02 §5.1's status columns; `EpgRepository.coverage()`), so the panel
 * adds no data channel — the same rule P2-8 set for the rest of the overview.
 */
data class EpgRefreshStatus(
    val enabled: Boolean,
    val settings: EpgRefreshSettings,
    val sources: EpgSourceStatus,
    val coverage: EpgCoverage,
)
