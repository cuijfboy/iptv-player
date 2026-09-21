package ilab.iptv.player.feature.channels

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.domain.channel.ChannelNumberSource
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgMatchType
import org.junit.Test

/**
 * Pins the row builder: one header per group, one row per channel, and D12 numbering applied in the
 * order the list actually renders (docs/01 D12 tier 3 is "automatic numbering by list order").
 */
class ChannelListRowsTest {

    @Test
    fun `a header is emitted per group with its channel count`() {
        val rows = ChannelListRows.build(
            listOf(
                channel(1, "CCTV1", "央视"),
                channel(2, "CCTV2", "央视"),
                channel(3, "湖南卫视", "卫视"),
            ),
        )

        assertThat(rows.map { it.key }).containsExactly(
            "group:央视",
            "channel:1",
            "channel:2",
            "group:卫视",
            "channel:3",
        ).inOrder()
        assertThat((rows[0] as ChannelListRow.GroupHeader).count).isEqualTo(2)
        assertThat((rows[3] as ChannelListRow.GroupHeader).count).isEqualTo(1)
        assertThat((rows[0] as ChannelListRow.GroupHeader).title).isEqualTo("央视")
    }

    @Test
    fun `automatic numbers follow the rendered order, not the input order`() {
        val rows = ChannelListRows.build(
            listOf(
                channel(1, "湖南卫视", "卫视"),
                channel(2, "CCTV2", "央视"),
                channel(3, "CCTV1", "央视"),
            ),
        )

        val items = rows.filterIsInstance<ChannelListRow.ChannelItem>()
        assertThat(items.map { it.name }).containsExactly("CCTV1", "CCTV2", "湖南卫视").inOrder()
        assertThat(items.map { it.number }).containsExactly(1, 2, 3).inOrder()
        assertThat(items.map { it.numberSource })
            .containsExactly(
                ChannelNumberSource.AUTO,
                ChannelNumberSource.AUTO,
                ChannelNumberSource.AUTO,
            )
            .inOrder()
    }

    @Test
    fun `a source channel number is kept and the automatic cursor steps over it`() {
        val rows = ChannelListRows.build(
            listOf(
                channel(1, "CCTV1", "央视", channelNo = 5),
                channel(2, "CCTV2", "央视"),
            ),
        )

        val items = rows.filterIsInstance<ChannelListRow.ChannelItem>()
        assertThat(items.map { it.number }).containsExactly(5, 1).inOrder()
        assertThat(items.map { it.numberSource }).containsExactly(
            ChannelNumberSource.SOURCE_TVG_CHNO,
            ChannelNumberSource.AUTO,
        ).inOrder()
    }

    @Test
    fun `658 channels become 663 rows and the list stays one flat sequence`() {
        val channels = ArrayList<Channel>(658)
        val groups = listOf("央视" to 80, "卫视" to 69, "港澳台" to 7, "地方/其他" to 500, "其他频道" to 2)
        var id = 1L
        for ((title, count) in groups) {
            repeat(count) {
                channels += channel(id, "ch-$id", title)
                id++
            }
        }

        val rows = ChannelListRows.build(channels)

        assertThat(rows).hasSize(663)
        assertThat(rows.count { it is ChannelListRow.GroupHeader }).isEqualTo(5)
        assertThat(rows.filterIsInstance<ChannelListRow.ChannelItem>()).hasSize(658)
        assertThat(rows.map { it.key }.toSet()).hasSize(663)
        assertThat(rows.filterIsInstance<ChannelListRow.GroupHeader>().map { it.title })
            .containsExactly("央视", "卫视", "港澳台", "地方/其他", "其他频道")
            .inOrder()
    }

    @Test
    fun `the logo placeholder falls back to a question mark for a blank name`() {
        val rows = ChannelListRows.build(listOf(channel(1, "   ", "央视")))

        assertThat((rows[1] as ChannelListRow.ChannelItem).initial).isEqualTo("?")
    }

    private fun channel(id: Long, name: String, groupTitle: String, channelNo: Int? = null) = Channel(
        id = id,
        name = name,
        tvgId = null,
        group = ChannelGrouping.classify(groupTitle),
        logoUrl = null,
        channelNo = channelNo,
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
