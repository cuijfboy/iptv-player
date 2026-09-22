package ilab.iptv.player.core.domain.playback

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamHealth
import org.junit.Test

/*
 * The §4.6 decision table, row by row, plus the four P1-6 behaviours the card calls out: same-channel
 * backup preference, consecutive-failure demotion, the cooldown window, and the no-backup path of
 * docs/01 F4 (`RetrySame → ResolveFresh → 明确提示`).
 */
class DefaultFailoverPolicyTest {

    private val policy = DefaultFailoverPolicy()

    private fun decide(
        failureClass: FailureClass,
        attempt: Int = 1,
        channelId: Long = 1L,
        activeStreamId: Long? = 10L,
        candidates: List<Stream> = listOf(stream(10), stream(11)),
        health: Map<Long, StreamHealth> = emptyMap(),
        nowMs: Long = 0L,
        limits: FailoverLimits = FailoverLimits(),
        startupFailure: Boolean = false,
    ): FailoverAction = policy.decide(
        failoverInput(
            channelId = channelId,
            attempt = attempt,
            activeStreamId = activeStreamId,
            candidates = candidates,
            failure = failure(failureClass),
            health = health,
            nowMs = nowMs,
            limits = limits,
            startupFailure = startupFailure,
        ),
    )

    // ---- §4.6 · immediate switch rows ---------------------------------------------------------

    @Test
    fun `http client switches at once and demotes the dead source for the session`() {
        val action = decide(FailureClass.HTTP_CLIENT)
        assertThat(action).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
        assertThat(policy.demotedStreamIds()).containsExactly(10L)

        // The demoted stream is not a target any more: only the active stream is left → fresh resolve.
        val again = decide(FailureClass.HTTP_CLIENT, activeStreamId = 11L)
        assertThat(again).isInstanceOf(FailoverAction.ResolveFresh::class.java)
    }

    // ---- 环境闸门 (docs/05 66): 418/451/511/605 switch this round but earn no permanent demotion ---

    @Test
    fun `an environment-gated refusal switches this round and does not permanently demote the source`() {
        // Negative control: a plain 4xx (404) DOES demote — see the test above. A gate refusal must
        // not, because the source was never reached.
        val action = policy.decide(
            failoverInput(failure = AppError.http(418, EventCodes.PLAY_PREPARE_FAIL)),
        )
        assertThat(action).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
        assertThat(policy.demotedStreamIds()).isEmpty()
    }

    @Test
    fun `an environment-gated source is selectable again in a later round`() {
        // Round 1: the active 10 is gated (418) → switch to 11, but 10 stays selectable.
        policy.decide(failoverInput(activeStreamId = 10L, failure = AppError.http(418, EventCodes.PLAY_PREPARE_FAIL)))
        // Round 2: the active 11 is also gated → the policy may come straight back to 10.
        val back = policy.decide(
            failoverInput(activeStreamId = 11L, failure = AppError.http(451, EventCodes.PLAY_PREPARE_FAIL)),
        )
        assertThat(back).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat((back as FailoverAction.SwitchTo).stream.id).isEqualTo(10L)
        assertThat(policy.demotedStreamIds()).isEmpty()
    }

