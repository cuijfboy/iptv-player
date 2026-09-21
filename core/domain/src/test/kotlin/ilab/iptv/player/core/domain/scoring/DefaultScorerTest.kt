package ilab.iptv.player.core.domain.scoring

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultScorerTest {

    private val scorer = DefaultScorer()

    @Test
    fun `a fully verified 1080p h264 stream normalizes to 100`() {
        val breakdown = scorer.score(
            scoreInput(
                stream = scoreStream(),
                probe = probe(evidence = mapOf("segStatus" to 200)),
            ),
        )
        assertThat(breakdown.total).isEqualTo(100)
        assertThat(breakdown.byRule["availability"]).isEqualTo(35)
        assertThat(breakdown.byRule["device_compat"]).isEqualTo(20)
        assertThat(breakdown.byRule["anti_leech"]).isEqualTo(5)
    }

    @Test
    fun `everything missing keeps only the two unconditional credits`() {
        val breakdown = scorer.score(
            scoreInput(
                stream = scoreStream(),
                probe = probe(passed = false),
                videoCodec = null,
                audioCodec = null,
                width = 0,
                height = 0,
                stability = 0.0,
            ),
        )
        // The only points left are the neutral 12 for "cannot tell the codec" and the 5 for a stream
        // that needs no special headers: raw 17 of 97 → 18 of 100.
        assertThat(breakdown.total).isEqualTo(18)
        assertThat(breakdown.byRule["availability"]).isEqualTo(0)
        assertThat(breakdown.byRule["resolution"]).isEqualTo(0)
    }

    @Test
    fun `a rule can never exceed its weight`() {
        val greedy = object : ScoringRule {
            override val id: String = "greedy"
            override val weight: Int = 10
            override fun evaluate(input: ilab.iptv.player.core.model.ScoreInput): Int = 1_000
        }
        val breakdown = DefaultScorer(listOf(greedy)).score(scoreInput())
        assertThat(breakdown.byRule["greedy"]).isEqualTo(10)
        assertThat(breakdown.total).isEqualTo(100)
    }

    @Test
    fun `an empty rule set scores zero instead of dividing by zero`() {
        assertThat(DefaultScorer(emptyList()).score(scoreInput()).total).isEqualTo(0)
    }

    @Test
    fun `the default rule set is the six documented dimensions in order`() {
        val ids = DefaultScoringRules.all().map { it.id }
        assertThat(ids)
            .containsExactly("availability", "stability", "device_compat", "resolution", "anti_leech", "segment")
            .inOrder()
    }
}
