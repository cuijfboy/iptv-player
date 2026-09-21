package ilab.iptv.player.core.domain.scoring

import ilab.iptv.player.core.model.StreamHealth

/**
 * The 0..1 health factor docs/02 §6.1's 稳定性 dimension is scaled by.
 *
 * The document gives two inputs and one fold:
 * - "按轮询失败率线性扣" — the observed failure rate;
 * - "**源端起播耗时**（W-S1-2）…对历史 `PLAY_FIRST_FRAME.costMs` 中位数 >3 s 的源降权…
 *   先并入「稳定性」维度扣分" — a tail penalty for slow-starter streams, so a source that takes
 *   3.7–6.2 s to first frame loses stability rather than being named and blocked (the document
 *   forbids hard-coding source names or regions).
 *
 * Pure arithmetic, no clock, no repository: the caller supplies already-aggregated numbers.
 */
object Stability {

    /** No observation yet → neutral 1.0 (a brand-new stream must not be punished for being new). */
    const val NEUTRAL: Double = 1.0

    /** Above this start-up cost the stream starts losing points (docs/02 §6.1 W-S1-2: p50 ≈1.2 s). */
    const val SLOW_START_MS: Long = 3_000

    /** At (and beyond) this start-up cost the full tail penalty is applied. */
    const val VERY_SLOW_START_MS: Long = 6_000

    /** The most the tail penalty may remove (docs/02 §6.1: "降权", not "作废"). */
    const val MAX_TAIL_PENALTY: Double = 0.20

    /**
     * @param health attempts/failures for the stream, so the failure rate can be derived;
     * @param startCostMedianMs the median `PLAY_FIRST_FRAME.costMs` for the stream, or null when
     *   there is no play history yet (the pipeline cannot read one today — see the P2-4b report).
     */
    fun of(health: StreamHealth, startCostMedianMs: Long? = null): Double {
        val failureRate = failureRate(health)
        val tail = tailPenalty(startCostMedianMs)
        return (NEUTRAL - failureRate - tail).coerceIn(0.0, NEUTRAL)
    }

    /** `failures / attempts`, 0 when nothing was ever polled (an unobserved stream is not a failure). */
    fun failureRate(health: StreamHealth): Double =
        if (health.attempts <= 0) 0.0 else (health.failures.toDouble() / health.attempts).coerceIn(0.0, 1.0)

    /** Linear from 0 at [SLOW_START_MS] to [MAX_TAIL_PENALTY] at [VERY_SLOW_START_MS] and beyond. */
    fun tailPenalty(startCostMedianMs: Long?): Double {
        val cost = startCostMedianMs ?: return 0.0
        if (cost <= SLOW_START_MS) return 0.0
        if (cost >= VERY_SLOW_START_MS) return MAX_TAIL_PENALTY
        val span = (VERY_SLOW_START_MS - SLOW_START_MS).toDouble()
        return MAX_TAIL_PENALTY * (cost - SLOW_START_MS) / span
    }
}
