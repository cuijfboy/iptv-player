package ilab.iptv.player.core.domain.channel

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.channel.ChannelGroupingTest.Companion.channel
import org.junit.Test

/** Pins the display order: classification → group_key → channel number (nulls last) → name → id. */
class ChannelSorterTest {

    @Test
    fun `orders by classification first, then group key`() {
        val sorted = ChannelSorter.sort(
            listOf(
                channel(1, "local-a", groupTitle = "地方/其他"),
                channel(2, "sat-a", groupTitle = "卫视"),
                channel(3, "cctv-b", groupTitle = "央视"),
                channel(4, "cctv-a", groupTitle = "央视"),
            ),
        )

        assertThat(sorted.map { it.id }).containsExactly(4L, 3L, 2L, 1L).inOrder()
    }

    @Test
    fun `a channel number sorts before the manual order and the name`() {
        val sorted = ChannelSorter.sort(
            listOf(
                channel(1, "zeta", groupTitle = "央视", channelNo = 9),
                channel(2, "alpha", groupTitle = "央视", channelNo = 2),
                channel(3, "beta", groupTitle = "央视", channelNo = null),
            ),
        )

        assertThat(sorted.map { it.name }).containsExactly("alpha", "zeta", "beta").inOrder()
    }

    @Test
    fun `equal keys fall back to name then id, so the order is total`() {
        val sorted = ChannelSorter.sort(
            listOf(
                channel(7, "same", groupTitle = "央视"),
                channel(3, "same", groupTitle = "央视"),
                channel(5, "another", groupTitle = "央视"),
            ),
        )

        assertThat(sorted.map { it.id }).containsExactly(5L, 3L, 7L).inOrder()
    }
}
