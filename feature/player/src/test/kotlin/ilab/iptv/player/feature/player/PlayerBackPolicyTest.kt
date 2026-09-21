package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P1-4 item 4's "返回键层级一致": with the info bar showing, BACK takes the bar down; the next BACK
 * leaves playback. The test walks the whole sequence, including the auto-fade case, because that is
 * where a two-level back usually breaks.
 */
class PlayerBackPolicyTest {

    private val policy = PlayerBackPolicy()

    @Test
    fun `first back hides the overlay second back exits`() {
        policy.onOverlayShown()

        assertThat(policy.onBack()).isEqualTo(BackAction.HideOverlay)
        assertThat(policy.level).isEqualTo(PlayerBackPolicy.Level.IMMERSIVE)
        assertThat(policy.onBack()).isEqualTo(BackAction.ExitPlayer)
    }

    @Test
    fun `back on an immersive screen exits immediately`() {
        assertThat(policy.onBack()).isEqualTo(BackAction.ExitPlayer)
    }

    @Test
    fun `an auto-faded bar does not swallow the next back`() {
        policy.onOverlayShown()
        policy.onOverlayHidden()

        assertThat(policy.onBack()).isEqualTo(BackAction.ExitPlayer)
    }

    @Test
    fun `showing the bar again re-arms the first level`() {
        policy.onOverlayShown()
        assertThat(policy.onBack()).isEqualTo(BackAction.HideOverlay)

        policy.onOverlayShown()

        assertThat(policy.onBack()).isEqualTo(BackAction.HideOverlay)
    }

    @Test
    fun `exiting is idempotent once the screen is immersive`() {
        assertThat(policy.onBack()).isEqualTo(BackAction.ExitPlayer)
        assertThat(policy.onBack()).isEqualTo(BackAction.ExitPlayer)
    }
}
