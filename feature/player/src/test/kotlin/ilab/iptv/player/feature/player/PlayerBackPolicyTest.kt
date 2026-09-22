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

    @Test
    fun `the failure card is not a back level so the second press leaves playback`() {
        // P3-7 item 1's audit correction. docs/02 §8.2 freezes the two levels as "信息条可见 → 先收起
        // 信息条；再按 → 回浏览页", and the failure card is a status the screen is in (with 重试 on it),
        // not a dismissible layer — so it must not swallow a BACK press. This used to be contradicted by
        // the policy's own comment; the comment now says the same thing this asserts.
        policy.onOverlayShown() // the bar came up together with the card

        assertThat(policy.onBack()).isEqualTo(BackAction.HideOverlay)
        // Bar down, card still up: the next BACK exits.
        assertThat(policy.onBack()).isEqualTo(BackAction.ExitPlayer)
    }
}
