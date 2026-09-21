package ilab.iptv.player.core.domain.scoring

import ilab.iptv.player.core.model.ScoreBreakdown
import ilab.iptv.player.core.model.ScoreInput
import kotlin.math.roundToInt

/**
 * docs/02 §4.3 `Scorer`: sums the registered [ScoringRule]s and normalizes the total to 0..100.
 *
 * Two properties worth stating because callers depend on them:
 * - **clamped per rule**: a rule's answer is clamped to `0..weight`, so a buggy rule cannot inflate
 *   the sum (and a penalty can never push a dimension negative);
 * - **normalized by the registered weight** (`sum of weights`, 97 with the §6.1 table): the
 *   document's weights do not add up to 100, and §8.4 E6 asks for a 100-point total, so the raw sum
 *   is scaled. A caller that swaps the rule set gets a total on the same 0..100 scale, and
 *   [ScoreBreakdown.byRule] still carries the raw, un-scaled points per dimension.
 */
class DefaultScorer(
    private val rules: List<ScoringRule> = DefaultScoringRules.all(),
) : Scorer {

    override fun score(input: ScoreInput): ScoreBreakdown {
        val byRule = LinkedHashMap<String, Int>(rules.size)
        var raw = 0
        for (rule in rules) {
            val points = rule.evaluate(input).coerceIn(0, rule.weight)
            byRule[rule.id] = points
            raw += points
        }
        val maxRaw = rules.sumOf { it.weight }
        val total = if (maxRaw <= 0) 0 else (raw * 100.0 / maxRaw).roundToInt()
        return ScoreBreakdown(total = total, byRule = byRule)
    }
}
