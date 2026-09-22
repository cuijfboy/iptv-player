package ilab.iptv.player.feature.channels

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.channel.ChannelNumberSource
import ilab.iptv.player.core.model.ChannelGroup
import org.junit.Test

/**
 * docs/04 P2-9 item 2 ③: "进入频道列表并聚焦**第一个可播放**频道".
 *
 * The definition is the whole point of the test: a group header is not a channel, and a channel with
 * no streams is not playable — the refresh deliberately keeps such channels (docs/01 F2), so a partly
 * failed run can put one at the top of the list, and landing on it would make the wizard's promise
 * false on the very first press of OK.
 */
class FirstPlayableRowTest {

    @Test
    fun `an empty list has nothing to focus`() {
        assertThat(FirstPlayableRow.indexOf(emptyList())).isEqualTo(-1)
    }

    @Test
    fun `the index points at the first channel with a stream`() {
        val rows = listOf(
            header(),
            channel(1, streams = 0),
            channel(2, streams = 1),
            channel(3, streams = 2),
        )

        assertThat(FirstPlayableRow.indexOf(rows)).isEqualTo(2)
    }

    @Test
    fun `a list where nothing can play has no landing spot`() {
        val rows = listOf(header(), channel(1, streams = 0), channel(2, streams = 0))

        assertThat(FirstPlayableRow.indexOf(rows)).isEqualTo(-1)
    }

    @Test
    fun `a group header is never the landing spot`() {
        val rows = listOf(header(), channel(1, streams = 1))

        assertThat(FirstPlayableRow.indexOf(rows)).isEqualTo(1)
    }

    private fun header() = ChannelListRow.GroupHeader(
        groupKey = ChannelGroup.CCTV.key,
        title = "央视",
        count = 3,
        group = ChannelGroup.CCTV,
    )

    private fun channel(id: Long, streams: Int) = ChannelListRow.ChannelItem(
        channelId = id,
        number = id.toInt(),
        numberSource = ChannelNumberSource.AUTO,
        name = "频道 $id",
        sourceName = "频道 $id",
        renamed = false,
        logoUrl = null,
        streamCount = streams,
        groupTitle = "央视",
        groupKey = ChannelGroup.CCTV.key,
    )
}
