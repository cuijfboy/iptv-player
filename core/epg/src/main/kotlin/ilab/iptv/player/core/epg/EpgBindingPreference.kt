package ilab.iptv.player.core.epg

import ilab.iptv.player.core.model.EpgMatchType

/**
 * One source's proposal for a channel: "bind this channel to this guide channel id, by this tier".
 *
 * A channel can be proposed by several sources at once — a mainland guide and a Hong Kong guide both
 * carry `深圳卫视` — and only one of them can be stored in `channel.epg_channel_id` (docs/02 §5.1).
 * [sourceOrder] is the source's position in the run (`epg_source.id` ascending, the order
 * `LoadEpgUseCase` walks), so a decision can be reproduced from the log alone.
 */
data class EpgBindingCandidate(
    val channelId: Long,
    val epgChannelId: String,
    val type: EpgMatchType,
    val matchedOn: String,
    val guideKey: String? = null,
    val sourceOrder: Int,
)

/**
 * Picks **one** binding for a channel out of every source's proposal — the decision EPG-TRAD-1 showed
 * was missing.
 *
 * Until this rule existed, `LoadEpgUseCase` overwrote the binding as it walked the sources, so the
 * *last* source that matched a channel won. That happened to be right for the five channels the
 * Traditional→Simplified fold moved to the Hong Kong guide (the HK id holds 7–129 programmes where
 * the mainland one holds 0–12), but it was right by luck: a later source with a *thinner* table
 * would have overwritten a better binding just as silently.
 *
 * The rule is depth-first, exactly as the card asks:
 *
 * 1. **the most programmes in the retention window wins** — the count is supplied by the caller, so
 *    this class stays pure and the window rule lives in one SQL predicate;
 * 2. ties keep the **existing** arbitration: the later source wins (`sourceOrder` desc), which is what
 *    the old overwrite loop did, then the higher tier (`TVG_ID` < `NAME_EXACT` < `NAME_FUZZY` <
 *    `ALIAS`), then the guide id ascending.
 *
 * Rules 2's tail exists so the result is a *total* order: for any input there is exactly one winner,
 * so re-running a refresh that sees the same guides binds the same id (the same idempotency promise
 * the matcher makes). A candidate whose id holds nothing still wins when it is the only candidate —
 * "no programmes yet" is not a reason to unbind a channel that has no better option; it is a reason
 * for the coverage report to say so (`EpgCoverage.emptyBinding`).
 */
object EpgBindingPreference {

    /**
     * @param programmesInWindow programmes the given guide channel id holds inside the retention
     *   window; anything missing from the caller's map is 0.
     * @return the winning candidate, or `null` when there is nothing to choose from.
     */
    fun choose(
        candidates: List<EpgBindingCandidate>,
        programmesInWindow: (String) -> Int,
    ): EpgBindingCandidate? {
        var best: EpgBindingCandidate? = null
        var bestDepth = 0
        for (candidate in candidates) {
            if (best == null) {
                best = candidate
                bestDepth = programmesInWindow(candidate.epgChannelId)
                continue
            }
            val depth = programmesInWindow(candidate.epgChannelId)
            if (beats(candidate, depth, best, bestDepth)) {
                best = candidate
                bestDepth = depth
            }
        }
        return best
    }

    /** See the class doc for the four keys, in order. Pure and total. */
    private fun beats(
        challenger: EpgBindingCandidate,
        challengerDepth: Int,
        incumbent: EpgBindingCandidate,
        incumbentDepth: Int,
    ): Boolean {
        if (challengerDepth != incumbentDepth) return challengerDepth > incumbentDepth
        if (challenger.sourceOrder != incumbent.sourceOrder) {
            return challenger.sourceOrder > incumbent.sourceOrder
        }
        if (challenger.type.ordinal != incumbent.type.ordinal) {
            return challenger.type.ordinal < incumbent.type.ordinal
        }
        return challenger.epgChannelId < incumbent.epgChannelId
    }
}
