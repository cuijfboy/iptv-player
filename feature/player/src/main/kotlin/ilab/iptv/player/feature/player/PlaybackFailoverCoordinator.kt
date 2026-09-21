package ilab.iptv.player.feature.player

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.playback.DefaultFailoverPolicy
import ilab.iptv.player.core.domain.playback.FailoverAction
import ilab.iptv.player.core.domain.playback.FailoverInput
import ilab.iptv.player.core.domain.playback.FailoverLimits
import ilab.iptv.player.core.domain.playback.FailurePolicies
import ilab.iptv.player.core.domain.playback.PlaybackSample
import ilab.iptv.player.core.domain.playback.PlaybackWatchdog
import ilab.iptv.player.core.domain.playback.StallSignal
import ilab.iptv.player.core.domain.playback.WatchdogVerdict
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.player.PlaybackFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The P1-5 fail-over wiring: the controller layer that turns engine failures and watchdog stalls into
 * `FailoverPolicy` calls and then into actual playback commands (docs/02 §4.3/§4.5 C1/§6.2).
 *
 * WHY IT LIVES IN `:feature:player` (deviation, reported to god): docs/02 §3.1/§7.2 put this role in
 * `:core:player`, but §3.2's frozen dependency matrix forbids `:core:player -> :core:domain` and the
 * policy + watchdog of P1-6 live in `:core:domain`. `:feature:player` is the only module the matrix
 * lets see BOTH (`:core:player` is its one special case, `:core:domain` comes with every feature), and
 * it is also the only layer where this wiring can be unit-tested on the JVM — which is exactly what
 * P1-5 asks to prove. The session keeps ownership of `PlaybackUiState` (§4.5 C1); this class only
 * asks it for state changes through [PlaybackPort].
 *
 * THE LOOP (one coroutine per playback session, serial by construction):
 *  1. prepare the active stream on the shared engine instance and wait for it;
 *  2. while it runs, sample position/buffering and let [PlaybackWatchdog] judge (§6.2);
 *  3. on a failure or a stall, build the frozen [FailoverInput] and ask the policy;
 *  4. apply the answer: `Backoff` waits and re-asks, `RetrySame` re-prepares the same stream,
 *     `SwitchTo` re-prepares another candidate, `ResolveFresh` re-checks the channel once,
 *     `GiveUp` shows the row's user message and ends the session;
 *  5. log `PLAY_FAILOVER` (`from`/`to`/`reason`/`attempt`) and `PLAY_STALL` exactly where docs/03
 *     §3.3 says they belong — the controller, never the engine.
 *
 * `FailoverInput.attempt` follows the frozen semantics: it is the 1-based ordinal of the *active
 * stream's* failure in this session, and it resets to 1 when the loop moves to another stream. A
 * `Backoff` consumes an ordinal without preparing anything, which is why the recipes come out as
 * `Backoff(1s) -> RetrySame -> SwitchTo` rather than backoff-and-retry in one step.
 */
