package ilab.iptv.player.core.domain.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.RefreshTrigger
import org.junit.Test

/**
 * The P3-6 trigger table, asserted as data (no clock, no WorkManager, no network).
 *
 * The three triggers — a cold start, the follow-up after a source refresh and the panel's button —
 * all land in [EpgRefreshPolicy], so this file is where "何时该拉" and "避让时怎么退" are pinned:
 * freshness (rule 3) is the idempotency gate, the deferral cap (rule 4) is what stops a TV that is on
 * all evening from never getting a guide.
 */
class EpgRefreshPolicyTest {

    private val policy = EpgRefreshPolicy()
    private val settings = EpgRefreshSettings()
    private val now = 1_700_000_000_000L
    private val interval = EpgRefreshSettings.DEFAULT_MIN_INTERVAL_MS

    /** A dataset fetched [ageMs] ago. */
    private fun lastFetch(ageMs: Long): Long = now - ageMs

    private fun decide(
        trigger: RefreshTrigger,
        playing: Boolean = false,
        respectPlayback: Boolean = true,
        deferrals: Int = 0,
        lastFetchAtMs: Long? = lastFetch(interval + 1),
        settings: EpgRefreshSettings = this.settings,
    ) = policy.decide(
        trigger = trigger,
        playing = playing,
        respectPlayback = respectPlayback,
        deferrals = deferrals,
        lastFetchAtMs = lastFetchAtMs,
        nowMs = now,
        settings = settings,
    )

    @Test
    fun `the default interval is six hours and the deferral cap is three`() {
        assertThat(settings.minIntervalMs).isEqualTo(6L * 60 * 60_000L)
        assertThat(settings.enabled).isTrue()
        assertThat(EpgRefreshPolicy.MAX_DEFERRALS).isEqualTo(3)
        // One "the TV is busy" story covers both jobs: EPG defers on the same backoff the refresh uses.
        assertThat(EpgRefreshPolicy.DEFER_BACKOFF_MS)
            .isEqualTo(PlaybackAvoidancePolicy.DEFER_BACKOFF_MS)
    }

    @Test
    fun `nothing runs while EPG is switched off, not even the user's own button`() {
        val off = EpgRefreshSettings(enabled = false)

        assertThat(decide(RefreshTrigger.FIRST_RUN, settings = off))
            .isEqualTo(EpgRefreshDecision(EpgRefreshAction.SKIP, EpgRefreshPolicy.REASON_DISABLED))
        assertThat(decide(RefreshTrigger.MANUAL, settings = off).action)
            .isEqualTo(EpgRefreshAction.SKIP)
    }

    @Test
    fun `a cold start inside the interval does nothing, which is what makes repeated triggers idempotent`() {
        assertThat(decide(RefreshTrigger.FIRST_RUN, lastFetchAtMs = lastFetch(60_000)))
            .isEqualTo(EpgRefreshDecision(EpgRefreshAction.SKIP, EpgRefreshPolicy.REASON_FRESH))
        // The same gate covers the post-refresh follow-up.
        assertThat(decide(RefreshTrigger.SCHEDULED, lastFetchAtMs = lastFetch(interval - 1)).action)
            .isEqualTo(EpgRefreshAction.SKIP)
    }

    @Test
    fun `data right at the interval is stale, one millisecond younger is not`() {
        assertThat(decide(RefreshTrigger.SCHEDULED, lastFetchAtMs = lastFetch(interval)).action)
            .isEqualTo(EpgRefreshAction.RUN)
        assertThat(decide(RefreshTrigger.SCHEDULED, lastFetchAtMs = lastFetch(interval - 1)).action)
            .isEqualTo(EpgRefreshAction.SKIP)
    }

    @Test
    fun `a stale dataset runs on both background triggers`() {
        assertThat(decide(RefreshTrigger.FIRST_RUN))
            .isEqualTo(EpgRefreshDecision(EpgRefreshAction.RUN, EpgRefreshPolicy.REASON_STALE))
        assertThat(decide(RefreshTrigger.SCHEDULED).action).isEqualTo(EpgRefreshAction.RUN)
    }

    @Test
    fun `never fetched means stale, so the very first run always happens`() {
        assertThat(decide(RefreshTrigger.FIRST_RUN, lastFetchAtMs = null).action)
            .isEqualTo(EpgRefreshAction.RUN)
    }

    @Test
    fun `the user's button ignores the freshness gate and a playing session`() {
        assertThat(decide(RefreshTrigger.MANUAL, lastFetchAtMs = lastFetch(0)))
            .isEqualTo(EpgRefreshDecision(EpgRefreshAction.RUN, EpgRefreshPolicy.REASON_MANUAL))
        assertThat(decide(RefreshTrigger.MANUAL, playing = true, lastFetchAtMs = lastFetch(0)).action)
            .isEqualTo(EpgRefreshAction.RUN)
    }

    @Test
    fun `a background trigger defers while playing, but only until the cap`() {
        val trigger = RefreshTrigger.SCHEDULED

        assertThat(decide(trigger, playing = true, deferrals = 0))
            .isEqualTo(EpgRefreshDecision(EpgRefreshAction.DEFER, EpgRefreshPolicy.REASON_PLAYING))
        assertThat(decide(trigger, playing = true, deferrals = 1).action).isEqualTo(EpgRefreshAction.DEFER)
        assertThat(decide(trigger, playing = true, deferrals = 2).action).isEqualTo(EpgRefreshAction.DEFER)
        // Fourth attempt: a TV left on a channel all evening still gets its guide.
        assertThat(decide(trigger, playing = true, deferrals = 3).action).isEqualTo(EpgRefreshAction.RUN)
    }

    @Test
    fun `a cold start during playback also waits, because it is not the user asking`() {
        assertThat(decide(RefreshTrigger.FIRST_RUN, playing = true).action)
            .isEqualTo(EpgRefreshAction.DEFER)
    }

    @Test
    fun `avoidance off means playback does not postpone anything`() {
        assertThat(decide(RefreshTrigger.SCHEDULED, playing = true, respectPlayback = false).action)
            .isEqualTo(EpgRefreshAction.RUN)
    }

    @Test
    fun `the enqueue gate ignores playback, so a fresh dataset costs no wake-up at all`() {
        val fresh = lastFetch(60_000)
        val stale = lastFetch(interval + 1)

        assertThat(policy.gate(RefreshTrigger.SCHEDULED, fresh, now, settings))
            .isEqualTo(policy.decide(RefreshTrigger.SCHEDULED, false, true, 0, fresh, now, settings))
        assertThat(policy.gate(RefreshTrigger.SCHEDULED, stale, now, settings).action)
            .isEqualTo(EpgRefreshAction.RUN)
    }
}
