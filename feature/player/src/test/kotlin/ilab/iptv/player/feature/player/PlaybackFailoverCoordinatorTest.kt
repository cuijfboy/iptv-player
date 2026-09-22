package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.domain.playback.FailoverLimits
import ilab.iptv.player.core.domain.playback.FailoverTuning
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

    // ---------------------------------------------------------------- SWITCH-P95-1（换台 p95）
    // docs/05-过程记录/65-换台p95修复.md：起播超时（未出首帧）不再重试同一流。

    @Test
    fun `a start-up timeout switches to the backup at once, without waiting the second window`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        // The dead main source really costs its window; the backup comes up at once.
        harness.port.prepareDelayFor = { id, _ -> if (id == main.id) 4_000L else 0L }
        harness.port.responses = { id, _ ->
            if (id == main.id) AppResult.Err(failure(FailureClass.TIMEOUT)) else prepared(id)
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(10_000)
        runCurrent()

        // ① 首次超时→立即切换：the dead main source is prepared ONCE, then the channel moves on.
        // 修前（G7-1 §5.4 实测 24,940 ms）是两轮 12 s 超时：RetrySame(≤1) → SwitchTo(next)。
        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 2L).inOrder()
        assertThat(harness.port.prepares.map { it.attempt }).containsExactly(1, 1).inOrder()
        assertThat(harness.port.switchedTo).containsExactly(2L)
        val switch = harness.logger.all(EventCodes.PLAY_FAILOVER).first { it.fields["action"] == "switch" }
        assertThat(switch.fields["from"]).isEqualTo(1L)
        assertThat(switch.fields["to"]).isEqualTo(2L)
        assertThat(switch.fields["reason"]).isEqualTo("TIMEOUT")
        assertThat(switch.fields["attempt"]).isEqualTo(1)
        // god 条件 3b：起播失败与播放中失败在 `PLAY_FAILOVER` 里可区分（只加字段）。
        assertThat(switch.fields["stage"]).isEqualTo(PlaybackFailoverCoordinator.STAGE_STARTUP)
        // 判据 ≤5 s：主源窗口（4 s，见 harness/coordinator 常量）+ 备胎自身起播（夹具为 0）。
        val deadSourceCostMs = harness.port.prepares[1].atMs - harness.port.prepares[0].atMs
        assertThat(deadSourceCostMs)
            .isEqualTo(PlaybackFailoverCoordinator.FAST_FIRST_ATTEMPT_TIMEOUT_MS)
        assertThat(deadSourceCostMs).isAtMost(5_000L)
        // ④ 切换只发生一次：备胎活着，就不会再回切主源。
        val switchActions = harness.logger.all(EventCodes.PLAY_FAILOVER).map { it.fields["action"] }
        assertThat(switchActions.count { it == "switch" }).isEqualTo(1)
        harness.stop()
    }

    @Test
    fun `a slow but usable main source is traded for the backup at 4 s - an intentional trade`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        val slowStartMs = 6_000L
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        // 主源 6 s 才能出首帧——比 4 s 窗口慢，但**能**出画（G7-1 实测慢源尾部 3.7–6.2 s）。
        harness.port.prepareDelayFor = { id, timeoutMs ->
            if (id == main.id) minOf(slowStartMs, timeoutMs) else 0L
        }
        harness.port.responses = { id, _ ->
            if (id == main.id && harness.port.lastRequestedTimeoutMs < slowStartMs) {
                AppResult.Err(failure(FailureClass.TIMEOUT))
            } else {
                prepared(id)
            }
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(30_000)
        runCurrent()

        // god 条件 3c：这是**有意**的取舍——用户 4 s 拿到备胎画面，而不是等主源第 6 s 出画。
        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 2L).inOrder()
        val switchAt = harness.port.prepares[1].atMs - harness.port.prepares[0].atMs
        assertThat(switchAt).isEqualTo(PlaybackFailoverCoordinator.FAST_FIRST_ATTEMPT_TIMEOUT_MS)
        assertThat(switchAt).isLessThan(slowStartMs)
        harness.stop()
    }

    @Test
    fun `when the backup fails to start too the channel comes back to the slow main source`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        val slowStartMs = 6_000L
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        harness.port.prepareDelayFor = { id, timeoutMs ->
            when {
                id == main.id -> minOf(slowStartMs, timeoutMs)
                else -> timeoutMs // 备胎永远不出首帧（死）
            }
        }
        harness.port.responses = { id, _ ->
            when {
                id == backup.id -> AppResult.Err(failure(FailureClass.TIMEOUT))
                harness.port.lastRequestedTimeoutMs < slowStartMs ->
                    AppResult.Err(failure(FailureClass.TIMEOUT))

                else -> prepared(id)
            }
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(120_000)
        runCurrent()

        // god 条件 3a：4 s 窗口不能把慢主源永久丢掉——备胎也失败后，有界重试仍能回到主源，
        // 并给它一个**完整**窗口（12 s），于是这条 6 s 的源最终出画。
        assertThat(harness.port.prepares.map { it.streamId })
            .containsExactly(1L, 2L, 2L, 1L)
            .inOrder()
        val mainPrepares = harness.port.prepares.filter { it.streamId == main.id }
        // 首次 4 s（快窗口，只在会话第一跳）→ 回切后的那次 12 s（完整窗口）→ 6 s 的源出画。
        assertThat(mainPrepares.map { it.timeoutMs })
            .containsExactly(
                PlaybackFailoverCoordinator.FAST_FIRST_ATTEMPT_TIMEOUT_MS,
                PlaybackFailoverCoordinator.DEFAULT_PREPARE_TIMEOUT_MS,
            )
            .inOrder()
        assertThat(harness.port.exhausted).isEmpty()
        harness.stop()
    }

    /**
     * 复现（补充，要求 1）：两条流都死的频道也会被 §4.6 的 `maxSwitchPerSession` 兜住，并且**比修前更快**
     * 放弃（修前 8 个 12 s 窗口 = 96 s；修后 = 4 s + 6 个 12 s = 76 s）。
     */
    @Test
    fun `reproduction - a fully dead channel gives up sooner than before the fix`() = runTest {
        val before = fullyDeadGiveUpMs(switchOnStartupTimeout = false, fastFirstAttemptTimeoutMs = 0L)
        val after = fullyDeadGiveUpMs(
            switchOnStartupTimeout = true,
            fastFirstAttemptTimeoutMs = PlaybackFailoverCoordinator.FAST_FIRST_ATTEMPT_TIMEOUT_MS,
        )

        assertThat(before).isEqualTo(96_000L)
        assertThat(after).isEqualTo(76_000L)
        assertThat(after).isLessThan(before)
    }

    /** Virtual time from session start to `onFailoverExhausted` on a channel where nothing comes up. */
    private fun TestScope.fullyDeadGiveUpMs(
        switchOnStartupTimeout: Boolean,
        fastFirstAttemptTimeoutMs: Long,
    ): Long {
        val harness = harness(
            tuning = FailoverTuning(switchOnStartupTimeout = switchOnStartupTimeout),
            fastFirstAttemptTimeoutMs = fastFirstAttemptTimeoutMs,
        )
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        harness.port.prepareDelayFor = { _, timeoutMs -> timeoutMs }
        harness.port.responses = { _, _ -> AppResult.Err(failure(FailureClass.TIMEOUT)) }

        val startedAt = harness.clock.nowMs()
        harness.coordinator.open(channel, main)
        advanceTimeBy(600_000)
        runCurrent()

        val at = harness.port.exhaustedAtMs ?: error("the channel never gave up")
        harness.stop()
        return at - startedAt
    }

    @Test
    fun `the first attempt of a channel with a backup gets the fast window, later attempts do not`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        harness.port.responses = { id, attempt ->
            if (id == main.id && attempt == 1) AppResult.Err(failure(FailureClass.HTTP_SERVER)) else prepared(id)
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(30_000)
        runCurrent()

        // §4.6 HTTP_SERVER is untouched: Backoff(1 s) → RetrySame → prepare again. The window is the
        // fast one on the channel's first attempt and the §7.5 12 s one on every later attempt.
        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 1L).inOrder()
        assertThat(harness.port.prepares.map { it.timeoutMs })
            .containsExactly(
                PlaybackFailoverCoordinator.FAST_FIRST_ATTEMPT_TIMEOUT_MS,
                PlaybackFailoverCoordinator.DEFAULT_PREPARE_TIMEOUT_MS,
            )
            .inOrder()
        harness.stop()
    }

    @Test
    fun `a mid-play timeout still retries the same stream - a live edge overrun is not a switch`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        harness.port.responses = { id, _ -> prepared(id) }
        var failedOnce = false
        harness.port.samples = {
            // A failure AFTER the first frame: `ERROR_CODE_BEHIND_LIVE_WINDOW` also maps to TIMEOUT,
            // and there the frozen RetrySame step is the correct answer (switching would be a 误切).
            if (!failedOnce && harness.clock.nowMs() >= 1_000) {
                failedOnce = true
                harness.port.failMidPlay(failure(FailureClass.TIMEOUT))
            }
            EngineSample(harness.clock.nowMs(), harness.clock.nowMs(), false)
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(60_000)
        runCurrent()

        assertThat(harness.port.prepares.map { it.streamId }).containsExactly(1L, 1L).inOrder()
        assertThat(harness.port.switchedTo).isEmpty()
        harness.stop()
    }

    /**
     * 复现（要求 1）：把「死主源 + 活备胎」在夹具里跑到切备胎，量化修前/修后的用户等待。
     *
     * 修前 = `failoverTuning.switchOnStartupTimeout = false` + 12 s 窗口（与基线 `c1ea5cc` 逐字一致）：
     * `RetrySame(≤1) → SwitchTo(next)` = 两次 12 s = 24,000 ms（G7-1 §5.4 真机 24,940 ms）。
     * 修后 = 4 s 窗口 + 首次超时立即切换 = 4,000 ms ≤ 5 s（要求 4 的判据）。
     */
    @Test
    fun `reproduction - the dead main source cost two 12 s windows before the fix and one 4 s window after`() = runTest {
        val before = deadMainSwitchCostMs(
            tuning = FailoverTuning(switchOnStartupTimeout = false),
            fastFirstAttemptTimeoutMs = 0L,
        )
        val after = deadMainSwitchCostMs(
            tuning = FailoverTuning(),
            fastFirstAttemptTimeoutMs = PlaybackFailoverCoordinator.FAST_FIRST_ATTEMPT_TIMEOUT_MS,
        )

        assertThat(before).isEqualTo(24_000L)
        assertThat(after).isEqualTo(4_000L)
        assertThat(after).isAtMost(5_000L)
    }

    /** Virtual-time cost from the dead main source's first `prepare` to the backup's. */
    private fun TestScope.deadMainSwitchCostMs(
        tuning: FailoverTuning,
        fastFirstAttemptTimeoutMs: Long,
    ): Long {
        val harness = harness(tuning = tuning, fastFirstAttemptTimeoutMs = fastFirstAttemptTimeoutMs)
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        // A truly dead main source: it never produces a first frame, so it costs exactly the window it
        // was given. The backup comes up at once (real ones took 729–805 ms, G7-1 §5.5 / G7-2 §4.5).
        harness.port.prepareDelayFor = { id, timeoutMs -> if (id == main.id) timeoutMs else 0L }
        harness.port.responses = { id, _ ->
            if (id == main.id) AppResult.Err(failure(FailureClass.TIMEOUT)) else prepared(id)
        }

        harness.coordinator.open(channel, main)
        advanceTimeBy(60_000)
        runCurrent()

        val cost = harness.port.prepares.last().atMs - harness.port.prepares.first().atMs
        harness.stop()
        return cost
    }

    @Test
    fun `a fully dead channel stays inside the switch budget and still ends in give_up`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        harness.port.responses = { _, _ -> AppResult.Err(failure(FailureClass.TIMEOUT)) }

        harness.coordinator.open(channel, main)
        advanceTimeBy(180_000)
        runCurrent()

        // 两条流都起不来时不会无限来回：仍然受 §4.6 的 `maxSwitchPerSession`(=3) 约束并最终 give_up；
        // 「切换只发生一次」的判据由 `a start-up timeout switches to the backup at once…` 断言（备胎活）。
        val actions = harness.logger.all(EventCodes.PLAY_FAILOVER).map { it.fields["action"] }
        assertThat(actions.count { it == "switch" }).isAtMost(harness.limits.maxSwitchPerSession)
        assertThat(actions.count { it == "switch" }).isAtLeast(1)
        assertThat(actions).contains("give_up")
        assertThat(harness.port.exhausted).containsExactly(HINT_NO_SOURCE)
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
        // ③ 无备胎语义逐字不变（SWITCH-P95-1 不碰这条路）：没有备胎就没有快窗口，仍走 12 s。
        assertThat(harness.port.prepares.map { it.timeoutMs })
            .containsExactly(
                PlaybackFailoverCoordinator.DEFAULT_PREPARE_TIMEOUT_MS,
                PlaybackFailoverCoordinator.DEFAULT_PREPARE_TIMEOUT_MS,
            )
            .inOrder()
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
        // 看门狗判的是**播放中**的问题（已出首帧），所以 stage 必须是 playing（god 条件 3b）。
        assertThat(switch.fields["stage"]).isEqualTo(PlaybackFailoverCoordinator.STAGE_PLAYING)
        harness.stop()
    }

    @Test
    fun `a pause that came through the MediaSession is not a stall`() = runTest {
        val harness = harness()
        val channel = channel(1)
        val main = stream(1, channelId = 1, score = 80)
        val backup = stream(2, channelId = 1, score = 60)
        harness.catalog.candidatesByChannel = mapOf(1L to listOf(main, backup))
        // P1-7: the remote's play/pause key and the TV's system media control pause the player through
        // the MediaSession, which never reaches the coordinator's own `paused` flag. A frozen position
        // with `playWhenReady = false` is a *pause*, not the stall the watchdog exists for.
        harness.port.samples = { EngineSample(0, 0, false, playWhenReady = false) }

        harness.coordinator.open(channel, main)
        advanceTimeBy(20_000)
        runCurrent()

        assertThat(harness.logger.all(EventCodes.PLAY_STALL)).isEmpty()
        assertThat(harness.port.prepares).hasSize(1)
        assertThat(harness.port.exhausted).isEmpty()
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
    private fun TestScope.harness(
        tuning: FailoverTuning = FailoverTuning(),
        fastFirstAttemptTimeoutMs: Long = PlaybackFailoverCoordinator.FAST_FIRST_ATTEMPT_TIMEOUT_MS,
    ): FailoverHarness = FailoverHarness(
        scope = backgroundScope,
        scheduler = testScheduler,
        tuning = tuning,
        fastFirstAttemptTimeoutMs = fastFirstAttemptTimeoutMs,
    )

    private fun FailoverHarness.stop() = coordinator.stop(reason = "test-end")
}
