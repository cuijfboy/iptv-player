package ilab.iptv.player.core.domain.scoring

import ilab.iptv.player.core.model.ScoreBreakdown
import ilab.iptv.player.core.model.ScoreInput

/**
 * docs/02 §4.3 `Scorer` / `ScoringRule`: the pure scoring policy.
 *
 * Declared here (and implemented by [DefaultScorer]) so the refresh pipeline (P2-4b) depends on the
 * port, and so a caller can compose a different rule set — docs/02 §9 E6 "实现 `ScoringRule`
 * （`weight`）→ 自动纳入总分" is exactly this seam.
 */
interface Scorer {
    fun score(input: ScoreInput): ScoreBreakdown
}

/** One scoring dimension (docs/02 §4.3). `evaluate` returns 0..[weight] points. */
interface ScoringRule {
    val id: String
    val weight: Int
    fun evaluate(input: ScoreInput): Int
}
