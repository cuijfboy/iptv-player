package ilab.iptv.player.core.domain.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.RefreshTrigger
import org.junit.Test

/**
 * Pins the R7 scheduling policy (docs/04 P2-5 item 3, docs/02 §4.5 C3): a scheduled run waits for a
 * playing session, user-facing runs do not, and the waiting is bounded so a TV left on all day still
 * gets refreshed.
 */
class PlaybackAvoidancePolicyTest {

    private val policy = PlaybackAvoidancePolicy()

    @Test
    fun `nothing playing means run, whatever the trigger`() {
        RefreshTrigger.entries.forEach { trigger ->
            assertThat(policy.decide(playing = false, respectPlayback = true, trigger = trigger, deferrals = 0))
                .isEqualTo(RefreshRunDecision.RUN)
        }
    }

    @Test
    fun `a scheduled run defers while playing`() {
        assertThat(
            policy.decide(
                playing = true,
                respectPlayback = true,
                trigger = RefreshTrigger.SCHEDULED,
                deferrals = 0,
            ),
        ).isEqualTo(RefreshRunDecision.DEFER)
    }

    @Test
    fun `user-facing triggers always run, because somebody is waiting`() {
        listOf(
            RefreshTrigger.MANUAL,
            RefreshTrigger.FIRST_RUN,
            RefreshTrigger.ON_DEMAND_SINGLE_CHANNEL,
        ).forEach { trigger ->
            assertThat(policy.decide(playing = true, respectPlayback = true, trigger = trigger, deferrals = 0))
                .isEqualTo(RefreshRunDecision.RUN)
        }
    }

    @Test
    fun `respectPlayback false disables the deferral entirely`() {
        assertThat(
            policy.decide(
                playing = true,
                respectPlayback = false,
                trigger = RefreshTrigger.SCHEDULED,
                deferrals = 0,
            ),
        ).isEqualTo(RefreshRunDecision.RUN)
    }

    @Test
    fun `the deferral stops at MAX_DEFERRALS so a playing TV is still refreshed`() {
        val decisions = (0..PlaybackAvoidancePolicy.MAX_DEFERRALS).map { deferrals ->
            policy.decide(
                playing = true,
                respectPlayback = true,
                trigger = RefreshTrigger.SCHEDULED,
                deferrals = deferrals,
            )
        }

        assertThat(decisions).containsExactly(
            RefreshRunDecision.DEFER,
            RefreshRunDecision.DEFER,
            RefreshRunDecision.DEFER,
            RefreshRunDecision.RUN,
        ).inOrder()
    }

    @Test
    fun `the deferral window follows the backoff the WorkRequest uses`() {
        assertThat(PlaybackAvoidancePolicy.DEFER_BACKOFF_MS).isEqualTo(30 * 60_000L)
        // LINEAR backoff: 30 + 60 + 90 min.
        assertThat(PlaybackAvoidancePolicy.DEFER_WINDOW_MS).isEqualTo(3 * 60 * 60_000L)
    }
}
