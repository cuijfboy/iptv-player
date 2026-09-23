package ilab.iptv.player.core.domain.refresh

import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgSourceStatus
import ilab.iptv.player.core.model.EpgStoredGuide
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
 * (`disabled` / `fresh` / `manual` / `stale` / `playing` / `catalog_empty` / `empty_guide` /
 * `empty_backoff`), not prose: it lands in `WORK_SCHEDULE` and `WORK_RUN` as a field, so "why did EPG
 * not run at 06:00?" is one grep.
 */
data class EpgRefreshDecision(val action: EpgRefreshAction, val reason: String)

/**
 * The EPG half of the settings (P3-6, card EPG-SETTINGS-1). The two user-facing knobs are [enabled]
 * (the EPG master switch) and [minIntervalMs] (the freshness threshold); both are persisted by
 * `EpgSettingsStore` (`:core:domain`) and surfaced on the settings page's 刷新 group.
 *
 * [minIntervalMs] is what makes the three triggers cheap: a cold start, the daily refresh and the
 * panel's button all funnel into the same gate, and a trigger that finds data younger than this does
 * nothing at all. Six hours is the default because a daily-refresh cadence plus a handful of app
 * starts per day must not re-download 4.5 MiB of guides each time. The value is a *setting* now, but
 * bounded — see [sanitizeMinInterval] — so a user cannot configure a window that breaks the §6.3
 * 地板 30 min / 天花板 6 h shape of the empty-guide backoff.
 *
 * [emptyRetryMs] is the other half of the same rule, and the BUG-20260922-016 fix: freshness alone is
 * not enough when the stored guide has *nothing to show* (no channel bound, or every binding empty).
 * Such a guide may be re-fetched after this much shorter interval instead of waiting the full
 * [minIntervalMs], because "we fetched successfully 20 minutes ago" is not an answer to a blank TV.
 * It is not user-editable this round; the store keeps its default.
 */
