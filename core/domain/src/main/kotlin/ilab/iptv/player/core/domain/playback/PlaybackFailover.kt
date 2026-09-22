package ilab.iptv.player.core.domain.playback

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamHealth

// The fail-over contract, copied verbatim from the frozen docs/02 §4.3 ("端口与策略", interface v1).
// P1-6 is the first work package to need these types, so `:core:domain`'s new `playback` package
// declares them here. Do not rename a field without an arch write-back: the controller (P1-3/P1-5)
// and the player engine (P1-3, `:core:player`) are built against these exact signatures.

/*
 * One watchdog stall observation (docs/02 §4.3).
 *
 * A stall is deliberately **not** an [AppError] (docs/02 §4.6 closing note): the watchdog produces
 * this signal directly and the fail-over policy turns it into `Backoff` → `SwitchTo`.
 */
data class StallSignal(
    val stalledMs: Long,
    val positionMs: Long,
    val bufferedPositionMs: Long,
)

/*
 * Fail-over budgets and thresholds (docs/02 §4.3, frozen defaults).
 *
 * `stallThresholdMs` is the one place the "long time without progress" line is defined for the
 * playback path; `PlaybackWatchdog` is constructed with the same number so the two never disagree.
 */
data class FailoverLimits(
    val maxRetrySame: Int = 1,
    val maxSwitchPerSession: Int = 3,
    val stallThresholdMs: Long = 8_000,
    val backoffMs: Long = 1_000,
    val allowFreshResolve: Boolean = true,
)

/*
 * The policy's whole input (docs/02 §4.3). The controller builds it; the policy never talks to a
 * repository or an engine, which is what keeps P1-6 unit-testable without Android.
 *
 * [attempt] is the **1-based ordinal of the failure of the active stream in this session**:
 * `1` = the stream failed for the first time, `2` = it failed again after one retry, and so on.
 * The controller resets it to `1` after every switch (a fresh stream starts at `1`). This is the
 * contract the retry budgets below are written against — see `14-P1-6故障转移验证.md` §5.
 */
data class FailoverInput(
    val channelId: Long,
    val attempt: Int,
    val activeStreamId: Long?,
    val candidates: List<Stream>,
    val failure: AppError?,
    val stall: StallSignal?,
    val health: Map<Long, StreamHealth>,
    val nowMs: Long,
    val limits: FailoverLimits,
    /*
     * SWITCH-P95-1 (docs/05-过程记录/65-换台p95修复.md): true when the failure happened while the
     * stream had not produced its first frame yet — the coordinator sets it for a `prepare` failure
     * (engine start-up timeout or the watchdog's `PrepareTimeout`) and leaves it false for anything
     * that surfaced after the first frame.
     *
     * WHY: a start-up timeout means "this source did not come up", and retrying the very same source
     * costs another full `prepareTimeoutMs` before the backup is even considered (G7-1 measured
     * 24,940 ms of user wait: two 12 s windows). A mid-play timeout is a different animal — it can be
     * the live edge overrunning (`ERROR_CODE_BEHIND_LIVE_WINDOW` also maps to `TIMEOUT`), where
     * re-preparing the same stream is the correct move and switching would be a false switch.
     *
     * Additive and defaulted, so every existing construction site keeps its exact behaviour; the
     * §4.3 field list needs an arch write-back (proposal sent to god with the SWITCH-P95-1 report).
     */
    val startupFailure: Boolean = false,
)

/** One decision of the fail-over state machine (docs/02 §4.3 + §6.2, frozen). */
sealed interface FailoverAction {

    /*
     * Re-prepare the **same** stream after [delayMs] (`0` = retry right away). The §4.6 rows that
     * say `RetrySame(≤1)` land here; `FailoverLimits.maxRetrySame` bounds how often.
     */
    data class RetrySame(val delayMs: Long) : FailoverAction

    /*
     * Do not re-prepare yet: wait [delayMs] and ask the policy again (§4.6 `Backoff(1s)`, used for
     * HTTP 429/5xx and for a stall). A `Backoff` **never** switches by itself.
     */
    data class Backoff(val delayMs: Long) : FailoverAction

    /**
     * Switch the channel to [stream]; [reason] is what the caller logs as `PLAY_FAILOVER.reason`.
     */
    data class SwitchTo(val stream: Stream, val reason: AppError) : FailoverAction

    /*
     * There is no candidate left: re-probe this single channel on demand (docs/01 F4 /
     * `RefreshTrigger.ON_DEMAND_SINGLE_CHANNEL`). Its `GiveUp` twin ends the session, and the UI
     * then shows the plan's `userMessage` ("该频道暂时不可用").
     */
    data class ResolveFresh(val reason: AppError) : FailoverAction

    /** Stop trying; the caller keeps `PlaybackPhase.ERROR` and shows the plan's `userMessage`. */
    data object GiveUp : FailoverAction
}

/*
 * The single decision point of the playback state machine (docs/02 §4.3, frozen).
 *
 * Implementations must be pure with respect to their inputs: same [FailoverInput] → same action,
 * no Android API, no clock of their own (`nowMs` is on the input). `:core:player` must **not** call
 * this — the engine only reports `PlaybackEvent.Error/Stalled`, the controller decides (docs/02
 * §4.5 C1, §6.2 "状态机归属").
 */
interface FailoverPolicy {
    fun decide(input: FailoverInput): FailoverAction
}
