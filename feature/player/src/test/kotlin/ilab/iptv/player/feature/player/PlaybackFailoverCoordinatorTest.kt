package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.domain.playback.FailoverLimits
import ilab.iptv.player.core.player.EngineSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P1-5 acceptance: the wired controller calls the frozen policy in the order docs/02 §4.6 says, and
 * the playback commands that come out of it are the ones the user sees (retry, switch, give up).
 *
 * Everything runs on the JVM with virtual time: the backoff (`1 s`), the stall threshold (`8 s`) and
 * the watchdog tick are all real numbers from `FailoverLimits`/`WatchdogConfig`, driven by the test
 * scheduler instead of by sleeping.
 *
 * The engine-side proof (real streams, a real dead source, 30 channel switches) is on the device and
 * lives in `docs/05-过程记录/20-P1-5换台与故障转移验证.md`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFailoverCoordinatorTest {

    @Test
    fun `a dead source switches to the backup at once and logs PLAY_FAILOVER`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        harness.port.responses = { id, _ ->
            if (id == main.id) AppResult.Err(failure(FailureClass.HTTP_CLIENT)) else prepared(id)
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(1_000)
        runCurrent()

        // §4.6 HTTP_CLIENT: SwitchTo(next) with no retry of the dead source.
        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 2L).inOrder()
        assertThat(harness.port.prepares.map { it.attempt }).containsExactly(1, 1).inOrder()
        assertThat(harness.port.runningHints).contains(HINT_SWITCHING)
        assertThat(harness.port.switchedTo).containsExactly(2L)
        val switch = harness.logger.all(EventCodes.PLAY_FAILOVER).first()
        assertThat(switch.fields["from"]).isEqualTo(1L)
        assertThat(switch.fields["to"]).isEqualTo(2L)
        assertThat(switch.fields["reason"]).isEqualTo("HTTP_CLIENT")
        assertThat(switch.fields["attempt"]).isEqualTo(1)
        harness.stop()
    }

    @Test
    fun `a server error backs off, retries the same stream once, then switches`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        harness.port.responses = { id, _ ->
            if (id == main.id) AppResult.Err(failure(FailureClass.HTTP_SERVER)) else prepared(id)
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(5_000)
        runCurrent()

        // §4.6 HTTP_SERVER: Backoff(1 s) -> RetrySame(<=1) -> SwitchTo(next). The backoff consumes an
        // attempt ordinal without preparing anything, which is why the retry is attempt 3, not 2.
        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 1L, 2L).inOrder()
        assertThat(harness.port.prepares.map { it.attempt }).containsExactly(1, 3, 1).inOrder()
        assertThat(harness.port.prepares[1].atMs - harness.port.prepares[0].atMs)
            .isAtLeast(FailoverLimits().backoffMs)
        harness.stop()
    }

    @Test
    fun `a timeout retries once and then switches`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        harness.port.responses = { id, _ ->
            if (id == main.id) AppResult.Err(failure(FailureClass.TIMEOUT)) else prepared(id)
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 1L, 2L).inOrder()
        assertThat(harness.port.prepares.map { it.attempt }).containsExactly(1, 2, 1).inOrder()
        harness.stop()
    }

    @Test
    fun `an unsupported codec retries the same stream with passthrough off`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main))
        harness.port.responses = { id, attempt ->
            if (id == main.id && attempt == 1) {
                AppResult.Err(failure(FailureClass.NO_CAPABILITY))
            } else {
                prepared(id)
            }
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(1_000)
        runCurrent()

        // §7.6 step 2 / §4.6 NO_CAPABILITY: the retry is the same stream with preferPassthrough=false.
        assertThat(harness.port.prepares).hasSize(2)
        assertThat(harness.port.prepares[0].preferPassthrough).isTrue()
        assertThat(harness.port.prepares[1].streamId).isEqualTo(main.id)
        assertThat(harness.port.prepares[1].preferPassthrough).isFalse()
        harness.stop()
    }

    @Test
    fun `a channel with no backup retries, re-probes once and gives up with a message`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val only = stream(1, channelId = 1, score = 80)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(only))
        harness.port.responses = { _, _ -> AppResult.Err(failure(FailureClass.TIMEOUT)) }

        harness.coordinator.open(channel, only)
        advanceTimeBy(2_000)
        runCurrent()

        // docs/01 F4: RetrySame(<=1) -> ResolveFresh (once) -> GiveUp + user message.
        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 1L).inOrder()
        assertThat(harness.catalog.reprobed).containsExactly(1L)
        assertThat(harness.port.exhausted).containsExactly(HINT_NO_SOURCE)
        // `PLAY_PREPARE_FAIL` belongs to the session's own telemetry (it sees the failed prepare);
        // the coordinator owns `PLAY_FAILOVER`, which is what it decides and acts on.
        assertThat(harness.logger.codes()).contains(EventCodes.PLAY_FAILOVER)
        val actions = harness.logger.all(EventCodes.PLAY_FAILOVER).map { it.fields["action"] }
        assertThat(actions).containsAtLeast("resolve_fresh", "give_up")
        harness.stop()
    }

    @Test
    fun `a fresh resolve that finds a new stream plays it instead of giving up`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val only = stream(1, channelId = 1, score = 80)
        val refreshed = stream(9, channelId = 1, score = 90)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(only))
        harness.catalog.reprobeResult = mapOf(1L to listOf(only, refreshed))
        harness.port.responses = { id, _ ->
            if (id == only.id) AppResult.Err(failure(FailureClass.TIMEOUT)) else prepared(id)
        }

        harness.coordinator.open(channel, only)
        advanceTimeBy(2_000)
        runCurrent()

        assertThat(harness.catalog.reprobed).containsExactly(1L)
        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 1L, 9L).inOrder()
        assertThat(harness.port.exhausted).isEmpty()
        harness.stop()
    }

    @Test
    fun `storage and permission failures never switch and never emit PLAY_FAILOVER`() = runTest {
        val expectations = mapOf(
            FailureClass.STORAGE to "存储不可用，本次改动不会保存",
            FailureClass.PERMISSION to "请授予存储权限",
        )
        expectations.forEach { (failureClass, message) ->
            val harness = harness()
            val channel = channel(1)
            val main = stream(1, channelId = 1, score = 80)
            val backup = stream(2, channelId = 1, score = 60)
            harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
            harness.port.responses = { _, _ -> AppResult.Err(failure(failureClass)) }

            harness.coordinator.open(channel, main)
            advanceTimeBy(1_000)
            runCurrent()

            assertThat(harness.port.prepares).hasSize(1)
            assertThat(harness.port.exhausted).containsExactly(message)
            assertThat(harness.logger.all(EventCodes.PLAY_FAILOVER)).isEmpty()
            harness.stop()
        }
    }

    @Test
    fun `a stall over the threshold is logged and switches to the backup`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        // A live stream that freezes: position and buffer stop moving, no error is ever reported.
        harness.port.samples = { EngineSample(0, 0, false) }

        harness.coordinator.open(channel, main)
        advanceTimeBy(12_000)
        runCurrent()

        // §4.6 看门狗: PLAY_STALL, then Backoff(1 s) -> SwitchTo(next).
        assertThat(harness.logger.all(EventCodes.PLAY_STALL)).isNotEmpty()
        assertThat(harness.port.prepares.map { it.streamId }.take(2)).containsExactly(1L, 2L).inOrder()
        val switch = harness.logger.all(EventCodes.PLAY_FAILOVER).first()
        assertThat(switch.fields["action"]).isEqualTo("switch")
        assertThat(switch.fields["reason"]).isEqualTo(FailureClass.TIMEOUT.name)
        harness.stop()
    }

    @Test
    fun `switching a channel logs PLAY_SWITCH_CHANNEL with from, to and cost`() = runTest {
        val harness = harness()
        val first = channel(1)
        val second = channel(2)
        val main = stream(1, channelId = 1, score = 80)
        val other = stream(2, channelId = 2, score = 80)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main), 2L to listOf(other))
        harness.port.prepareDelayMs = 300

        harness.coordinator.open(first, main)
        advanceTimeBy(1_000)
        runCurrent()
        harness.coordinator.open(second, other, switchedFromChannelId = first.id)
        advanceTimeBy(1_000)
        runCurrent()

        val entry = harness.logger.first(EventCodes.PLAY_SWITCH_CHANNEL)
        assertThat(entry).isNotNull()
        assertThat(entry!!.fields["from"]).isEqualTo(1L)
        assertThat(entry.fields["to"]).isEqualTo(2L)
        assertThat((entry.fields["costMs"] as Long) >= 300L).isTrue()
        harness.stop()
    }

    @Test
    fun `a channel switch resets the policy session`() = runTest {
        val harness = harness()
        val first = channel(1)
        val second = channel(2)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        val other = stream(3, channelId = 2, score = 80)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup), 2L to listOf(other))
        harness.port.responses = { id, _ ->
            if (id == main.id) AppResult.Err(failure(FailureClass.HTTP_CLIENT)) else prepared(id)
        }

        harness.coordinator.open(first, main)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(harness.policy.switchCountInSession()).isEqualTo(1)
        assertThat(harness.policy.demotedStreamIds()).containsExactly(main.id)

        harness.coordinator.open(second, other, switchedFromChannelId = first.id)
        advanceTimeBy(1_000)
        runCurrent()

        // §4.3 per-session bookkeeping: a new Watch (and a channel switch) clears budget + demotions.
        assertThat(harness.policy.switchCountInSession()).isEqualTo(0)
        assertThat(harness.policy.demotedStreamIds()).isEmpty()
        harness.stop()
    }

    @Test
    fun `the switch budget caps one session`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val streams = (1L..4L).map { stream(it, channelId = 1, score = (100 - it * 10).toInt()) }
        harness.catalog.candidatesByChannel = mapOf(1L to streams)
        harness.port.responses = { _, _ -> AppResult.Err(failure(FailureClass.HTTP_CLIENT)) }

        harness.coordinator.open(channel, streams.first())
        advanceTimeBy(2_000)
        runCurrent()

        // FailoverLimits.maxSwitchPerSession = 3: four streams, three switches, then give up.
        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(harness.policy.switchCountInSession()).isEqualTo(FailoverLimits().maxSwitchPerSession)
        assertThat(harness.port.exhausted).isNotEmpty()
        harness.stop()
    }

    /** One wired system per test; the coordinator's coroutine runs in `backgroundScope`. */
    private fun TestScope.harness(): FailoverHarness = FailoverHarness(backgroundScope, testScheduler)

    private fun FailoverHarness.stop() = coordinator.stop(reason = "test-end")
}
