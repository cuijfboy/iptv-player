package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.AspectRatioMode
import org.junit.Test

/** docs/02 §7.4: the four display modes exposed as an engine parameter. */
class AspectRatioPlanTest {

    @Test
    fun `all four modes map to a plan`() {
        assertThat(AspectRatioPlan.of(AspectRatioMode.FIT)).isEqualTo(AspectRatioPlan(ScaleMode.FIT, null))
        assertThat(AspectRatioPlan.of(AspectRatioMode.FILL)).isEqualTo(AspectRatioPlan(ScaleMode.FILL, null))
        assertThat(AspectRatioPlan.of(AspectRatioMode.ZOOM)).isEqualTo(AspectRatioPlan(ScaleMode.ZOOM, null))
        assertThat(AspectRatioPlan.of(AspectRatioMode.FIXED_4_3).scaleMode).isEqualTo(ScaleMode.FIXED_ASPECT)
        assertThat(AspectRatioPlan.of(AspectRatioMode.FIXED_4_3).fixedAspectRatio).isWithin(0.0001f).of(4f / 3f)
    }

    @Test
    fun `every enum value is covered`() {
        AspectRatioMode.entries.forEach { mode ->
            assertThat(AspectRatioPlan.of(mode)).isNotNull()
        }
    }

    @Test
    fun `only the fixed mode carries a ratio`() {
        assertNoRatio(AspectRatioMode.FIT)
        assertNoRatio(AspectRatioMode.FILL)
        assertNoRatio(AspectRatioMode.ZOOM)
        assertThat(AspectRatioPlan.of(AspectRatioMode.FIXED_4_3).fixedAspectRatio).isNotNull()
    }

    private fun assertNoRatio(mode: AspectRatioMode) {
        assertThat(AspectRatioPlan.of(mode).fixedAspectRatio).isNull()
    }
}
