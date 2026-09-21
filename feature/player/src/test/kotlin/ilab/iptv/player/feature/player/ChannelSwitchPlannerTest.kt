package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelGroup
import org.junit.Test

/**
 * P1-5 item 1, the pure half: which channel UP/DOWN and the digit keys select. The keys themselves
 * are proven on the device (see the verification file); this pins the selection rules.
 */
class ChannelSwitchPlannerTest {

    private val cctv = listOf(
        channel(id = 11, number = 1, groupKey = "cctv"),
        channel(id = 12, number = 2, groupKey = "cctv"),
        channel(id = 13, number = 3, groupKey = "cctv"),
    )
    private val local = listOf(channel(id = 21, number = 11, groupKey = "local"))
    private val numbered = ChannelSwitchPlanner.numbered(cctv + local)

    @Test
    fun `up and down walk the current group in list order`() {
        assertThat(ChannelSwitchPlanner.neighbor(numbered, currentId = 12, delta = 1)?.id).isEqualTo(13)
        assertThat(ChannelSwitchPlanner.neighbor(numbered, currentId = 12, delta = -1)?.id).isEqualTo(11)
    }

    @Test
    fun `group edges clamp instead of wrapping into another group`() {
        assertThat(ChannelSwitchPlanner.neighbor(numbered, currentId = 13, delta = 1)).isNull()
        assertThat(ChannelSwitchPlanner.neighbor(numbered, currentId = 11, delta = -1)).isNull()
        // A single-channel group has no neighbour at all.
        assertThat(ChannelSwitchPlanner.neighbor(numbered, currentId = 21, delta = 1)).isNull()
    }

    @Test
    fun `an unknown current channel selects nothing`() {
        assertThat(ChannelSwitchPlanner.neighbor(numbered, currentId = 999, delta = 1)).isNull()
        assertThat(ChannelSwitchPlanner.neighbor(numbered, currentId = 12, delta = 0)).isNull()
    }

    @Test
    fun `the digit jump uses the number the user sees in the browse list`() {
        assertThat(ChannelSwitchPlanner.byNumber(numbered, 3)?.id).isEqualTo(13)
        assertThat(ChannelSwitchPlanner.byNumber(numbered, 11)?.id).isEqualTo(21)
        assertThat(ChannelSwitchPlanner.byNumber(numbered, 99)).isNull()
    }

    @Test
    fun `channels without a source number are numbered automatically in list order`() {
        val auto = listOf(
            channel(id = 31, number = null, groupKey = "cctv"),
            channel(id = 32, number = null, groupKey = "cctv"),
        )
        val numbered = ChannelSwitchPlanner.numbered(auto)

        assertThat(numbered.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(ChannelSwitchPlanner.byNumber(numbered, 2)?.id).isEqualTo(32)
    }

    private fun channel(id: Long, number: Int?, groupKey: String): Channel = Channel(
        id = id,
        name = "CH$id",
        nameKey = "ch$id",
        tvgId = null,
        group = ChannelGroup.CCTV,
        groupKey = groupKey,
        groupTitle = groupKey,
        logoUrl = null,
        channelNo = number,
        favorite = false,
        hidden = false,
        sortOrder = id.toInt(),
        epgChannelId = null,
        epgMatch = ilab.iptv.player.core.model.EpgMatchType.NONE,
        streamCount = 1,
    )
}
