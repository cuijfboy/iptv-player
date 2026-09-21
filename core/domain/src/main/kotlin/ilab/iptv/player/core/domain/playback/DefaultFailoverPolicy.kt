package ilab.iptv.player.core.domain.playback

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.model.Stream

/*
 * The two knobs §4.3 does not freeze (they are P1-6's own tuning, not part of `FailoverLimits`):
 * how long a failure streak keeps a stream demoted, and how long the streak has to be before the
 * demotion kicks in.
 */
data class FailoverTuning(
    val cooldownMs: Long = 60_000,
    val demoteAfterConsecutiveFails: Int = 2,
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
 *
 * The instance keeps only session bookkeeping: the switch budget, the set of permanently demoted
 * streams and the set of channels that already spent their on-demand re-probe. `decide` itself is
 * pure in everything else: same input, same action, no clock, no Android.
 */
class DefaultFailoverPolicy(
    private val tuning: FailoverTuning = FailoverTuning(),
) : FailoverPolicy {

    private val permanentlyDemoted = mutableSetOf<Long>()
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
        freshResolvedChannels.clear()
    }

    /** Streams this session refuses to pick again (sources that answered 403/404/410). */
    fun demotedStreamIds(): Set<Long> = permanentlyDemoted.toSet()

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
        if (plan.demotion == StreamDemotion.PERMANENT) {
            input.activeStreamId?.let(permanentlyDemoted::add)
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
            compareBy<Stream> { isCoolingDown(it, input) }
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

    private fun FailoverStep.isSameStreamStep(): Boolean =
        this == FailoverStep.RETRY_SAME || this == FailoverStep.BACKOFF
}
