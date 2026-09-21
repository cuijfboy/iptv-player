package ilab.iptv.player.core.domain.channel

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.channel.ChannelGroupingTest.Companion.channel
import org.junit.Test

/** docs/01 D12: user edit > source `tvg-chno` > automatic numbering by list position. */
class ChannelNumberAssignerTest {

    @Test
    fun `a source number is used verbatim and automatic numbering steps around it`() {
        val assigned = ChannelNumberAssigner.assign(
            listOf(
                channel(1, "a", channelNo = 3),
                channel(2, "b"),
                channel(3, "c"),
                channel(4, "d", channelNo = 1),
                channel(5, "e"),
            ),
        )

        // a keeps the source's 3; the automatic cursor starts at 1 (taken by d), so b→2, c→4, e→5.
        assertThat(assigned.map { it.number }).containsExactly(3, 2, 4, 1, 5).inOrder()
        assertThat(assigned.map { it.source }).containsExactly(
            ChannelNumberSource.SOURCE_TVG_CHNO,
            ChannelNumberSource.AUTO,
            ChannelNumberSource.AUTO,
            ChannelNumberSource.SOURCE_TVG_CHNO,
            ChannelNumberSource.AUTO,
        ).inOrder()
    }

    @Test
    fun `a user edit outranks the source number`() {
        val assigned = ChannelNumberAssigner.assign(
            listOf(channel(1, "a", channelNo = 3), channel(2, "b")),
            userEdits = mapOf(1L to 50),
        )

        assertThat(assigned[0].number).isEqualTo(50)
        assertThat(assigned[0].source).isEqualTo(ChannelNumberSource.USER_EDIT)
        assertThat(assigned[1].number).isEqualTo(1)
    }

    @Test
    fun `automatic numbering follows the input order and starts at one`() {
        val assigned = ChannelNumberAssigner.assign(
            listOf(channel(9, "first"), channel(4, "second"), channel(7, "third")),
        )

        assertThat(assigned.map { it.number }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `two channels may declare the same source number and automatic numbers skip both`() {
        val assigned = ChannelNumberAssigner.assign(
            listOf(
                channel(1, "a", channelNo = 5),
                channel(2, "b", channelNo = 5),
                channel(3, "c"),
                channel(4, "d"),
            ),
        )

        assertThat(assigned.map { it.number }).containsExactly(5, 5, 1, 2).inOrder()
    }

    @Test
    fun `a non-positive source number is treated as absent`() {
        val assigned = ChannelNumberAssigner.assign(
            listOf(channel(1, "a", channelNo = 0), channel(2, "b", channelNo = -4)),
        )

        assertThat(assigned.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(assigned.map { it.source })
            .containsExactly(ChannelNumberSource.AUTO, ChannelNumberSource.AUTO)
            .inOrder()
    }
}