class PlaybackFailoverCoordinator(
    private val port: PlaybackPort,
    private val policy: DefaultFailoverPolicy,
    private val catalog: FailoverCatalog,
    private val watchdog: PlaybackWatchdog,
    private val clock: Clock,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val limits: FailoverLimits = FailoverLimits(),
    private val tickMs: Long = TICK_MS,
) {

    private var job: Job? = null
    private var switchFromChannelId: Long? = null
    private var switchStartedAtMs = 0L
    private var pendingSwitchFromStreamId: Long? = null
    private var paused = false
    private var preferPassthrough = true

    /** True while a session coroutine is still running (used by tests and the screen teardown). */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Start (or replace) the playback session for [channel]/[stream].
     *
     * [switchedFromChannelId] is non-null for a remote-driven channel switch (P1-5 item 1) and is what
     * makes the switch cost measurable: `PLAY_SWITCH_CHANNEL` is logged with `from`/`to`/`costMs` when
     * the new channel actually reaches PLAYING ("耗时" = what the user waited for).
     */
    fun open(channel: Channel, stream: Stream, switchedFromChannelId: Long? = null) {
        job?.cancel()
        watchdog.onStopped()
        policy.onSessionStarted(channel.id)
        paused = false
        preferPassthrough = true
        pendingSwitchFromStreamId = null
        this.switchFromChannelId = switchedFromChannelId
        switchStartedAtMs = clock.nowMs()
        job = scope.launch { runSession(channel, stream) }
    }

    /** User pause/resume: a paused stream holds its position on purpose and must never count as a stall. */
    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    /** End the session (screen closed / user stop) and cancel the loop. */
    fun stop(reason: String = "session-stop") {
        job?.cancel()
        job = null
        watchdog.onStopped()
        port.stop(reason)
    }

    // ---------------------------------------------------------------- the session loop

    private suspend fun runSession(channel: Channel, initial: Stream) {
        var stream = initial
        var attempt = 1
        while (currentCoroutineContext().isActive) {
            watchdog.onPrepareStart(clock.nowMs())
            val result = port.watch(channel, stream, attempt, preferPassthrough)
            val episode = when (result) {
                is AppResult.Ok -> supervise(channel, stream)
                is AppResult.Err -> Episode.Failure(result.error)
            }
            when (val step = act(channel, stream, attempt, episode)) {
                is Step.Reprepare -> {
                    stream = step.stream
                    attempt = step.attempt
                }

                Step.End -> return
            }
        }
    }

    /**
     * Watch the running stream until it fails, stalls or stops. Two clocks matter here: the
     * watchdog's own thresholds (fed by [PlaybackSample]) and the poll cadence [tickMs], which is
     * how often a fresh position is read. Ticking faster than the threshold is required — a frozen
     * position can only be seen by looking at it twice.
     */
    private suspend fun supervise(channel: Channel, stream: Stream): Episode {
        var started = false
        while (currentCoroutineContext().isActive) {
            when (port.state.value.phase) {
                PlaybackPhase.PLAYING -> if (!started) {
                    started = true
                    watchdog.onFirstFrame(clock.nowMs())
                    onPlaybackStarted(channel, stream)
                }

                PlaybackPhase.ERROR -> return Episode.Failure(
                    port.state.value.lastError ?: AppError.unknown(EventCodes.PLAY_PREPARE_FAIL),
                )

                PlaybackPhase.STOPPED, PlaybackPhase.RELEASED, PlaybackPhase.IDLE -> return Episode.Stopped
                else -> Unit
            }
            val deadline = watchdog.nextDeadlineMs()
            val waitMs = deadline?.let { (it - clock.nowMs()).coerceIn(0L, tickMs) } ?: tickMs
            delay(waitMs)
            val sample = port.sample()
            val verdict = watchdog.onSample(
                PlaybackSample(
                    positionMs = sample.positionMs,
                    bufferedPositionMs = sample.bufferedPositionMs,
                    isLoading = sample.isLoading,
                    nowMs = clock.nowMs(),
                    isPaused = paused,
                ),
            )
            when (verdict) {
                WatchdogVerdict.Ok -> Unit

                is WatchdogVerdict.PrepareTimeout -> return Episode.Failure(
                    AppError.timeout(EventCodes.PLAY_PREPARE_FAIL)
                        .copy(detail = "watchdog prepare timeout ${verdict.waitedMs}ms"),
                )

                is WatchdogVerdict.NoProgress -> {
                    logStall(stream, verdict.signal)
                    return Episode.Stall(verdict.signal)
                }

                is WatchdogVerdict.Buffering -> {
                    logStall(stream, verdict.signal)
                    return Episode.Stall(verdict.signal)
                }
            }
        }
        return Episode.Stopped
    }

    // ---------------------------------------------------------------- decide and act

    private suspend fun act(channel: Channel, stream: Stream, attempt: Int, episode: Episode): Step {
        if (episode is Episode.Stopped) return Step.End
        val failure = (episode as? Episode.Failure)?.error
        val stall = (episode as? Episode.Stall)?.signal

        // docs/02 §4.6: STORAGE / PERMISSION / CANCELLED never switch and never emit PLAY_FAILOVER.
        if (failure != null && !FailurePolicies.emitsFailoverEvent(failure.failure)) {
            val plan = FailurePolicies.plan(failure.failure)
            val message = plan.userMessage ?: PlaybackFailureText.of(failure)
            port.onFailoverExhausted(failure, message)
            return Step.End
        }

        var ordinal = attempt.coerceAtLeast(1)
        var passthrough = preferPassthrough
        while (currentCoroutineContext().isActive) {
            val action = policy.decide(inputFor(channel, stream, ordinal, failure, stall))
            when (action) {
                is FailoverAction.Backoff -> {
                    // "Wait, then ask me again" — a Backoff never prepares anything by itself (§4.6).
                    port.onFailoverRunning(hintFor(failure))
                    if (action.delayMs > 0) delay(action.delayMs)
                    ordinal += 1
                }

                is FailoverAction.RetrySame -> {
                    port.onFailoverRunning(hintFor(failure))
                    if (action.delayMs > 0) delay(action.delayMs)
                    // §7.6 step 2: the NO_CAPABILITY row retries the SAME stream with passthrough off.
                    if (failure != null && FailurePolicies.plan(failure.failure).retryWithoutPassthrough) {
                        passthrough = false
                        preferPassthrough = false
                    }
                    return Step.Reprepare(stream, ordinal + 1)
                }

                is FailoverAction.SwitchTo -> {
                    port.onFailoverRunning(HINT_SWITCHING)
                    logFailover(stream, action.stream, action.reason, ordinal, ACTION_SWITCH)
                    pendingSwitchFromStreamId = stream.id
                    preferPassthrough = true
                    return Step.Reprepare(action.stream, 1)
                }

                is FailoverAction.ResolveFresh -> {
                    port.onFailoverRunning(HINT_SWITCHING)
                    logFailover(stream, null, action.reason, ordinal, ACTION_RESOLVE_FRESH)
                    val refreshed = catalog.reprobe(channel.id)
                    val target = bestTarget(refreshed, stream)
                    if (target != null) {
                        logFailover(stream, target, action.reason, ordinal, ACTION_SWITCH)
                        pendingSwitchFromStreamId = stream.id
                        preferPassthrough = true
                        return Step.Reprepare(target, 1)
                    }
                    val message = FailurePolicies.plan(action.reason.failure).userMessage ?: HINT_NO_SOURCE
                    logFailover(stream, null, action.reason, ordinal, ACTION_GIVE_UP)
                    port.onFailoverExhausted(action.reason, message)
                    return Step.End
                }

                FailoverAction.GiveUp -> {
                    val message = failure
                        ?.let { FailurePolicies.plan(it.failure).userMessage }
                        ?: HINT_NO_SOURCE
                    if (failure != null) {
                        logFailover(stream, null, failure, ordinal, ACTION_GIVE_UP)
                    }
                    port.onFailoverExhausted(failure, message)
                    return Step.End
                }
            }
        }
        return Step.End
    }

    private suspend fun inputFor(
        channel: Channel,
        activeStream: Stream,
        attempt: Int,
        failure: AppError?,
        stall: StallSignal?,
    ): FailoverInput {
        val candidates = catalog.candidates(channel.id)
            .ifEmpty { listOf(activeStream) }
            .let { candidates -> if (candidates.any { it.id == activeStream.id }) candidates else candidates + activeStream }
        return FailoverInput(
            channelId = channel.id,
            attempt = attempt,
            activeStreamId = activeStream.id,
            candidates = candidates,
            failure = failure,
            stall = stall,
            health = catalog.health(candidates.map { it.id }),
            nowMs = clock.nowMs(),
            limits = limits,
        )
    }

    /**
     * Best candidate for the `ResolveFresh` path, using the frozen §4.3 ordering key. Permanent
     * demotions recorded by the policy this session are honoured, so a 404 source is not resurrected
     * by the re-check.
     */
    private fun bestTarget(candidates: List<Stream>, active: Stream): Stream? {
        val demoted = policy.demotedStreamIds()
        return candidates
            .asSequence()
            .filter { !it.disabled && it.id != active.id && it.id !in demoted }
            .sortedWith(
                compareByDescending<Stream> { it.score }
                    .thenBy { it.priority }
                    .thenByDescending { it.lastOkAtMs ?: Long.MIN_VALUE }
                    .thenBy { it.id },
            )
            .firstOrNull()
    }

    // ---------------------------------------------------------------- telemetry + presentation

    private fun onPlaybackStarted(channel: Channel, stream: Stream) {
        pendingSwitchFromStreamId?.let { from ->
            pendingSwitchFromStreamId = null
            port.onFailoverSwitched(stream.id, HINT_SWITCHED)
            logger.w(
                LogCategory.PLAYER,
                EventCodes.PLAY_FAILOVER,
                "failover switch completed",
                mapOf("from" to from, "to" to stream.id, "action" to ACTION_SWITCH_DONE),
            )
        }
        val fromChannel = switchFromChannelId ?: return
        switchFromChannelId = null
        logger.i(
            LogCategory.PLAYER,
            EventCodes.PLAY_SWITCH_CHANNEL,
            "channel switched",
            mapOf(
                "from" to fromChannel,
                "to" to channel.id,
                "costMs" to (clock.nowMs() - switchStartedAtMs),
                "streamId" to stream.id,
            ),
        )
    }

    private fun logFailover(
        from: Stream,
        to: Stream?,
        reason: AppError,
        attempt: Int,
        action: String,
    ) {
        logger.w(
            LogCategory.PLAYER,
            EventCodes.PLAY_FAILOVER,
            "failover decision",
            mapOf(
                "from" to from.id,
                "to" to to?.id,
                "reason" to reason.failure.name,
                "httpStatus" to reason.httpStatus,
                "attempt" to attempt,
                "action" to action,
            ),
        )
    }

    private fun logStall(stream: Stream, signal: StallSignal) {
        logger.w(
            LogCategory.PLAYER,
            EventCodes.PLAY_STALL,
            "playback stalled",
            mapOf(
                "streamId" to stream.id,
                "stalledMs" to signal.stalledMs,
                "positionMs" to signal.positionMs,
                "bufferedPositionMs" to signal.bufferedPositionMs,
            ),
        )
    }

    private fun hintFor(failure: AppError?): String =
        if (failure == null) HINT_SWITCHING else HINT_RETRYING

    /** One failure/stall episode of the active stream. */
    private sealed interface Episode {
        data class Failure(val error: AppError) : Episode
        data class Stall(val signal: StallSignal) : Episode
        data object Stopped : Episode
    }

    /** What the loop does next: prepare a stream, or end the session. */
    private sealed interface Step {
        data class Reprepare(val stream: Stream, val attempt: Int) : Step
        data object End : Step
    }

    private companion object {
        /** Position sampling cadence: 4 Hz, which is finer than every watchdog threshold. */
        const val TICK_MS = 250L

        const val ACTION_SWITCH = "switch"
        const val ACTION_SWITCH_DONE = "switch_done"
        const val ACTION_RESOLVE_FRESH = "resolve_fresh"
        const val ACTION_GIVE_UP = "give_up"
    }
}

/** What the info bar says while the state machine is deciding/acting (P1-5 item 4). */
const val HINT_SWITCHING = "正在切换备用源…"
const val HINT_SWITCHED = "已切换备用源"
const val HINT_RETRYING = "正在重试…"
const val HINT_NO_SOURCE = "无可用源"
