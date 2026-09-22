package ilab.iptv.player.feature.channels

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/**
 * P3-4: what the browse rows show once the user has renamed a channel, moved it to another group, or
 * marked it in the manager. The row is the only place these overlays become visible, so the mapping
 * from the domain fields to the rendered fields is pinned here.
 */
class ChannelListRowsOverlayTest {

    @Test
    fun `a rename shows the user's name and keeps the source name for the meta line`() {
        val rows = ChannelListRows.build(
            listOf(channel(1, "CCTV-1 综合").copy(displayName = "中央一套")),
        )

        val item = rows.filterIsInstance<ChannelListRow.ChannelItem>().single()
        assertThat(item.name).isEqualTo("中央一套")
        assertThat(item.sourceName).isEqualTo("CCTV-1 综合")
        assertThat(item.renamed).isTrue()
    }

    @Test
    fun `without a rename the row is the source name and is not flagged`() {
        val item = ChannelListRows.build(listOf(channel(1, "CCTV-1 综合")))
            .filterIsInstance<ChannelListRow.ChannelItem>()
            .single()

        assertThat(item.name).isEqualTo("CCTV-1 综合")
        assertThat(item.renamed).isFalse()
    }

    @Test
    fun `manage mode marks the row and the selection is reflected`() {
        val rows = ChannelListRows.build(
            channels = listOf(channel(1, "CCTV1"), channel(2, "CCTV2")),
            selected = setOf(2L),
            manageMode = true,
        )

        val items = rows.filterIsInstance<ChannelListRow.ChannelItem>().associateBy { it.channelId }
        assertThat(items.getValue(1L).manageMode).isTrue()
        assertThat(items.getValue(1L).selected).isFalse()
        assertThat(items.getValue(2L).selected).isTrue()
    }

    @Test
    fun `a group move renders the row under the target group key`() {
        val item = ChannelListRows.build(
            listOf(channel(1, "CCTV1").copy(userGroupTitle = "卫视")),
        ).filterIsInstance<ChannelListRow.ChannelItem>().single()

        assertThat(item.groupKey).isEqualTo(ChannelGrouping.groupKey("卫视"))
        assertThat(item.groupTitle).isEqualTo("卫视")
    }

    private fun channel(id: Long, name: String) = Channel(
        id = id,
        name = name,
        tvgId = null,
        group = ChannelGrouping.classify("央视"),
        logoUrl = null,
        channelNo = null,
        favorite = false,
        hidden = false,
        sortOrder = 0,
        epgChannelId = null,
        epgMatch = EpgMatchType.NONE,
        streamCount = 1,
        nameKey = ChannelGrouping.normalizeKey(name),
        groupKey = ChannelGrouping.groupKey("央视"),
        groupTitle = "央视",
    )
}
