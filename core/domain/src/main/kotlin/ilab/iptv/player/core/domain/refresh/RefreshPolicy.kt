package ilab.iptv.player.core.domain.refresh

import ilab.iptv.player.core.model.RefreshTrigger

/**
 * When the daily refresh runs (docs/04 P2-5: "默认每日 06:00 触发一次"; docs/02 §6.1 增量与可恢复).
 *
 * It is a *port*, not a constant, because P2-8's settings page will let the user move the time; until
 * that page exists the only implementation returns [DEFAULT_MINUTE_OF_DAY]. The value is minutes from
 * local midnight, i.e. the same unit the WorkManager initial-delay arithmetic consumes.
 */
fun interface RefreshScheduleSettings {

    /** Minutes from local midnight; 360 = 06:00. Out-of-range values fall back to the default. */
    fun refreshAtMinuteOfDay(): Int

    companion object {

        /** 06:00 — the frozen default (docs/04 P2-5). */
        const val DEFAULT_MINUTE_OF_DAY: Int = 6 * 60

        const val MIN_MINUTE_OF_DAY: Int = 0
        const val MAX_MINUTE_OF_DAY: Int = 24 * 60 - 1

        /** Guards a corrupted/experimental setting instead of letting WorkManager reject the delay. */
        fun sanitize(minuteOfDay: Int): Int =
            if (minuteOfDay in MIN_MINUTE_OF_DAY..MAX_MINUTE_OF_DAY) minuteOfDay else DEFAULT_MINUTE_OF_DAY
    }
}

/** What a refresh run does about the session that may be playing (docs/02 §4.5 C3, §6.1 播放避让). */
enum class RefreshRunDecision {

    /** Run now; the pipeline halves Fetch/Shallow/Deep concurrency by itself while playback is on. */
    RUN,

    /** Do not start (and do not spend the 45 min budget) — retry later, when the TV is free. */
    DEFER,
}

/**
 * R7 playback avoidance for the *scheduling* half (P2-5). The pipeline's half is
 * `ConcurrencyGovernor` (P2-4a): while a session plays it halves Fetch/Shallow/Deep concurrency.
 * This policy answers the earlier question — should the run start at all.
 *
 * THE POLICY (thresholds are the frozen part; see `docs/05-过程记录/26-P2-5定时刷新验证.md`):
 *
 * 1. `respectPlayback = false` (the user turned avoidance off) → always [RefreshRunDecision.RUN];
 * 2. nothing playing → [RefreshRunDecision.RUN];
 * 3. **user-facing triggers always run** — `MANUAL`, `FIRST_RUN` and `ON_DEMAND_SINGLE_CHANNEL` are
 *    the user waiting for a result, so they run with halved concurrency instead of being postponed;
 * 4. `SCHEDULED` + playing → [RefreshRunDecision.DEFER], but **at most [MAX_DEFERRALS] times**: a TV
 *    left on a channel all evening must still get its refresh, so the fourth attempt runs anyway
 *    (halved). With the 30 min backoff that is at most [DEFER_WINDOW_MS] of postponement.
 *
 * Pure and clock-free: the caller owns the attempt counter (WorkManager's `runAttemptCount`).
 */
class PlaybackAvoidancePolicy(private val maxDeferrals: Int = MAX_DEFERRALS) {

    fun decide(
        playing: Boolean,
        respectPlayback: Boolean,
        trigger: RefreshTrigger,
        deferrals: Int,
    ): RefreshRunDecision = when {
        !respectPlayback -> RefreshRunDecision.RUN
        !playing -> RefreshRunDecision.RUN
        trigger != RefreshTrigger.SCHEDULED -> RefreshRunDecision.RUN
        deferrals < maxDeferrals -> RefreshRunDecision.DEFER
        else -> RefreshRunDecision.RUN
    }

    companion object {

        /** Three postponements, then the run happens regardless (a TV can be on for days). */
        const val MAX_DEFERRALS: Int = 3

        /** The retry backoff that makes a deferral wait; the WorkRequest uses the same number. */
        const val DEFER_BACKOFF_MS: Long = 30 * 60_000L

        /**
         * Worst case with WorkManager's LINEAR backoff (attempt n waits n × [DEFER_BACKOFF_MS]):
         * 30 + 60 + 90 min = 3 h of postponement before the run happens anyway.
         */
        const val DEFER_WINDOW_MS: Long = DEFER_BACKOFF_MS * (MAX_DEFERRALS * (MAX_DEFERRALS + 1) / 2)
    }
}
