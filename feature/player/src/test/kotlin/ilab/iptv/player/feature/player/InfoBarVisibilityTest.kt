package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P1-4 item 2's acceptance number, as a test: any key shows the bar, and 5 s of no input fades it.
 * The clock is injected, so the assertion is about the rule and not about sleeping in a test.
 */
class InfoBarVisibilityTest {

    private val infoBar = InfoBarVisibility()

    @Test
    fun `the auto-fade window is the frozen five seconds`() {
        assertThat(InfoBarVisibility.INFO_BAR_TIMEOUT_MS).isEqualTo(5_000L)
    }

    @Test
    fun `a key shows the bar and arms the fade`() {
        infoBar.onKey(nowMs = 1_000)

        assertThat(infoBar.visible).isTrue()
        assertThat(infoBar.hideAtMs).isEqualTo(6_000)
    }

    @Test
    fun `the bar survives until the window is over`() {
        infoBar.onKey(1_000)

        assertThat(infoBar.tick(5_999)).isFalse()
        assertThat(infoBar.visible).isTrue()
        assertThat(infoBar.tick(6_000)).isTrue()
        assertThat(infoBar.visible).isFalse()
    }

    @Test
    fun `the fade fires exactly once`() {
        infoBar.onKey(0)
        assertThat(infoBar.tick(5_000)).isTrue()

        assertThat(infoBar.tick(5_001)).isFalse()
    }

    @Test
    fun `another key restarts the countdown instead of letting the bar fade mid-read`() {
        infoBar.onKey(1_000)

        val shown = infoBar.onKey(4_500)

        assertThat(shown).isTrue()
        assertThat(infoBar.hideAtMs).isEqualTo(9_500)
        assertThat(infoBar.tick(6_000)).isFalse()
        assertThat(infoBar.tick(9_500)).isTrue()
    }

    @Test
    fun `hiding by hand cancels the fade so a late tick cannot double-fire`() {
        infoBar.onKey(1_000)

        infoBar.hide()

        assertThat(infoBar.visible).isFalse()
        assertThat(infoBar.tick(6_000)).isFalse()
    }
}
