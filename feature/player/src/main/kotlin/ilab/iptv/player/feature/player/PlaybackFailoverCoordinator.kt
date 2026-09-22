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
 *  6. SWITCH-P95-1: hand the policy the *phase* of the failure (`FailoverInput.startupFailure`) and
 *     give the first `prepare` of a channel that has a backup a shorter window, so a dead main source
 *     reaches its backup in seconds instead of two full 12 s timeouts.
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
    /**
     * §7.5 `EngineTuning.prepareTimeoutMs`: the start-up window of every attempt that is not the
     * "fast first attempt" below. Unchanged value, unchanged meaning.
     */
    private val prepareTimeoutMs: Long = DEFAULT_PREPARE_TIMEOUT_MS,
    /**
     * SWITCH-P95-1: the start-up window of the **first** `prepare` of a session whose channel has a
     * backup. Every later attempt (a retry, or the `prepare` after a switch) keeps the 12 s window on
     * purpose: once the user is already in a fail-over round, patience is what lets a *slow but alive*
     * main source (measured 0.7–10 s on the imported lists) come up rather than be lost for good.
     *
     * Why a shorter window at all: keeping the 12 s line means a dead main source alone costs the
     * user 12 s before the backup is even considered — the G7-1 tail (p95 5,953 ms / max 24,940 ms).
     * The budget is the fail-over criterion minus the measured backup start-up:
     * `5,000 ms − 729…805 ms (G7-1 §5.5 / G7-2 §4.5) ≈ 4,200 ms`, rounded down to `4,000 ms`.
     * Data for the other side of the trade (a *slow but alive* main source must not be abandoned):
     * the delivered 154-channel snapshot is single-stream, so this window is never used there; on the
     * 571-channel import the measured first frames were 642–2,004 ms (G7-1/G7-2), i.e. 4 s keeps a
     * ~2× margin. `0` switches the fast window off (every attempt then uses [prepareTimeoutMs]).
     */
    private val fastFirstAttemptTimeoutMs: Long = FAST_FIRST_ATTEMPT_TIMEOUT_MS,
) {

    private var job: Job? = null
    private var switchFromChannelId: Long? = null
    private var switchStartedAtMs = 0L
    private var pendingSwitchFromStreamId: Long? = null
    private var pendingSwitchStage: String? = null
    /** SWITCH-P95-1: only the session's very first `prepare` gets the short window. */
    private var firstPrepareOfSession = true
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
        pendingSwitchStage = null
        firstPrepareOfSession = true
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
            val candidates = catalog.candidates(channel.id)
            val firstOfSession = firstPrepareOfSession
            firstPrepareOfSession = false
            val timeoutMs = prepareTimeoutFor(attempt, candidates.size, firstOfSession)
            watchdog.onPrepareStart(clock.nowMs(), timeoutMs)
            val result = port.watch(channel, stream, attempt, preferPassthrough, timeoutMs)
            val episode = when (result) {
                is AppResult.Ok -> supervise(channel, stream)
                // The engine never reached the first frame: a start-up failure (SWITCH-P95-1).
                is AppResult.Err -> Episode.Failure(result.error, startup = true)
            }
            when (val step = act(channel, stream, attempt, episode, candidates)) {
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
                // A failure before we ever saw PLAYING is a start-up failure; after it, it is not.
                startup = !started,
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
                    // P1-7: the remote's play key, the notification's control and the system media
                    // control all pause the player *through the MediaSession*, so `paused` (the
                    // controller's own flag) does not see them. The engine's `playWhenReady` does, and
                    // treating it as a pause is what keeps a user-driven pause from being answered
                    // with a fail-over to another source.
                    isPaused = paused || sample.isPausedByUser,
                ),
            )
            when (verdict) {
                WatchdogVerdict.Ok -> Unit

                is WatchdogVerdict.PrepareTimeout -> return Episode.Failure(
                    AppError.timeout(EventCodes.PLAY_PREPARE_FAIL)
                        .copy(detail = "watchdog prepare timeout ${verdict.waitedMs}ms"),
                    startup = true,
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

    private suspend fun act(
        channel: Channel,
        stream: Stream,
        attempt: Int,
        episode: Episode,
        candidates: List<Stream>,
    ): Step {
        if (episode is Episode.Stopped) return Step.End
        val failure = (episode as? Episode.Failure)?.error
        val startup = (episode as? Episode.Failure)?.startup == true
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
            val action = policy.decide(inputFor(channel, stream, ordinal, failure, stall, startup, candidates))
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
                    logFailover(stream, action.stream, action.reason, ordinal, ACTION_SWITCH, startup)
                    pendingSwitchFromStreamId = stream.id
                    pendingSwitchStage = stageOf(startup)
                    preferPassthrough = true
                    return Step.Reprepare(action.stream, 1)
                }

                is FailoverAction.ResolveFresh -> {
                    port.onFailoverRunning(HINT_SWITCHING)
                    logFailover(stream, null, action.reason, ordinal, ACTION_RESOLVE_FRESH, startup)
                    val refreshed = catalog.reprobe(channel.id)
                    val target = bestTarget(refreshed, stream)
                    if (target != null) {
                        logFailover(stream, target, action.reason, ordinal, ACTION_SWITCH, startup)
                        pendingSwitchFromStreamId = stream.id
                        pendingSwitchStage = stageOf(startup)
                        preferPassthrough = true
                        return Step.Reprepare(target, 1)
                    }
                    val message = FailurePolicies.plan(action.reason.failure).userMessage ?: HINT_NO_SOURCE
                    logFailover(stream, null, action.reason, ordinal, ACTION_GIVE_UP, startup)
                    port.onFailoverExhausted(action.reason, message)
                    return Step.End
                }

                FailoverAction.GiveUp -> {
                    val message = failure
                        ?.let { FailurePolicies.plan(it.failure).userMessage }
                        ?: HINT_NO_SOURCE
                    if (failure != null) {
                        logFailover(stream, null, failure, ordinal, ACTION_GIVE_UP, startup)
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
        startup: Boolean,
        candidates: List<Stream>,
    ): FailoverInput {
        val candidates = candidates
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
            startupFailure = startup,
        )
    }

    /**
     * Best candidate for the `ResolveFresh` path, using the frozen §4.3 ordering key. Permanent
     * demotions recorded by the policy this session are honoured, so a 404 source is not resurrected
     * by the re-check.
     */
    private fun bestTarget(candidates: List<Stream>, active: Stream): Stream? {
        // Streams this session refuses outright: permanent demotions (403/404/410).
        val demoted = policy.demotedStreamIds()
        // SWITCH-P95-1 (god 条件 3a): a stream that failed to start is only *down-ranked* here too, so
        // this re-probe can still come back to a slow main source instead of writing it off.
        val startupFailed = policy.startupFailedStreamIds()
        return candidates
            .asSequence()
            .filter { !it.disabled && it.id != active.id && it.id !in demoted }
            .sortedWith(
                compareBy<Stream> { it.id in startupFailed }
                    .thenByDescending { it.score }
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
            val stage = pendingSwitchStage ?: STAGE_PLAYING
            pendingSwitchStage = null
            port.onFailoverSwitched(stream.id, HINT_SWITCHED)
            logger.w(
                LogCategory.PLAYER,
                EventCodes.PLAY_FAILOVER,
                "failover switch completed",
                mapOf(
                    "from" to from,
                    "to" to stream.id,
                    "action" to ACTION_SWITCH_DONE,
                    "stage" to stage,
                ),
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
        startup: Boolean,
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
                // god 条件 3b (2026-09-23): tell a start-up failure apart from a mid-play one.
                "stage" to stageOf(startup),
            ),
        )
    }

    /** `PLAY_FAILOVER.stage`: `startup` = the stream never produced a first frame (SWITCH-P95-1). */
    private fun stageOf(startup: Boolean): String = if (startup) STAGE_STARTUP else STAGE_PLAYING

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

    /**
     * The start-up window of this attempt (SWITCH-P95-1): the first `prepare` of a channel that has a
     * backup gets the short one, everything else keeps the §7.5 window.
     */
    private fun prepareTimeoutFor(attempt: Int, candidateCount: Int, firstOfSession: Boolean): Long {
        val fast = fastFirstAttemptTimeoutMs
        return if (fast > 0 && firstOfSession && attempt <= 1 && candidateCount >= 2) {
            fast
        } else {
            prepareTimeoutMs
        }
    }

    /** One failure/stall episode of the active stream. */
    private sealed interface Episode {
        /** [startup] = the stream had not produced a first frame yet (SWITCH-P95-1). */
        data class Failure(val error: AppError, val startup: Boolean) : Episode
        data class Stall(val signal: StallSignal) : Episode
        data object Stopped : Episode
    }

    /** What the loop does next: prepare a stream, or end the session. */
    private sealed interface Step {
        data class Reprepare(val stream: Stream, val attempt: Int) : Step
        data object End : Step
    }

    companion object {
        /** docs/02 §7.5: `EngineTuning.prepareTimeoutMs`, the unchanged start-up window. */
        const val DEFAULT_PREPARE_TIMEOUT_MS = 12_000L

        /** SWITCH-P95-1: the first attempt of a channel with a backup (see the constructor KDoc). */
        const val FAST_FIRST_ATTEMPT_TIMEOUT_MS = 4_000L

        /** Position sampling cadence: 4 Hz, which is finer than every watchdog threshold. */
        private const val TICK_MS = 250L

        private const val ACTION_SWITCH = "switch"
        private const val ACTION_SWITCH_DONE = "switch_done"
        private const val ACTION_RESOLVE_FRESH = "resolve_fresh"
        private const val ACTION_GIVE_UP = "give_up"

        /** `PLAY_FAILOVER.stage` values (god 条件 3b, 2026-09-23). */
        const val STAGE_STARTUP = "startup"
        const val STAGE_PLAYING = "playing"
    }
}

/** What the info bar says while the state machine is deciding/acting (P1-5 item 4). */
const val HINT_SWITCHING = "正在切换备用源…"
const val HINT_SWITCHED = "已切换备用源"
const val HINT_RETRYING = "正在重试…"
const val HINT_NO_SOURCE = "无可用源"
