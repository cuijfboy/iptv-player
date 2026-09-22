package ilab.iptv.player.core.domain.playback

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.FailureOrigin
import ilab.iptv.player.core.model.Stream

/*
 * The knobs §4.3 does not freeze (they are P1-6/SWITCH-P95-1's own tuning, not part of
 * `FailoverLimits`): how long a failure streak keeps a stream demoted, how long the streak has to be
 * before the demotion kicks in, and whether a start-up timeout switches instead of retrying itself.
 */
data class FailoverTuning(
    val cooldownMs: Long = 60_000,
    val demoteAfterConsecutiveFails: Int = 2,
    /*
     * SWITCH-P95-1: 起播超时（未出首帧）不再重试同一流 —— 有备胎就立即 `SwitchTo(next)`.
     *
     * Set to `false` to restore the pre-SWITCH-P95-1 sequence byte for byte (`RetrySame(≤1) →
     * SwitchTo(next)`, two full windows of user wait); it exists so the before/after numbers in
     * `docs/05-过程记录/65-换台p95修复.md` stay reproducible and so the §4.6/§6.2 write-back can be
     * reverted with one flag if god/arch declines it.
     */
    val switchOnStartupTimeout: Boolean = true,
)

/*
 * The frozen `FailoverPolicy` implementation (`docs/02` §4.3/§4.6/§6.2).
 *
 * How it decides, in order:
 *  1. `STORAGE` / `PERMISSION` / `CANCELLED` never switch (docs/02 §4.6): report only, `GiveUp`.
 *  2. Otherwise the row of `FailurePolicies` is walked with [FailoverInput.attempt]: attempt 1
 *     plays the first step of the recipe, attempt 2 the second, later attempts the last one. A
 *     `maxRetrySame` of 0 drops the leading same-stream steps, so a read-only policy that refuses
 *     retries still switches straight away.
 *  3. `SWITCH_NEXT` picks the best candidate from [rankedCandidates] (frozen §4.3 key: score desc
 *     → priority asc → lastOkAt desc → id asc), with two P1-6 demotions in front of that key:
 *     `PERMANENT` (a 403/404/410 source is never picked again this session) and `COOLDOWN`
 *     (a fresh failure streak ranks the stream behind healthy ones). A permanently demoted stream
 *     is dropped outright, so it can never be the target.
 *  4. When no target is left — the common case, 417/579 channels have a single stream (docs/01 F4)
 *     — the policy falls back to `ResolveFresh` once per channel session, then `GiveUp` with the
 *     row's user message.
 *  5. SWITCH-P95-1 refinement on top of step 2: a **start-up** timeout (`FailoverInput.startupFailure`)
 *     that still has a fresh candidate switches at once instead of retrying the same stream — the
 *     stream that failed to come up is remembered for the session and never chosen as a target again.
 *
 * The instance keeps only session bookkeeping: the switch budget, the set of permanently demoted
 * streams, the set of streams that failed to start and the set of channels that already spent their
 * on-demand re-probe. `decide` itself is pure in everything else: same input, same action, no clock,
 * no Android.
 */
