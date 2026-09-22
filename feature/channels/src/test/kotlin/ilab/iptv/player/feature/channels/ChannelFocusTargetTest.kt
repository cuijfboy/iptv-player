package ilab.iptv.player.feature.channels

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/**
 * P3-7 items 2/4: where the remote lands when the browse screen comes back. `docs/02 §8.1 播放返回`
 * promises "按 channelId 恢复焦点（找不到则回到分组首项）"; the same fallback chain is what re-arms the
 * screen on resume, and the trap it must avoid is focusing a group header (not focusable, so the
 * request silently fails and the screen is left with no cursor).
 */
class ChannelFocusTargetTest {

    private val rows = ChannelListRows.build(
        listOf(
            channel(1, "CCTV1", "央视"),
            channel(2, "CCTV2", "央视"),
            channel(3, "湖南卫视", "卫视"),
        ),
    )

    @Test
    fun `the channel that was playing gets focus back`() {
        assertThat(ChannelFocusTarget.positionOf(rows, preferredChannelId = 2L)).isEqualTo(2)
    }

    @Test
    fun `an unknown channel falls back to the first channel row, not the header`() {
        val target = ChannelFocusTarget.positionOf(rows, preferredChannelId = 999L)

        assertThat(target).isEqualTo(1)
        assertThat(rows[target]).isInstanceOf(ChannelListRow.ChannelItem::class.java)
    }

    @Test
    fun `no preferred channel means the first channel row`() {
        assertThat(ChannelFocusTarget.positionOf(rows, preferredChannelId = null)).isEqualTo(1)
    }

    @Test
    fun `an empty list reports that there is nothing to focus`() {
        assertThat(ChannelFocusTarget.positionOf(emptyList(), preferredChannelId = 1L))
            .isEqualTo(ChannelFocusTarget.NO_ROW)
    }

    @Test
    fun `a list of headers only has nothing to focus`() {
        val headersOnly: List<ChannelListRow> = ChannelListRows.build(listOf(channel(1, "CCTV1", "央视")))
            .filter { it is ChannelListRow.GroupHeader }

        assertThat(ChannelFocusTarget.positionOf(headersOnly, preferredChannelId = 1L))
            .isEqualTo(ChannelFocusTarget.NO_ROW)
    }

    private fun channel(id: Long, name: String, groupTitle: String) = Channel(
        id = id,
        name = name,
        tvgId = null,
        group = ChannelGrouping.classify(groupTitle),
        logoUrl = null,
        channelNo = null,
        favorite = false,
        hidden = false,
        sortOrder = 0,
        epgChannelId = null,
        epgMatch = EpgMatchType.NONE,
        streamCount = 1,
        nameKey = ChannelGrouping.normalizeKey(name),
        groupKey = ChannelGrouping.groupKey(groupTitle),
        groupTitle = groupTitle,
    )
}
