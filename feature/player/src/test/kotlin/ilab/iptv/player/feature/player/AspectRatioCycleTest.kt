package ilab.iptv.player.feature.player

import androidx.media3.ui.AspectRatioFrameLayout
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.AspectRatioMode
import org.junit.Test

/**
 * The four-mode cycle of docs/02 §7.4 plus the view constants it feeds. The constant check is not
 * ceremony: `AspectRatioCycle` repeats media3-ui's numbers so the mapping stays pure and unit-tested,
 * and this test is what stops those numbers from silently drifting from the library's.
 */
class AspectRatioCycleTest {

    @Test
    fun `every mode is in the cycle exactly once`() {
        assertThat(AspectRatioCycle.ORDER).containsExactlyElementsIn(AspectRatioMode.entries)
    }

    @Test
    fun `cycling walks the four modes and wraps`() {
        var mode = AspectRatioMode.FIT
        val seen = mutableListOf(mode)
        repeat(AspectRatioCycle.ORDER.size) {
            mode = AspectRatioCycle.next(mode)
            seen += mode
        }

        assertThat(seen).containsExactly(
            AspectRatioMode.FIT,
            AspectRatioMode.FILL,
            AspectRatioMode.ZOOM,
            AspectRatioMode.FIXED_4_3,
            AspectRatioMode.FIT,
        ).inOrder()
    }

    @Test
    fun `resize modes match media3-ui`() {
        assertThat(AspectRatioCycle.RESIZE_MODE_FIT).isEqualTo(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        assertThat(AspectRatioCycle.RESIZE_MODE_FIXED_WIDTH)
            .isEqualTo(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH)
        assertThat(AspectRatioCycle.RESIZE_MODE_FILL).isEqualTo(AspectRatioFrameLayout.RESIZE_MODE_FILL)
        assertThat(AspectRatioCycle.RESIZE_MODE_ZOOM).isEqualTo(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    }

    @Test
    fun `only the fixed mode pins an aspect ratio and it is four by three`() {
        AspectRatioMode.entries.forEach { mode ->
            val ratio = AspectRatioCycle.fixedAspectRatio(mode)
            if (mode == AspectRatioMode.FIXED_4_3) {
                assertThat(ratio).isWithin(0.0001f).of(4f / 3f)
            } else {
                assertThat(ratio).isNull()
            }
        }
    }

    @Test
    fun `each mode has a distinct label`() {
        val labels = AspectRatioMode.entries.map(AspectRatioCycle::label)

        assertThat(labels).containsNoDuplicates()
        assertThat(labels).doesNotContain("")
    }
}
