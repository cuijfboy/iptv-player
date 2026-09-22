package ilab.iptv.player.core.domain.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * docs/04 P2-9 item 1: "首次运行判定 — 已完成的用户不再打扰（含升级场景）".
 *
 * The three rows of the table are the whole card's entry condition, so they are asserted directly
 * rather than through the launcher.
 */
class FirstRunGateTest {

    private fun facts(
        completed: Boolean = false,
        imported: Boolean = false,
        subscriptions: Int = 0,
    ) = FirstRunFacts(
        wizardCompleted = completed,
        importedPlaylist = imported,
        subscriptionCount = subscriptions,
    )

    @Test
    fun `a fresh install with nothing configured gets the wizard`() {
        assertThat(FirstRunGate.decide(facts())).isEqualTo(FirstRunRoute.WIZARD)
    }

    @Test
    fun `a user whose flag is set goes straight to the channel list`() {
        assertThat(FirstRunGate.decide(facts(completed = true))).isEqualTo(FirstRunRoute.BROWSE)
    }

    @Test
    fun `the flag wins over evidence, so a returning user is never re-gated`() {
        val decided = FirstRunGate.decide(facts(completed = true, imported = true, subscriptions = 4))

        assertThat(decided).isEqualTo(FirstRunRoute.BROWSE)
    }

    @Test
    fun `an upgraded install that imported a playlist goes to the list as an upgrade`() {
        assertThat(FirstRunGate.decide(facts(imported = true)))
            .isEqualTo(FirstRunRoute.BROWSE_UPGRADE)
    }

    @Test
    fun `an upgraded install with subscriptions goes to the list as an upgrade`() {
        assertThat(FirstRunGate.decide(facts(subscriptions = 1)))
            .isEqualTo(FirstRunRoute.BROWSE_UPGRADE)
    }

    @Test
    fun `only the upgrade route records completion`() {
        // The wizard must record it when the user finishes (the screen does that), and the upgrade
        // route must record it immediately; the plain BROWSE route has nothing left to write, and
        // WIZARD must NOT write it — an abandoned wizard has to come back next launch.
        assertThat(FirstRunGate.recordsCompletion(FirstRunRoute.BROWSE_UPGRADE)).isTrue()
        assertThat(FirstRunGate.recordsCompletion(FirstRunRoute.BROWSE)).isFalse()
        assertThat(FirstRunGate.recordsCompletion(FirstRunRoute.WIZARD)).isFalse()
    }

    @Test
    fun `prior use is exactly the two pieces of evidence`() {
        assertThat(facts().hasPriorUse).isFalse()
        assertThat(facts(imported = true).hasPriorUse).isTrue()
        assertThat(facts(subscriptions = 1).hasPriorUse).isTrue()
    }
}
