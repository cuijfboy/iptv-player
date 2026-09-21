package ilab.iptv.player.core.domain.scoring

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.StreamHealth
import org.junit.Test

class StabilityTest {

    @Test
    fun `an unobserved stream is neutral, not a failure`() {
        val health = StreamHealth(attempts = 0, failures = 0, lastOkAtMs = null, consecutiveFails = 0)
        assertThat(Stability.of(health)).isEqualTo(1.0)
    }

    @Test
    fun `the failure rate subtracts linearly`() {
        assertThat(Stability.of(StreamHealth(4, 0, null, 0))).isEqualTo(1.0)
        assertThat(Stability.of(StreamHealth(4, 2, null, 2))).isEqualTo(0.5)
        assertThat(Stability.of(StreamHealth(4, 4, null, 4))).isEqualTo(0.0)
    }

    @Test
    fun `a slow starter loses the W-S1-2 tail penalty`() {
        val healthy = StreamHealth(4, 0, null, 0)
        assertThat(Stability.of(healthy, startCostMedianMs = 1_200)).isEqualTo(1.0)
        assertThat(Stability.of(healthy, startCostMedianMs = 3_000)).isEqualTo(1.0)
        assertThat(Stability.of(healthy, startCostMedianMs = 4_500)).isWithin(1e-9).of(0.9)
        assertThat(Stability.of(healthy, startCostMedianMs = 6_200)).isEqualTo(0.8)
    }

    @Test
    fun `the two penalties compose and clamp at zero`() {
        val bad = StreamHealth(2, 2, null, 2)
        assertThat(Stability.of(bad, startCostMedianMs = 6_000)).isEqualTo(0.0)
    }
}