data class EpgRefreshSettings(
    val enabled: Boolean = true,
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    /** 30 min — see the class doc. Never longer than [minIntervalMs]; the gate clamps it. */
    val emptyRetryMs: Long = DEFAULT_EMPTY_RETRY_MS,
) {

    /**
     * The settings as the scheduler and coordinator must see them (card EPG-SETTINGS-1, requirement 4):
     * an out-of-range [minIntervalMs] is clamped into [MIN_INTERVAL_MS]..[MAX_INTERVAL_MS], and
     * [emptyRetryMs] is clamped under it — the same `coerceIn` shape the policy already applies to the
     * empty window, moved to the edge so a corrupted preference cannot reach the gate in the first
     * place. Also applied by the store on read and write.
     */
    fun sanitized(): EpgRefreshSettings {
        val min = sanitizeMinInterval(minIntervalMs)
        return copy(minIntervalMs = min, emptyRetryMs = emptyRetryMs.coerceIn(0L, min))
    }

    companion object {

        /** 6 h — see the class doc; also the ceiling for the freshness setting. */
        const val DEFAULT_MIN_INTERVAL_MS: Long = 6 * 60 * 60_000L

        /** 30 min — the empty-guide retry interval, the same wait a busy TV's deferral uses. */
        const val DEFAULT_EMPTY_RETRY_MS: Long = PlaybackAvoidancePolicy.DEFER_BACKOFF_MS

        /**
         * The freshness setting's floor: 30 min — the empty-guide retry interval (§6.3 的 30 min 地板).
         * A window shorter than the retry one would let a healthy guide be re-downloaded every few
         * minutes for no gain.
         */
        const val MIN_INTERVAL_MS: Long = DEFAULT_EMPTY_RETRY_MS

        /**
         * The freshness setting's ceiling: the 6 h default. The §6.3 天花板 promise is exactly
         * "an empty guide is not waited on for longer than [DEFAULT_MIN_INTERVAL_MS]", so the user knob
         * must not raise it.
         */
        const val MAX_INTERVAL_MS: Long = DEFAULT_MIN_INTERVAL_MS

        /**
         * The values the settings page's 刷新 row cycles through when the remote presses 确定: 30 分钟 →
         * 1 小时 → 2 小时 → 3 小时 → 6 小时. Presets rather than a free-form entry, the same trade
         * `SettingsDestination.CYCLE_LOG_LEVEL` already makes — a TV remote has no keyboard and the list
         * is short. Every value is already inside [MIN_INTERVAL_MS]..[MAX_INTERVAL_MS].
         */
        val FRESHNESS_PRESETS_MS: List<Long> = listOf(
            MIN_INTERVAL_MS,
            60 * 60_000L,
            2 * 60 * 60_000L,
            3 * 60 * 60_000L,
            MAX_INTERVAL_MS,
        )

        /** Clamps the freshness threshold into [MIN_INTERVAL_MS]..[MAX_INTERVAL_MS]. */
        fun sanitizeMinInterval(minIntervalMs: Long): Long =
            minIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

        /**
         * The preset after [currentMinIntervalMs], wrapping back to the first. A value that is not itself
         * a preset resolves to the next preset above it (so a stored 90 min cycles to 2 h, not back to
         * 30 min); the maximum wraps to the minimum.
         */
        fun nextMinInterval(currentMinIntervalMs: Long): Long {
            val safe = sanitizeMinInterval(currentMinIntervalMs)
            return FRESHNESS_PRESETS_MS.firstOrNull { it > safe } ?: FRESHNESS_PRESETS_MS.first()
        }
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
 * 2. no channel list yet ([EpgStoredGuide.catalogReady] is false) → `DEFER(catalog_empty)`. This is
 *    BUG-20260922-016's first half: a cold start happens before the channel table is seeded, so the
 *    old gate happily ran and fetched 26 s of guides into a table with nothing to bind
 *    (`matched=0/total=0`) — *and stamped `last_fetch_at` while doing it*, which then froze the next
 *    six hours. Waiting for the catalogue costs no network: the run is asked for again with the job's
 *    backoff and the very same trigger succeeds once the list is there (an import, or the first boot's
 *    seeding, both land inside that wait);
 * 3. a **user** trigger ([RefreshTrigger.MANUAL]) always `RUN`s — the user is waiting for a result,
 *    and an explicit tap outranks both the freshness gate and a playing session;
 * 4. anything else that finds a *usable* dataset younger than `minIntervalMs` → `SKIP(fresh)`. This is
 *    the idempotency gate: repeated cold starts inside the window cost no network and write no rows;
 * 5. a stored guide that is **empty** ([EpgStoredGuide.empty] — nothing bound, or every binding
 *    blank) gets the shorter `emptyRetryMs` instead: inside it → `SKIP(empty_backoff)`, past it →
 *    `RUN(empty_guide)`. Two things follow from that pair, and both were the bug: a blank TV is
 *    retried instead of being locked out for six hours, and consecutive empty runs cannot loop
 *    without end because every retry is at least `emptyRetryMs` behind the last fetch. The retry
 *    interval is bounded on both sides — no faster than `emptyRetryMs`, no slower than
 *    `minIntervalMs` — which is what the "退避上限" of BUG-016 asks for. (A per-attempt escalation
 *    would need a stored counter; the database is the only persisted state this job has, so the
 *    backoff is a floor plus a ceiling rather than a ladder. Recorded as a suggestion in
 *    `docs/05-过程记录/46-BUG016与018修复.md`.)
 * 6. a background trigger (`SCHEDULED`, the post-refresh follow-up) while a session plays and
 *    `respectPlayback` is on → `DEFER(playing)`, **at most [MAX_DEFERRALS] times**, exactly like
 *    `PlaybackAvoidancePolicy` does for the source refresh: a TV left on all evening still gets its
 *    guide on the fourth attempt;
 * 7. otherwise `RUN` (`manual` / `stale` / `empty_guide`).
 *
 * [RefreshTrigger.FIRST_RUN] is deliberately *not* special: it is "the app started", which can happen
 * twenty times a day, and rule 4 is what keeps that cheap. Its only privilege is that it can `DEFER`
 * (rule 6 applies to every non-manual trigger) so a cold start during playback never competes.
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
        stored: EpgStoredGuide,
    ): EpgRefreshDecision {
        if (!settings.enabled) return EpgRefreshDecision(EpgRefreshAction.SKIP, REASON_DISABLED)
        // Rule 2: there is nothing to bind yet, so do not spend a run (or a freshness stamp) on it.
        // The answer is the waiting one, not the do-nothing one: the catalogue arrives a moment later.
        if (!stored.catalogReady) {
            return EpgRefreshDecision(EpgRefreshAction.DEFER, REASON_CATALOG_EMPTY)
        }
        if (trigger != RefreshTrigger.MANUAL &&
            lastFetchAtMs != null &&
            nowMs - lastFetchAtMs < intervalFor(stored, settings)
        ) {
            return EpgRefreshDecision(
                EpgRefreshAction.SKIP,
                if (stored.empty) REASON_EMPTY_BACKOFF else REASON_FRESH,
            )
        }
        return EpgRefreshDecision(EpgRefreshAction.RUN, runReason(trigger, stored))
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
        stored: EpgStoredGuide,
    ): EpgRefreshDecision {
        val gate = gate(trigger, lastFetchAtMs, nowMs, settings, stored)
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

    /**
     * The freshness window this stored guide earns: the normal one when it has something to show, the
     * shorter empty-guide one when it does not. Clamped so a caller cannot configure an "empty" window
     * that is longer than the ordinary one (that would make the empty case *more* patient, which is the
     * bug in a different costume).
     */
    private fun intervalFor(stored: EpgStoredGuide, settings: EpgRefreshSettings): Long =
        if (stored.empty) {
            settings.emptyRetryMs.coerceIn(0L, settings.minIntervalMs)
        } else {
            settings.minIntervalMs
        }

    private fun runReason(trigger: RefreshTrigger, stored: EpgStoredGuide): String = when {
        trigger == RefreshTrigger.MANUAL -> REASON_MANUAL
        stored.empty -> REASON_EMPTY_GUIDE
        else -> REASON_STALE
    }

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

        /** Nothing to bind: the channel table has not been seeded or imported yet (BUG-016). */
        const val REASON_CATALOG_EMPTY: String = "catalog_empty"

        /** The stored guide binds nothing, or every binding is blank; retry (BUG-016). */
        const val REASON_EMPTY_GUIDE: String = "empty_guide"

        /** The same, but the last fetch is still inside `emptyRetryMs` — the bounded retry backoff. */
        const val REASON_EMPTY_BACKOFF: String = "empty_backoff"
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
