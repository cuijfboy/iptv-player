package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/04 P3-3: 过扫描微调（±几档）持久化 — the ladder and its clamp behaviour. */
class OverscanPolicyTest {

    @Test
    fun `the ladder is symmetric around the neutral step`() {
        assertThat(OverscanPolicy.PERCENTS).containsExactly(94, 96, 98, 100, 102, 104, 106).inOrder()
        assertThat(OverscanPolicy.percent(OverscanPolicy.DEFAULT_INDEX)).isEqualTo(100)
        assertThat(OverscanPolicy.scale(OverscanPolicy.DEFAULT_INDEX)).isEqualTo(1f)
        assertThat(OverscanPolicy.isDefault(OverscanPolicy.DEFAULT_INDEX)).isTrue()
    }

    @Test
    fun `steps move one notch and stop at the ends`() {
        val middle = OverscanPolicy.DEFAULT_INDEX
        assertThat(OverscanPolicy.move(middle, +1)).isEqualTo(middle + 1)
        assertThat(OverscanPolicy.move(middle, -1)).isEqualTo(middle - 1)
        // Clamp, never wrap: the end of the ladder is a deliberate place to be.
        assertThat(OverscanPolicy.move(OverscanPolicy.PERCENTS.lastIndex, +1))
            .isEqualTo(OverscanPolicy.PERCENTS.lastIndex)
        assertThat(OverscanPolicy.move(0, -1)).isEqualTo(0)
    }

    @Test
    fun `a corrupt stored index is clamped instead of throwing`() {
        assertThat(OverscanPolicy.clamp(-9)).isEqualTo(0)
        assertThat(OverscanPolicy.clamp(99)).isEqualTo(OverscanPolicy.PERCENTS.lastIndex)
        assertThat(OverscanPolicy.percent(99)).isEqualTo(OverscanPolicy.PERCENTS.last())
        assertThat(OverscanPolicy.move(-5, +1)).isEqualTo(1)
    }

    @Test
    fun `scale and label follow the percent`() {
        assertThat(OverscanPolicy.scale(0)).isEqualTo(0.94f)
        assertThat(OverscanPolicy.scale(OverscanPolicy.PERCENTS.lastIndex)).isEqualTo(1.06f)
        assertThat(OverscanPolicy.label(OverscanPolicy.DEFAULT_INDEX)).isEqualTo("100%")
        assertThat(OverscanPolicy.label(0)).isEqualTo("94%")
    }
}
