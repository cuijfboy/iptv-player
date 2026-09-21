package ilab.iptv.player.core.domain.selection

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.playback.stream
import org.junit.Test

class SelectionRulesTest {

    @Test
    fun `the highest score is primary and the next two at or above 55 are backups`() {
        val selection = SelectionRules.select(
            ranked = listOf(
                stream(1, score = 90),
                stream(2, score = 70),
                stream(3, score = 55),
                stream(4, score = 54),
            ),
            verified = { true },
        )
        assertThat(selection.primary?.id).isEqualTo(1L)
        assertThat(selection.backups.map { it.id }).containsExactly(2L, 3L).inOrder()
        assertThat(selection.dropped.map { it.id }).containsExactly(4L)
        assertThat(selection.isAvailable).isTrue()
    }

    @Test
    fun `the cap is three streams, so a fourth candidate is dropped but kept`() {
        val selection = SelectionRules.select(
            ranked = listOf(
                stream(1, score = 90),
                stream(2, score = 80),
                stream(3, score = 70),
                stream(4, score = 60),
            ),
            verified = { true },
        )
        assertThat(selection.selected.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(selection.dropped.map { it.id }).containsExactly(4L)
    }

    @Test
    fun `an unverified stream never makes it in, however high it scores`() {
        val selection = SelectionRules.select(
            ranked = listOf(stream(1, score = 100), stream(2, score = 10)),
            verified = { it.id == 2L },
        )
        assertThat(selection.primary?.id).isEqualTo(2L)
        assertThat(selection.backups).isEmpty()
        assertThat(selection.dropped.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `a weak but verified stream still becomes primary - only failing makes a channel unavailable`() {
        val selection = SelectionRules.select(ranked = listOf(stream(1, score = 20)), verified = { true })
        assertThat(selection.primary?.id).isEqualTo(1L)
        assertThat(selection.backups).isEmpty()
        assertThat(selection.isAvailable).isTrue()
    }

    @Test
    fun `no verified stream means the channel is kept but unavailable`() {
        val selection = SelectionRules.select(
            ranked = listOf(stream(1, score = 90), stream(2, score = 80)),
            verified = { false },
        )
        assertThat(selection.primary).isNull()
        assertThat(selection.selected).isEmpty()
        assertThat(selection.isAvailable).isFalse()
        assertThat(selection.dropped.map { it.id }).containsExactly(1L, 2L)
    }

    @Test
    fun `an empty candidate list is an unavailable channel, not a crash`() {
        val selection = SelectionRules.select(ranked = emptyList(), verified = { true })
        assertThat(selection.isAvailable).isFalse()
        assertThat(selection.selected).isEmpty()
    }
}
