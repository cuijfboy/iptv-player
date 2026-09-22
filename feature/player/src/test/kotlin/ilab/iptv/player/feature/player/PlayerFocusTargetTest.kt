package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P3-7 items 2/4: the player's focus rules, pinned without a screen.
 *
 * 1. a retryable failure card takes focus (one OK press reaches 重试);
 * 2. when that card goes away, focus comes back to the video root instead of staying on a `GONE` view;
 * 3. nothing else is moved behind the user's back — walking the info bar with LEFT/RIGHT must not be
 *    interrupted by a state emission.
 */
class PlayerFocusTargetTest {

    private val failure = PlayerOverlay.error("无可用源，按「重试」再试一次")
    private val buffering = PlayerOverlay.BUFFERING
    private val none = PlayerOverlay.NONE

    @Test
    fun `a retryable failure takes focus from wherever it was`() {
        PlayerFocusSlot.entries.forEach { holding ->
            assertThat(PlayerFocusTarget.afterOverlayChange(failure, holding))
                .isEqualTo(PlayerFocusSlot.RETRY)
        }
    }

    @Test
    fun `a starting or buffering card without a retry entry does not steal focus`() {
        assertThat(PlayerFocusTarget.afterOverlayChange(buffering, PlayerFocusSlot.ROOT))
            .isEqualTo(PlayerFocusSlot.NONE)
        assertThat(PlayerFocusTarget.afterOverlayChange(PlayerOverlay.STARTING, PlayerFocusSlot.INFO_BAR))
            .isEqualTo(PlayerFocusSlot.NONE)
    }

    @Test
    fun `when the failure card disappears focus comes back to the root`() {
        assertThat(PlayerFocusTarget.afterOverlayChange(none, PlayerFocusSlot.RETRY))
            .isEqualTo(PlayerFocusSlot.ROOT)
        assertThat(PlayerFocusTarget.afterOverlayChange(buffering, PlayerFocusSlot.RETRY))
            .isEqualTo(PlayerFocusSlot.ROOT)
    }

    @Test
    fun `the info bar is left alone while it is up`() {
        // The bar's four controls and the root are both live targets; a state emission must not move
        // the cursor between them mid-walk.
        assertThat(PlayerFocusTarget.afterOverlayChange(none, PlayerFocusSlot.INFO_BAR))
            .isEqualTo(PlayerFocusSlot.NONE)
        assertThat(PlayerFocusTarget.afterOverlayChange(buffering, PlayerFocusSlot.INFO_BAR))
            .isEqualTo(PlayerFocusSlot.NONE)
    }

    @Test
    fun `hiding the bar hands focus back to the video root only if the bar had it`() {
        assertThat(PlayerFocusTarget.afterInfoBarHidden(PlayerFocusSlot.INFO_BAR))
            .isEqualTo(PlayerFocusSlot.ROOT)
        assertThat(PlayerFocusTarget.afterInfoBarHidden(PlayerFocusSlot.ROOT))
            .isEqualTo(PlayerFocusSlot.NONE)
        assertThat(PlayerFocusTarget.afterInfoBarHidden(PlayerFocusSlot.RETRY))
            .isEqualTo(PlayerFocusSlot.NONE)
    }

    @Test
    fun `a visible failure card is never bypassed`() {
        // Item 4: as long as the card is up, no emission may park the remote on the root while the
        // user has 重试 in front of them — that is the "焦点不在该在的地方" the card is about.
        PlayerFocusSlot.entries.forEach { holding ->
            assertThat(PlayerFocusTarget.afterOverlayChange(failure, holding))
                .isNotEqualTo(PlayerFocusSlot.ROOT)
        }
    }

    @Test
    fun `a failure card that arrives while nothing has focus still takes it`() {
        assertThat(PlayerFocusTarget.afterOverlayChange(failure, PlayerFocusSlot.NONE))
            .isEqualTo(PlayerFocusSlot.RETRY)
    }
}
