package ilab.iptv.player.core.domain.channel

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/**
 * Pins the display order: classification → group_key → manual order → channel number (nulls last) →
 * name → id. The manual order moved ahead of the number in P2-2 (the P1-2 record left the sort key
 * open: "列表排序键…P2-2 可改一处"): if the number won, a channel the user moved would snap back to
 * its numbered slot on the next list build. Every row defaults to `sort_order = 0`, so with no
 * manual order the number still decides and the default look is unchanged.
 */
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
    fun `without a manual order the channel number decides and nulls sort last`() {
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
    fun `a manual order outranks the channel number inside its section`() {
        val sorted = ChannelSorter.sort(
            listOf(
                channel(1, "numbered-first", groupTitle = "央视", channelNo = 1, sortOrder = 5),
                channel(2, "moved-above", groupTitle = "央视", channelNo = 9, sortOrder = 0),
            ),
        )

        assertThat(sorted.map { it.name }).containsExactly("moved-above", "numbered-first").inOrder()
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

    private fun channel(
        id: Long,
        name: String,
        groupTitle: String,
        channelNo: Int? = null,
        sortOrder: Int = 0,
    ): Channel = Channel(
        id = id,
        name = name,
        tvgId = null,
        group = ChannelGrouping.classify(groupTitle),
        logoUrl = null,
        channelNo = channelNo,
        favorite = false,
        hidden = false,
        sortOrder = sortOrder,
        epgChannelId = null,
        epgMatch = EpgMatchType.NONE,
        streamCount = 1,
        nameKey = ChannelGrouping.normalizeKey(name),
        groupKey = ChannelGrouping.groupKey(groupTitle),
        groupTitle = groupTitle,
    )
}
