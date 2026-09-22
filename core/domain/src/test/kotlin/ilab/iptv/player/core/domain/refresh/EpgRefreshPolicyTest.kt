package ilab.iptv.player.core.domain.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.EpgStoredGuide
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

    /** A stored guide with channels, bindings and programmes: the ordinary, healthy case. */
    private val ready = EpgStoredGuide(channels = 571, matched = 153, programmed = 126)

    /** A dataset fetched [ageMs] ago. */
    private fun lastFetch(ageMs: Long): Long = now - ageMs

    private fun decide(
        trigger: RefreshTrigger,
        playing: Boolean = false,
        respectPlayback: Boolean = true,
        deferrals: Int = 0,
        lastFetchAtMs: Long? = lastFetch(interval + 1),
        settings: EpgRefreshSettings = this.settings,
        stored: EpgStoredGuide = ready,
    ) = policy.decide(
        trigger = trigger,
        playing = playing,
        respectPlayback = respectPlayback,
        deferrals = deferrals,
        lastFetchAtMs = lastFetchAtMs,
        nowMs = now,
        settings = settings,
        stored = stored,
    )

    @Test
    fun `the default interval is six hours and the deferral cap is three`() {
        assertThat(settings.minIntervalMs).isEqualTo(6L * 60 * 60_000L)
        assertThat(settings.enabled).isTrue()
        assertThat(EpgRefreshPolicy.MAX_DEFERRALS).isEqualTo(3)
        // The empty-guide retry is the shorter window BUG-016 asked for, and the same 30 min the
        // deferral uses: one "come back later" story for both.
        assertThat(settings.emptyRetryMs).isEqualTo(30L * 60_000L)
        assertThat(settings.emptyRetryMs).isEqualTo(EpgRefreshPolicy.DEFER_BACKOFF_MS)
        // One "the TV is busy" story covers both jobs: EPG defers on the same backoff the refresh uses.
        assertThat(EpgRefreshPolicy.DEFER_BACKOFF_MS)
            .isEqualTo(PlaybackAvoidancePolicy.DEFER_BACKOFF_MS)
    }

    @Test
    fun `no channel list yet defers instead of fetching into an empty table`() {
        // BUG-20260922-016: the cold-start white run. `matched=0/total=0` was the symptom; the bug was
        // running at all, and stamping `last_fetch_at` while doing it.
        val noChannels = EpgStoredGuide(channels = 0, matched = 0, programmed = 0)

        assertThat(decide(RefreshTrigger.FIRST_RUN, lastFetchAtMs = null, stored = noChannels))
            .isEqualTo(EpgRefreshDecision(EpgRefreshAction.DEFER, EpgRefreshPolicy.REASON_CATALOG_EMPTY))
        // Every trigger waits, the user's button included: there is nothing to bind, so a run would only
        // burn a fetch. Nothing here is capped by MAX_DEFERRALS — waiting costs no network.
        assertThat(decide(RefreshTrigger.MANUAL, lastFetchAtMs = null, stored = noChannels).action)
            .isEqualTo(EpgRefreshAction.DEFER)
        assertThat(decide(RefreshTrigger.FIRST_RUN, deferrals = 99, stored = noChannels).action)
            .isEqualTo(EpgRefreshAction.DEFER)
    }

    @Test
    fun `a run that matched nothing is not fresh, so it is allowed to run again`() {
        // The second half of BUG-016: a successful fetch of four guides over a channel table it could
        // not bind is not "fresh data" — the TV has no guide. matched=0 and programmed=0 both count.
        val matchedNothing = EpgStoredGuide(channels = 571, matched = 0, programmed = 0)
        val emptyBindings = EpgStoredGuide(channels = 571, matched = 140, programmed = 0)
        // Older than the empty retry window — the same trigger at 20 minutes is the backoff case below.
        val old = lastFetch(EpgRefreshSettings.DEFAULT_EMPTY_RETRY_MS + 1)

        assertThat(decide(RefreshTrigger.FIRST_RUN, lastFetchAtMs = old, stored = matchedNothing))
            .isEqualTo(EpgRefreshDecision(EpgRefreshAction.RUN, EpgRefreshPolicy.REASON_EMPTY_GUIDE))
        assertThat(decide(RefreshTrigger.FIRST_RUN, lastFetchAtMs = old, stored = emptyBindings).action)
            .isEqualTo(EpgRefreshAction.RUN)
    }

    @Test
    fun `consecutive empty runs are spaced by the short backoff, never by nothing`() {
        // An empty guide is retried, but not immediately and not forever: each attempt waits at least
        // emptyRetryMs after the last fetch, and the normal six-hour window remains the ceiling. That
        // pair is the "退避上限" this card asks for — a bounded retry cadence instead of a loop.
        val empty = EpgStoredGuide(channels = 571, matched = 0, programmed = 0)
        val retry = EpgRefreshSettings.DEFAULT_EMPTY_RETRY_MS

        assertThat(decide(RefreshTrigger.FIRST_RUN, lastFetchAtMs = lastFetch(0), stored = empty))
            .isEqualTo(EpgRefreshDecision(EpgRefreshAction.SKIP, EpgRefreshPolicy.REASON_EMPTY_BACKOFF))
        assertThat(decide(RefreshTrigger.SCHEDULED, lastFetchAtMs = lastFetch(retry - 1), stored = empty).action)
            .isEqualTo(EpgRefreshAction.SKIP)
        assertThat(decide(RefreshTrigger.SCHEDULED, lastFetchAtMs = lastFetch(retry), stored = empty).action)
            .isEqualTo(EpgRefreshAction.RUN)
        // ...and never faster than the floor, however many empty runs pile up: 30 minutes after each one.
        assertThat(decide(RefreshTrigger.FIRST_RUN, lastFetchAtMs = lastFetch(retry / 2), stored = empty).action)
            .isEqualTo(EpgRefreshAction.SKIP)
    }

    @Test
    fun `a configured empty window can never be longer than the normal one`() {
        val odd = EpgRefreshSettings(minIntervalMs = 10 * 60_000L, emptyRetryMs = 6 * 60 * 60_000L)
        val empty = EpgStoredGuide(channels = 10, matched = 0, programmed = 0)

        // Clamped to minIntervalMs: an "empty" guide must not be trusted for longer than a full one.
        assertThat(decide(RefreshTrigger.SCHEDULED, lastFetchAtMs = lastFetch(11 * 60_000L), settings = odd, stored = empty).action)
            .isEqualTo(EpgRefreshAction.RUN)
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

        assertThat(policy.gate(RefreshTrigger.SCHEDULED, fresh, now, settings, ready))
            .isEqualTo(policy.decide(RefreshTrigger.SCHEDULED, false, true, 0, fresh, now, settings, ready))
        assertThat(policy.gate(RefreshTrigger.SCHEDULED, stale, now, settings, ready).action)
            .isEqualTo(EpgRefreshAction.RUN)
    }
}