class DefaultFailoverPolicy(
    private val tuning: FailoverTuning = FailoverTuning(),
) : FailoverPolicy {

    private val permanentlyDemoted = mutableSetOf<Long>()
    /*
     * SWITCH-P95-1: streams whose `prepare` failed *before the first frame* in this session. They are
     * never picked as a switch target again (the switch would just re-run the same dead source) and
     * they are the reason a start-up timeout switches instead of retrying itself.
     */
    private val startupFailed = mutableSetOf<Long>()
    private val freshResolvedChannels = mutableSetOf<Long>()
    private var switchCount = 0
    private var sessionChannelId: Long? = null

    /**
     * Start a fresh playback session on [channelId]: the switch budget, the permanent demotions and
     * the on-demand re-probe budget are per session (docs/02 §4.6 "该流永久降权" is per session —
     * a new `Watch` re-resolves the streams). Called automatically when [decide] sees a new channel.
     */
    fun onSessionStarted(channelId: Long) {
        sessionChannelId = channelId
        switchCount = 0
        permanentlyDemoted.clear()
        startupFailed.clear()
        freshResolvedChannels.clear()
    }

    /** Streams this session refuses to pick again (sources that answered 403/404/410). */
    fun demotedStreamIds(): Set<Long> = permanentlyDemoted.toSet()

    /** Streams whose start-up failed in this session (SWITCH-P95-1; see [startupFailed]). */
    fun startupFailedStreamIds(): Set<Long> = startupFailed.toSet()

    /** `SwitchTo` actions this policy has already handed out for the current channel session. */
    fun switchCountInSession(): Int = switchCount

    override fun decide(input: FailoverInput): FailoverAction {
        startSessionIfNeeded(input.channelId)
        val failure = input.failure
        val stall = input.stall
        if (failure == null && stall == null) return FailoverAction.GiveUp
        if (failure != null) {
            // docs/02 §4.6: 协程取消 is not a failure — no retry, no switch, no PLAY_FAILOVER.
            if (!FailurePolicies.emitsFailoverEvent(failure.failure)) return FailoverAction.GiveUp
            return decideFailure(input, failure)
        }
        return decideStall(input, stall!!)
    }

    /*
     * Watchdog path (docs/02 §4.6 closing note, §6.2): stall → `Backoff(1s)` → `SwitchTo(next)`.
     * A stall under `stallThresholdMs` is a jitter, not a stall: `Backoff(0)` holds the stream
     * (`Backoff` never switches) and keeps a short freeze from costing the user a channel switch.
     */
    private fun decideStall(input: FailoverInput, stall: StallSignal): FailoverAction {
        if (stall.stalledMs < input.limits.stallThresholdMs) return FailoverAction.Backoff(0)
        val reason = AppError.timeout(EventCodes.PLAY_STALL)
        return if (input.attempt.coerceAtLeast(1) <= input.limits.maxRetrySame) {
            FailoverAction.Backoff(input.limits.backoffMs)
        } else {
            switchOrFallback(input, reason)
        }
    }

    private fun decideFailure(input: FailoverInput, failure: AppError): FailoverAction {
        val plan = FailurePolicies.plan(failure.failure)
        /*
         * 环境闸门 (docs/05 66): a failure the network's gate caused (FailureOrigin.ENV_GATED —
         * HTTP 418/451/511/605) still switches this round via its §4.6 recipe, but it must NOT be
         * booked as a source that answered "gone": no permanent demotion, so the stream stays
         * selectable in a later round. A genuine source refusal (403/404/410, FailureOrigin.SOURCE)
         * keeps its permanent demotion untouched.
         */
        if (plan.demotion == StreamDemotion.PERMANENT && failure.origin != FailureOrigin.ENV_GATED) {
            input.activeStreamId?.let(permanentlyDemoted::add)
        }
        /*
         * SWITCH-P95-1: 起播超时不再重试同一流。 A start-up timeout that still has a fresh candidate
         * hands the channel straight to that candidate — "首次起播超时后立即切备胎".
         *
         * Scoped on purpose: only a *start-up* timeout (`startupFailure`, §4.3 addition) with an
         * alternative that has not itself failed to start this session. A channel with a single
         * stream therefore keeps the frozen `RetrySame(≤1) → SwitchTo(next)` recipe untouched (it
         * still retries once and then re-probes before giving up), and so does a mid-play timeout
         * (live-edge overrun) where re-preparing the same stream is the right answer.
         *
         * The stream that failed to start is remembered either way (god 条件 3a, 2026-09-23): it is
         * only *down-ranked* among the candidates, never dropped, so a later round can come back to a
         * slow main source once the backup has failed too.
         */
        if (tuning.switchOnStartupTimeout &&
            input.startupFailure &&
            failure.failure == FailureClass.TIMEOUT
        ) {
            input.activeStreamId?.let(startupFailed::add)
            if (hasFreshTarget(input)) {
                return switchOrFallback(input, failure)
            }
        }
        val steps = if (input.limits.maxRetrySame <= 0) {
            plan.steps.dropWhile { it.isSameStreamStep() }
        } else {
            plan.steps
        }
        if (steps.isEmpty()) return FailoverAction.GiveUp
        val index = (input.attempt.coerceAtLeast(1) - 1).coerceIn(0, steps.lastIndex)
        return when (steps[index]) {
            FailoverStep.RETRY_SAME -> FailoverAction.RetrySame(0)
            FailoverStep.BACKOFF -> FailoverAction.Backoff(input.limits.backoffMs)
            FailoverStep.SWITCH_NEXT -> switchOrFallback(input, failure)
            FailoverStep.RESOLVE_FRESH -> resolveFreshOrGiveUp(input, failure)
            FailoverStep.GIVE_UP -> FailoverAction.GiveUp
        }
    }

    private fun switchOrFallback(input: FailoverInput, reason: AppError): FailoverAction {
        val target = rankedCandidates(input).firstOrNull { it.id != input.activeStreamId }
            ?: return resolveFreshOrGiveUp(input, reason)
        if (switchCount >= input.limits.maxSwitchPerSession) return FailoverAction.GiveUp
        switchCount++
        return FailoverAction.SwitchTo(target, reason)
    }

    private fun resolveFreshOrGiveUp(input: FailoverInput, reason: AppError): FailoverAction {
        if (!input.limits.allowFreshResolve) return FailoverAction.GiveUp
        if (!freshResolvedChannels.add(input.channelId)) return FailoverAction.GiveUp
        return FailoverAction.ResolveFresh(reason)
    }

    /*
     * Candidate ranking. From the frozen §4.3 key onward the order is untouched (score desc →
     * priority asc → lastOkAt desc → id asc); the two demotions are the P1-6 addition and only
     * move a bad stream behind a good one, they never reorder two healthy streams.
     */
    internal fun rankedCandidates(input: FailoverInput): List<Stream> {
        val selectable = input.candidates.filter { !it.disabled && it.id !in permanentlyDemoted }
        return selectable.sortedWith(
            // 起播失败过的流排在最后（可回退，但不优先）；再后面才是 §4.3 的冻结排序键。
            compareBy<Stream> { it.id in startupFailed }
                .thenBy { isCoolingDown(it, input) }
                .thenByDescending { it.score }
                .thenBy { it.priority }
                .thenByDescending { it.lastOkAtMs ?: Long.MIN_VALUE }
                .thenBy { it.id },
        )
    }

    /*
     * 冷却降权: a stream is demoted while its failure streak is both long enough
     * (`demoteAfterConsecutiveFails`) and fresh (`cooldownMs`). `StreamHealth.consecutiveFails` is
     * the session view, `Stream.failCount` the persisted one — the worse of the two wins, so a
     * stream that failed before this session still starts demoted.
     */
    private fun isCoolingDown(stream: Stream, input: FailoverInput): Boolean {
        val health = input.health[stream.id]
        val streak = maxOf(health?.consecutiveFails ?: 0, stream.failCount)
        if (streak < tuning.demoteAfterConsecutiveFails) return false
        val lastObservedAtMs = listOfNotNull(health?.lastOkAtMs, stream.lastCheckAtMs).maxOrNull()
            ?: return true
        return input.nowMs - lastObservedAtMs < tuning.cooldownMs
    }

    private fun startSessionIfNeeded(channelId: Long) {
        if (sessionChannelId != channelId) onSessionStarted(channelId)
    }

    /**
     * A candidate the channel can move to *right now*: selectable, not this stream, and not one that
     * already failed to start this session (the "fresh" half of SWITCH-P95-1). A stream that failed to
     * start stays selectable — it is only reached by the later rounds, not by the immediate switch.
     */
    private fun hasFreshTarget(input: FailoverInput): Boolean =
        rankedCandidates(input).any { it.id != input.activeStreamId && it.id !in startupFailed }

    private fun FailoverStep.isSameStreamStep(): Boolean =
        this == FailoverStep.RETRY_SAME || this == FailoverStep.BACKOFF
}
