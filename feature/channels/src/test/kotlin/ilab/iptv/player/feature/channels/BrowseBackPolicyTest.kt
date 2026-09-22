package ilab.iptv.player.feature.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P3-7 item 1/5: the browse screen's BACK rule as a table. The row this pins is
 * "浏览页 · 管理模式 → 退出模式；否则 → 离开浏览页（App 根，回系统）" from
 * `docs/05-过程记录/45-P3-7焦点与返回键打磨.md`.
 */
class BrowseBackPolicyTest {

    @Test
    fun `back inside manage mode leaves the mode and stays on the list`() {
        assertThat(BrowseBackPolicy.decide(manageModeActive = true))
            .isEqualTo(BrowseBackAction.EXIT_MANAGE_MODE)
    }

    @Test
    fun `back outside manage mode leaves the screen`() {
        assertThat(BrowseBackPolicy.decide(manageModeActive = false))
            .isEqualTo(BrowseBackAction.LEAVE_SCREEN)
    }

    @Test
    fun `the two presses of a manage-mode session are mode first, screen second`() {
        // The user's sequence: enter manage mode, BACK, BACK. State is the caller's; the policy is
        // asked twice with the state that each press sees.
        val first = BrowseBackPolicy.decide(manageModeActive = true)
        val second = BrowseBackPolicy.decide(manageModeActive = false)

        assertThat(listOf(first, second))
            .containsExactly(BrowseBackAction.EXIT_MANAGE_MODE, BrowseBackAction.LEAVE_SCREEN)
            .inOrder()
    }
}