    @Test
    fun `a gate refusal before a real 404 leaves the gate out of the permanent record`() {
        // Same stream, same session: 605 first (a gate → backoff this round, no demotion), then 404
        // (the source itself saying "gone") — only the 404 is booked permanently.
        val gated = policy.decide(
            failoverInput(activeStreamId = 10L, attempt = 1, failure = AppError.http(605, EventCodes.PLAY_PREPARE_FAIL)),
        )
        assertThat(gated).isInstanceOf(FailoverAction.Backoff::class.java)
        assertThat(policy.demotedStreamIds()).isEmpty()

        val refused = policy.decide(
            failoverInput(activeStreamId = 10L, attempt = 2, failure = AppError.http(404, EventCodes.PLAY_PREPARE_FAIL)),
        )
        assertThat(refused).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat((refused as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
        assertThat(policy.demotedStreamIds()).containsExactly(10L)
    }

    @Test
    fun `network tls parse empty media and unknown switch without retrying`() {
        listOf(
            FailureClass.NET_UNREACHABLE,
            FailureClass.TLS,
            FailureClass.PARSE,
            FailureClass.EMPTY_MEDIA,
            FailureClass.UNKNOWN,
        ).forEach { failureClass ->
            val fresh = DefaultFailoverPolicy()
            val action = fresh.decide(
                failoverInput(failure = failure(failureClass), attempt = 1),
            )
            assertThat(action).isInstanceOf(FailoverAction.SwitchTo::class.java)
            assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
            assertThat(action.reason.failure).isEqualTo(failureClass)
        }
    }

    @Test
    fun `unsupported codec switches and marks the codec combination`() {
        val action = decide(FailureClass.DECODE_UNSUPPORTED)
        assertThat(action).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat(FailurePolicies.plan(FailureClass.DECODE_UNSUPPORTED).demoteCodecCombination).isTrue()
        // Only a 4xx source is demoted outright; a codec problem keeps the stream eligible.
        assertThat(policy.demotedStreamIds()).isEmpty()
    }

    // ---- SWITCH-P95-1 · 起播超时立即切备胎（docs/05-过程记录/65-换台p95修复.md） ------------------

    @Test
    fun `a start-up timeout switches at once and does not come straight back to the dead source`() {
        val action = decide(FailureClass.TIMEOUT, attempt = 1, startupFailure = true)
        assertThat(action).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
        assertThat(policy.startupFailedStreamIds()).containsExactly(10L)
        // 起播失败的流**不被永久降权**（god 条件 3a）：它只是排在后面，后面几轮仍可回退到它。
        assertThat(policy.demotedStreamIds()).isEmpty()

        // From the backup the channel does NOT switch straight back to the source that failed to start
        // — it takes the frozen retry step instead (no ping-pong, docs/05/65 §4.1).
        val next = decide(
            FailureClass.TIMEOUT,
            attempt = 1,
            activeStreamId = 11L,
            startupFailure = true,
        )
        assertThat(next).isEqualTo(FailoverAction.RetrySame(0))
    }

    @Test
    fun `the stream that failed to start is still reachable once nothing fresh is left`() {
        // 主源起播超时（被记为"起播失败"）→ 切备胎；备胎也起播超时（没有 fresh 目标）→ 按 §4.6 重试
        // 备胎一次，第二次失败后 `SwitchTo(next)` 回到主源（god 条件 3a 的「有界回退」）。
        decide(FailureClass.TIMEOUT, attempt = 1, startupFailure = true)
        assertThat(policy.startupFailedStreamIds()).containsExactly(10L)
        decide(FailureClass.TIMEOUT, attempt = 1, activeStreamId = 11L, startupFailure = true)
        assertThat(policy.startupFailedStreamIds()).containsExactly(10L, 11L)
        val back = decide(FailureClass.TIMEOUT, attempt = 2, activeStreamId = 11L, startupFailure = true)
        assertThat(back).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat((back as FailoverAction.SwitchTo).stream.id).isEqualTo(10L)
    }

    @Test
    fun `a mid-play timeout keeps the frozen retry-then-switch recipe`() {
        // `startupFailure = false`: the stream had already produced a first frame (a live-edge overrun
        // also maps to TIMEOUT), where re-preparing the same stream is correct and switching is a 误切.
        assertThat(decide(FailureClass.TIMEOUT, attempt = 1)).isEqualTo(FailoverAction.RetrySame(0))
        assertThat(policy.startupFailedStreamIds()).isEmpty()
    }

    @Test
    fun `a start-up timeout with no alternative keeps the retry and re-probe path`() {
        val action = decide(
            FailureClass.TIMEOUT,
            attempt = 1,
            candidates = listOf(stream(10)),
            startupFailure = true,
        )
        // 单流频道：没有备胎 → 不触发起播快切，逐字走 §4.6（`RetrySame(≤1) → ResolveFresh → GiveUp`）。
        assertThat(action).isEqualTo(FailoverAction.RetrySame(0))
    }

    // ---- §4.6 · retry-then-switch rows --------------------------------------------------------

    @Test
    fun `timeout retries the same stream once and then switches`() {
        assertThat(decide(FailureClass.TIMEOUT, attempt = 1)).isEqualTo(FailoverAction.RetrySame(0))
        val second = decide(FailureClass.TIMEOUT, attempt = 2)
        assertThat(second).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat((second as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
    }

    @Test
    fun `a zero retry budget turns the retry row into an immediate switch`() {
        val action = decide(FailureClass.TIMEOUT, attempt = 1, limits = FailoverLimits(maxRetrySame = 0))
        assertThat(action).isInstanceOf(FailoverAction.SwitchTo::class.java)
    }

    @Test
    fun `http server backs off, retries once, then switches`() {
        assertThat(decide(FailureClass.HTTP_SERVER, attempt = 1)).isEqualTo(FailoverAction.Backoff(1_000))
        assertThat(decide(FailureClass.HTTP_SERVER, attempt = 2)).isEqualTo(FailoverAction.RetrySame(0))
        val third = decide(FailureClass.HTTP_SERVER, attempt = 3)
        assertThat(third).isInstanceOf(FailoverAction.SwitchTo::class.java)
    }

    @Test
    fun `a zero retry budget skips the http server backoff too`() {
        val action = decide(FailureClass.HTTP_SERVER, attempt = 1, limits = FailoverLimits(maxRetrySame = 0))
        assertThat(action).isInstanceOf(FailoverAction.SwitchTo::class.java)
    }

    @Test
    fun `corrupt decode retries once then switches`() {
        assertThat(decide(FailureClass.DECODE_CORRUPT, attempt = 1)).isEqualTo(FailoverAction.RetrySame(0))
        assertThat(decide(FailureClass.DECODE_CORRUPT, attempt = 2))
            .isInstanceOf(FailoverAction.SwitchTo::class.java)
    }

    @Test
    fun `no capability retries without passthrough once, then switches`() {
        assertThat(decide(FailureClass.NO_CAPABILITY, attempt = 1)).isEqualTo(FailoverAction.RetrySame(0))
        assertThat(FailurePolicies.plan(FailureClass.NO_CAPABILITY).retryWithoutPassthrough).isTrue()
        assertThat(decide(FailureClass.NO_CAPABILITY, attempt = 2))
            .isInstanceOf(FailoverAction.SwitchTo::class.java)
    }

    // ---- watchdog path (§4.6 closing note, §6.2) ----------------------------------------------

    @Test
    fun `a stall under the threshold holds the stream`() {
        val action = policy.decide(
            failoverInput(
                failure = null,
                stall = StallSignal(stalledMs = 7_999, positionMs = 1_000, bufferedPositionMs = 2_000),
                limits = FailoverLimits(stallThresholdMs = 8_000),
            ),
        )
        assertThat(action).isEqualTo(FailoverAction.Backoff(0))
    }

    @Test
    fun `a stall over the threshold backs off then switches`() {
        val stall = StallSignal(stalledMs = 8_000, positionMs = 1_000, bufferedPositionMs = 9_000)
        val first = policy.decide(failoverInput(failure = null, stall = stall, attempt = 1))
        assertThat(first).isEqualTo(FailoverAction.Backoff(1_000))

        val second = policy.decide(failoverInput(failure = null, stall = stall, attempt = 2))
        assertThat(second).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat((second as FailoverAction.SwitchTo).reason.failure).isEqualTo(FailureClass.TIMEOUT)
    }

    @Test
    fun `a stall with no backup resolves fresh instead of switching`() {
        val stall = StallSignal(stalledMs = 9_000, positionMs = 1_000, bufferedPositionMs = 1_000)
        val action = policy.decide(
            failoverInput(
                failure = null,
                stall = stall,
                attempt = 2,
                candidates = listOf(stream(10)),
            ),
        )
        assertThat(action).isInstanceOf(FailoverAction.ResolveFresh::class.java)
    }

    // ---- no-backup path (docs/01 F4) ----------------------------------------------------------

    @Test
    fun `a single-stream channel retries, re-probes once, then gives up`() {
        val onlyOne = listOf(stream(10))
        assertThat(decide(FailureClass.TIMEOUT, attempt = 1, candidates = onlyOne))
            .isEqualTo(FailoverAction.RetrySame(0))
        assertThat(decide(FailureClass.TIMEOUT, attempt = 2, candidates = onlyOne))
            .isInstanceOf(FailoverAction.ResolveFresh::class.java)
        assertThat(decide(FailureClass.TIMEOUT, attempt = 3, candidates = onlyOne))
            .isEqualTo(FailoverAction.GiveUp)
        // The re-probe budget is spent for the session: it does not come back.
        assertThat(decide(FailureClass.TIMEOUT, attempt = 4, candidates = onlyOne))
            .isEqualTo(FailoverAction.GiveUp)
    }

    @Test
    fun `playlist gone resolves fresh once and then gives up`() {
        val empty = emptyList<Stream>()
        assertThat(decide(FailureClass.PLAYLIST_GONE, attempt = 1, activeStreamId = null, candidates = empty))
            .isInstanceOf(FailoverAction.ResolveFresh::class.java)
        assertThat(decide(FailureClass.PLAYLIST_GONE, attempt = 2, activeStreamId = null, candidates = empty))
            .isEqualTo(FailoverAction.GiveUp)
    }

    @Test
    fun `the on-demand re-probe can be disabled`() {
        val action = decide(
            FailureClass.PLAYLIST_GONE,
            attempt = 1,
            activeStreamId = null,
            candidates = emptyList(),
            limits = FailoverLimits(allowFreshResolve = false),
        )
        assertThat(action).isEqualTo(FailoverAction.GiveUp)
    }

    @Test
    fun `the switch budget caps one session`() {
        val many = listOf(stream(10), stream(11), stream(12), stream(13), stream(14))
        val limits = FailoverLimits(maxRetrySame = 0, maxSwitchPerSession = 2)
        assertThat(policy.decide(failoverInput(attempt = 1, activeStreamId = 10L, candidates = many, failure = failure(FailureClass.NET_UNREACHABLE), limits = limits)))
            .isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat(policy.decide(failoverInput(attempt = 1, activeStreamId = 11L, candidates = many, failure = failure(FailureClass.NET_UNREACHABLE), limits = limits)))
            .isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat(policy.decide(failoverInput(attempt = 1, activeStreamId = 12L, candidates = many, failure = failure(FailureClass.NET_UNREACHABLE), limits = limits)))
            .isEqualTo(FailoverAction.GiveUp)
        assertThat(policy.switchCountInSession()).isEqualTo(2)
    }

    // ---- no-switch rows -----------------------------------------------------------------------

    @Test
    fun `storage and permission report instead of switching`() {
        assertThat(decide(FailureClass.STORAGE)).isEqualTo(FailoverAction.GiveUp)
        assertThat(decide(FailureClass.PERMISSION)).isEqualTo(FailoverAction.GiveUp)
        assertThat(policy.switchCountInSession()).isEqualTo(0)
        assertThat(FailurePolicies.plan(FailureClass.STORAGE).userMessage).isNotNull()
    }

    @Test
    fun `cancellation is not a failure`() {
        assertThat(decide(FailureClass.CANCELLED)).isEqualTo(FailoverAction.GiveUp)
        assertThat(FailurePolicies.emitsFailoverEvent(FailureClass.CANCELLED)).isFalse()
    }

    @Test
    fun `an input with neither failure nor stall gives up`() {
        assertThat(policy.decide(failoverInput(failure = null, stall = null))).isEqualTo(FailoverAction.GiveUp)
    }

    // ---- same-channel backup preference -------------------------------------------------------

    @Test
    fun `the best-scoring backup wins, ties break on priority, lastOkAt then id`() {
        val candidates = listOf(
            stream(10, score = 0),
            stream(11, score = 50, priority = 2),
            stream(12, score = 50, priority = 1, lastOkAtMs = 6_000),
            stream(13, score = 50, priority = 1, lastOkAtMs = 5_000),
            stream(14, score = 50, priority = 1, lastOkAtMs = 5_000),
        )
        val ranked = policy.rankedCandidates(
            failoverInput(activeStreamId = 10L, candidates = candidates),
        )
        assertThat(ranked.map { it.id }).containsExactly(12L, 13L, 14L, 11L, 10L).inOrder()
    }

    @Test
    fun `a null lastOkAt sorts behind a known-good timestamp`() {
        val candidates = listOf(stream(11, lastOkAtMs = null), stream(12, lastOkAtMs = 0L))
        val ranked = policy.rankedCandidates(failoverInput(candidates = candidates))
        assertThat(ranked.map { it.id }).containsExactly(12L, 11L).inOrder()
    }

    @Test
    fun `disabled streams are never a target`() {
        val action = decide(
            FailureClass.NET_UNREACHABLE,
            candidates = listOf(stream(10), stream(11, disabled = true), stream(12)),
        )
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(12L)
    }

    @Test
    fun `never switch to the stream that is already playing`() {
        val action = decide(
            FailureClass.NET_UNREACHABLE,
            candidates = listOf(stream(11, score = 99), stream(10, score = 1), stream(12)),
        )
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
    }

    // ---- cooldown demotion --------------------------------------------------------------------

    @Test
    fun `a fresh failure streak drops the stream behind a healthy one`() {
        val candidates = listOf(
            stream(10, score = 0),
            stream(11, score = 100, failCount = 3, lastCheckAtMs = 95_000L),
            stream(12, score = 50),
        )
        val action = policy.decide(
            failoverInput(
                activeStreamId = 10L,
                candidates = candidates,
                failure = failure(FailureClass.NET_UNREACHABLE),
                nowMs = 100_000L,
            ),
        )
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(12L)
    }

    @Test
    fun `the demotion expires once the cooldown window closes`() {
        val candidates = listOf(
            stream(10, score = 0),
            stream(11, score = 100, failCount = 3, lastCheckAtMs = 0L),
            stream(12, score = 50),
        )
        val action = policy.decide(
            failoverInput(
                activeStreamId = 10L,
                candidates = candidates,
                failure = failure(FailureClass.NET_UNREACHABLE),
                nowMs = 120_000L, // 20 s older than cooldownMs = 60 s away → outside the window
            ),
        )
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
    }

    @Test
    fun `the session health view demotes a stream the persisted count does not know about`() {
        val candidates = listOf(
            stream(10, score = 0),
            stream(11, score = 100),
            stream(12, score = 50),
        )
        val action = policy.decide(
            failoverInput(
                activeStreamId = 10L,
                candidates = candidates,
                failure = failure(FailureClass.NET_UNREACHABLE),
                health = mapOf(11L to health(consecutiveFails = 2, lastOkAtMs = 99_000L)),
                nowMs = 100_000L,
            ),
        )
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(12L)
    }

    @Test
    fun `a single failure does not demote`() {
        val candidates = listOf(
            stream(10, score = 0),
            stream(11, score = 100, failCount = 1, lastCheckAtMs = 99_000L),
            stream(12, score = 50),
        )
        val action = policy.decide(
            failoverInput(
                activeStreamId = 10L,
                candidates = candidates,
                failure = failure(FailureClass.NET_UNREACHABLE),
                nowMs = 100_000L,
            ),
        )
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
    }

    @Test
    fun `tuning controls how long a streak has to be and how long it lasts`() {
        val cautious = DefaultFailoverPolicy(FailoverTuning(cooldownMs = 1_000, demoteAfterConsecutiveFails = 1))
        val candidates = listOf(
            stream(10, score = 0),
            stream(11, score = 100, failCount = 1, lastCheckAtMs = 99_500L),
            stream(12, score = 50),
        )
        val action = cautious.decide(
            failoverInput(
                activeStreamId = 10L,
                candidates = candidates,
                failure = failure(FailureClass.NET_UNREACHABLE),
                nowMs = 100_000L,
            ),
        )
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(12L)
    }

    // ---- session bookkeeping ------------------------------------------------------------------

    @Test
    fun `a new playback session clears the demotions and the switch budget`() {
        policy.decide(failoverInput(failure = failure(FailureClass.HTTP_CLIENT)))
        assertThat(policy.demotedStreamIds()).containsExactly(10L)

        policy.onSessionStarted(channelId = 1L)
        assertThat(policy.demotedStreamIds()).isEmpty()
        assertThat(policy.switchCountInSession()).isEqualTo(0)

        val action = policy.decide(failoverInput(failure = failure(FailureClass.NET_UNREACHABLE)))
        assertThat((action as FailoverAction.SwitchTo).stream.id).isEqualTo(11L)
    }

    @Test
    fun `switching channel resets the session automatically`() {
        val limits = FailoverLimits(maxSwitchPerSession = 1)
        val many = listOf(stream(10, channelId = 1), stream(11, channelId = 1), stream(12, channelId = 1))
        policy.decide(
            failoverInput(
                channelId = 1L,
                activeStreamId = 10L,
                candidates = many,
                failure = failure(FailureClass.NET_UNREACHABLE),
                limits = limits,
            ),
        )
        assertThat(policy.switchCountInSession()).isEqualTo(1)

        val otherChannel = policy.decide(
            failoverInput(
                channelId = 2L,
                activeStreamId = 11L,
                candidates = listOf(stream(11, channelId = 2), stream(12, channelId = 2)),
                failure = failure(FailureClass.NET_UNREACHABLE),
                limits = limits,
            ),
        )
        assertThat(otherChannel).isInstanceOf(FailoverAction.SwitchTo::class.java)
        assertThat(policy.switchCountInSession()).isEqualTo(1)
    }

    @Test
    fun `the action's reason is the failure the controller logged`() {
        val error = failure(FailureClass.HTTP_SERVER)
        val action = policy.decide(
            failoverInput(attempt = 3, failure = error),
        )
        assertThat((action as FailoverAction.SwitchTo).reason).isEqualTo(error)
    }

    @Test
    fun `candidates come from the same channel and are ranked without the active stream`() {
        val candidates = listOf(stream(10, score = 1), stream(11, score = 2))
        val ranked = policy.rankedCandidates(failoverInput(candidates = candidates))
        assertThat(ranked.map { it.id }).containsExactly(11L, 10L).inOrder()
    }
}
