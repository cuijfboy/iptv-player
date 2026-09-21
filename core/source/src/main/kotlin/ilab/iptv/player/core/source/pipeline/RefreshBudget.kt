package ilab.iptv.player.core.source.pipeline

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.model.RefreshPhase

/**
 * The expected (median) wall-clock cost of starting one more stage / item. The admission rule
 * (docs/02 §6.1, frozen) compares the *remaining* budget against this: if `remaining < p50` we stop
 * admitting new work instead of running over and being killed.
 */
data class StageProfile(val phase: RefreshPhase, val p50Ms: Long)

/**
 * The default per-stage medians, derived from the §6.1 numbers and the S1/S5 Spike timings. See the
 * verification file (`docs/05-过程记录/17-P2-4a刷新管线验证.md`) for the full derivation; the short
 * version: Fetch ≈ one source round trip, Shallow ≈ one HEAD (or a HEAD+Range), Deep ≈ one Media3
 * prepare (S1 mean 2.14 s).
 */
object StageProfiles {
    val FETCH = StageProfile(RefreshPhase.FETCH, p50Ms = 3_000)
    val PARSE = StageProfile(RefreshPhase.PARSE, p50Ms = 800)
    val NORMALIZE = StageProfile(RefreshPhase.NORMALIZE, p50Ms = 300)
    val DEDUPE = StageProfile(RefreshPhase.DEDUPE, p50Ms = 300)
    val SHALLOW = StageProfile(RefreshPhase.SHALLOW, p50Ms = 1_000)
    val DEEP = StageProfile(RefreshPhase.DEEP, p50Ms = 2_500)
    val SCORE = StageProfile(RefreshPhase.SCORE, p50Ms = 500)
    val SELECT = StageProfile(RefreshPhase.SELECT, p50Ms = 300)
    val PERSIST = StageProfile(RefreshPhase.PERSIST, p50Ms = 2_000)

    val ALL: List<StageProfile> = listOf(FETCH, PARSE, NORMALIZE, DEDUPE, SHALLOW, DEEP, SCORE, SELECT, PERSIST)
}

/**
 * Deadline-driven admission (docs/02 §6.1, frozen): the run is given an absolute [deadlineMs] and
 * every stage asks [canAdmitNext] before taking on another item. When the answer is false the caller
 * finishes gracefully (persist what it has) instead of running past the budget.
 *
 * [clock] is injected so the arithmetic is unit-testable with no real waiting.
 */
class RefreshBudget(
    private val deadlineMs: Long,
    private val clock: Clock,
) {
    fun remainingMs(): Long = deadlineMs - clock.nowMs()

    fun isExpired(): Boolean = remainingMs() <= 0

    /** True when at least a median stage/item cost still fits before the deadline. */
    fun canAdmitNext(profile: StageProfile): Boolean = remainingMs() >= profile.p50Ms

    /** True when [count] more items at [perItem] median each would still fit. */
    fun canAdmit(count: Int, perItem: StageProfile): Boolean =
        count <= 0 || remainingMs() >= perItem.p50Ms * count
}
